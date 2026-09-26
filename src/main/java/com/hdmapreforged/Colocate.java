package com.hdmapreforged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Things at one place become one icon: teleports landing at a host icon, minigames in one building, twins. */
final class Colocate
{
    static final int TELEPORT_RADIUS = 3;
    static final int MINIGAME_RADIUS = 12;

    private Colocate()
    {
    }

    static List<Poi> merge(List<Poi> pois)
    {
        List<Poi> hosts = pois.stream().filter(Colocate::isHost).collect(Collectors.toList());
        List<Poi> kept = new ArrayList<>(pois.size());
        List<Poi> minigames = new ArrayList<>();
        for (Poi poi : pois)
        {
            boolean teleport = poi.type == PoiType.TELEPORT;
            boolean minigame = poi.type == PoiType.MINIGAME;
            Poi host = teleport ? nearest(hosts, poi, TELEPORT_RADIUS)
                : minigame ? nearest(minigames, poi, MINIGAME_RADIUS) : null;
            if (host != null)
            {
                host.addNearby(poi);
                continue;
            }
            if (minigame)
            {
                minigames.add(poi);
            }
            kept.add(poi);
        }
        return twins(kept);
    }

    /** The same place under one name this close is drawn once. */
    static final int TWIN_RADIUS = 4;

    /** The first keeps the icon; the others are listed on it and still used for routes. */
    private static List<Poi> twins(List<Poi> pois)
    {
        List<Poi> kept = new ArrayList<>(pois.size());
        Map<String, List<Poi>> byName = new HashMap<>();

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
        return name == null ? "" : NOT_KEY.matcher(BRACKETS.matcher(name.toLowerCase(Locale.ROOT))
            .replaceAll("")).replaceAll("");
    }

    private static final Pattern BRACKETS = Pattern.compile("\\s*\\(.*\\)");
    private static final Pattern NOT_KEY = Pattern.compile("[^a-z0-9]");

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
