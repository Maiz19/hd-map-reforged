package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which wiki maps really show a 64×64 region, for regions where map bounds overlap. A region with an empty owner
 * list was checked and no candidate showed it. Where several maps draw parts of one region (Bryophyta's lair and the
 * Varrock Sewers), which of them draws each 8×8 zone: a map is only the answer where its tiles show something. Filled
 * from the bundled table, a cached table per map version, and {@link RegionResolver}.
 */
final class RegionTable
{
    private static final int[] NONE = new int[0];

    /** Zones per region side: 8 zones of 8 tiles. */
    static final int ZONES = 8;
    static final int ZONE = 8;
    /** A zone's owners as a bit mask over the region's owner list, one character each (up to 6 owners). */
    private static final String MASKS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-";

    private final Map<Integer, int[]> owners = new ConcurrentHashMap<>();
    /** For regions with several owners: 64 mask characters, zone (x, y) at index x * 8 + y. */
    private final Map<Integer, String> zones = new ConcurrentHashMap<>();
    /**
     * Regions answered only by "no such tile" from the wiki: used for this session but not written, so a passing
     * wiki problem is not remembered for good.
     */
    private final java.util.Set<Integer> unsure = ConcurrentHashMap.newKeySet();

    static int regionId(int x, int y)
    {
        return ((x >> 6) << 8) | (y >> 6);
    }

    /** Owner map ids, an empty array when checked without owner, or null when not checked yet. */
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

    /** A region no candidate map had any tile for: kept for this session only (see {@link #write}). */
    void putUnsure(int region)
    {
        owners.put(region, NONE);
        zones.remove(region);
        unsure.add(region);
    }

    /** A region's owners with, when several, which of them draw each zone ({@code masks[x * 8 + y]}, bits by owner). */
    void put(int region, int[] ids, int[] masks)
    {
        owners.put(region, ids);
        unsure.remove(region);
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
     * The owners (map ids) that draw the zone of a tile — or, when none draws that zone, the zones around it (an
     * entrance on the black just beside a drawing) — when the region's zones are known; null when only the region's
     * owners are known. An empty array: none of them draws anything near.
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

    /**
     * Reads lines of a region id, a tab and comma-separated map ids (possibly none), optionally a tab and the zones.
     * Lines that cannot be read are skipped (a table on disk may be damaged).
     */
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
                // Skipped: that region is checked again when needed.
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
        else if (!java.util.Arrays.equals(owners.get(region), ids))
        {
            // A later table (the cache for a version) with other owners: its zones, if any, are not known.
            zones.remove(region);
        }
        owners.put(region, ids);
        unsure.remove(region);
    }

    private static boolean validZones(String zoned)
    {
        for (int i = 0; i < zoned.length(); i++)
        {
            if (MASKS.indexOf(zoned.charAt(i)) < 0)
            {
                return false;
            }
        }
        return true;
    }

    /** Writes the table, leaving out regions only known from missing tiles ({@link #putUnsure}). */
    void write(File file, String header) throws IOException
    {
        write(file, header, false);
    }

    /** Writes the table; {@code withUnsure} keeps regions only known from missing tiles too (building the bundled one). */
    void write(File file, String header, boolean withUnsure) throws IOException
    {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
        {
            throw new IOException("Cannot create " + parent);
        }
        File temp = new File(parent, file.getName() + ".part");
        try (Writer out = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8))
        {
            out.write("# " + header + "\n");
            for (Map.Entry<Integer, int[]> entry : new TreeMap<>(owners).entrySet())
            {
                if (!withUnsure && unsure.contains(entry.getKey()))
                {
                    continue;
                }
                StringBuilder ids = new StringBuilder();
                for (int id : entry.getValue())
                {
                    ids.append(ids.length() > 0 ? "," : "").append(id);
                }
                String zoned = zones.get(entry.getKey());
                out.write(entry.getKey() + "\t" + ids + (zoned != null ? "\t" + zoned : "") + "\n");
            }
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
