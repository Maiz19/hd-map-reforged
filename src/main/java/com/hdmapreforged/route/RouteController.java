package com.hdmapreforged.route;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * When to search, so it never happens too often: one search at a time; when the player strays, automatic searches
 * with a doubling pause, at most {@link #MAX_AUTOMATIC} per target, none for a target proved unreachable (until a user
 * action) and none in instances other than the house (their positions are template copies). Called on one thread
 * (Swing); results come back through {@code callbacks}, also when a search fails.
 */
@Slf4j
@RequiredArgsConstructor
public final class RouteController
{
    public static final long FIRST_BACKOFF_MS = 3_000;
    public static final long MAX_BACKOFF_MS = 60_000;
    public static final int MAX_AUTOMATIC = 6;
    /** Tiles from the route before the player counts as having left it. */
    public static final int STRAY = 12;

    private final Executor searches;
    private final Executor callbacks;
    private final Pathfinder pathfinder;
    /** Builds a search for a target, or null when there is no start. */
    private final IntFunction<RouteRequest> requests;
    private final Consumer<RouteController> changed;
    private final LongSupplier clock;

    private int target = -1;
    private Route route;
    private AtomicBoolean running;
    /** A user search asked for while another ran; started when that one ends. */
    private boolean pendingUser;
    private int automatic;
    private long backoff = FIRST_BACKOFF_MS;
    private long nextAutomatic;
    /** Targets proved unreachable, with where that search started from. */
    private final Map<Integer, Integer> unreachable = new HashMap<>();
    private int searchesStarted;
    private String status;
    /** A search could not start for want of a position, and when to try again. */
    private boolean noStart;
    private long nextStartTry;

    public void setTarget(int target)
    {
        userAction();
        this.target = target;
        route = null;
        status = "Searching…";
        start(true);
        changed.accept(this);
    }

    /** A route planned already (a custom route's next part): shown as found, without a search. */
    public void show(int target, Route planned)
    {
        userAction();
        cancelRunning();
        this.target = target;
        route = planned;
        status = null;
        changed.accept(this);
    }

    public void clear()
    {
        userAction();
        target = -1;
        route = null;
        status = null;
        noStart = false;
        cancelRunning();
        changed.accept(this);
    }

    private void cancelRunning()
    {
        if (running != null)
        {
            running.set(true);
            running = null;
        }
        pendingUser = false;
    }

    /** A setting that changes routes (or unlocks, bank) changed: search again once. */
    public void settingsChanged()
    {
        userAction();
        if (target >= 0)
        {
            start(true);
        }
    }

    private void userAction()
    {
        unreachable.clear();
        automatic = 0;
        backoff = FIRST_BACKOFF_MS;
        nextAutomatic = 0;
    }

    /** {@code player} packed or -1; starts a search when the player left the route. */
    public void playerMoved(int player, boolean automaticAllowed)
    {
        if (target >= 0 && running == null && route == null && noStart && player >= 0 && automaticAllowed)
        {
            // Asked for where the position was unknown: retried now and then once it is known.
            long now = clock.getAsLong();
            if (now >= nextStartTry)
            {
                nextStartTry = now + FIRST_BACKOFF_MS;
                status = "Searching…";
                start(true);
                changed.accept(this);
            }
            return;
        }
        if (target < 0 || running != null || !automaticAllowed || player < 0 || route == null)
        {
            return;
        }
        if (onRoute(player))
        {
            // Back on the way: long trips get their automatic searches again.
            backoff = FIRST_BACKOFF_MS;
            automatic = 0;
            return;
        }
        long now = clock.getAsLong();
        // Unreachable from where that search started; from elsewhere (past a door) it may not be.
        Integer from = unreachable.get(target);
        boolean stillUnreachable = from != null && (from < 0 || near(player, from, STRAY));
        if (stillUnreachable || automatic >= MAX_AUTOMATIC || now < nextAutomatic)
        {
            return;
        }
        automatic++;
        nextAutomatic = now + backoff;
        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        start(false);
    }

    public static final int ARRIVED = 3;

    /** Within {@link #ARRIVED} tiles of the route's end (or the tile asked for), on the same floor. */
    public boolean arrived(int player)
    {
        if (player < 0 || route == null || Tiles.isSea(player))
        {
            return false;
        }
        // A route that cannot get there ends where it gets stuck: no arrival.
        int end = route.outcome != Route.Outcome.FOUND ? -1 : route.end >= 0 ? route.end : target;
        return end >= 0 && Tiles.z(end) == Tiles.z(player) && Tiles.distance(player, end) <= ARRIVED
            || target >= 0 && Tiles.z(target) == Tiles.z(player) && Tiles.distance(player, target) <= ARRIVED;
    }

    boolean onRoute(int player)
    {
        // With no steps: already there, or no start found, which a later position may fix.
        if (route.end >= 0 && near(player, route.end, STRAY))
        {
            return true;
        }
        for (Route.Step step : route.steps)
        {
            if (step.isJump())
            {
                if (near(player, step.last(), STRAY) || step.first() != step.last() && near(player, step.first(), STRAY))
                {
                    return true;
                }
                continue;
            }
            for (int point : step.points)
            {
                if (near(player, point, Tiles.isSea(point) ? STRAY + Tiles.CELL : STRAY))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean near(int a, int b, int radius)
    {
        boolean sea = Tiles.isSea(a) || Tiles.isSea(b);
        return (sea || Tiles.z(a) == Tiles.z(b)) && Tiles.distance(a, b) <= radius;
    }

    private void start(boolean user)
    {
        if (running != null)
        {
            if (user)
            {
                // The newest user request wins: start it when the old one ends.
                running.set(true);
                pendingUser = true;
            }
            return;
        }
        RouteRequest request = requests.apply(target);
        noStart = request == null;
        if (request == null)
        {
            status = "Your position is not known here; try again outside";
            changed.accept(this);
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        searchesStarted++;
        int searched = target;
        int from = request.start;
        try
        {
            searches.execute(() -> {
                Route result = null;
                try
                {
                    result = pathfinder.find(request, cancel::get);
                }
                catch (Throwable e)
                {
                    log.warn("Route search to {} failed", Tiles.format(searched), e);
                }
                finally
                {
                    Route done = result != null ? result : new Route(Route.Outcome.NONE,
                        Collections.emptyList(), 0, searched, -1, false, false, 0);
                    callbacks.execute(() -> finished(cancel, searched, from, done));
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            running = null;
        }
    }

    private void finished(AtomicBoolean search, int searched, int searchedFrom, Route result)
    {
        if (running == search)
        {
            running = null;
        }
        if (pendingUser && running == null)
        {
            pendingUser = false;
            if (target >= 0)
            {
                start(true);
                return;
            }
        }
        if (search.get() || searched != target || result.outcome == Route.Outcome.CANCELLED)
        {
            return;
        }
        route = result;
        if (result.exhausted && result.outcome != Route.Outcome.FOUND)
        {
            unreachable.put(searched, searchedFrom);
        }
        status = null;
        changed.accept(this);
    }

    public int target()
    {
        return target;
    }

    public Route route()
    {
        return route;
    }

    public boolean searching()
    {
        return running != null;
    }

    /** A message instead of a route, or null. */
    public String status()
    {
        return status;
    }

    public boolean isUnreachable(int target)
    {
        return unreachable.containsKey(target);
    }

    /** For tests. */
    public int searchesStarted()
    {
        return searchesStarted;
    }

    public Pathfinder pathfinder()
    {
        return pathfinder;
    }
}
