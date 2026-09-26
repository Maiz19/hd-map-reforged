package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Which of several overlapping wiki maps really shows a point: the one whose tile has something drawn at that exact
 * spot. Finer than the region table, whose 64×64 regions can hold parts of two maps (Brimhaven Dungeon and Yanille's
 * underground share regions). Background threads only: may download a few tiles.
 */
@Slf4j
final class PointMaps
{
    /** Wiki zoom level used for the check: 4 pixels per game tile. */
    static final int LEVEL = 2;
    /** Game tiles around a point looked at for anything drawn. */
    private static final int NEAR = 10;

    private PointMaps()
    {
    }

    /**
     * Per point, the smallest map that draws it where several maps cover it; a null value when maps cover it but
     * none draws it (a place the wiki has not drawn yet). Points without a doubt are left out.
     */
    static Map<WorldPoint, BaseMap> resolve(TileCache tiles, String version, BaseMaps maps, List<WorldPoint> points)
    {
        Map<WorldPoint, BaseMap> found = new HashMap<>();
        for (WorldPoint point : points)
        {
            WorldMapMoves.Drawn moved = WorldMapMoves.drawn(point);
            if (moved != null && maps.byId(moved.map) != null)
            {
                // Drawn elsewhere by its map (the Kalphite Lair): that map shows it, whatever its tiles have here.
                found.put(point, maps.byId(moved.map));
                continue;
            }
            BaseMap best = null;
            int bestScore = 0;
            int candidates = 0;
            boolean unknown = false;
            for (BaseMap map : maps.all())
            {
                if (map.id == BaseMap.FULL || !map.contains(point.getX(), point.getY()))
                {
                    continue;
                }
                candidates++;
                int drawn = check(tiles, version, map, point);
                unknown |= drawn < 0;
                if (drawn > bestScore || drawn > 0 && drawn == bestScore && map.area() < best.area())
                {
                    best = map;
                    bestScore = drawn;
                }
            }
            if (best != null && candidates > 1)
            {
                found.put(point, best);
            }
            else if (best == null && !unknown)
            {
                // Some places are only on the wiki's map of everything (map -1), such as the Charred Dungeon or the
                // Ancient Guthixian Temple, which no other map even covers.
                BaseMap full = maps.byId(BaseMap.FULL);
                int drawn = full == null ? 0 : check(tiles, version, full, point);
                if (drawn >= 0)
                {
                    found.put(point, drawn > 0 ? full : null);
                }
            }
        }
        return found;
    }

    /** Whether any wiki map, the map of everything too, draws something at or near a point; false when unknown. */
    static boolean drawnAnywhere(TileCache tiles, String version, BaseMaps maps, WorldPoint point)
    {
        for (BaseMap map : maps.all())
        {
            if (map.contains(point.getX(), point.getY()) && check(tiles, version, map, point) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a wiki map of its own (not only the map of everything, which also draws instances) draws something at
     * or near a point; false when unknown.
     */
    static boolean drawnOnAMap(TileCache tiles, String version, BaseMaps maps, WorldPoint point)
    {
        for (BaseMap map : maps.all())
        {
            if (map.id != BaseMap.FULL && map.contains(point.getX(), point.getY()) && check(tiles, version, map, point) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /** Whether a map's tile has something drawn at a point (not the empty black background). */
    static boolean shows(TileCache tiles, String version, BaseMap map, WorldPoint point)
    {
        return check(tiles, version, map, point) == 2;
    }

    /** Answers already found, by version, map and point: a second look at the same monster reads no tile. */
    private static final Map<String, Integer> CHECKED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Forgets the answers found (the plugin shutting down). */
    static void clear()
    {
        CHECKED.clear();
    }

    /**
     * 2 when the map draws the point itself; 1 when only something near it (dark floors, such as the Catacombs of
     * Kourend, are black between their outlines); 0 when nothing near it (or no tile at all); -1 when the tile could
     * not be loaded.
     */
    private static int check(TileCache tiles, String version, BaseMap map, WorldPoint point)
    {
        String key = version + "/" + map.id + "/" + point.getX() + "," + point.getY() + "," + point.getPlane();
        Integer known = CHECKED.get(key);
        if (known != null)
        {
            return known;
        }
        int answer = checkTile(tiles, version, map, point);
        if (answer >= 0)
        {
            if (CHECKED.size() > 20_000)
            {
                CHECKED.clear();
            }
            CHECKED.put(key, answer);
        }
        return answer;
    }

    private static int checkTile(TileCache tiles, String version, BaseMap map, WorldPoint point)
    {
        double span = TileCache.worldPerTile(LEVEL);
        int tx = TileCache.tileIndex(point.getX(), LEVEL);
        int ty = TileCache.tileIndex(point.getY(), LEVEL);
        try
        {
            BufferedImage tile = tiles.loadNow(version, new TileCache.Key(map.id, LEVEL, point.getPlane(), tx, ty));
            if (tile == null)
            {
                return 0;
            }
            double scale = tile.getWidth() / span;
            int px = (int) ((point.getX() + 0.5 - tx * span) * scale);
            int py = (int) (((ty + 1) * span - point.getY() - 0.5) * scale);
            if (isDrawn(tile.getRGB(Math.max(0, Math.min(tile.getWidth() - 1, px)),
                Math.max(0, Math.min(tile.getHeight() - 1, py)))))
            {
                return 2;
            }
            int r = (int) (NEAR * scale);
            for (int y = Math.max(0, py - r); y <= Math.min(tile.getHeight() - 1, py + r); y += 2)
            {
                for (int x = Math.max(0, px - r); x <= Math.min(tile.getWidth() - 1, px + r); x += 2)
                {
                    if (isDrawn(tile.getRGB(x, y)))
                    {
                        return 1;
                    }
                }
            }
            return 0;
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not check {} on map {}", point, map.id, e);
            return -1;
        }
    }

    /**
     * Whether a pixel of a wiki tile shows something: not black, nor the even dark grey (16, 16, 16) the wiki fills a
     * floor above with where it has drawn nothing (the Kalphite Lair's floor 2).
     */
    static boolean isDrawn(int rgb)
    {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return r + g + b > 60;
    }
}
