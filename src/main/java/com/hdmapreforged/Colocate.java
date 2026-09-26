package com.hdmapreforged;

import java.util.ArrayList;
import java.util.List;

/**
 * Things at the same place become one icon: a teleport that lands right at a portal, transport stop, minigame or
 * dungeon entrance is listed on that icon ("Teleports that land here") instead of drawn beside it, and minigames in
 * one building share an icon, as the game's map shows them.
 */
final class Colocate
{
    /** A teleport landing this close to another icon lands at it. */
    static final int TELEPORT_RADIUS = 3;
    /** Minigames this close are in the same place. */
    static final int MINIGAME_RADIUS = 12;

    private Colocate()
    {
    }

    static List<Poi> merge(List<Poi> pois)
    {
        List<Poi> hosts = new ArrayList<>();
        for (Poi poi : pois)
        {
            if (isHost(poi))
            {
                hosts.add(poi);
            }
        }
        List<Poi> kept = new ArrayList<>(pois.size());
        List<Poi> minigames = new ArrayList<>();
        for (Poi poi : pois)
        {
            if (poi.type == PoiType.TELEPORT)
            {
                Poi host = nearest(hosts, poi, TELEPORT_RADIUS);
                if (host != null)
                {
                    host.addNearby(poi);
                    continue;
                }
            }
            else if (poi.type == PoiType.MINIGAME)
            {
                Poi host = nearest(minigames, poi, MINIGAME_RADIUS);
                if (host != null)
                {
                    host.addNearby(poi);
                    continue;
                }
                minigames.add(poi);
            }
            kept.add(poi);
        }
        return twins(kept);
    }

    /** The same place under one name this close is drawn once. */
    static final int TWIN_RADIUS = 4;

    /**
     * One icon for what is one place: both ends of a short shortcut, a place listed twice, a minigame that is also a
     * dungeon entrance and an agility course. The first keeps the icon; the others are listed on it and still used
     * for routes.
     */
    private static List<Poi> twins(List<Poi> pois)
    {
        List<Poi> kept = new ArrayList<>(pois.size());
        java.util.Map<String, List<Poi>> byName = new java.util.HashMap<>();
        for (Poi poi : pois)
        {
            String key = key(poi.name);
            List<Poi> same = key.isEmpty() || poi.type == PoiType.TELEPORT ? null : byName.get(key);
            Poi host = same == null ? null : nearest(same, poi, TWIN_RADIUS);
            if (host != null)
            {
                host.addNearby(poi);
                continue;
            }
            if (!key.isEmpty())
            {
                byName.computeIfAbsent(key, k -> new ArrayList<>()).add(poi);
            }
            kept.add(poi);
        }
        return kept;
    }

    /** "Rocks (Al Kharid)" and "Rocks (Al Kharid)" alike; "Jutting wall (Cosmic Temple)" as "Jutting wall". */
    static String key(String name)
    {
        return name == null ? "" : NOT_KEY.matcher(BRACKETS.matcher(name.toLowerCase(java.util.Locale.ROOT))
            .replaceAll("")).replaceAll("");
    }

    private static final java.util.regex.Pattern BRACKETS = java.util.regex.Pattern.compile("\\s*\\(.*\\)");
    private static final java.util.regex.Pattern NOT_KEY = java.util.regex.Pattern.compile("[^a-z0-9]");

    private static boolean isHost(Poi poi)
    {
        Layer layer = poi.type.layer;
        return layer == Layer.TRANSPORTS || layer == Layer.ACTIVITIES || layer == Layer.DUNGEONS;
    }

    private static Poi nearest(List<Poi> candidates, Poi poi, int radius)
    {
        Poi best = null;
        int bestDistance = radius + 1;
        for (Poi other : candidates)
        {
            if (other == poi || other.map != poi.map || other.location.getPlane() != poi.location.getPlane())
            {
                continue;
            }
            int d = PoiLoader.chebyshev(other.location, poi.location);
            if (d < bestDistance)
            {
                best = other;
                bestDistance = d;
            }
        }
        return best;
    }
}
