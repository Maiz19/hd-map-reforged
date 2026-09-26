package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * A custom route: stops to visit one after another, made and saved by the player and run again whenever wanted. A
 * stop is a place (a spot on the map, an icon) or a kind of place ("Yew trees"), which becomes the nearest one of
 * that kind when the route runs. Kept as text in the plugin's settings.
 */
final class Tour
{
    /** One stop of a route. */
    static final class Stop
    {
        final String name;
        /** Where it is; for a kind, null. */
        final WorldPoint point;
        /** The kind of place ("Yew trees", as the search names it), or null for a fixed place. */
        final String kind;

        Stop(String name, WorldPoint point, String kind)
        {
            this.name = name;
            this.point = point;
            this.kind = kind;
        }

        static Stop place(String name, WorldPoint point)
        {
            return new Stop(name, point, null);
        }

        static Stop kind(String kind)
        {
            return new Stop("Nearest of: " + kind, null, kind);
        }

        boolean isKind()
        {
            return kind != null;
        }

        /** The same place, or the same kind. */
        boolean same(Stop other)
        {
            return isKind() ? kind.equals(other.kind) : !other.isKind() && point.equals(other.point);
        }

        @Override
        public String toString()
        {
            return isKind() ? name : name + "  (" + point.getX() + ", " + point.getY()
                + (point.getPlane() > 0 ? ", floor " + point.getPlane() : "") + ")";
        }
    }

    /** Stops a route may have: the fastest order is worked out over all of them. */
    static final int MAX_STOPS = 12;
    /** Routes kept at most. */
    static final int MAX_TOURS = 50;
    private static final String HEADER = "# route\t";

    final String name;
    final List<Stop> stops;

    Tour(String name, List<Stop> stops)
    {
        this.name = name;
        this.stops = new ArrayList<>(stops);
    }

    /** The name, or with a number added when another route has it. */
    static String unique(List<Tour> tours, String name)
    {
        String candidate = name;
        for (int n = 2; ; n++)
        {
            String c = candidate;
            if (tours.stream().noneMatch(t -> t.name.equalsIgnoreCase(c)))
            {
                return candidate;
            }
            candidate = name + " " + n;
        }
    }

    /**
     * Whether stop {@code i} is the same as the one before it: going there again at once goes nowhere, so it is
     * shown greyed out and skipped when the route runs. It is kept, to be moved elsewhere (there and back).
     */
    boolean repeats(int i)
    {
        return i > 0 && i < stops.size() && stops.get(i).same(stops.get(i - 1));
    }

