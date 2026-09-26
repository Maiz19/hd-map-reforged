package com.hdmapreforged;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads the whole map to the disk cache in the background, when the player asks for it, so every part of it
 * shows at once, also offline. Two tiles at a time, coarse zoom levels first (so the whole map is usable early), the
 * surface before the dungeons; tiles already on disk are skipped, so it resumes where it stopped. The choice is
 * remembered. It is done once: a new wiki map version is not downloaded again in one go, its tiles replace the
 * earlier ones as they are looked at (see TileCache).
 */
@Slf4j
final class MapDownloader
{
    /** What to download. */
    enum Scope
    {
        SURFACE("the surface", 200),
        ALL("the surface and all dungeons", 500);

        final String description;
        /** A rough size on disk, for the question asked before starting. */
        final int megabytes;

        Scope(String description, int megabytes)
        {
            this.description = description;
            this.megabytes = megabytes;
        }

        static Scope parse(String name)
        {
            for (Scope scope : values())
            {
                if (scope.name().equals(name))
                {
                    return scope;
                }
            }
            return null;
        }
    }

    /** Parallel downloads: gentle on the wiki's servers. */
    static final int THREADS = 2;
    /** Each download thread waits this long after every request, so the wiki sees at most a few a second. */
    static final long PAUSE_MS = 150;
    /** After this many failures in a row on one thread (no connection) it stops; the download resumes next start. */
    private static final int MAX_FAILURES = 20;
    /** A tile is tried this often, with longer pauses in between, then skipped (it loads when looked at). */
    static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MS = 3000;
    private static final String COMPLETE = "complete-";

    private final TileCache tiles;
    private final Runnable changed;

    private final long pauseMs;
    private final AtomicInteger done = new AtomicInteger();
    /** Tiles given up on in this run. */
    private final AtomicInteger skipped = new AtomicInteger();
    /** A thread of this run stopped early (no connection). */
    private volatile boolean gaveUp;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int total;
    private volatile boolean running;
    private volatile Scope scope;
    private volatile String version;
    /** A short message after the download ended or stopped, or null. */
    private volatile String finished;
    private final List<Thread> workers = new ArrayList<>();

    /** {@code changed} is called (on a download thread) whenever the progress to show changes. */
    MapDownloader(TileCache tiles, Runnable changed)
    {
        this(tiles, changed, PAUSE_MS);
    }

    /** With another pause between requests (tests). */
    MapDownloader(TileCache tiles, Runnable changed, long pauseMs)
    {
        this.tiles = tiles;
        this.changed = changed;
        this.pauseMs = pauseMs;
    }

    /**
     * Every tile of the chosen maps on the ground floor, coarse zoom levels first and the surface before other
     * maps. Upper floors are left out: most of them are empty, and they load quickly when looked at.
     */
    static List<TileCache.Key> keys(BaseMaps maps, Scope scope)
    {
        List<BaseMap> chosen = new ArrayList<>();
        chosen.add(maps.surface());
        if (scope == Scope.ALL)
        {
            for (BaseMap map : maps.all())
            {
                if (map.id != BaseMap.SURFACE && map.id != BaseMap.FULL)
                {
                    chosen.add(map);
                }
            }
        }
        List<TileCache.Key> keys = new ArrayList<>();
        for (int level = TileCache.MIN_ZOOM; level <= TileCache.MAX_ZOOM; level++)
        {
            for (BaseMap map : chosen)
            {
                int x0 = TileCache.tileIndex(map.minX, level);
                int x1 = TileCache.tileIndex(map.maxX - 1, level);
                int y0 = TileCache.tileIndex(map.minY, level);
                int y1 = TileCache.tileIndex(map.maxY - 1, level);
                for (int y = y1; y >= y0; y--)
                {
                    for (int x = x0; x <= x1; x++)
                    {
                        keys.add(new TileCache.Key(map.id, level, 0, x, y));
                    }
                }
            }
        }
        return keys;
    }

    /** Starts (or restarts) downloading {@code scope} of a map version. */
    synchronized void start(Scope scope, String version, BaseMaps maps)
    {
        stop();
        if (scope == null || version == null || maps == null || !WikiClient.isSafeVersion(version))
        {
            return;
        }
        this.scope = scope;
        this.version = version;
        finished = null;
        if (completeMarker(version, scope).isFile() || tiles.fallbackComplete(completeName(scope)))
        {
            // Downloaded before, for this version or an earlier one (whose tiles are shown until this version's
            // arrive as they are looked at): not downloaded again.
            done.set(0);
            total = 0;
            changed.run();
            return;
        }
        List<TileCache.Key> keys = keys(maps, scope);
        ConcurrentLinkedQueue<TileCache.Key> queue = new ConcurrentLinkedQueue<>(keys);
        total = keys.size();
        done.set(0);
        skipped.set(0);
        gaveUp = false;
        running = true;
        int started = generation.incrementAndGet();
        AtomicInteger working = new AtomicInteger(THREADS);
        for (int i = 0; i < THREADS; i++)
        {
            Thread worker = new Thread(() -> work(queue, started, working), "HD Map Reforged map download");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            workers.add(worker);
            worker.start();
        }
        changed.run();
    }

