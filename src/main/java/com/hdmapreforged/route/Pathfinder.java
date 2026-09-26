package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * A* over tiles, sea blocks and jumps ({@link Edge}); costs in half ticks. The heuristic stays a lower bound by folding
 * underground y onto the surface (y − 6400) and bounding far/cheap jumps with a backward search over the jumps alone.
 * Thread-safe: each search keeps its own state; the maps are immutable.
 */
public final class Pathfinder
{
    /** Half ticks. */
    static final int RUN = 1;
    static final int WALK = 2;
    static final int DOOR = 2;
    /** Vines, jungle or a web: needs a tool, so walking around is better. */
    static final int OBSTACLE = 10;
    static final int SAIL = Tiles.CELL;
    static final int STAIRS = 5;
    /** Through a solid object found by shape: only when no known way is much shorter. */
    static final int THROUGH = 30;
    static final int SNAP_RADIUS = 12;
    private static final int LONG_JUMP = 24;
    private static final int BLOCK = 8;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] NO_EDGES = {};

    private final CollisionMap map;
    private final SeaMap sea;
    private final List<Edge> stairs;
    private final List<ShortcutPassage> shortcutPassages;
    /** For route requests built without this pathfinder at hand. */
    private static final Map<CollisionMap, List<ShortcutPassage>> SHORTCUT_PASSAGES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private final EdgeIndex stairsByOrigin;
    private final List<Edge> heuristicStairs;

    public Pathfinder(CollisionMap map, SeaMap sea)
    {
        this.map = map;
        this.sea = sea;
        List<Edge> edges = new ArrayList<>();
        // Passages only from (see local-development/APPROACH.md): the game's map links, hand links citing a source,
        // and cache passages within SAME_SPOT (a far one only where a map or hand link confirms it).
        List<Edge> mapLinks = links(map, "/com/hdmapreforged/route/map_link_passages.tsv");
        List<Edge> handLinks = links(map, "/com/hdmapreforged/route/links.tsv");
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
                if (sameSpot(origin, t.to) || confirmed(mapLinks, origin, t.to) || confirmed(handLinks, origin, t.to))
                {
                    edges.add(new Edge(origin, t.to, kind, name, detail, cost));
                }
            }
        }
        edges.addAll(mapLinks);
        edges.addAll(handLinks);
        OneWay oneWay = OneWay.load();
        edges.removeIf(e -> oneWay.against(e.from, e.to));
        // Passages that are wiki Agility shortcuts: added only to requests whose player meets the shortcut.
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
        heuristicStairs = edges.stream().filter(Pathfinder::shortCut).collect(Collectors.toUnmodifiableList());
    }

    public List<ShortcutPassage> shortcutPassages()
    {
        return shortcutPassages;
    }

    public static List<ShortcutPassage> shortcutPassages(CollisionMap map)
    {
        List<ShortcutPassage> known = SHORTCUT_PASSAGES.get(map);
        return known != null ? known : new Pathfinder(map, null).shortcutPassages;
    }

    public List<Edge> passages()
    {
        return stairs;
    }

    public static boolean far(int from, int to)
    {
        return Tiles.distance(from, to) > 64 || Math.abs(Tiles.y(from) - Tiles.y(to)) > 1000;
    }

    static boolean sameSpot(int from, int to)
    {
        return Math.max(Math.abs(Tiles.x(from) - Tiles.x(to)), Math.abs(Tiles.y(from) - Tiles.y(to))) <= SAME_SPOT;
    }

    /** Guessed cache passages leap far further (6400 tiles north), so this keeps only what the game decides. */
    static final int SAME_SPOT = 10;

    private static boolean confirmed(List<Edge> passages, int from, int to)
    {
        return passages.stream().anyMatch(e -> Tiles.z(e.from) == Tiles.z(from) && Tiles.distance(e.from, from) <= 4
            && Tiles.z(e.to) == Tiles.z(to) && Tiles.distance(e.to, to) <= 16);
    }

    private static List<Edge> links(CollisionMap map, String resource)
    {
        List<Edge> edges = new ArrayList<>();
        for (String[] parts : rows(resource))
        {
            int a = parts.length < 3 ? -1 : Tiles.parse(parts[0]);
            int b = a < 0 ? -1 : Tiles.parse(parts[1]);
            int from = b < 0 ? -1 : map.nearestWalkable(Tiles.x(a), Tiles.y(a), Tiles.z(a), 2);
            int to = from < 0 ? -1 : map.nearestWalkable(Tiles.x(b), Tiles.y(b), Tiles.z(b), 2);
            if (to >= 0)
            {
                edges.add(new Edge(from, to, Edge.Kind.ENTRANCE, parts[2].trim(), null, STAIRS));
            }
        }
        return edges;
    }

    /** A bundled table's lines split at tabs, without {@code #} comments; what could be read. */
    public static List<String[]> rows(String resource)
    {
        List<String[]> rows = new ArrayList<>();
        InputStream in = Pathfinder.class.getResourceAsStream(resource);
        if (in != null)
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                for (String line = reader.readLine(); line != null; line = reader.readLine())
                {
                    if (!line.startsWith("#"))
                    {
                        rows.add(line.split("\t"));
                    }
                }
            }
            catch (IOException e)
            {
            }
        }
        return rows;
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

    /** Jumps the heuristic must know, else it would overestimate and A* could miss the best way. */
    private static boolean shortCut(Edge e)
    {
        if (e.from == Edge.ANYWHERE)
        {
            return false;
        }
        int length = folded(e.from, e.to);
        return length > LONG_JUMP || e.cost < length * RUN;
    }

    public int resolveGoal(int target)
    {
        if (Tiles.isSea(target))
        {
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
        // Only a pocket here: the wiki may give the icon on the wrong floor.
        int there = otherFloor(x, y, z, 3, true);
        if (there >= 0 || near >= 0)
        {
            return there >= 0 ? there : near;
        }
        // Wrong floor in the wiki (Brimhaven's upper fire giants): the same spot on another floor first.
        near = otherFloor(x, y, z, 1, false);
        if (near < 0)
        {
            near = otherFloor(x, y, z, SNAP_RADIUS, false);
        }
        if (near >= 0)
        {
            return near;
        }
        if (z == 0 && sea != null && sea.sailable(x / Tiles.CELL, y / Tiles.CELL, 99))
        {
            // Open water with no ground near: sailed to, not walked to a far shore.
            return Tiles.seaAt(x, y);
        }
        return -1;
    }

    /** {@code open}: the nearest open non-pocket tile, else any walkable one. */
    private int otherFloor(int x, int y, int z, int radius, boolean open)
    {
        for (int dz = 1; dz < 4; dz++)
        {
            for (int other : new int[]{z - dz, z + dz})
            {
                int near = other < 0 || other > 3 ? -1
                    : open ? nearestOpen(x, y, other, radius) : map.nearestWalkable(x, y, other, radius);
                if (near >= 0 && !(open && pocket(near)))
                {
                    return near;
                }
            }
        }
        return -1;
    }

    /** A start the data calls unwalkable (a boat's deck, a stepping stone) begins from the nearest open tile. */
    private int startNode(int start)
    {
        if (start < 0 || Tiles.isSea(start) || map.walkable(Tiles.x(start), Tiles.y(start), Tiles.z(start)))
        {
            return start;
        }
        int near = nearestOpen(Tiles.x(start), Tiles.y(start), Tiles.z(start), 5);
        return near >= 0 ? near : start;
    }

    /** Nearest walkable non-pocket tile, else nearest walkable, else -1. */
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

    private static final int POCKET = 120;

    /** A small shut-in area (behind bank booths, between stepping stones) with no stairs. */
    boolean pocket(int node)
    {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(node);
        seen.add(node);
        while (!queue.isEmpty())
        {
            int at = queue.poll();
            if (stairsByOrigin.get(at) != null)
            {
                return false;
            }
            int ax = Tiles.x(at);
            int ay = Tiles.y(at);
            int az = Tiles.z(at);
            for (int d = 0; d < 4; d++)
            {
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

    /** Every land tile reachable from the request, by {@link Tiles#pack}; about 128 MB, a development aid. */
    public BitSet reachable(RouteRequest request)
    {
        BitSet seen = new BitSet(1 << 30);
        BitSet seaSeen = new BitSet();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        EdgeIndex requestByOrigin = EdgeIndex.of(request.edges, 0);
        java.util.function.IntConsumer visit = node -> {
            BitSet set = Tiles.isSea(node) ? seaSeen : seen;
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
            boolean sailing = Tiles.isSea(node);
            int x = sailing ? Tiles.cellX(node) : Tiles.x(node);
            int y = sailing ? Tiles.cellY(node) : Tiles.y(node);
            int z = Tiles.z(node);
            for (int d = 0; d < 8; d++)
            {
                if (sailing ? sea.sailable(x + DX[d], y + DY[d], request.sailingLevel) : map.canStep(x, y, z, DX[d], DY[d]))
                {
                    visit.accept(sailing ? Tiles.sea(x + DX[d], y + DY[d]) : Tiles.pack(x + DX[d], y + DY[d], z));
                }
            }
            int[] up = sailing ? null : stairsByOrigin.get(node);
            int[] out = requestByOrigin.get(node);
            for (int i : up != null ? up : NO_EDGES)
            {
                visit.accept(stairs.get(i).to);
            }
            for (int i : out != null ? out : NO_EDGES)
            {
                visit.accept(request.edges.get(i).to);
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
            relax(nodes, open, heuristic, -1, start, 0, -1);
        }
        for (int i = startOffset; i < all.size(); i++)
        {
            relax(nodes, open, heuristic, -1, all.get(i).to, all.get(i).cost, i);
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
                reversed.add(new Route.Step(Route.Step.Kind.valueOf(e.kind.name()), new int[]{from, node}, e.name,
                    e.detail, e.time, 0, Collections.emptyList(), e.category));
                run.clear();
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

    /** {@code run} is collected backwards. */
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
            List<Route.Obstacle> obstacles = new ArrayList<>();
            StringBuilder detail = new StringBuilder();
            if (!sailing)
            {
                points = GameWalk.follow(map, points);
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
                sailing ? hazards(points) : detail.length() > 0 ? detail.toString() : null, cost, doors, obstacles, null));
        }
        // Only the last point stays.
        run.subList(0, Math.max(0, run.size() - 1)).clear();
    }

    private String hazards(int[] points)
    {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < points.length; i += 3)
        {
            SeaMap.Area area = sea.nearest(Tiles.x(points[i]), Tiles.y(points[i]));
            if (area != null && area.level > 0 && !area.hazard.isEmpty())
            {
                names.add(area.hazard + " (" + area.name + ", " + area.level + " Sailing)");
            }
        }
        return names.isEmpty() ? null : "Crosses " + String.join(", ", names);
    }

    /** Folded distance, or via a jump's backward-searched bound; jump bounds cached per 8×8 block. */
    private static final class Heuristic
    {
        private final int goal;
        private final int[] origins;
        private final int[] bounds;
        private final IntMap blocks = new IntMap(1 << 12);

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
            // Backward Dijkstra over the jumps, walking between them as the crow flies.
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
                }
                if (known == IntMap.MISSING || b < known)
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
