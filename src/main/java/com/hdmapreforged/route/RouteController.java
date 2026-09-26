package com.hdmapreforged.route;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * When to search, so it never happens too often. The user asks for a route to a tile ("Path to here"); the search
 * runs on a background executor, one at a time. Later, when the player strays from the route (walked elsewhere,
 * teleported), the route is searched again automatically, but:
 * <ul>
 * <li>never while a search runs;</li>
 * <li>with a growing pause between automatic searches ({@link #FIRST_BACKOFF_MS}, doubling up to
 * {@link #MAX_BACKOFF_MS});</li>
 * <li>at most {@link #MAX_AUTOMATIC} automatic searches per target;</li>
 * <li>not at all for a target a finished (exhausted) search proved unreachable, until the user does something or a
 * setting changes;</li>
 * <li>not while the start is unknown or the player is in an instance other than their house (the position there is
 * a copy of some template, and would make every tick look like a stray).</li>
 * </ul>
 * All methods are called from one thread (Swing); results come back through {@code callbacks}, also when a search
 * fails.
 */
@Slf4j
public final class RouteController
{
    public static final long FIRST_BACKOFF_MS = 3_000;
    public static final long MAX_BACKOFF_MS = 60_000;
    public static final int MAX_AUTOMATIC = 6;
    /** How far (tiles) the player may be from the route before it counts as left. */
    public static final int STRAY = 12;

    /** Builds a search for a target from the player's current state, or returns null when it cannot (no start). */
    public interface Requests extends IntFunction<RouteRequest>
    {
    }

    private final Executor searches;
    private final Executor callbacks;
    private final Pathfinder pathfinder;
    private final Requests requests;
    private final Consumer<RouteController> changed;
    private final LongSupplier clock;

    private int target = -1;
    private Route route;
    private AtomicBoolean running;
    /** A search the user asked for while another ran; started when that one ends. */
    private boolean pendingUser;
    private int automatic;
    private long backoff = FIRST_BACKOFF_MS;
    private long nextAutomatic;
    /** Targets a finished search proved unreachable, with where that search started from. */
    private final java.util.Map<Integer, Integer> unreachable = new java.util.HashMap<>();
    private int searchesStarted;
    private String status;
    /** When a search could not start for want of a position, and when it may be tried again (see playerMoved). */
    private boolean noStart;
    private long nextStartTry;

    public RouteController(Executor searches, Executor callbacks, Pathfinder pathfinder, Requests requests,
        Consumer<RouteController> changed, LongSupplier clock)
    {
        this.searches = searches;
        this.callbacks = callbacks;
        this.pathfinder = pathfinder;
        this.requests = requests;
        this.changed = changed;
        this.clock = clock;
    }

    /** "Path to here": a user action. */
    public void setTarget(int target)
    {
        userAction();
        this.target = target;
        route = null;
        status = "Searching…";
        start(true);
        changed.accept(this);
    }

    /**
     * A route planned already (the next part of a custom route, from the stop before): shown as found, without a
     * search. When the player leaves it, it is searched again as usual.
     */
    public void show(int target, Route planned)
    {
        userAction();
        if (running != null)
        {
            running.set(true);
            running = null;
        }
        pendingUser = false;
        this.target = target;
        route = planned;
        status = null;
        changed.accept(this);
    }

    /** "Clear path": a user action. */
    public void clear()
    {
        userAction();
        target = -1;
        route = null;
        status = null;
        noStart = false;
        if (running != null)
        {
            running.set(true);
            running = null;
        }
        pendingUser = false;
        changed.accept(this);
    }

    /** A setting that changes routes (or the player's unlocks, bank) changed: search again once. */
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

    /**
     * Where the player is now ({@code player} packed, or -1 when unknown), and whether an automatic search may run
     * at all (false in instances other than the house). Starts a search when the player left the route.
     */
    public void playerMoved(int player, boolean automaticAllowed)
    {
        if (target >= 0 && running == null && route == null && noStart && player >= 0)
        {
            // Asked for where the position was not known: tried again once it is, now and then.
            long now = clock.getAsLong();
            if (now >= nextStartTry)
            {
                nextStartTry = now + FIRST_BACKOFF_MS;
                start(true);
            }
            return;
        }
        if (target < 0 || running != null || !automaticAllowed || player < 0 || route == null)
        {
            return;
        }
        if (onRoute(player))
        {
            // Back on the way: long trips (a dungeon with detours) get their automatic searches again.
            backoff = FIRST_BACKOFF_MS;
            automatic = 0;
            return;
        }
        long now = clock.getAsLong();
        // Unreachable from where that search started; from somewhere else (past a door, off a stepping stone) it may
        // not be.
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

    /** How close to the end of the route counts as arrived. */
    public static final int ARRIVED = 3;

    /**
     * Whether the player has reached the end of the route (or the tile asked for): within {@link #ARRIVED} tiles, on
     * the same floor.
     */
    public boolean arrived(int player)
    {
        if (player < 0 || route == null || Tiles.isSea(player))
        {
            return false;
        }
        // A route that cannot get there ends where it gets stuck (a door, a gate): that is no arrival.
        int end = route.outcome != Route.Outcome.FOUND ? -1 : route.end >= 0 ? route.end : target;
        return end >= 0 && Tiles.z(end) == Tiles.z(player) && Tiles.distance(player, end) <= ARRIVED
            || target >= 0 && Tiles.z(target) == Tiles.z(player) && Tiles.distance(player, target) <= ARRIVED;
    }

    /** Whether the player is on or near the route (or past its end). */
    boolean onRoute(int player)
    {
        if (route.steps.isEmpty())
        {
            // Already there; or no start found, which a later position may fix (within the backoff and cap).
            return route.end >= 0 && near(player, route.end, STRAY);
        }
        if (route.end >= 0 && near(player, route.end, STRAY))
        {
            return true;
        }
        for (Route.Step step : route.steps)
        {
            int[] points = step.points;
            if (step.isJump())
            {
                if (near(player, points[points.length - 1], STRAY) || points[0] != points[points.length - 1]
                    && near(player, points[0], STRAY))
                {
                    return true;
                }
                continue;
            }
            for (int point : points)
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
                // The user's newest request wins: stop the old search, start this one when it ends.
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
                    // Out of memory, a bug: said in the log, and the controller is free for the next search.
                    log.warn("Route search to {} failed", Tiles.format(searched), e);
                }
                finally
                {
                    Route done = result != null ? result : new Route(Route.Outcome.NONE,
                        java.util.Collections.emptyList(), 0, searched, -1, false, false, 0);
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

    /** A message instead of a route (searching, no start), or null. */
    public String status()
    {
        return status;
    }

    public boolean isUnreachable(int target)
    {
        return unreachable.containsKey(target);
    }

    /** For tests: how many searches were started. */
    public int searchesStarted()
    {
        return searchesStarted;
    }

    public Pathfinder pathfinder()
    {
        return pathfinder;
    }
}