    private void work(ConcurrentLinkedQueue<TileCache.Key> queue, int started, AtomicInteger working)
    {
        String tileVersion = version;
        // Failures in a row on this thread.
        int[] failures = new int[1];
        try
        {
            TileCache.Key key;
            while (started == generation.get() && (key = queue.poll()) != null)
            {
                Boolean fetched = fetch(tileVersion, key, started, failures);
                if (fetched == null)
                {
                    gaveUp = true;
                    return;
                }
                if (fetched)
                {
                    done.incrementAndGet();
                }
                else
                {
                    skipped.incrementAndGet();
                }
                changed.run();
            }
        }
        finally
        {
            if (working.decrementAndGet() == 0 && started == generation.get())
            {
                ended(started, done.get() >= total, gaveUp || queue.peek() != null, tileVersion);
                // What the download added counts toward the disk limit too.
                tiles.limitDiskUse();
            }
        }
    }

    /**
     * One tile, tried up to {@link #MAX_ATTEMPTS} times with growing pauses: true when on disk (or known empty),
     * false when skipped, null to stop this thread (no connection, stopped or interrupted).
     */
    private Boolean fetch(String tileVersion, TileCache.Key key, int started, int[] failures)
    {
        for (int attempt = 1; started == generation.get(); attempt++)
        {
            try
            {
                if (tiles.fetchToDisk(tileVersion, key) != TileCache.Fetch.HAD && !pause(pauseMs))
                {
                    return null;
                }
                failures[0] = 0;
                return true;
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Map download: {} failed", key.path(), e);
                if (++failures[0] >= MAX_FAILURES)
                {
                    return null;
                }
                if (attempt >= MAX_ATTEMPTS)
                {
                    return false;
                }
                if (!pause(RETRY_PAUSE_MS * attempt))
                {
                    return null;
                }
            }
        }
        return null;
    }

    /** False when interrupted (stopped). */
    private static boolean pause(long millis)
    {
        if (millis <= 0)
        {
            return true;
        }
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void ended(int started, boolean complete, boolean stopped, String tileVersion)
    {
        if (started != generation.get())
        {
            // A new download started meanwhile.
            return;
        }
        running = false;
        workers.clear();
        if (complete)
        {
            File marker = completeMarker(tileVersion, scope);
            try
            {
                File parent = marker.getParentFile();
                if ((parent.isDirectory() || parent.mkdirs()) && !marker.isFile() && !marker.createNewFile())
                {
                    log.debug("Could not mark the map download as complete");
                }
            }
            catch (IOException e)
            {
                log.debug("Could not mark the map download as complete", e);
            }
            finished = "Whole map downloaded";
        }
        else if (!stopped)
        {
            finished = String.format(Locale.ROOT, "Map downloaded; %,d tiles failed and load when looked at.",
                skipped.get());
        }
        else
        {
            finished = "Map download stopped: no connection to the wiki. It continues next time.";
        }
        changed.run();
    }

    /** Stops downloading; what is on disk stays. */
    synchronized void stop()
    {
        generation.incrementAndGet();
        for (Thread worker : workers)
        {
            worker.interrupt();
        }
        workers.clear();
        running = false;
    }

    /** Forgets the finished message, such as after it has been shown for a while. */
    void clearMessage()
    {
        finished = null;
    }

    private File completeMarker(String tileVersion, Scope scope)
    {
        return new File(tiles.versionFolder(tileVersion), completeName(scope));
    }

    private static String completeName(Scope scope)
    {
        return COMPLETE + scope.name().toLowerCase(Locale.ROOT);
    }

    boolean isRunning()
    {
        return running;
    }

    Scope scope()
    {
        return scope;
    }

    int done()
    {
        return done.get();
    }

    int total()
    {
        return total;
    }

    /** Whether {@code scope} of the current version is completely on disk. */
    boolean isComplete(Scope scope)
    {
        String current = version;
        return current != null && (completeMarker(current, scope).isFile() || tiles.fallbackComplete(completeName(scope)));
    }

    /** What the map shows about the download, or null when nothing. */
    String status()
    {
        if (running)
        {
            int all = Math.max(1, total);
            int now = done.get();
            return String.format(Locale.ROOT, "Downloading the map: %d%% (%,d of %,d tiles)", now * 100 / all, now, all);
        }
        return finished;
    }
}
