package com.hdmapreforged;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.util.Text;

/**
 * Where a party member is, sent to their party by this plugin. The class name is the message's type on the party
 * server, so it is kept distinctive. Short field names keep the message small.
 */
public class HdMapPartyLocation extends PartyMemberMessage
{
    /** Highest tile coordinate accepted from others; the game world fits well inside it. */
    static final int MAX_COORDINATE = 16383;
    static final int MAX_NAME = 12;
    /** Longer names are not looked at: a name with its tags is never near this long. */
    private static final int MAX_RAW_NAME = 64;

    private final int x;
    private final int y;
    private final int p;
    /** The member's world, 0 when unknown. */
    private final int w;
    /** The member's character name. */
    private final String n;

    HdMapPartyLocation(WorldPoint point, int world, String name)
    {
        x = point == null ? -1 : point.getX();
        y = point == null ? -1 : point.getY();
        p = point == null ? 0 : point.getPlane();
        w = world;
        n = name;
    }

    /** A message saying the member left the game, so others take their marker off the map. */
    static HdMapPartyLocation offline(String name)
    {
        return new HdMapPartyLocation(null, 0, name);
    }

    /** The location, or null when offline or out of range (messages come from other clients). */
    WorldPoint point()
    {
        if (!plausible(x, y, p))
        {
            return null;
        }
        return new WorldPoint(x, y, p);
    }

    /** Whether a location from another client lies in the game world (also RuneLite Party plugin's messages). */
    static boolean plausible(WorldPoint point)
    {
        return point != null && plausible(point.getX(), point.getY(), point.getPlane());
    }

    private static boolean plausible(int x, int y, int plane)
    {
        return x >= 0 && y >= 0 && x <= MAX_COORDINATE && y <= MAX_COORDINATE && plane >= 0 && plane <= 3;
    }

    int world()
    {
        return w > 0 && w < 1000 ? w : 0;
    }

    /** The character name, or null if missing or implausible. */
    String name()
    {
        if (n == null || n.length() > MAX_RAW_NAME)
        {
            return null;
        }
        String name = Text.removeTags(n).replace('\u00a0', ' ').trim();
        return name.isEmpty() || name.length() > MAX_NAME ? null : name;
    }
}
