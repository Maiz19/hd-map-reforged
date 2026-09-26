package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Decides which wiki map shows a place when several maps' bounds contain it, by looking at each candidate map's
 * tile there: the wiki leaves areas a map does not show black. Works for any map version, so a new wiki map needs
 * no plugin update. Blocking; run on a background thread.
 */
@Slf4j
final class RegionResolver
{
    /** At zoom 0 one game tile is one pixel, so a region is a 64×64 block of a 256×256 tile. */
    private static final int ZOOM = 0;
    private static final int REGION = 64;
    private static final int MIN_LIT = 24;
    /** Drawn tiles an 8×8 zone needs to count as drawn by a map. */
    private static final int ZONE_LIT = 3;

    private final TileCache tiles;

    RegionResolver(TileCache tiles)
    {
        this.tiles = tiles;
    }

    /** Maps whose bounds contain the tile; the combined map never counts. */
    static List<BaseMap> candidates(BaseMaps maps, int x, int y)
    {
        List<BaseMap> found = new ArrayList<>();
        for (BaseMap map : maps.all())
        {
            if (map.id != BaseMap.FULL && map.contains(x, y))
            {
                found.add(map);
            }
        }
        return found;
    }

    /**
     * Whether a place needs checking: several maps claim it, or a single map other than the surface (dungeon map
     * bounds are loose, and some areas are shown only on the combined map).
     */
    static boolean needsCheck(BaseMaps maps, int x, int y)
    {
        List<BaseMap> found = candidates(maps, x, y);
        return found.size() > 1 || found.size() == 1 && found.get(0).id != BaseMap.SURFACE;
    }

    /**
     * Resolves every not yet checked region, among those of {@code points}, that more than one map claims.
     * Returns how many regions were added to {@code table}.
     */
    int resolve(String version, BaseMaps maps, RegionTable table, Collection<WorldPoint> points, BooleanSupplier cancelled)
    {
        Map<Integer, WorldPoint> regions = new LinkedHashMap<>();
        for (WorldPoint point : points)
        {
            int region = RegionTable.regionId(point.getX(), point.getY());
            if (!table.isResolved(region) && !regions.containsKey(region) && needsCheck(maps, point.getX(), point.getY()))
            {
                regions.put(region, point);
            }
        }
        Map<TileCache.Key, BufferedImage> images = new HashMap<>();
        int added = 0;
        for (Map.Entry<Integer, WorldPoint> entry : regions.entrySet())
        {
            if (cancelled.getAsBoolean())
            {
                break;
            }
            WorldPoint point = entry.getValue();
            if (resolveRegion(version, maps, table, point.getX() & ~63, point.getY() & ~63, images))
            {
                added++;
            }
            if (images.size() > 64)
            {
                images.clear();
            }
        }
        return added;
    }

