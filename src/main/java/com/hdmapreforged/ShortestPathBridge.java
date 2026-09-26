package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;

/**
 * Talking to the Shortest Path plugin, if the player has it, through RuneLite's plugin messages: its public
 * message format only, none of its code. Plugins such as Quest Helper send it directions this way; routes asked for
 * on this map can be handed to it too.
 */
final class ShortestPathBridge
{
    static final String NAMESPACE = "shortestpath";
    static final String PATH = "path";
    static final String CLEAR = "clear";
    static final String TARGET = "target";
    /** The plugin's name in RuneLite's plugin list. */
    static final String PLUGIN_NAME = "Shortest Path";
    /** Jumps and targets read from one message at most: messages come from other plugins. */
    static final int MAX_POINTS = 64;

    private ShortestPathBridge()
    {
    }

    /** "Show a path to here" for Shortest Path. */
    static PluginMessage path(WorldPoint target)
    {
        Map<String, Object> data = new HashMap<>();
        data.put(TARGET, target);
        // Ask it to tell which transports its path uses, so this map can draw the route too.
        Map<String, Object> config = new HashMap<>();
        config.put("postTransports", true);
        data.put("config", config);
        return new PluginMessage(NAMESPACE, PATH, data);
    }

    static final String TRANSPORTS = "transports";

    /** One jump of Shortest Path's route: a transport or teleport, from where to where. */
    static final class Jump
    {
        final WorldPoint from;
        final WorldPoint to;
        final String name;

        Jump(WorldPoint from, WorldPoint to, String name)
        {
            this.from = from;
            this.to = to;
            this.name = name;
        }
    }

    static boolean isTransports(PluginMessage message)
    {
        return NAMESPACE.equals(message.getNamespace()) && TRANSPORTS.equals(message.getName());
    }

    /** The transports of Shortest Path's current route, in order; empty when unreadable. */
    static List<Jump> jumps(PluginMessage message)
    {
        Map<String, Object> data = message.getData();
        if (data == null || !(data.get("origin") instanceof List<?>) || !(data.get("destination") instanceof List<?>))
        {
            return Collections.emptyList();
        }
        List<?> origins = (List<?>) data.get("origin");
        List<?> destinations = (List<?>) data.get("destination");
        List<?> names = data.get("displayInfo") instanceof List<?> ? (List<?>) data.get("displayInfo") : Collections.emptyList();
        List<Jump> jumps = new ArrayList<>();
        for (int i = 0; i < origins.size() && i < destinations.size() && jumps.size() < MAX_POINTS; i++)
        {
            WorldPoint from = point(origins.get(i));
            WorldPoint to = point(destinations.get(i));
            if (from == null || to == null)
            {
                continue;
            }
            Object name = i < names.size() ? names.get(i) : null;
            jumps.add(new Jump(from, to, name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim()
                : "Transport"));
        }
        return jumps;
    }

    static PluginMessage clear()
    {
        return new PluginMessage(NAMESPACE, CLEAR);
    }

    static boolean isPath(PluginMessage message)
    {
        return NAMESPACE.equals(message.getNamespace()) && PATH.equals(message.getName());
    }

    static boolean isClear(PluginMessage message)
    {
        return NAMESPACE.equals(message.getNamespace()) && CLEAR.equals(message.getName());
    }

    /**
     * The targets of a path message: a WorldPoint, a packed point, or a collection of either. Empty when there is
     * none or it is unreadable.
     */
    static List<WorldPoint> targets(PluginMessage message)
    {
        Map<String, Object> data = message.getData();
        Object target = data == null ? null : data.get(TARGET);
        List<WorldPoint> points = new ArrayList<>();
        if (target instanceof Collection<?>)
        {
            for (Object one : (Collection<?>) target)
            {
                if (points.size() >= MAX_POINTS)
                {
                    break;
                }
                WorldPoint point = point(one);
                if (point != null)
                {
                    points.add(point);
                }
            }
        }
        else
        {
            WorldPoint point = point(target);
            if (point != null)
            {
                points.add(point);
            }
        }
        return points.isEmpty() ? Collections.emptyList() : points;
    }

    /**
     * A WorldPoint, or a point packed as x | y << 15 | plane << 30 (the message format); -1 means none. Null too for a
     * point outside the game world.
     */
    static WorldPoint point(Object value)
    {
        WorldPoint point = null;
        if (value instanceof WorldPoint)
        {
            point = (WorldPoint) value;
        }
        else if (value instanceof Integer)
        {
            int packed = (Integer) value;
            if (packed == -1)
            {
                return null;
            }
            point = new WorldPoint(packed & 0x7FFF, (packed >>> 15) & 0x7FFF, (packed >>> 30) & 0x3);
        }
        return HdMapPartyLocation.plausible(point) ? point : null;
    }

    /** Of several targets, the one nearest to where the player is (or the first when that is unknown). */
    static WorldPoint nearest(List<WorldPoint> targets, WorldPoint from)
    {
        WorldPoint best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (WorldPoint target : targets)
        {
            int d = from == null ? 0 : target.getPlane() == from.getPlane() ? target.distanceTo2D(from)
                : target.distanceTo2D(from) + 1000;
            if (best == null || d < bestDistance)
            {
                best = target;
                bestDistance = d;
            }
        }
        return best;
    }
}
