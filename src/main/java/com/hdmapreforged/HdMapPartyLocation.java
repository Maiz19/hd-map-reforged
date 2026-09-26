package com.hdmapreforged;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.util.Text;

/** Where a party member is. The class name is the message type on the party server; short field names keep it small. */
public class HdMapPartyLocation extends PartyMemberMessage
{
    static final int MAX_COORDINATE = 16383;
    static final int MAX_NAME = 12;
    private static final int MAX_RAW_NAME = 64;

    private final int x;
    private final int y;
    private final int p;
    /** World, 0 when unknown. */
    private final int w;
    private final String n;

    HdMapPartyLocation(WorldPoint point, int world, String name)
    {
        x = point == null ? -1 : point.getX();
        y = point == null ? -1 : point.getY();
        p = point == null ? 0 : point.getPlane();
        w = world;
        n = name;
    }

    static HdMapPartyLocation offline(String name)
    {
        return new HdMapPartyLocation(null, 0, name);
    }

    WorldPoint point()
    {
        return plausible(x, y, p) ? new WorldPoint(x, y, p) : null;
    }

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
