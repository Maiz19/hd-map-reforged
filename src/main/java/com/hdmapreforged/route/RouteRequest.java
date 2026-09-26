package com.hdmapreforged.route;

import java.util.Collections;
import java.util.List;

/** What to search: from where, to where, with which of the player's transports. Immutable. */
public final class RouteRequest
{
    /**
     * Nodes a search may reach before it gives up. Proving a place out of reach takes a search over everything
     * reachable, about 2.1 million nodes with every requirement met (2026-09); below that, every place out of reach
     * came back as "too far" instead, and the way anyone could go was never found. About 1.5 s and well under 512 MB.
     */
    public static final int DEFAULT_NODE_LIMIT = 3_000_000;

    /** Packed start tile or sea block, or -1 when the route can only start with {@link #startEdges} (in a house). */
    public final int start;
    /** Packed target tile. */
    public final int target;
    /** Jumps available where the route starts: teleports, and the ways out of a player-owned house. */
    public final List<Edge> startEdges;
    /** Transports from particular tiles that this player may use, boarding and leaving their boat included. */
    public final List<Edge> edges;
    /** The player's Sailing level for the sea hazards, or -1 when they cannot sail their own boat. */
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