    /**
     * Resolves every region of the world that more than one map claims, or a single map other than the surface
     * (building the bundled table). Returns how many regions were added.
     */
    int resolveAll(String version, BaseMaps maps, RegionTable table, BooleanSupplier cancelled)
    {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BaseMap map : maps.all())
        {
            if (map.id != BaseMap.FULL)
            {
                minX = Math.min(minX, map.minX);
                minY = Math.min(minY, map.minY);
                maxX = Math.max(maxX, map.maxX);
                maxY = Math.max(maxY, map.maxY);
            }
        }
        Map<TileCache.Key, BufferedImage> images = new HashMap<>();
        int added = 0;
        // Column by column, so the tiles of one 256-wide strip are read together.
        for (int rx = minX & ~63; rx < maxX; rx += REGION)
        {
            for (int ry = minY & ~63; ry < maxY; ry += REGION)
            {
                if (cancelled.getAsBoolean())
                {
                    return added;
                }
                if (table.isResolved(RegionTable.regionId(rx, ry)) || candidatesIn(maps, rx, ry).isEmpty()
                    || !needsCheck(maps, rx, ry) && !needsCheckAnywhere(maps, rx, ry))
                {
                    continue;
                }
                if (resolveRegion(version, maps, table, rx, ry, images))
                {
                    added++;
                }
            }
            if (images.size() > 256)
            {
                images.clear();
            }
        }
        return added;
    }

    /** Whether any tile of a region needs a check (a dungeon map's bounds reaching into part of it). */
    private static boolean needsCheckAnywhere(BaseMaps maps, int rx, int ry)
    {
        List<BaseMap> found = candidatesIn(maps, rx, ry);
        return found.size() > 1 || found.size() == 1 && found.get(0).id != BaseMap.SURFACE;
    }

    /** Maps whose bounds reach into a region; the combined map never counts. */
    static List<BaseMap> candidatesIn(BaseMaps maps, int rx, int ry)
    {
        List<BaseMap> found = new ArrayList<>();
        for (BaseMap map : maps.all())
        {
            if (map.id != BaseMap.FULL && map.minX < rx + REGION && rx < map.maxX && map.minY < ry + REGION
                && ry < map.maxY)
            {
                found.add(map);
            }
        }
        return found;
    }

    /**
     * Looks at every candidate map's tiles (all floors) over one region: which maps draw it, and when several, which
     * of them draw each 8×8 zone. False when a tile could not be read (tried again another time). When no candidate
     * had a single tile there, the answer is kept for this session only: the wiki may have been missing them briefly.
     */
    private boolean resolveRegion(String version, BaseMaps maps, RegionTable table, int rx, int ry,
        Map<TileCache.Key, BufferedImage> images)
    {
        List<BaseMap> candidates = candidatesIn(maps, rx, ry);
        List<Integer> owners = new ArrayList<>();
        List<int[]> litZones = new ArrayList<>();
        boolean[] anyTile = new boolean[1];
        try
        {
            for (BaseMap map : candidates)
            {
                int[] zones = new int[RegionTable.ZONES * RegionTable.ZONES];
                int total = 0;
                for (int plane = 0; plane < 4; plane++)
                {
                    total += count(version, map, rx, ry, plane, zones, images, anyTile);
                }
                if (total >= MIN_LIT)
                {
                    owners.add(map.id);
                    litZones.add(zones);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Try again next time; the bounds decide until then.
            log.debug("Could not check region {},{} for map version {}", rx, ry, version, e);
            return false;
        }
        if (!anyTile[0])
        {
            table.putUnsure(RegionTable.regionId(rx, ry));
            return true;
        }
        int[] ids = new int[owners.size()];
        int[] masks = new int[RegionTable.ZONES * RegionTable.ZONES];
        for (int i = 0; i < ids.length; i++)
        {
            ids[i] = owners.get(i);
            for (int z = 0; z < masks.length && i < 6; z++)
            {
                if (litZones.get(i)[z] >= ZONE_LIT)
                {
                    masks[z] |= 1 << i;
                }
            }
        }
        boolean whole = true;
        for (int mask : masks)
        {
            whole &= mask == (1 << Math.min(6, ids.length)) - 1;
        }
        // Zones where several maps share a region, or where its one map draws only part of it (the rest belongs to the
        // map of everything, or to no map); none where one map draws all of it.
        table.put(RegionTable.regionId(rx, ry), ids, whole ? null : masks);
        return true;
    }

    /** Drawn pixels (one per game tile at zoom 0) of a map's floor over a region, added up per zone too. */
    private int count(String version, BaseMap map, int rx, int ry, int plane, int[] zones,
        Map<TileCache.Key, BufferedImage> images, boolean[] anyTile) throws IOException
    {
        int tx = TileCache.tileIndex(rx, ZOOM);
        int ty = TileCache.tileIndex(ry, ZOOM);
        TileCache.Key key = new TileCache.Key(map.id, ZOOM, plane, tx, ty);
        BufferedImage image;
        if (images.containsKey(key))
        {
            image = images.get(key);
        }
        else
        {
            image = tiles.loadNow(version, key);
            images.put(key, image);
        }
        if (image == null)
        {
            return 0;
        }
        anyTile[0] = true;
        int left = rx - tx * TileCache.TILE_SIZE;
        int top = TileCache.TILE_SIZE - (ry - ty * TileCache.TILE_SIZE) - REGION;
        int lit = 0;
        for (int dx = 0; dx < REGION; dx++)
        {
            for (int dy = 0; dy < REGION; dy++)
            {
                // Image rows grow southwards, the region's tiles northwards.
                if (PointMaps.isDrawn(image.getRGB(left + dx, top + REGION - 1 - dy)))
                {
                    lit++;
                    zones[(dx / RegionTable.ZONE) * RegionTable.ZONES + dy / RegionTable.ZONE]++;
                }
            }
        }
        return lit;
    }
}
