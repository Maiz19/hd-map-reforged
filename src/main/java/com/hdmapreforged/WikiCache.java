package com.hdmapreforged;

import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.HttpUrl;

/**
 * Wiki answers kept on disk: the same search within {@link #FRESH_MS} does not ask the wiki again; an older answer is
 * asked again and replaced, and still used while the wiki cannot be reached. Background threads only.
 */
@Slf4j
@RequiredArgsConstructor
final class WikiCache
{
    static final long FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    /** Not asked for this long: removed at start-up. */
    private static final long UNUSED_MS = 30L * 24 * 60 * 60 * 1000;
    static final long MAX_BYTES = 50L * 1024 * 1024;

    private final Filepath dir;

    private Filepath file(HttpUrl url)
    {
        return dir.joinSegment(Hashing.sha256().hashString(url.toString(), StandardCharsets.UTF_8).toString()
            .substring(0, 40));
    }

    /** The kept answer when it is fresh, or at any age with {@code stale}; else null. */
    byte[] get(HttpUrl url, boolean stale)
    {
        Filepath file = file(url);
        long age = System.currentTimeMillis() - TileCache.modified(file);
        try
        {
            return file.isFile() && (stale || age >= 0 && age < FRESH_MS) ? TileCache.read(file) : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Keeps JSON that is no error answer, and pictures; never an error page. */
    void put(HttpUrl url, byte[] bytes)
    {
        String head = new String(bytes, 0, Math.min(bytes.length, 9), StandardCharsets.ISO_8859_1);
        if (!(head.startsWith("{") && !head.startsWith("{\"error\"") || head.startsWith("[")
            || head.startsWith("\u0089PNG") || head.startsWith("GIF8") || head.startsWith("ÿØ")))
        {
            return;
        }
        try
        {
            TileCache.writeAtomically(file(url), bytes);
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not keep the wiki answer {}", url, e);
        }
    }

    /** Removes answers not asked for in a long time, then the oldest while over {@link #MAX_BYTES}. */
    void trim()
    {
        long now = System.currentTimeMillis();
        List<TileCache.Entry> entries = new ArrayList<>();
        long total = 0;
        for (Filepath file : TileCache.files(dir))
        {
            try
            {
                TileCache.Entry entry = new TileCache.Entry(file);
                if (now - entry.modified > UNUSED_MS)
                {
                    TileCache.delete(file);
                    continue;
                }
                entries.add(entry);
                total += entry.size;
            }
            catch (IOException e)
            {
                // Removed meanwhile.
            }
        }
        TileCache.trimOldest(entries, total, MAX_BYTES);
    }
}
