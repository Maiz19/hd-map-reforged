package com.hdmapreforged.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Our own route search: A* over game tiles (8 directions, walls and doors from {@link CollisionMap}), sea blocks
 * (from {@link SeaMap}) and jumps ({@link Edge}: stairs from the game cache, and the teleports and transports of
 * a {@link RouteRequest}). Costs are half game ticks: running one tile costs 1, walking 2, sailing a 4-tile block 4
 * (about running speed; the real speed depends on the boat and the wind), a door 2 extra.
 *
 * <p>The heuristic stays a lower bound despite jumps: distances are measured with underground coordinates folded
 * onto the surface (y − 6400, where the game puts most dungeons), so ordinary stairs cost nothing to guess across,
 * and every jump that goes further than it costs (transports, and short passages cheaper than walking their length)
 * gets a lower bound to the target from a small backward search over those jumps alone.
 *
 * <p>Thread-safe: each search keeps its own state; the maps are immutable.
 */
public final class Pathfinder
{
    /** Half ticks. */
    static final int RUN = 1;
    static final int WALK = 2;
    static final int DOOR = 2;
    /** Cutting through vines, jungle or a web: a few ticks, and it needs a tool, so walking around is better. */
    static final int OBSTACLE = 10;
    static final int SAIL = Tiles.CELL;
    static final int STAIRS = 5;
    /** Extra for walking through a solid object found by shape: taken only when no known way is much shorter. */
    static final int THROUGH = 30;
    /** How far a blocked target is moved to the nearest walkable tile. */
    static final int SNAP_RADIUS = 12;
    /** Jumps longer than this (folded) are kept in the heuristic; shorter ones too where they cost less than that. */
    private static final int LONG_JUMP = 24;
    private static final int BLOCK = 8;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private final CollisionMap map;
    private final SeaMap sea;
    private final List<Edge> stairs;
    private final List<ShortcutPassage> shortcutPassages;
    /** The shortcut passages per collision map, for route requests built without this pathfinder at hand. */
    private static final Map<CollisionMap, List<ShortcutPassage>> SHORTCUT_PASSAGES =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final EdgeIndex stairsByOrigin;
    /** The passages the heuristic has to know of (see {@link #shortCut}), the same for every search. */
    private final List<Edge> heuristicStairs;

    public Pathfinder(CollisionMap map, SeaMap sea)
    {
        this.map = map;
        this.sea = sea;
        List<Edge> edges = new ArrayList<>();
        // Where passages come from (local-development/APPROACH.md), nothing else:
        // 1. the game's own world map links (map_link_passages.tsv, built by MapLinkPassages): the passage object beside
        //    each link, to where the link leads;
        List<Edge> mapLinks = links(map, "/com/hdmapreforged/route/map_link_passages.tsv");
        // 2. passages added by hand (links.tsv), each citing the wiki page or cache object it comes from;
        List<Edge> handLinks = links(map, "/com/hdmapreforged/route/links.tsv");
        // 3. the cache's own passages where the destination follows from the game's data: the other side of an
        //    obstacle, or the same spot on another floor (stairs, ladders, trapdoors), within SAME_SPOT tiles. A far
        //    one (the cache tools' 6400-tiles-north guesses) only where a map link or a hand link confirms it.
        for (Transition t : map.transitions())
        {
            Edge.Kind kind = Tiles.z(t.to) != t.plane && Math.abs(Tiles.y(t.to) - t.y) < 1000 ? Edge.Kind.STAIRS
                : Edge.Kind.ENTRANCE;
            String name = t.action + (t.name.isEmpty() ? "" : " " + t.name);
            Fees.Fee fee = Fees.at(t.x, t.y, t.plane);
            String detail = fee != null ? "Needs: " + fee.text
                : t.through ? "May need a quest, level or item the map does not know" : null;
            int cost = STAIRS + (fee == null ? 0 : fee.cost) + (t.through ? THROUGH : 0);
            for (int origin : t.origins)
            {
                if (!sameSpot(origin, t.to) && !confirmed(mapLinks, origin, t.to) && !confirmed(handLinks, origin, t.to))
                {
                    continue;
                }
                edges.add(new Edge(origin, t.to, kind, name, detail, cost));
            }
        }
        edges.addAll(mapLinks);
        edges.addAll(handLinks);
        // One-way passages (one_way.tsv): whatever source pairs them both ways, never taken backwards.
        OneWay oneWay = OneWay.load();
        edges.removeIf(e -> oneWay.against(e.from, e.to));
        // Agility shortcuts of the wiki's (shortcuts.tsv) that the game's data also knows as a passage, without the
        // shortcut's level: not an always open passage, but added to each request whose player meets the shortcut.
        List<ShortcutPassage> atShortcuts = new ArrayList<>();
        List<ShortcutPassage.Shortcut> known = ShortcutPassage.shortcuts();
        edges.removeIf(e -> {
            for (ShortcutPassage.Shortcut shortcut : known)
            {
                if (shortcut.stands(e))
                {
                    atShortcuts.add(new ShortcutPassage(e, shortcut));
                    return true;
                }
            }
            return false;
        });
        shortcutPassages = Collections.unmodifiableList(atShortcuts);
        SHORTCUT_PASSAGES.put(map, shortcutPassages);
        stairs = Collections.unmodifiableList(edges);
        stairsByOrigin = EdgeIndex.of(edges, 0);
        List<Edge> cutting = new ArrayList<>();
        for (Edge e : edges)
        {
            if (shortCut(e))
            {
                cutting.add(e);
            }
        }
        heuristicStairs = Collections.unmodifiableList(cutting);
    }

