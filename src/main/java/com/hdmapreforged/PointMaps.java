package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/** Which overlapping wiki map draws a point, finer than the region table. Background threads: may download tiles. */
@Slf4j
final class PointMaps
{
    static final int LEVEL = 2;
    private static final int NEAR = 10;

    private PointMaps()
    {
    }

    static Map<WorldPoint, BaseMap> resolve(TileCache tiles, String version, BaseMaps maps, List<WorldPoint> points)
    {
        Map<WorldPoint, BaseMap> found = new HashMap<>();
        for (WorldPoint point : points)
        {
            WorldMapMoves.Drawn moved = WorldMapMoves.drawn(point);
            if (moved != null && maps.byId(moved.map) != null)
            {
                // Drawn elsewhere by its map (the Kalphite Lair).
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
                // Some places are only on the map of everything (the Charred Dungeon).
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

    static boolean shows(TileCache tiles, String version, BaseMap map, WorldPoint point)
    {
        return check(tiles, version, map, point) == 2;
    }

    private static final Map<String, Integer> CHECKED = new java.util.concurrent.ConcurrentHashMap<>();

    static void clear()
    {
        CHECKED.clear();
    }

    /** 2: drawn at the point; 1: only near it (dark floors like the Catacombs are black inside); 0: not; -1: unknown. */
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

    /** Not black, nor the dark grey (16, 16, 16) the wiki fills empty upper floors with. */
    static boolean isDrawn(int rgb)
    {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return r + g + b > 60;
    }
}
