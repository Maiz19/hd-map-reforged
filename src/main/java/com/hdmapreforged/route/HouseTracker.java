package com.hdmapreforged.route;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps track of the player-owned house. Inside, the game puts the player in an instance whose tiles copy the house
 * templates far from the house's town, so a route cannot start from where the player stands; it starts from the ways
 * out instead: the exit portal (to where the player came in) and, in the player's own house only, what it has
 * (portals, jewellery box, fairy ring, spirit tree), learned from the objects seen there.
 *
 * <p>Whose house it is: teleporting in (spell or tablet) always leads to one's own house, and building mode only
 * works there. Coming in through a house portal might be a friend's house, so what is seen then is not recorded,
 * and the exit leads back to that portal. Not thread-safe; used from the client thread.
 */
public final class HouseTracker
{
    /** The house templates the game copies into the house instance. */
    static final int TEMPLATE_WEST = 1856;
    static final int TEMPLATE_EAST = 2047;
    static final int TEMPLATE_SOUTH = 5696;
    static final int TEMPLATE_NORTH = 5775;
    /** Where the house portal of each house location stands, by the value of varbit 2187 (POH_HOUSE_LOCATION). */
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
    /** Packed tile the player stood on before entering, or -1. */
    private int lastOutside = -1;
    /** Packed tile the exit portal leads to, or -1. */
    private int exit = -1;
    private boolean own;
    private final Set<String> features = new LinkedHashSet<>();
    private boolean featuresChanged;

    public static boolean isTemplate(int x, int y)
    {
        return x >= TEMPLATE_WEST && x <= TEMPLATE_EAST && y >= TEMPLATE_SOUTH && y <= TEMPLATE_NORTH;
    }

    /** The portal of a house location (varbit 2187), packed, or -1. */
    public static int portal(int location)
    {
        return location > 0 && location < PORTALS.length ? Tiles.pack(PORTALS[location][0], PORTALS[location][1], 0) : -1;
    }

    /** The house location whose portal is near a tile, or 0. */
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

    /**
     * Called every tick with where the player is ({@code node}, packed, as the map shows it), whether that is in an
     * instance, the house location varbit and whether building mode is on.
     */
    public void update(int node, boolean instance, int houseLocation, boolean buildingMode)
    {
        boolean nowInside = instance && node >= 0 && isTemplate(Tiles.x(node), Tiles.y(node));
        if (nowInside && !inside)
        {
            int entered = lastOutside >= 0 ? portalNear(lastOutside) : 0;
            if (entered > 0)
            {
                // Through a house portal: maybe a friend's house; the exit leads back to this portal.
                exit = portal(entered);
                own = false;
            }
            else
            {
                // Teleported in: one's own house.
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

    /** Whether the house the player is in is certainly their own. */
    public boolean own()
    {
        return inside && own;
    }

    /** Packed tile the exit portal leads to, or -1. */
    public int exit()
    {
        return exit;
    }

    /**
     * An object seen in the house (from the scene); remembered only in the player's own house and only when it is
     * one that leads somewhere. Returns true when this added something.
     */
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

    /** The feature an object stands for: "portal:Varrock", "box:ornate", "fairy ring", "spirit tree", "glory". */
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

    /** Restores what was learned before (saved per account). */
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

    /** True once after something new was learned, to save it. */
    public boolean takeChanged()
    {
        boolean changed = featuresChanged;
        featuresChanged = false;
        return changed;
    }

    /** What the house has, as the player set it: in place of what was known, keeping where the player is. */
    public void replaceFeatures(java.util.Collection<String> known)
    {
        features.clear();
        features.addAll(known);
        featuresChanged = false;
    }

    /** Logged out or another account: forget where the player came from (features are restored per account). */
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