    /** The route as it runs: without stops that repeat the one before (see {@link #repeats}). */
    Tour withoutRepeats()
    {
        List<Stop> kept = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++)
        {
            if (!repeats(i))
            {
                kept.add(stops.get(i));
            }
        }
        return new Tour(name, kept);
    }

    Tour renamed(String newName)
    {
        return new Tour(newName, stops);
    }

    /**
     * The place of a kind nearest {@code near} (as the crow flies, the same floor first); the first place when
     * {@code near} is not known; null for no kind or none of it.
     */
    static WorldPoint nearest(KindIndex.Kind kind, WorldPoint near)
    {
        if (kind == null || kind.entries.isEmpty())
        {
            return null;
        }
        WorldPoint best = kind.entries.get(0).point;
        if (near == null)
        {
            return best;
        }
        long bestDistance = Long.MAX_VALUE;
        for (KindIndex.Entry entry : kind.entries)
        {
            long dx = entry.point.getX() - near.getX();
            long dy = entry.point.getY() - near.getY();
            long d = dx * dx + dy * dy + (entry.point.getPlane() == near.getPlane() ? 0 : 100_000_000L);
            if (d < bestDistance)
            {
                best = entry.point;
                bestDistance = d;
            }
        }
        return best;
    }

    // ---- as text ----

    /**
     * All routes as text: a line "# route" and its name per route, then a line per stop: "x y plane", its name and,
     * for a kind, the kind; tab-separated. Names lose tabs and line breaks.
     */
    static String encode(List<Tour> tours)
    {
        StringBuilder text = new StringBuilder();
        for (Tour tour : tours)
        {
            text.append(HEADER).append(clean(tour.name)).append('\n');
            for (Stop stop : tour.stops)
            {
                if (stop.isKind())
                {
                    text.append("-\t").append(clean(stop.name)).append('\t').append(clean(stop.kind));
                }
                else
                {
                    WorldPoint p = stop.point;
                    text.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getPlane()).append('\t')
                        .append(clean(stop.name));
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    /** Routes from {@link #encode}'s text; lines that make no sense are skipped, never an error. */
    static List<Tour> decode(String text)
    {
        List<Tour> tours = new ArrayList<>();
        if (text == null)
        {
            return tours;
        }
        String name = null;
        List<Stop> stops = new ArrayList<>();
        for (String line : text.split("\n"))
        {
            if (line.startsWith(HEADER))
            {
                if (name != null)
                {
                    tours.add(new Tour(name, stops));
                }
                name = line.substring(HEADER.length()).trim();
                stops = new ArrayList<>();
                continue;
            }
            if (name == null || line.trim().isEmpty() || stops.size() >= MAX_STOPS)
            {
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (cells.length >= 3 && cells[0].equals("-") && !cells[2].trim().isEmpty())
            {
                stops.add(new Stop(cells[1].trim(), null, cells[2].trim()));
                continue;
            }
            // Only a tile of the world (routes pack x and y in 14 bits); anything else is not a stop.
            int at = com.hdmapreforged.route.Tiles.parse(cells[0]);
            if (at >= 0)
            {
                int x = com.hdmapreforged.route.Tiles.x(at);
                int y = com.hdmapreforged.route.Tiles.y(at);
                stops.add(Stop.place(cells.length > 1 && !cells[1].trim().isEmpty() ? cells[1].trim() : x + ", " + y,
                    new WorldPoint(x, y, com.hdmapreforged.route.Tiles.z(at))));
            }
        }
        if (name != null)
        {
            tours.add(new Tour(name, stops));
        }
        return tours.size() > MAX_TOURS ? new ArrayList<>(tours.subList(0, MAX_TOURS)) : tours;
    }

    private static String clean(String text)
    {
        return text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    // ---- the fastest order ----

    /**
     * The order to visit places in that takes least in total, starting at place 0 (where the player is) and not
     * coming back: {@code cost[a][b]} is how long from a to b (a negative value: not possible). Exact for up to
     * {@link #MAX_STOPS} places after the start; returns the order of places 1.., without the start.
     */
    static List<Integer> fastestOrder(long[][] cost)
    {
        int n = cost.length - 1;
        if (n <= 0)
        {
            return new ArrayList<>();
        }
        if (n > MAX_STOPS)
        {
            throw new IllegalArgumentException("Too many stops: " + n);
        }
        long unreachable = Long.MAX_VALUE / 4;
        int full = 1 << n;
        long[][] best = new long[full][n];
        int[][] from = new int[full][n];
        for (long[] row : best)
        {
            java.util.Arrays.fill(row, unreachable);
        }
        for (int i = 0; i < n; i++)
        {
            best[1 << i][i] = weight(cost[0][i + 1], unreachable);
            from[1 << i][i] = -1;
        }
        for (int set = 1; set < full; set++)
        {
            for (int last = 0; last < n; last++)
            {
                long here = best[set][last];
                if ((set & 1 << last) == 0 || here >= unreachable)
                {
                    continue;
                }
                for (int next = 0; next < n; next++)
                {
                    if ((set & 1 << next) != 0)
                    {
                        continue;
                    }
                    long via = here + weight(cost[last + 1][next + 1], unreachable);
                    int with = set | 1 << next;
                    if (via < best[with][next])
                    {
                        best[with][next] = via;
                        from[with][next] = last;
                    }
                }
            }
        }
        int end = 0;
        for (int last = 1; last < n; last++)
        {
            if (best[full - 1][last] < best[full - 1][end])
            {
                end = last;
            }
        }
        List<Integer> order = new ArrayList<>();
        int set = full - 1;
        for (int at = end; at >= 0; )
        {
            order.add(at + 1);
            int previous = from[set][at];
            set &= ~(1 << at);
            at = previous;
        }
        Collections.reverse(order);
        return order;
    }

    /** A trip that is not possible weighs a lot, so it comes last, but the order still visits everything. */
    private static long weight(long cost, long unreachable)
    {
        return cost < 0 ? unreachable / (MAX_STOPS + 2) : cost;
    }
}
