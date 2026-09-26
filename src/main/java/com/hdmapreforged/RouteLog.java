package com.hdmapreforged;

import com.hdmapreforged.route.PlayerState;
import com.hdmapreforged.route.Route;
import com.hdmapreforged.route.Tiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The last routes planned, in a plain text file of the player's own ({@code routes.log} in the plugin's folder), so a
 * wrong route can be looked at afterwards: where it went from and to, the player's Sailing and boat, and every step with
 * its tiles and what it needs; also the way anyone could go when the player's way did not arrive. Nothing is sent
 * anywhere. Only while switched on (a setting); written in the order the routes came, whatever thread writes.
 */
@Slf4j
final class RouteLog
{
    /** Routes kept, newest last. */
    static final int KEEP = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final Executor io;
    private final BooleanSupplier enabled;
    private final Deque<String> entries = new ArrayDeque<>();
    /** Numbers the texts to write, so an older one never overwrites a newer (the executor may run them in any order). */
    private long added;
    private long written;
    private final Object writing = new Object();

    RouteLog(File file, Executor io)
    {
        this(file, io, () -> true);
    }

    /** {@code enabled}: whether routes are written now (the setting). */
    RouteLog(File file, Executor io, BooleanSupplier enabled)
    {
        this.file = file;
        this.io = io;
        this.enabled = enabled;
    }

    /** Adds a route: {@code what} says which (the player's way, the way anyone could go). Swing thread. */
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
                // The time the trip takes, as the route card says it; not the planner's weights.
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
        io.execute(() -> write(number, all));
    }

    /** Writes the routes as they were at {@code number}, unless a later text was written already. */
    private void write(long number, String all)
    {
        synchronized (writing)
        {
            if (number <= written)
            {
                return;
            }
            written = number;
            write(all);
        }
    }

    private void write(String all)
    {
        try
        {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            {
                return;
            }
            Files.write(file.toPath(), all.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            log.debug("Could not write {}", file, e);
        }
    }
}