    /** The passages that are Agility shortcuts, taken only by players who meet the shortcut (see ShortcutPassage). */
    public List<ShortcutPassage> shortcutPassages()
    {
        return shortcutPassages;
    }

    /** {@link #shortcutPassages()} for a collision map, from its pathfinder (made once if there is none yet). */
    public static List<ShortcutPassage> shortcutPassages(CollisionMap map)
    {
        List<ShortcutPassage> known = SHORTCUT_PASSAGES.get(map);
        return known != null ? known : new Pathfinder(map, null).shortcutPassages;
    }

    /** Every passage the planner knows besides walking and what a request adds (teleports, transport, shortcuts). */
    public List<Edge> passages()
    {
        return stairs;
    }

    /** Whether a passage leaps far (into another map), rather than to the floor above or through a door. */
    public static boolean far(int from, int to)
    {
        return Tiles.distance(from, to) > 64 || Math.abs(Tiles.y(from) - Tiles.y(to)) > 1000;
    }

    /**
     * Whether a cache passage's destination follows from the game's data: the other side of what it crosses (the same
     * floor, a few tiles), or the same spot on another floor (stairs, ladders, trapdoors).
     */
    static boolean sameSpot(int from, int to)
    {
        int dx = Math.abs(Tiles.x(from) - Tiles.x(to));
        int dy = Math.abs(Tiles.y(from) - Tiles.y(to));
        return Math.max(dx, dy) <= SAME_SPOT;
    }

    /**
     * Tiles a cache passage may move the player and still be "the same spot": across an obstacle (rocks, stepping
     * stones) or a staircase landing a few tiles off. Every guessed passage of the cache tools leaps far further (the
     * underground 6400 tiles north), so this keeps what the game's data decides and drops what was guessed.
     */
    static final int SAME_SPOT = 10;

    /** Whether a passage from the game's map links or a hand link starts and ends near this one. */
    private static boolean confirmed(List<Edge> passages, int from, int to)
    {
        for (Edge e : passages)
        {
            if (Tiles.z(e.from) == Tiles.z(from) && Tiles.distance(e.from, from) <= 4 && Tiles.z(e.to) == Tiles.z(to)
                && Tiles.distance(e.to, to) <= 16)
            {
                return true;
            }
        }
        return false;
    }

