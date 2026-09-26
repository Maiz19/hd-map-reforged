package com.hdmapreforged;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Wiki map tiles: kept in memory, optionally on disk, and downloaded a few at a time. Each frame queues the
 * tiles it needs, farthest first, and starts loading when the frame ends, so the middle of the view loads first;
 * tiles that scrolled out of view before their turn are dropped.
 */
@Slf4j
final class TileCache
{
    static final int TILE_SIZE = 256;
    static final int MIN_ZOOM = -3;
    static final int MAX_ZOOM = 3;
    private static final String BASE_URL = "https://maps.runescape.wiki/osrs/versions/";
    /**
     * Tiles the map loads at once, from disk or the wiki. Their downloads go through the HTTP client's queue, which
     * runs at most 5 requests per host at a time (OkHttp's default, shared with the rest of RuneLite). The whole-map
     * download ({@link MapDownloader#THREADS} threads, pausing between tiles) and the region checks
     * ({@link #loadNow}, one data thread) come on top of that.
     */
    private static final int MAX_IN_FLIGHT = 10;
    /** Larger answers are not tiles (the wiki's are well under 200 KB). */
    static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    /** A tile the wiki did not have is asked for again after this long (the wiki adds tiles now and then). */
    static final long NONE_EXPIRES_MS = 7 * 24 * 60 * 60 * 1000L;
    /** A half-written file older than this was left by a crash; newer ones may still be being written. */
    private static final long PART_STALE_MS = 10 * 60 * 1000L;
    private static final long RETRY_AFTER_MS = 30_000;
    private static final long MB = 1024 * 1024;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    static final class Key
    {
        final int map;
        final int zoom;
        final int plane;
        final int x;
        final int y;

        Key(int map, int zoom, int plane, int x, int y)
        {
            this.map = map;
            this.zoom = zoom;
            this.plane = plane;
            this.x = x;
            this.y = y;
        }

        String path()
        {
            return map + "/" + zoom + "/" + plane + "_" + x + "_" + y + ".png";
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Key))
            {
                return false;
            }
            Key k = (Key) o;
            return map == k.map && zoom == k.zoom && plane == k.plane && x == k.x && y == k.y;
        }

        @Override
        public int hashCode()
        {
            return (((map * 31 + zoom) * 31 + plane) * 31 + x) * 31 + y;
        }
    }

    private final OkHttpClient http;
    private final ExecutorService io;
    private final File cacheRoot;
    private final Runnable onLoaded;

    private final LinkedHashMap<Key, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Key> missing = new HashSet<>();
    private final Map<Key, Long> failed = new HashMap<>();
    /** Tiles shown from the previous version while this version's are fetched; retried when that failed. */
    private final Set<Key> stale = new HashSet<>();
    private final Deque<Key> queue = new ArrayDeque<>();
    private final Set<Key> queued = new HashSet<>();
    private final Set<Key> loading = new HashSet<>();
    private volatile String version;
    private volatile boolean diskCache;
    private volatile int memoryLimit = 256;
    private volatile int diskLimitMb = 1000;
    /** The disk limit never goes below this: the whole-map download's size, so trimming never undoes it. */
    private volatile int diskFloorMb;
    /** Tiles the current view needs; the memory cache never shrinks below this, or tiles in view would reload. */
    private int frameNeed;
    private int generation;
    /** Set when switching to a version the wiki reported: very old versions are removed once one of its tiles loads. */
    private boolean cleanupPending;
    /**
     * The previous map version still on disk. Its tiles are shown at once while the same tiles of the current version
     * download, so a new wiki map version never starts from an empty map; each old tile is removed once replaced.
     */
    private volatile String fallbackVersion;

    TileCache(OkHttpClient http, ExecutorService io, File cacheRoot, Runnable onLoaded)
    {
        this.http = http;
        this.io = io;
        this.cacheRoot = cacheRoot;
        this.onLoaded = onLoaded;
    }

    static String tileUrl(String version, Key key)
    {
        return BASE_URL + version + "/tiles/rendered/" + key.path();
    }

    /** Tile index containing a world coordinate at a wiki zoom level. */
    static int tileIndex(double world, int zoom)
    {
        return (int) Math.floor(world * Math.pow(2, zoom) / TILE_SIZE);
    }

    /** World tiles covered by one image tile at a wiki zoom level. */
    static double worldPerTile(int zoom)
    {
        return TILE_SIZE / Math.pow(2, zoom);
    }

    void configure(boolean diskCache, int memoryLimit, int diskLimitMb)
    {
        this.diskCache = diskCache;
        this.memoryLimit = memoryLimit;
        this.diskLimitMb = diskLimitMb;
        synchronized (this)
        {
            trim();
        }
    }

    /**
     * The smallest disk limit, whatever the setting says: the chosen whole-map download must fit, or trimming would
     * delete it and the next start download it again.
     */
    void setDiskFloor(int megabytes)
    {
        diskFloorMb = Math.max(0, megabytes);
    }

    /** The disk limit in effect, in megabytes. */
    int diskLimitMb()
    {
        return Math.max(diskLimitMb, diskFloorMb);
    }

    String version()
    {
        return version;
    }

    /**
     * Switches to another map version, dropping tiles of the previous one. When {@code official} (the version the
     * wiki reports, not a manual setting), older versions on disk are removed after the first tile of this one
     * has downloaded, so a mistyped version never wipes the cache.
     */
    void setVersion(String version, boolean official)
    {
        if (version.equals(this.version))
        {
            return;
        }
        // Listing the disk can take a while; the map keeps painting meanwhile.
        String fallback = previousOnDisk(version);
        synchronized (this)
        {
            if (version.equals(this.version))
            {
                return;
            }
            this.version = version;
            generation++;
            memory.clear();
            missing.clear();
            failed.clear();
            stale.clear();
            queue.clear();
            queued.clear();
            loading.clear();
            cleanupPending = official;
            fallbackVersion = fallback;
        }
        if (official && fallback != null)
        {
            // Older versions go into the one shown while this one's tiles arrive: a whole-map download made for an
            // earlier version stays usable, and is replaced tile by tile, never downloaded again in one go.
            execute(() -> mergeOlder(version, fallback));
        }
        execute(this::limitDiskUse);
    }

    /** Keeps the disk cache within its limit, in the background. */
    void trimDiskLater()
    {
        execute(this::limitDiskUse);
    }

    /** The most recently used other version folder in the cache, or null. */
    private String previousOnDisk(String current)
    {
        File[] versions = cacheRoot.listFiles(File::isDirectory);
        if (versions == null)
        {
            return null;
        }
        File best = null;
        for (File dir : versions)
        {
            if (!dir.getName().equals(current) && WikiClient.isSafeVersion(dir.getName())
                && !java.nio.file.Files.isSymbolicLink(dir.toPath()) && (best == null || dir.lastModified() > best.lastModified()))
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

    /** Call before painting: tiles queued for the previous frame that have not started are forgotten. */
    synchronized void beginFrame(int visibleTiles)
    {
        queue.clear();
        queued.clear();
        frameNeed = visibleTiles * 2 + 16;
    }

    /** Call after painting: starts loading the queued tiles, nearest the middle first. */
    synchronized void endFrame()
    {
        pump();
    }

    /** A loaded tile without requesting it, or null. */
    synchronized BufferedImage peek(Key key)
    {
        return memory.get(key);
    }

    /** A loaded tile, or null after queueing it; queue tiles farthest from the middle first. */
    synchronized BufferedImage get(Key key)
    {
        BufferedImage image = memory.get(key);
        if (image != null && !stale.contains(key) || version == null || missing.contains(key) || loading.contains(key)
            || queued.contains(key))
        {
            return image;
        }
        Long failedAt = failed.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS)
        {
            return image;
        }
        // Not loaded yet, or only the previous version's tile shows because this one's failed: (again) in line.
        queue.addFirst(key);
        queued.add(key);
        return image;
    }

    /**
     * Loads one tile synchronously from memory, disk or the wiki; null when the wiki has no such tile. For
     * background work only, never the Swing or client thread.
     */
    BufferedImage loadNow(String tileVersion, Key key) throws IOException
    {
        synchronized (this)
        {
            if (tileVersion.equals(version))
            {
                BufferedImage image = memory.get(key);
                if (image != null)
                {
                    return image;
                }
            }
        }
        File file = file(tileVersion, key);
        BufferedImage cached = readCached(file);
        if (cached != null)
        {
            return cached;
        }
        Request request = new Request.Builder().url(tileUrl(tileVersion, key)).build();
        try (Response response = http.newCall(request).execute(); ResponseBody body = response.body())
        {
            if (response.code() == 404)
            {
                return null;
            }
            if (!response.isSuccessful() || body == null)
            {
                throw new IOException("HTTP " + response.code());
            }
            byte[] bytes = readBody(body, MAX_TILE_BYTES);
            BufferedImage image = decode(bytes);
            if (image != null && diskCache)
            {
                write(file, bytes);
            }
            return image;
        }
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
        while (loading.size() < MAX_IN_FLIGHT && !queue.isEmpty())
        {
            Key key = queue.pollFirst();
            queued.remove(key);
            loading.add(key);
            int started = generation;
            String tileVersion = version;
            if (!execute(() -> load(key, tileVersion, started)))
            {
                loading.remove(key);
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
            // The plugin is shutting down.
            return false;
        }
    }

    private File file(String tileVersion, Key key)
    {
        return new File(new File(cacheRoot, tileVersion), key.path());
    }

    /** An empty file next to where a tile would be, saying the wiki has no such tile (empty sea or rock). */
    private File noneMarker(String tileVersion, Key key)
    {
        return new File(new File(cacheRoot, tileVersion), key.path() + NONE_SUFFIX);
    }

    static final String NONE_SUFFIX = ".none";
    private static final String PART_SUFFIX = ".part";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** What {@link #fetchToDisk} did with a tile. */
    enum Fetch
    {
        /** Downloaded and saved. */
        SAVED,
        /** Already on disk, or known to be missing: nothing was asked of the wiki. */
        HAD,
        /** The wiki answered it has no such tile. */
        NONE
    }

    /**
     * Makes sure a tile is on disk, downloading it when needed, without decoding it. For downloading the whole map in
     * the background; never on the Swing or client thread.
     */
    Fetch fetchToDisk(String tileVersion, Key key) throws IOException
    {
        File file = file(tileVersion, key);
        if (file.isFile())
        {
            return Fetch.HAD;
        }
        File none = noneMarker(tileVersion, key);
        if (isFreshMarker(none))
        {
            return Fetch.HAD;
        }
        Request request = new Request.Builder().url(tileUrl(tileVersion, key)).build();
        try (Response response = http.newCall(request).execute(); ResponseBody body = response.body())
        {
            if (response.code() == 404)
            {
                write(none, new byte[0]);
                return Fetch.NONE;
            }
            if (!response.isSuccessful() || body == null)
            {
                throw new IOException("HTTP " + response.code());
            }
            byte[] bytes = readBody(body, MAX_TILE_BYTES);
            if (!isPng(bytes))
            {
                throw new IOException("Not a PNG: " + key.path());
            }
            write(file, bytes);
            return Fetch.SAVED;
        }
    }

    /**
     * A "no such tile" marker younger than {@link #NONE_EXPIRES_MS}; an older one is removed, so the tile is asked
     * for again.
     */
    private static boolean isFreshMarker(File marker)
    {
        if (!marker.isFile())
        {
            return false;
        }
        if (System.currentTimeMillis() - marker.lastModified() < NONE_EXPIRES_MS)
        {
            return true;
        }
        if (!marker.delete())
        {
            log.debug("Could not remove expired marker {}", marker);
        }
        return false;
    }

    /**
     * A response body, refused when longer than {@code max} bytes whether or not the server said how long it is.
     */
    static byte[] readBody(ResponseBody body, int max) throws IOException
    {
        long declared = body.contentLength();
        if (declared > max)
        {
            throw new IOException("Too large: " + declared + " bytes");
        }
        try (java.io.InputStream in = body.byteStream())
        {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(declared > 0 ? (int) declared : 16384);
            byte[] buffer = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1)
            {
                total += n;
                if (total > max)
                {
                    throw new IOException("Too large: over " + max + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static boolean isPng(byte[] bytes)
    {
        if (bytes.length < PNG_SIGNATURE.length)
        {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++)
        {
            if (bytes[i] != PNG_SIGNATURE[i])
            {
                return false;
            }
        }
        return true;
    }

    /** The folder of a version's tiles. */
    File versionFolder(String tileVersion)
    {
        return new File(cacheRoot, tileVersion);
    }

    /**
     * A tile read from the disk cache at once, on the calling thread, or null. For the first frame of a map just
     * opened: without it that frame shows the icons on black before the tiles pop in.
     */
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

    /** A tile from the disk cache, or null. Unreadable files are removed so they download again. */
    private BufferedImage readCached(File file)
    {
        if (!diskCache || !file.isFile())
        {
            return null;
        }
        try
        {
            BufferedImage image = decode(Files.readAllBytes(file.toPath()));
            if (image != null)
            {
                // The size limit removes the least recently used tiles first, so mark this one as used.
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
        if (!file.delete())
        {
            log.debug("Could not remove unreadable tile {}", file);
        }
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
            if (diskCache && isFreshMarker(noneMarker(tileVersion, key)))
            {
                // Known to be empty from downloading the whole map: no need to ask the wiki.
                finish(key, started, null, true, false);
                return;
            }
            String previous = fallbackVersion;
            File old = previous == null ? null : file(previous, key);
            BufferedImage stale = old == null ? null : readCached(old);
            if (stale != null)
            {
                // Shown until this version's tile has downloaded.
                showStale(key, started, stale);
            }
            Request request = new Request.Builder().url(tileUrl(tileVersion, key)).build();
            http.newCall(request).enqueue(new Callback()
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
                    try (ResponseBody body = response.body())
                    {
                        if (response.code() == 404)
                        {
                            // Gone in this version: the stale copy must not linger.
                            forget(key, started);
                            finish(key, started, null, true, false);
                            return;
                        }
                        if (!response.isSuccessful() || body == null)
                        {
                            finish(key, started, null, false, false);
                            return;
                        }
                        byte[] bytes = readBody(body, MAX_TILE_BYTES);
                        BufferedImage image = decode(bytes);
                        if (image != null && diskCache)
                        {
                            write(file(tileVersion, key), bytes);
                        }
                        if (image != null && old != null && old.isFile() && !old.delete())
                        {
                            log.debug("Could not remove replaced tile {}", old);
                        }
                        finish(key, started, image, image == null, image != null);
                    }
                    catch (IOException | RuntimeException e)
                    {
                        log.debug("Unusable tile {}", key.path(), e);
                        finish(key, started, null, false, false);
                    }
                }
            });
        }
        catch (RuntimeException e)
        {
            log.debug("Could not load tile {}", key.path(), e);
            finish(key, started, null, false, false);
        }
    }

    /** Puts an old version's tile in view while the current one loads; the key stays loading. */
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
            if (started != generation)
            {
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

    /** Decodes into an image type Java2D draws quickly; anything but a 256 by 256 image is refused. */
    static BufferedImage decode(byte[] bytes) throws IOException
    {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null)
        {
            return null;
        }
        if (source.getWidth() != TILE_SIZE || source.getHeight() != TILE_SIZE)
        {
            throw new IOException("Not a map tile: " + source.getWidth() + "x" + source.getHeight());
        }
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return image;
    }

    private static void write(File file, byte[] bytes)
    {
        try
        {
            File parent = file.getParentFile();
            // Another thread may create the same folder at the same moment: only its existence matters.
            if (!parent.mkdirs() && !parent.isDirectory())
            {
                return;
            }
            // A name of its own: two threads may write the same tile at once (the map and the whole-map download).
            java.nio.file.Path temp = Files.createTempFile(parent.toPath(), file.getName() + ".", PART_SUFFIX);
            try
            {
                Files.write(temp, bytes);
                try
                {
                    Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (java.nio.file.AtomicMoveNotSupportedException e)
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
        }
    }

    /**
     * Moves every tile of other versions that {@code fallback} lacks into it, then removes those versions: versions
     * older than the current one and its fallback are never shown again. Only this plugin's own cache folder is
     * touched.
     */
    private void mergeOlder(String current, String fallback)
    {
        File[] versions = cacheRoot.listFiles(File::isDirectory);
        File into = versionFolder(fallback);
        for (File dir : versions == null ? new File[0] : versions)
        {
            if (dir.getName().equals(current) || dir.getName().equals(fallback) || !WikiClient.isSafeVersion(dir.getName())
                || java.nio.file.Files.isSymbolicLink(dir.toPath()))
            {
                continue;
            }
            List<File> files = new ArrayList<>();
            collect(dir, files);
            java.nio.file.Path base = dir.toPath();
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
                        java.nio.file.Files.move(file.toPath(), target.toPath());
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

    /**
     * Whether an earlier version on disk holds a whole-map download of that scope (it is, or is being merged into,
     * the version shown while this one's tiles arrive).
     */
    boolean fallbackComplete(String marker)
    {
        String current = version;
        File[] versions = cacheRoot.listFiles(File::isDirectory);
        for (File dir : versions == null ? new File[0] : versions)
        {
            if (!dir.getName().equals(current) && new File(dir, marker).isFile())
            {
                return true;
            }
        }
        return false;
    }

    private void removeOtherVersions(String keep, String fallback)
    {
        File[] versions = cacheRoot.listFiles(File::isDirectory);
        if (versions == null)
        {
            return;
        }
        for (File dir : versions)
        {
            if (!dir.getName().equals(keep) && !dir.getName().equals(fallback))
            {
                deleteTree(dir);
            }
        }
    }

    /** One file of the cache as it was when listed; the disk may change while trimming. */
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

    /**
     * Removes the least recently written tiles when the cache grows past its size limit, and half-written files a
     * crash left behind. Runs on a background thread; a download calls it itself when it has finished.
     */
    void limitDiskUse()
    {
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
                if (!file.delete())
                {
                    log.debug("Could not remove {}", file);
                }
                continue;
            }
            entries.add(entry);
            total += entry.size;
        }
        long limit = diskLimitMb() * MB;
        if (total <= limit)
        {
            return;
        }
        entries.sort(Comparator.comparingLong((Entry e) -> e.modified));
        boolean removed = false;
        for (Entry entry : entries)
        {
            if (total <= limit * 8 / 10)
            {
                break;
            }
            if (entry.file.getName().startsWith("complete-"))
            {
                continue;
            }
            if (entry.file.delete())
            {
                total -= entry.size;
                removed = true;
            }
        }
        if (removed)
        {
            // A downloaded whole map now has gaps: let the download check it again.
            File[] versions = cacheRoot.listFiles(File::isDirectory);
            for (File dir : versions == null ? new File[0] : versions)
            {
                File[] markers = dir.listFiles((d, name) -> name.startsWith("complete-"));
                for (File marker : markers == null ? new File[0] : markers)
                {
                    if (!marker.delete())
                    {
                        log.debug("Could not remove {}", marker);
                    }
                }
            }
        }
    }

    private static void collect(File dir, List<File> into)
    {
        File[] children = dir.listFiles();
        if (children == null)
        {
            return;
        }
        for (File child : children)
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

    /** Deletes a folder without following symbolic links out of it. */
    private static void deleteTree(File file)
    {
        if (!Files.isSymbolicLink(file.toPath()))
        {
            File[] children = file.listFiles();
            if (children != null)
            {
                for (File child : children)
                {
                    deleteTree(child);
                }
            }
        }
        if (!file.delete())
        {
            log.debug("Could not delete old tile cache entry {}", file);
        }
    }
}
