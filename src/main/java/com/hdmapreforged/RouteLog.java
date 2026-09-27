package com.hdmapreforged;

import com.hdmapreforged.route.PlayerState;
import com.hdmapreforged.route.Route;
import com.hdmapreforged.route.Tiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** The last routes planned, in a local {@code routes.log} for looking at wrong routes; only with the setting on. */
@Slf4j
@RequiredArgsConstructor
final class RouteLog
{
    static final int KEEP = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final Executor io;
    private final BooleanSupplier enabled;
    private final Deque<String> entries = new ArrayDeque<>();
    /** So an older text never overwrites a newer (the executor may run them in any order). */
    private long added;
    private long written;
    private final Object writing = new Object();

    /** Swing thread. */
    void add(String what, int start, int target, PlayerState state, Route route)
    {
        if (!enabled.getAsBoolean())
        {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(LocalDateTime.now().format(TIME)).append("  ").append(what).append(": from ")
            .append(start >= 0 ? Tiles.format(start) : "unknown").append(" to ").append(Tiles.format(target)).append('\n');
        if (state != null)
        {
            text.append("  player: Sailing ").append(state.sailing ? String.valueOf(state.sailingLevel) : "none")
                .append(", boats ").append(state.boats.length).append(state.running ? ", running" : ", walking")
                .append('\n');
        }
        if (route == null)
        {
            text.append("  (no route)\n");
        }
        else
        {
            text.append("  ").append(route.outcome).append(route.exhausted ? " (searched everything)" : "")
                .append(route.limited ? " (stopped at the search limit)" : "").append(", ")
                // As the route card says it; not the planner's weights.
                .append((route.time() + 1) / 2).append(" ticks, ends at ")
                .append(route.end >= 0 ? Tiles.format(route.end) : "-").append('\n');
            for (Route.Step step : route.steps)
            {
                text.append("    ").append(step.kind).append(' ').append(RouteFeature.RouteText.describe(step))
                    .append(" | ").append(Tiles.format(step.first())).append(" -> ").append(Tiles.format(step.last()));
                if (step.detail != null)
                {
                    text.append(" [").append(step.detail).append(']');
                }
                text.append('\n');
            }
        }
        String entry = text.toString();
        String all;
        long number;
        synchronized (entries)
        {
            entries.addLast(entry);
            while (entries.size() > KEEP)
            {
                entries.removeFirst();
            }
            all = String.join("\n", entries);
            number = ++added;
        }
        try
        {
            io.execute(() -> write(number, all));
        }
        catch (RejectedExecutionException e)
        {
            // Shutting down.
        }
    }

    private void write(long number, String all)
    {
        synchronized (writing)
        {
            if (number <= written)
            {
                return;
            }
            written = number;
            try
            {
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                {
                    return;
                }
                Path target = file.toPath();
                if (Files.isSymbolicLink(target))
                {
                    log.debug("Not writing through a symbolic link: {}", file);
                    return;
                }
                TileCache.writeAtomically(target, all.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                log.debug("Could not write {}", file, e);
            }
        }
    }
}
