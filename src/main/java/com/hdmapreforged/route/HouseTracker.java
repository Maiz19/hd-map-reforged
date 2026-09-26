package com.hdmapreforged.route;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The player-owned house is an instance far from its town, so routes start from its ways out: the exit portal and,
 * in the player's own house only, the portals and such seen there. Teleporting in or building mode means one's own
 * house; entering by a house portal may be a friend's. Client thread only.
 */
public final class HouseTracker
{
    static final int TEMPLATE_WEST = 1856;
    static final int TEMPLATE_EAST = 2047;
    static final int TEMPLATE_SOUTH = 5696;
    static final int TEMPLATE_NORTH = 5775;
    /** By varbit 2187 (POH_HOUSE_LOCATION). */
    private static final int[][] PORTALS = {
        null,
        {2953, 3224}, // Rimmington
        {2893, 3465}, // Taverley
        {3340, 3003}, // Pollnivneach
        {2670, 3631}, // Rellekka
        {2757, 3178}, // Brimhaven
        {2544, 3096}, // Yanille
        {3239, 6076}, // Prifddinas
        {1743, 3517}, // Hosidius
        {1422, 2963}, // Aldarin
    };
    private static final int PORTAL_RADIUS = 12;

    private boolean inside;
    private int lastOutside = -1;
    private int exit = -1;
    private boolean own;
    private final Set<String> features = new LinkedHashSet<>();
    private boolean featuresChanged;

    public static boolean isTemplate(int x, int y)
    {
        return x >= TEMPLATE_WEST && x <= TEMPLATE_EAST && y >= TEMPLATE_SOUTH && y <= TEMPLATE_NORTH;
    }

    public static int portal(int location)
    {
        return location > 0 && location < PORTALS.length ? Tiles.pack(PORTALS[location][0], PORTALS[location][1], 0) : -1;
    }

    static int portalNear(int node)
    {
        for (int i = 1; i < PORTALS.length; i++)
        {
            if (Tiles.z(node) == 0 && Math.abs(Tiles.x(node) - PORTALS[i][0]) <= PORTAL_RADIUS
                && Math.abs(Tiles.y(node) - PORTALS[i][1]) <= PORTAL_RADIUS)
            {
                return i;
            }
        }
        return 0;
    }

    /** Every tick. */
    public void update(int node, boolean instance, int houseLocation, boolean buildingMode)
    {
        boolean nowInside = instance && node >= 0 && isTemplate(Tiles.x(node), Tiles.y(node));
        if (nowInside && !inside)
        {
            int entered = lastOutside >= 0 ? portalNear(lastOutside) : 0;
            if (entered > 0)
            {
                // Through a house portal: maybe a friend's house.
                exit = portal(entered);
                own = false;
            }
            else
            {
                exit = portal(houseLocation);
                own = true;
            }
        }
        if (nowInside && buildingMode)
        {
            own = true;
        }
        if (!nowInside && node >= 0 && !instance)
        {
            lastOutside = node;
        }
        inside = nowInside;
    }

    public boolean inside()
    {
        return inside;
    }

    public boolean own()
    {
        return inside && own;
    }

    public int exit()
    {
        return exit;
    }

    /** Remembered only in the player's own house; true when this added something. */
    public boolean seen(String objectName)
    {
        if (!own() || objectName == null)
        {
            return false;
        }
        String feature = feature(objectName);
        if (feature != null && features.add(feature))
        {
            featuresChanged = true;
            return true;
        }
        return false;
    }

    static String feature(String objectName)
    {
        String n = objectName.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" portal") && !lower.startsWith("exit") && !lower.equals("portal") && !lower.contains("nexus"))
        {
            return "portal:" + n.substring(0, n.length() - " portal".length()).trim();
        }
        if (lower.contains("jewellery box"))
        {
            return lower.startsWith("ornate") ? "box:ornate" : lower.startsWith("fancy") ? "box:fancy" : "box:basic";
        }
        if (lower.equals("fairy ring") || lower.equals("spiritual fairy tree"))
        {
            return lower.equals("fairy ring") ? "fairy ring" : "spirit tree+fairy ring";
        }
        if (lower.equals("spirit tree"))
        {
            return "spirit tree";
        }
        if (lower.contains("amulet of glory"))
        {
            return "glory";
        }
        return null;
    }

    public Set<String> features()
    {
        return Collections.unmodifiableSet(features);
    }

    public void restore(Set<String> saved)
    {
        features.clear();
        for (String f : saved)
        {
            if (f.startsWith("portal:") || f.startsWith("box:") || f.equals("fairy ring") || f.equals("spirit tree")
                || f.equals("spirit tree+fairy ring") || f.equals("glory"))
            {
                features.add(f);
            }
        }
        featuresChanged = false;
    }

    public boolean takeChanged()
    {
        boolean changed = featuresChanged;
        featuresChanged = false;
        return changed;
    }

    public void replaceFeatures(Collection<String> known)
    {
        features.clear();
        features.addAll(known);
        featuresChanged = false;
    }

    public void reset()
    {
        inside = false;
        lastOutside = -1;
        exit = -1;
        own = false;
        features.clear();
        featuresChanged = false;
    }
}