    /** Passages added by hand (links.tsv): ones neither the cache nor the wiki map link, such as new portals. */
    private static List<Edge> links(CollisionMap map, String resource)
    {
        List<Edge> edges = new ArrayList<>();
        java.io.InputStream in = Pathfinder.class.getResourceAsStream(resource);
        if (in == null)
        {
            return edges;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] parts = line.split("\t");
                if (line.startsWith("#") || parts.length < 3)
                {
                    continue;
                }
                int a = Tiles.parse(parts[0]);
                int b = Tiles.parse(parts[1]);
                int from = a < 0 ? -1 : map.nearestWalkable(Tiles.x(a), Tiles.y(a), Tiles.z(a), 2);
                int to = b < 0 ? -1 : map.nearestWalkable(Tiles.x(b), Tiles.y(b), Tiles.z(b), 2);
                if (from >= 0 && to >= 0)
                {
                    edges.add(new Edge(from, to, Edge.Kind.ENTRANCE, parts[2].trim(), null, STAIRS));
                }
            }
        }
        catch (java.io.IOException e)
        {
            // None then.
        }
        return edges;
    }

    public SeaMap sea()
    {
        return sea;
    }

    /** Folded y: most dungeons lie 6400 tiles north of what is above them. */
    private static int fy(int node)
    {
        int y = Tiles.y(node);
        return y >= 6400 ? y - 6400 : y;
    }

    private static int folded(int a, int b)
    {
        return Math.max(Math.abs(Tiles.x(a) - Tiles.x(b)), Math.abs(fy(a) - fy(b)));
    }

    /**
     * Whether the heuristic must know a jump: a long one, or one that costs less than walking its (folded) length
     * would; guessing across it by distance alone would overestimate, and A* could miss the best way.
     */
    private static boolean shortCut(Edge e)
    {
        if (e.from == Edge.ANYWHERE)
        {
            return false;
        }
        int length = folded(e.from, e.to);
        return length > LONG_JUMP || e.cost < length * RUN;
    }

    /** Where to aim for: the target, or the walkable tile nearest to it (same floor first), or -1. */
    public int resolveGoal(int target)
    {
        if (Tiles.isSea(target))
        {
            // A place at sea (a Barracuda Trial, a shipwreck): sailed to, never snapped to land.
            return target;
        }
        int x = Tiles.x(target);
        int y = Tiles.y(target);
        int z = Tiles.z(target);
        if (map.walkable(x, y, z))
        {
            return target;
        }
        int near = nearestOpen(x, y, z, SNAP_RADIUS);
        if (near >= 0 && !pocket(near))
        {
            return near;
        }
        // Only a pocket on this floor (an icon the wiki gives on the wrong floor): an open spot right there on
        // another floor is meant.
        for (int dz = 1; dz < 4; dz++)
        {
            for (int other : new int[]{z - dz, z + dz})
            {
                int there = other >= 0 && other < 4 ? nearestOpen(x, y, other, 3) : -1;
                if (there >= 0 && !pocket(there))
                {
                    return there;
                }
            }
        }
        if (near >= 0)
        {
            return near;
        }
        // Nothing near on this floor: the same spot on another floor first. The wiki gives some places on the wrong
        // floor (Brimhaven Dungeon's upper fire giants are on plane 2, the wiki says 1).
        for (int dz = 1; dz < 4; dz++)
        {
            for (int other : new int[]{z - dz, z + dz})
            {
                if (other >= 0 && other < 4)
                {
                    near = map.nearestWalkable(x, y, other, 1);
                    if (near >= 0)
                    {
                        return near;
                    }
                }
            }
        }
        for (int dz = 1; dz < 4; dz++)
        {
            for (int other : new int[]{z - dz, z + dz})
            {
                if (other >= 0 && other < 4)
                {
                    near = map.nearestWalkable(x, y, other, SNAP_RADIUS);
                    if (near >= 0)
                    {
                        return near;
                    }
                }
            }
        }
        if (z == 0 && sea != null && sea.sailable(x / Tiles.CELL, y / Tiles.CELL, 99))
        {
            // Open water with no ground near on any floor (a Barracuda Trial's icon, a click out at sea): sailed to,
            // not walked to a far shore. A spot by the shore or on a ship's deck is still that ground.
            return Tiles.seaAt(x, y);
        }
        return -1;
    }

    /**
     * The player stands where they stand, even where the data says one cannot (an object moved, a boat's deck): a
     * start on an unwalkable tile begins from the walkable tile nearest to it. Mid-shortcut (on a stepping stone over
     * lava) the nearest walkable tile can be a pocket between the stones: the nearest one that is not shut in.
     */
    private int startNode(int start)
    {
        if (start < 0 || Tiles.isSea(start) || map.walkable(Tiles.x(start), Tiles.y(start), Tiles.z(start)))
        {
            return start;
        }
        int near = nearestOpen(Tiles.x(start), Tiles.y(start), Tiles.z(start), 5);
        return near >= 0 ? near : start;
    }

    /**
     * The nearest walkable tile that is not a pocket (behind a bank's booths, between stepping stones), or the nearest
     * walkable one when all near ones are; -1 when none within {@code radius}.
     */
    private int nearestOpen(int x, int y, int z, int radius)
    {
        int fallback = -1;
        for (int r = 1; r <= radius; r++)
        {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !map.walkable(x + dx, y + dy, z))
                    {
                        continue;
                    }
                    int node = Tiles.pack(x + dx, y + dy, z);
                    int d = dx * dx + dy * dy;
                    if (fallback < 0)
                    {
                        fallback = node;
                    }
                    if (d < bestDistance && !pocket(node))
                    {
                        best = node;
                        bestDistance = d;
                    }
                }
            }
            if (best >= 0)
            {
                return best;
            }
        }
        return fallback;
    }

    /** Tiles a walkable area needs not to count as a pocket. */
    private static final int POCKET = 120;

    /** Whether a walkable tile lies in a small shut-in area (fewer than {@link #POCKET} tiles, stairs aside). */
    boolean pocket(int node)
    {
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        queue.add(node);
        seen.add(node);
        while (!queue.isEmpty())
        {
            int at = queue.poll();
            if (stairsByOrigin.get(at) != null)
            {
                return false;
            }
            for (int d = 0; d < 4; d++)
            {
                int ax = Tiles.x(at);
                int ay = Tiles.y(at);
                int az = Tiles.z(at);
                if (map.canStep(ax, ay, az, DX[d], DY[d]))
                {
                    int next = Tiles.pack(ax + DX[d], ay + DY[d], az);
                    if (seen.add(next))
                    {
                        if (seen.size() >= POCKET)
                        {
                            return false;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Every land tile the request's start (and its teleports) can reach, whatever the cost: for checking the data for
     * places nothing leads to. Sea is left out. Indexed by {@link Tiles#pack}; about 128 MB, a development aid.
     */
    public java.util.BitSet reachable(RouteRequest request)
    {
        java.util.BitSet seen = new java.util.BitSet(1 << 30);
        // Sea blocks apart: their index has the sea bit, which would double the land set.
        java.util.BitSet seaSeen = new java.util.BitSet();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        EdgeIndex requestByOrigin = EdgeIndex.of(request.edges, 0);
        java.util.function.IntConsumer visit = node -> {
            java.util.BitSet set = Tiles.isSea(node) ? seaSeen : seen;
            int index = node & ~Tiles.SEA;
            if (!set.get(index))
            {
                set.set(index);
                queue.add(node);
            }
        };
        int first = startNode(request.start);
        if (first >= 0)
        {
            visit.accept(first);
        }
        for (Edge e : request.startEdges)
        {
            visit.accept(e.to);
        }
        while (!queue.isEmpty())
        {
            int node = queue.poll();
            if (Tiles.isSea(node))
            {
                int cx = Tiles.cellX(node);
                int cy = Tiles.cellY(node);
                for (int d = 0; d < 8; d++)
                {
                    if (sea.sailable(cx + DX[d], cy + DY[d], request.sailingLevel))
                    {
                        visit.accept(Tiles.sea(cx + DX[d], cy + DY[d]));
                    }
                }
            }
            else
            {
                int x = Tiles.x(node);
                int y = Tiles.y(node);
                int z = Tiles.z(node);
                for (int d = 0; d < 8; d++)
                {
                    if (map.canStep(x, y, z, DX[d], DY[d]))
                    {
                        visit.accept(Tiles.pack(x + DX[d], y + DY[d], z));
                    }
                }
                int[] up = stairsByOrigin.get(node);
                if (up != null)
                {
                    for (int i : up)
                    {
                        visit.accept(stairs.get(i).to);
                    }
                }
            }
            int[] out = requestByOrigin.get(node);
            if (out != null)
            {
                for (int i : out)
                {
                    visit.accept(request.edges.get(i).to);
                }
            }
        }
        return seen;
    }

    public Route find(RouteRequest request, BooleanSupplier cancelled)
    {
        int target = request.target;
        int goal = resolveGoal(target);
        List<Edge> all = new ArrayList<>(stairs.size() + request.edges.size() + request.startEdges.size());
        all.addAll(stairs);
        int requestOffset = all.size();
        all.addAll(request.edges);
        int startOffset = all.size();
        all.addAll(request.startEdges);
        EdgeIndex requestByOrigin = EdgeIndex.of(request.edges, requestOffset);
        List<Edge> jumps = new ArrayList<>(heuristicStairs);
        for (Edge e : request.edges)
        {
            if (shortCut(e))
            {
                jumps.add(e);
            }
        }
        Heuristic heuristic = new Heuristic(jumps, goal >= 0 ? goal : target);

        NodeTable nodes = new NodeTable(1 << 16);
        LongHeap open = new LongHeap();
        int step = request.running ? RUN : WALK;
        int start = startNode(request.start);
        if (start >= 0)
        {
            nodes.put(start, 0, -1, -1);
            open.push(key(heuristic.h(start), start));
        }
        for (int i = startOffset; i < all.size(); i++)
        {
            Edge e = all.get(i);
            if (e.cost < nodes.cost(e.to))
            {
                nodes.put(e.to, e.cost, -1, i);
                open.push(key(e.cost + heuristic.h(e.to), e.to));
            }
        }
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        int popped = 0;
        boolean limited = false;
        int found = -1;
        while (!open.isEmpty())
        {
            long item = open.pop();
            int node = (int) item;
            int g = nodes.cost(node);
            if ((int) (item >>> 32) != g + heuristic.h(node))
            {
                continue;
            }
            if ((++popped & 4095) == 0 && cancelled.getAsBoolean())
            {
                return new Route(Route.Outcome.CANCELLED, Collections.emptyList(), 0, target, -1, false, false, popped);
            }
            if (node == goal)
            {
                found = node;
                break;
            }
            if (!Tiles.isSea(node))
            {
                long score = (long) (Math.max(Math.abs(Tiles.x(node) - Tiles.x(target)), Math.abs(Tiles.y(node) - Tiles.y(target)))
                    + (Tiles.z(node) != Tiles.z(target) ? 32 : 0)) << 32 | g;
                if (score < bestScore)
                {
                    bestScore = score;
                    best = node;
                }
            }
            if (nodes.size() > request.nodeLimit)
            {
                limited = true;
                break;
            }
            if (Tiles.isSea(node))
            {
                int cx = Tiles.cellX(node);
                int cy = Tiles.cellY(node);
                for (int d = 0; d < 8; d++)
                {
                    int nx = cx + DX[d];
                    int ny = cy + DY[d];
                    if (sea.sailable(nx, ny, request.sailingLevel))
                    {
                        relax(nodes, open, heuristic, node, Tiles.sea(nx, ny), g + SAIL, -1);
                    }
                }
            }
            else
            {
                int x = Tiles.x(node);
                int y = Tiles.y(node);
                int z = Tiles.z(node);
                for (int d = 0; d < 8; d++)
                {
                    if (map.canStep(x, y, z, DX[d], DY[d]))
                    {
                        int next = Tiles.pack(x + DX[d], y + DY[d], z);
                        int cost = step + (d < 4 && map.door(x, y, z, DX[d], DY[d]) ? DOOR : 0)
                            + (map.isObstacle(x + DX[d], y + DY[d], next) ? OBSTACLE : 0);
                        relax(nodes, open, heuristic, node, next, g + cost, -1);
                    }
                }
                jumps(stairsByOrigin.get(node), all, nodes, open, heuristic, node, g);
            }
            jumps(requestByOrigin.get(node), all, nodes, open, heuristic, node, g);
        }
        boolean exhausted = found < 0 && !limited;
        int end = found >= 0 ? found : best;
        if (end < 0)
        {
            return new Route(Route.Outcome.NONE, Collections.emptyList(), 0, target, -1, exhausted, limited, popped);
        }
        List<Route.Step> steps = steps(nodes, all, end);
        return new Route(found >= 0 ? Route.Outcome.FOUND : Route.Outcome.NEAREST, steps, nodes.cost(end), target, end,
            exhausted, limited, popped);
    }

    private static void jumps(int[] edges, List<Edge> all, NodeTable nodes, LongHeap open, Heuristic heuristic, int node,
        int g)
    {
        if (edges == null)
        {
            return;
        }
        for (int i : edges)
        {
            Edge e = all.get(i);
            relax(nodes, open, heuristic, node, e.to, g + e.cost, i);
        }
    }

    private static void relax(NodeTable nodes, LongHeap open, Heuristic heuristic, int from, int to, int cost, int edge)
    {
        if (cost < nodes.cost(to))
        {
            nodes.put(to, cost, from, edge);
            open.push(key(cost + heuristic.h(to), to));
        }
    }

    private static long key(int f, int node)
    {
        return (long) f << 32 | (node & 0xffffffffL);
    }

    private List<Route.Step> steps(NodeTable nodes, List<Edge> all, int end)
    {
        List<Route.Step> reversed = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        int node = end;
        run.add(node);
        while (true)
        {
            int via = nodes.via(node);
            int parent = nodes.parent(node);
            if (via >= 0)
            {
                flush(reversed, run, nodes);
                Edge e = all.get(via);
                int from = parent >= 0 ? parent : node;
                reversed.add(new Route.Step(kind(e.kind), new int[]{from, node}, e.name, e.detail, e.time, 0,
                    Collections.emptyList(), e.category));
                run.clear();
                if (parent < 0)
                {
                    break;
                }
                run.add(parent);
                node = parent;
                continue;
            }
            if (parent < 0)
            {
                break;
            }
            run.add(parent);
            node = parent;
        }
        flush(reversed, run, nodes);
        Collections.reverse(reversed);
        return reversed;
    }

    /** Turns a run of walked tiles or sailed blocks (collected backwards) into a step. */
    private void flush(List<Route.Step> reversed, List<Integer> run, NodeTable nodes)
    {
        if (run.size() >= 2)
        {
            int[] points = new int[run.size()];
            int doors = 0;
            for (int i = 0; i < points.length; i++)
            {
                points[i] = run.get(points.length - 1 - i);
            }
            for (int i = 1; i < points.length; i++)
            {
                int a = points[i - 1];
                int b = points[i];
                int dx = Tiles.x(b) - Tiles.x(a);
                int dy = Tiles.y(b) - Tiles.y(a);
                if (!Tiles.isSea(a) && (dx == 0 || dy == 0) && map.door(Tiles.x(a), Tiles.y(a), Tiles.z(a), dx, dy))
                {
                    doors++;
                }
            }
            int cost = nodes.cost(points[points.length - 1]) - nodes.cost(points[0]);
            boolean sailing = Tiles.isSea(points[0]);
            if (!sailing)
            {
                // The tiles the game itself walks between the same ends.
                points = GameWalk.follow(map, points);
            }
            List<Route.Obstacle> obstacles = new ArrayList<>();
            StringBuilder detail = new StringBuilder();
            if (!sailing)
            {
                for (int point : points)
                {
                    String obstacle = map.obstacle(point);
                    if (obstacle != null)
                    {
                        obstacles.add(new Route.Obstacle(point, obstacle));
                        if (detail.indexOf(obstacle) < 0)
                        {
                            detail.append(detail.length() > 0 ? "; " : "").append(obstacle);
                        }
                    }
                }
            }
            reversed.add(new Route.Step(sailing ? Route.Step.Kind.SAIL : Route.Step.Kind.WALK, points, null,
                sailing ? hazards(points) : detail.length() > 0 ? detail.toString() : null, cost, doors, obstacles));
        }
        int last = run.isEmpty() ? -1 : run.get(run.size() - 1);
        run.clear();
        if (last >= 0)
        {
            run.add(last);
        }
    }

    /** The hazardous seas a sailing leg crosses, as "Stormy seas (Kharazi Strait)", or null. */
    private String hazards(int[] points)
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < points.length; i += 3)
        {
            SeaMap.Area area = sea.nearest(Tiles.x(points[i]), Tiles.y(points[i]));
            if (area != null && area.level > 0 && !area.hazard.isEmpty())
            {
                String text = area.hazard + " (" + area.name + ", " + area.level + " Sailing)";
                if (!names.contains(text))
                {
                    names.add(text);
                }
            }
        }
        return names.isEmpty() ? null : "Crosses " + String.join(", ", names);
    }

    private static Route.Step.Kind kind(Edge.Kind kind)
    {
        switch (kind)
        {
            case STAIRS:
                return Route.Step.Kind.STAIRS;
            case ENTRANCE:
                return Route.Step.Kind.ENTRANCE;
            case TELEPORT:
                return Route.Step.Kind.TELEPORT;
            case SHIP:
                return Route.Step.Kind.SHIP;
            case BOARD:
                return Route.Step.Kind.BOARD;
            case DISEMBARK:
                return Route.Step.Kind.DISEMBARK;
            case HOUSE:
                return Route.Step.Kind.HOUSE;
            default:
                return Route.Step.Kind.TRANSPORT;
        }
    }

    /**
     * A lower bound of the cost to the goal: the folded distance, or the way through a jump the distance cannot see (its
     * lower bound from a backward search over those jumps), with jump bounds cached per 8×8 block.
     */
    private static final class Heuristic
    {
        private final int goal;
        private final int[] origins;
        private final int[] bounds;
        private final IntMap blocks = new IntMap(1 << 12);

        /** {@code jumps}: the jumps with {@link #shortCut}, all starting somewhere. */
        Heuristic(List<Edge> jumps, int goal)
        {
            this.goal = goal;
            int n = jumps.size();
            long[] bound = new long[n];
            boolean[] done = new boolean[n];
            int[] from = new int[n];
            int[] to = new int[n];
            int[] cost = new int[n];
            for (int i = 0; i < n; i++)
            {
                Edge e = jumps.get(i);
                from[i] = e.from;
                to[i] = e.to;
                cost[i] = e.cost;
                bound[i] = (long) e.cost + folded(e.to, goal);
            }
            // Backward Dijkstra over the jumps: the cheapest way from each jump's start to the goal, walking between
            // jumps as the crow flies.
            for (int round = 0; round < n; round++)
            {
                int pick = -1;
                for (int i = 0; i < n; i++)
                {
                    if (!done[i] && (pick < 0 || bound[i] < bound[pick]))
                    {
                        pick = i;
                    }
                }
                done[pick] = true;
                int start = from[pick];
                long reached = bound[pick];
                for (int i = 0; i < n; i++)
                {
                    if (!done[i])
                    {
                        long via = cost[i] + folded(to[i], start) + reached;
                        if (via < bound[i])
                        {
                            bound[i] = via;
                        }
                    }
                }
            }
            IntMap byOrigin = new IntMap(n);
            int count = 0;
            int[] keys = new int[n];
            for (int i = 0; i < n; i++)
            {
                int b = (int) Math.min(Integer.MAX_VALUE, bound[i]);
                int known = byOrigin.get(from[i]);
                if (known == IntMap.MISSING)
                {
                    keys[count++] = from[i];
                    byOrigin.put(from[i], b);
                }
                else if (b < known)
                {
                    byOrigin.put(from[i], b);
                }
            }
            origins = new int[count];
            bounds = new int[count];
            for (int i = 0; i < count; i++)
            {
                origins[i] = keys[i];
                bounds[i] = byOrigin.get(keys[i]);
            }
        }

        int h(int node)
        {
            int direct = folded(node, goal);
            if (origins.length == 0)
            {
                return direct;
            }
            int bx = Tiles.x(node) / BLOCK;
            int by = fy(node) / BLOCK;
            int blockKey = bx << 16 | by;
            int cached = blocks.get(blockKey);
            if (cached == IntMap.MISSING)
            {
                int cx = bx * BLOCK + BLOCK / 2;
                int cy = by * BLOCK + BLOCK / 2;
                int min = Integer.MAX_VALUE;
                for (int i = 0; i < origins.length; i++)
                {
                    int d = Math.max(Math.abs(Tiles.x(origins[i]) - cx), Math.abs(fy(origins[i]) - cy));
                    min = Math.min(min, Math.max(0, d - BLOCK / 2) + bounds[i]);
                }
                cached = min;
                blocks.put(blockKey, cached);
            }
            return Math.min(direct, cached);
        }
    }
}
