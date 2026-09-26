package com.hdmapreforged;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Wiki map tiles in memory, optionally on disk, downloaded a few at a time. Each frame queues tiles farthest first
 * and loads at frame end, so the middle loads first; tiles scrolled away before their turn are dropped.
 */
@Slf4j
@RequiredArgsConstructor
final class TileCache
{
    static final int TILE_SIZE = 256;
    static final int MIN_ZOOM = -3;
    static final int MAX_ZOOM = 3;
    private static final String BASE_URL = "https://maps.runescape.wiki/osrs/versions/";
    /** OkHttp itself still caps requests at 5 per host, shared with the rest of RuneLite. */
    private static final int MAX_IN_FLIGHT = 10;
    /** The wiki's tiles are well under 200 KB. */
    static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    /** Older half-written files were left by a crash; newer ones may still be being written. */
    private static final long PART_STALE_MS = 10 * 60 * 1000L;
    private static final long RETRY_AFTER_MS = 30_000;
    private static final long MB = 1024 * 1024;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @RequiredArgsConstructor
    @EqualsAndHashCode
    static final class Key
    {
        final int map;
        final int zoom;
        final int plane;
        final int x;
        final int y;

        String path()
        {
            return map + "/" + zoom + "/" + plane + "_" + x + "_" + y + ".png";
        }
    }

    private final OkHttpClient http;
    private final ExecutorService io;
    private final File cacheRoot;
    private final Runnable onLoaded;

    private final LinkedHashMap<Key, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Key> missing = new HashSet<>();
    private final Map<Key, Long> failed = new HashMap<>();
    /** Previous-version tiles shown while this version's are fetched. */
    private final Set<Key> stale = new HashSet<>();
    private final Deque<Key> queue = new ArrayDeque<>();
    private final Set<Key> queued = new HashSet<>();
    private final Set<Key> loading = new HashSet<>();
    /** Requests still running, of any generation: {@link #clear} empties {@code loading} but not the network. */
    private int inFlight;
    /** Bytes on disk as last counted, plus writes since; a trim runs once it passes the limit. */
    private final AtomicLong diskBytes = new AtomicLong();
    private final AtomicBoolean trimQueued = new AtomicBoolean();
    private boolean configured;
    private volatile String version;
    private volatile boolean diskCache;
    private volatile int memoryLimit = 256;
    private volatile int diskLimitMb = 1000;
    /** The memory cache never shrinks below this, or tiles in view would reload. */
    private int frameNeed;
    private int generation;
    /** Old versions are removed once a tile of an official version downloads, so a mistyped version never wipes the cache. */
    private boolean cleanupPending;
    /** Previous version on disk, shown while the new one downloads; each old tile is removed once replaced. */
    private volatile String fallbackVersion;

    static String tileUrl(String version, Key key)
    {
        return BASE_URL + version + "/tiles/rendered/" + key.path();
    }

    static int tileIndex(double world, int zoom)
    {
        return (int) Math.floor(world * Math.pow(2, zoom) / TILE_SIZE);
    }

    static double worldPerTile(int zoom)
    {
        return TILE_SIZE / Math.pow(2, zoom);
    }

    void configure(boolean diskCache, int memoryLimit, int diskLimitMb)
    {
        boolean wasOn;
        boolean first;
        int oldLimit;
        synchronized (this)
        {
            wasOn = this.diskCache;
            oldLimit = this.diskLimitMb;
            first = !configured;
            configured = true;
            this.diskCache = diskCache;
            this.memoryLimit = memoryLimit;
            this.diskLimitMb = diskLimitMb;
            trim();
        }
        if (!diskCache && (wasOn || first))
        {
            execute(this::deleteCachedTiles);
        }
        else if (diskCache && wasOn && diskLimitMb < oldLimit)
        {
            execute(this::limitDiskUse);
        }
    }

    String version()
    {
        return version;
    }

    /** {@code official}: the version the wiki reports, not a manual setting. */
    void setVersion(String version, boolean official)
    {
        if (version.equals(this.version))
        {
            return;
        }
        // Listed outside the lock: the map keeps painting meanwhile.
        String fallback = previousOnDisk(version);
        synchronized (this)
        {
            if (version.equals(this.version))
            {
                return;
            }
            this.version = version;
            clear();
            missing.clear();
            failed.clear();
            cleanupPending = official;
            fallbackVersion = fallback;
        }
        if (official && fallback != null)
        {
            // Older versions merge into the fallback, so their tiles still show while this version's arrive.
            execute(() -> mergeOlder(version, fallback));
        }
        execute(this::limitDiskUse);
    }

