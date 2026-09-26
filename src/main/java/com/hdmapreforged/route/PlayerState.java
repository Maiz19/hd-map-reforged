package com.hdmapreforged.route;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** What a route may use, taken on the client thread and read elsewhere. Immutable. */
public final class PlayerState
{
    public static final PlayerState UNKNOWN = new PlayerState(ItemSnapshot.NONE, 1, false, new int[0], true, false, -1,
        false, Collections.emptySet());

    public final ItemSnapshot items;
    public final int sailingLevel;
    /** Pandemonium done: the player can sail their own boat. */
    public final boolean sailing;
    public final int[] boats;
    public final boolean running;
    public final boolean inHouse;
    public final int houseExit;
    public final boolean ownHouse;
    public final Set<String> houseFeatures;

    public PlayerState(ItemSnapshot items, int sailingLevel, boolean sailing, int[] boats, boolean running, boolean inHouse,
        int houseExit, boolean ownHouse, Set<String> houseFeatures)
    {
        this.items = items;
        this.sailingLevel = sailingLevel;
        this.sailing = sailing;
        this.boats = boats.clone();
        this.running = running;
        this.inHouse = inHouse;
        this.houseExit = houseExit;
        this.ownHouse = ownHouse;
        this.houseFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(houseFeatures));
    }
}
