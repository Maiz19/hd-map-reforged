package com.hdmapreforged.route;

import java.util.*;

/** Immutable. */
public final class RouteRequest
{
    /** Proving a place unreachable floods about 2.1 million nodes (2026-09); about 1.5 s, well under 512 MB. */
    public static final int DEFAULT_NODE_LIMIT = 3_000_000;

    /** -1 when the route can only start with {@link #startEdges} (in a house). */
    public final int start;
    public final int target;
    /** Teleports, and the ways out of a player-owned house. */
    public final List<Edge> startEdges;
    public final List<Edge> edges;
    /** -1 when they cannot sail their own boat. */
    public final int sailingLevel;
    public final boolean running;
    public final int nodeLimit;

    public RouteRequest(int start, int target, List<Edge> startEdges, List<Edge> edges, int sailingLevel, boolean running,
        int nodeLimit)
    {
        this.start = start;
        this.target = target;
        this.startEdges = Collections.unmodifiableList(startEdges);
        this.edges = Collections.unmodifiableList(edges);
        this.sailingLevel = sailingLevel;
        this.running = running;
        this.nodeLimit = nodeLimit;
    }
}