    private String previousOnDisk(String current)
    {
        File best = null;
        for (File dir : versionDirs())
        {
            if (!dir.getName().equals(current) && WikiClient.isSafeVersion(dir.getName())
                && !Files.isSymbolicLink(dir.toPath()) && (best == null || dir.lastModified() > best.lastModified()))
            {
                best = dir;
            }
        }
        return best == null ? null : best.getName();
    }

    String fallbackVersion()
    {
        return fallbackVersion;
    }

    synchronized void beginFrame(int visibleTiles)
    {
        queue.clear();
        queued.clear();
        frameNeed = visibleTiles * 2 + 16;
    }

    synchronized void endFrame()
    {
        pump();
    }

    synchronized BufferedImage peek(Key key)
    {
        return memory.get(key);
    }

    /** Queue tiles farthest from the middle first. */
    synchronized BufferedImage get(Key key)
    {
        BufferedImage image = memory.get(key);
        Long failedAt = failed.get(key);
        if (!(image != null && !stale.contains(key) || version == null || missing.contains(key) || loading.contains(key)
            || queued.contains(key) || failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS))
        {
            queue.addFirst(key);
            queued.add(key);
        }
        return image;
    }

    /** Blocking; never on the Swing or client thread. Null when the wiki has no such tile. */
    BufferedImage loadNow(String tileVersion, Key key) throws IOException
    {
        synchronized (this)
        {
            BufferedImage image = tileVersion.equals(version) ? memory.get(key) : null;
            if (image != null)
            {
                return image;
            }
        }
        File file = file(tileVersion, key);
        BufferedImage cached = readCached(file);
        if (cached != null)
        {
            return cached;
        }
        byte[] bytes = download(tileVersion, key);
        return bytes == null ? null : keep(file, bytes);
    }

    /** Decoded, and on disk when that is on. */
    private BufferedImage keep(File file, byte[] bytes) throws IOException
    {
        BufferedImage image = decode(bytes);
        if (image != null && diskCache)
        {
            write(file, bytes);
        }
        return image;
    }

    /** Blocking; null on 404. */
    private byte[] download(String tileVersion, Key key) throws IOException
    {
        try (Response response = call(tileVersion, key).execute(); ResponseBody body = response.body())
        {
            return bytes(response, body);
        }
    }

    private Call call(String tileVersion, Key key)
    {
        return http.newCall(new Request.Builder().url(tileUrl(tileVersion, key)).build());
    }

    /** Null on 404. */
    private static byte[] bytes(Response response, ResponseBody body) throws IOException
    {
        if (response.code() == 404)
        {
            return null;
        }
        if (!response.isSuccessful() || body == null)
        {
            throw new IOException("HTTP " + response.code());
        }
        return readBody(body, MAX_TILE_BYTES);
    }

    synchronized void clear()
    {
        generation++;
        memory.clear();
        stale.clear();
        queue.clear();
        queued.clear();
        loading.clear();
    }

    private void pump()
    {
        while (inFlight < MAX_IN_FLIGHT && !queue.isEmpty())
        {
            Key key = queue.pollFirst();
            queued.remove(key);
            loading.add(key);
            inFlight++;
            int started = generation;
            String tileVersion = version;
            if (!execute(() -> load(key, tileVersion, started)))
            {
                loading.remove(key);
                inFlight--;
                return;
            }
        }
    }

    private boolean execute(Runnable task)
    {
        try
        {
            io.execute(task);
            return true;
        }
        catch (RejectedExecutionException e)
        {
            // Shutting down.
            return false;
        }
    }

    private File file(String tileVersion, Key key)
    {
        return new File(versionFolder(tileVersion), key.path());
    }

    private static final String PART_SUFFIX = ".part";

