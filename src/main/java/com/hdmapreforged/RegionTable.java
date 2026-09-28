package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Which wiki maps really show a 64×64 region where map bounds overlap (empty: checked, none), and where several share a
 * region, which draws each 8×8 zone. Filled from the bundled table, a cache per map version and {@link RegionResolver}.
 */
final class RegionTable
{
    private static final int[] NONE = new int[0];

    static final int ZONES = 8;
    static final int ZONE = 8;
    /** A zone's owners as a bit mask over the region's owner list, one character (up to 6 owners). */
    private static final String MASKS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-";

    private final Map<Integer, int[]> owners = new ConcurrentHashMap<>();
    /** 64 mask characters, zone (x, y) at index x * 8 + y. */
    private final Map<Integer, String> zones = new ConcurrentHashMap<>();
    /** Answered only by "no such tile": not written, so a passing wiki problem is not remembered. */
    private final Set<Integer> unsure = ConcurrentHashMap.newKeySet();

    static int regionId(int x, int y)
    {
        return ((x >> 6) << 8) | (y >> 6);
    }

    /** Owner map ids, empty when none, or null when not checked yet. */
    int[] owners(int region)
    {
        return owners.get(region);
    }

    boolean isResolved(int region)
    {
        return owners.containsKey(region);
    }

    void put(int region, int[] ids)
    {
        owners.put(region, ids);
        unsure.remove(region);
    }

    /** No candidate map had any tile: kept for this session only. */
    void putUnsure(int region)
    {
        owners.put(region, NONE);
        zones.remove(region);
        unsure.add(region);
    }

    /** With which owners draw each zone ({@code masks[x * 8 + y]}, bits by owner). */
    void put(int region, int[] ids, int[] masks)
    {
        put(region, ids);
        if (masks == null || ids.length == 0)
        {
            zones.remove(region);
            return;
        }
        StringBuilder text = new StringBuilder(ZONES * ZONES);
        for (int mask : masks)
        {
            text.append(MASKS.charAt(mask & 63));
        }
        zones.put(region, text.toString());
    }

    /**
     * The owners drawing a tile's zone, or when none does, the zones around it (an entrance just beside a drawing);
     * null when zones are not known; empty when none draws anything near.
     */
    int[] zoneOwners(int x, int y)
    {
        int region = regionId(x, y);
        String text = zones.get(region);
        int[] ids = owners.get(region);
        if (text == null || ids == null)
        {
            return null;
        }
        int zx = (x & 63) / ZONE;
        int zy = (y & 63) / ZONE;
        int mask = MASKS.indexOf(text.charAt(zx * ZONES + zy));
        if (mask == 0)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    int nx = zx + dx;
                    int ny = zy + dy;
                    if (nx >= 0 && ny >= 0 && nx < ZONES && ny < ZONES)
                    {
                        mask |= MASKS.indexOf(text.charAt(nx * ZONES + ny));
                    }
                }
            }
        }
        int count = Integer.bitCount(mask & ((1 << Math.min(6, ids.length)) - 1));
        int[] found = new int[count];
        int n = 0;
        for (int i = 0; i < ids.length && i < 6; i++)
        {
            if ((mask & 1 << i) != 0)
            {
                found[n++] = ids[i];
            }
        }
        return found;
    }

    int size()
    {
        return owners.size();
    }

    /** Lines of region id, tab, comma-separated map ids, optionally tab and zones; bad lines are skipped. */
    RegionTable read(Reader tsv) throws IOException
    {
        BufferedReader reader = new BufferedReader(tsv);
        String line;
        while ((line = reader.readLine()) != null)
        {
            if (line.startsWith("#") || line.trim().isEmpty())
            {
                continue;
            }
            try
            {
                readLine(line);
            }
            catch (RuntimeException e)
            {
                // Checked again when needed.
            }
        }
        return this;
    }

    private void readLine(String line)
    {
        String[] cells = line.split("\t", -1);
        int[] ids = NONE;
        if (cells.length > 1 && !cells[1].trim().isEmpty())
        {
            String[] parts = cells[1].split(",");
            ids = new int[parts.length];
            for (int i = 0; i < parts.length; i++)
            {
                ids[i] = Integer.parseInt(parts[i].trim());
            }
        }
        int region = Integer.parseInt(cells[0].trim());
        if (region < 0 || region > 0xFFFF)
        {
            return;
        }
        String zoned = cells.length > 2 ? cells[2].trim() : "";
        if (zoned.length() == ZONES * ZONES && ids.length > 0 && validZones(zoned))
        {
            zones.put(region, zoned);
        }
        else if (!Arrays.equals(owners.get(region), ids))
        {
            // A later table with other owners: its zones are not known.
            zones.remove(region);
        }
        put(region, ids);
    }

    private static boolean validZones(String zoned)
    {
        return zoned.chars().allMatch(c -> MASKS.indexOf(c) >= 0);
    }

    /** The table as {@link #read} takes it; {@code withUnsure}: also regions from {@link #putUnsure}. */
    String text(String header, boolean withUnsure)
    {
        StringBuilder out = new StringBuilder("# ").append(header).append('\n');
        for (Map.Entry<Integer, int[]> entry : new TreeMap<>(owners).entrySet())
        {
            if (!withUnsure && unsure.contains(entry.getKey()))
            {
                continue;
            }
            String ids = Arrays.stream(entry.getValue()).mapToObj(String::valueOf).collect(Collectors.joining(","));
            String zoned = zones.get(entry.getKey());
            out.append(entry.getKey()).append('\t').append(ids).append(zoned != null ? "\t" + zoned : "").append('\n');
        }
        return out.toString();
    }
}
