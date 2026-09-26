package com.hdmapreforged.route;

/**
 * A jump in the route graph that is not a walking step: stairs, entrances, teleports, transport networks, ships,
 * and boarding or leaving one's own boat. Costs are in half game ticks (running one tile takes one).
 */
public final class Edge
{
    public enum Kind
    {
        /** Ladders, staircases and trapdoors from the game cache. */
        STAIRS,
        /** Dungeon entrances, passages, portals, levers and agility shortcuts. */
        ENTRANCE,
        /** Spells, jewellery and other teleports: usable where the route starts. */
        TELEPORT,
        /** Fairy rings, spirit trees, gliders, balloons, carts, carpets, canoes and the like. */
        TRANSPORT,
        /** Boats and charter ships one pays or talks to. */
        SHIP,
        /** Boarding one's own boat at a dock. */
        BOARD,
        /** Leaving one's own boat at a dock. */
        DISEMBARK,
        /** Leaving the player-owned house, or using something in it. */
        HOUSE
    }

    /** Where teleports start from: wherever the route starts. */
    public static final int ANYWHERE = -1;

    /** Packed origin tile ({@link Tiles}), or {@link #ANYWHERE}. */
    public final int from;
    /** Packed destination (a tile, or a sea block for boarding). */
    public final int to;
    public final Kind kind;
    /** What to do, such as "Varrock Teleport" or "Climb-up Ladder". */
    public final String name;
    /** What it needs or a remark shown under the step, or null. */
    public final String detail;
    /** Half ticks, as the search weighs it: the time it takes, plus any extra the player asked for. */
    public final int cost;
    /** Half ticks it really takes, for how long a route is. */
    public final int time;
    /** The kind of teleport or transport it is ("Hot air balloon", "Minigame teleports"), or null. */
    public final String category;

    public Edge(int from, int to, Kind kind, String name, String detail, int cost)
    {
        this(from, to, kind, name, detail, cost, null);
    }

    public Edge(int from, int to, Kind kind, String name, String detail, int cost, String category)
    {
        this.category = category;
        this.from = from;
        this.to = to;
        this.kind = kind;
        this.name = name;
        this.detail = detail;
        this.cost = Math.max(1, cost);
        this.time = this.cost;
    }

    private Edge(Edge edge, int extra)
    {
        this.category = edge.category;
        this.from = edge.from;
        this.to = edge.to;
        this.kind = edge.kind;
        this.name = edge.name;
        this.detail = edge.detail;
        this.cost = edge.cost + Math.max(0, extra);
        this.time = edge.time;
    }

    /**
     * This edge weighed {@code extra} half ticks heavier in the search ("only use a teleport when it saves at least
     * 10 tiles"), taking as long as before.
     */
    public Edge weighed(int extra)
    {
        return extra <= 0 ? this : new Edge(this, extra);
    }

    @Override
    public String toString()
    {
        return kind + " " + name + " " + (from == ANYWHERE ? "anywhere" : Tiles.format(from)) + " -> " + Tiles.format(to);
    }
}