    /** Refused over {@code max} bytes whether or not the server declared the length. */
    static byte[] readBody(ResponseBody body, int max) throws IOException
    {
        long declared = body.contentLength();
        if (declared > max)
        {
            throw new IOException("Too large: " + declared + " bytes");
        }
        try (InputStream in = body.byteStream())
        {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max)
            {
                throw new IOException("Too large: over " + max + " bytes");
            }
            return bytes;
        }
    }

    File versionFolder(String tileVersion)
    {
        return new File(cacheRoot, tileVersion);
    }

    /** For a map's first frame, which would otherwise show icons on black before tiles pop in. */
    BufferedImage fromDiskNow(Key key)
    {
        String current = version;
        BufferedImage image = current == null ? null : readCached(file(current, key));
        if (image != null)
        {
            synchronized (this)
            {
                if (current.equals(version))
                {
                    memory.put(key, image);
                    trim();
                }
            }
        }
        return image;
    }

    /** Files that are no tile are removed so they download again; a failed read leaves the file. */
    private BufferedImage readCached(File file)
    {
        if (!diskCache || !file.isFile())
        {
            return null;
        }
        byte[] bytes;
        try
        {
            bytes = file.length() > MAX_TILE_BYTES ? null : Files.readAllBytes(file.toPath());
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not read cached tile {}", file, e);
            return null;
        }
        try
        {
            BufferedImage image = bytes == null ? null : decode(bytes);
            if (image != null)
            {
                // Trimming removes the least recently touched first.
                long now = System.currentTimeMillis();
                if (now - file.lastModified() > DAY_MS && !file.setLastModified(now))
                {
                    log.debug("Could not touch {}", file);
                }
                return image;
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Unreadable cached tile {}", file, e);
        }
        delete(file);
        return null;
    }

    private void load(Key key, String tileVersion, int started)
    {
        try
        {
            BufferedImage cached = readCached(file(tileVersion, key));
            if (cached != null)
            {
                finish(key, started, cached, false, false);
                return;
            }
            String previous = fallbackVersion;
            File old = previous == null ? null : file(previous, key);
            BufferedImage stale = old == null ? null : readCached(old);
            if (stale != null)
            {
                showStale(key, started, stale);
            }
            call(tileVersion, key).enqueue(new Callback()
            {
                @Override
                public void onFailure(Call call, IOException e)
                {
                    log.debug("Tile download failed: {}", key.path(), e);
                    finish(key, started, null, false, false);
                }

                @Override
                public void onResponse(Call call, Response response)
                {
                    BufferedImage image = null;
                    boolean notFound = false;
                    try (ResponseBody body = response.body())
                    {
                        byte[] bytes = bytes(response, body);
                        if (bytes == null)
                        {
                            // Gone in this version: the stale copy must not linger.
                            forget(key, started);
                            notFound = true;
                        }
                        else
                        {
                            // Not an image although found: a passing fault, asked again later.
                            image = keep(file(tileVersion, key), bytes);
                            if (image != null && old != null && old.isFile())
                            {
                                delete(old);
                            }
                        }
                    }
                    catch (IOException | RuntimeException e)
                    {
                        log.debug("Unusable tile {}", key.path(), e);
                    }
                    finish(key, started, image, notFound, image != null);
                }
            });
        }
        catch (RuntimeException e)
        {
            log.debug("Could not load tile {}", key.path(), e);
            finish(key, started, null, false, false);
        }
    }

    /** The key stays loading. */
    private void showStale(Key key, int started, BufferedImage image)
    {
        synchronized (this)
        {
            if (started != generation || memory.containsKey(key))
            {
                return;
            }
            memory.put(key, image);
            stale.add(key);
            trim();
        }
        onLoaded.run();
    }

    private synchronized void forget(Key key, int started)
    {
        if (started == generation)
        {
            memory.remove(key);
            stale.remove(key);
        }
    }

    private void finish(Key key, int started, BufferedImage image, boolean notFound, boolean downloaded)
    {
        boolean cleanup = false;
        synchronized (this)
        {
            inFlight--;
            if (started != generation)
            {
                pump();
                return;
            }
            loading.remove(key);
            if (image != null)
            {
                memory.put(key, image);
                stale.remove(key);
                failed.remove(key);
                trim();
                if (downloaded && cleanupPending)
                {
                    cleanupPending = false;
                    cleanup = true;
                }
            }
            else if (notFound)
            {
                missing.add(key);
            }
            else
            {
                failed.put(key, System.currentTimeMillis());
            }
            pump();
        }
        if (cleanup)
        {
            String keep = version;
            String fallback = fallbackVersion;
            execute(() -> removeOtherVersions(keep, fallback));
        }
        if (image != null)
        {
            onLoaded.run();
        }
    }

    private void trim()
    {
        int limit = Math.max(memoryLimit, frameNeed);
        while (memory.size() > limit)
        {
            Key oldest = memory.keySet().iterator().next();
            memory.remove(oldest);
            stale.remove(oldest);
        }
    }

    /** Into a type Java2D draws quickly; anything but 256x256 is refused before it is decoded. */
    static BufferedImage decode(byte[] bytes) throws IOException
    {
        BufferedImage source;
        // In memory: ImageIO's default stream would cache to a temporary file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
            {
                return null;
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != TILE_SIZE || height != TILE_SIZE)
                {
                    throw new IOException("Not a map tile: " + width + "x" + height);
                }
                source = reader.read(0);
            }
            finally
            {
                reader.dispose();
            }
        }
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return image;
    }

    private void write(File file, byte[] bytes)
    {
        try
        {
            File parent = file.getParentFile();
            // Another thread may create the folder at the same moment.
            if (!parent.mkdirs() && !parent.isDirectory())
            {
                return;
            }
            // Own temp name: two threads may write the same tile at once.
            Path temp = Files.createTempFile(parent.toPath(), file.getName() + ".", PART_SUFFIX);
            try
            {
                Files.write(temp, bytes);
                try
                {
                    Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (AtomicMoveNotSupportedException e)
                {
                    Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            finally
            {
                Files.deleteIfExists(temp);
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not cache tile {}", file, e);
            return;
        }
        if (diskBytes.addAndGet(bytes.length) > diskLimitMb * MB && trimQueued.compareAndSet(false, true)
            && !execute(this::limitDiskUse))
        {
            trimQueued.set(false);
        }
    }

    /** Moves tiles of other versions that {@code fallback} lacks into it, then removes those versions. */
    private void mergeOlder(String current, String fallback)
    {
        File into = versionFolder(fallback);
        for (File dir : versionDirs())
        {
            if (dir.getName().equals(current) || dir.getName().equals(fallback) || !WikiClient.isSafeVersion(dir.getName())
                || Files.isSymbolicLink(dir.toPath()))
            {
                continue;
            }
            List<File> files = new ArrayList<>();
            collect(dir, files);
            Path base = dir.toPath();
            for (File file : files)
            {
                File target = new File(into, base.relativize(file.toPath()).toString());
                if (target.exists())
                {
                    continue;
                }
                try
                {
                    File parent = target.getParentFile();
                    if (parent.isDirectory() || parent.mkdirs())
                    {
                        Files.move(file.toPath(), target.toPath());
                    }
                }
                catch (IOException e)
                {
                    log.debug("Could not keep {}", file, e);
                }
            }
            deleteTree(dir);
        }
    }

    private void removeOtherVersions(String keep, String fallback)
    {
        for (File dir : versionDirs())
        {
            if (!dir.getName().equals(keep) && !dir.getName().equals(fallback))
            {
                deleteTree(dir);
            }
        }
    }

    /** Snapshot: the disk may change while trimming. */
    private static final class Entry
    {
        final File file;
        final long modified;
        final long size;

        Entry(File file)
        {
            this.file = file;
            modified = file.lastModified();
            size = file.length();
        }
    }

    /** Background thread: removes oldest tiles past the limit and stale half-written files. */
    void limitDiskUse()
    {
        trimQueued.set(false);
        if (!diskCache || !cacheRoot.isDirectory())
        {
            return;
        }
        List<File> files = new ArrayList<>();
        collect(cacheRoot, files);
        List<Entry> entries = new ArrayList<>(files.size());
        long total = 0;
        long now = System.currentTimeMillis();
        for (File file : files)
        {
            Entry entry = new Entry(file);
            if (file.getName().endsWith(PART_SUFFIX) && now - entry.modified > PART_STALE_MS)
            {
                delete(file);
                continue;
            }
            entries.add(entry);
            total += entry.size;
        }
        long limit = diskLimitMb * MB;
        if (total > limit)
        {
            entries.sort(Comparator.comparingLong((Entry e) -> e.modified));
            for (Entry entry : entries)
            {
                if (total <= limit * 8 / 10)
                {
                    break;
                }
                if (entry.file.delete())
                {
                    total -= entry.size;
                }
            }
        }
        diskBytes.set(total);
    }

    /** Background thread: the disk cache was switched off, so its tiles go. */
    private void deleteCachedTiles()
    {
        if (diskCache || Files.isSymbolicLink(cacheRoot.toPath()))
        {
            return;
        }
        for (File dir : versionDirs())
        {
            if (WikiClient.isSafeVersion(dir.getName()))
            {
                deleteTree(dir);
            }
        }
        diskBytes.set(0);
    }

    private File[] versionDirs()
    {
        return list(cacheRoot, File::isDirectory);
    }

    private static File[] list(File dir, FileFilter filter)
    {
        File[] files = dir.listFiles(filter);
        return files == null ? new File[0] : files;
    }

    private static void collect(File dir, List<File> into)
    {
        for (File child : list(dir, null))
        {
            if (Files.isSymbolicLink(child.toPath()))
            {
                continue;
            }
            if (child.isDirectory())
            {
                collect(child, into);
            }
            else
            {
                into.add(child);
            }
        }
    }

    /** Never follows symbolic links. */
    private static void deleteTree(File file)
    {
        for (File child : Files.isSymbolicLink(file.toPath()) ? new File[0] : list(file, null))
        {
            deleteTree(child);
        }
        delete(file);
    }

    private static void delete(File file)
    {
        if (!file.delete())
        {
            log.debug("Could not remove {}", file);
        }
    }
}
