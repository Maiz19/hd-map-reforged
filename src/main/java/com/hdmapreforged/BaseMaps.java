package com.hdmapreforged;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** The wiki's {@code basemaps.json}; a region table decides between maps whose bounds overlap. */
final class BaseMaps
{
    private final List<BaseMap> sorted;
    private final Map<Integer, BaseMap> byId = new HashMap<>();
    private RegionTable regions = new RegionTable();

    BaseMaps(List<BaseMap> maps)
    {
        List<BaseMap> list = new ArrayList<>(maps);
        // Surface first, then the combined map, then alphabetical.
        list.sort(Comparator.comparingInt((BaseMap m) -> m.id == BaseMap.SURFACE ? 0 : m.id == BaseMap.FULL ? 1 : 2)
            .thenComparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
        sorted = Collections.unmodifiableList(list);
        for (BaseMap map : list)
        {
            byId.put(map.id, map);
        }
    }

    static final int MAX_COORDINATE = 20_000;
    private static final int MAX_NAME = 100;

    /** Implausible entries are left out, not the whole list; an unreadable list gives no maps. */
    static BaseMaps parse(Gson gson, Reader json)
    {
        JsonArray array;
        try
        {
            array = gson.fromJson(json, JsonArray.class);
        }
        catch (RuntimeException e)
        {
            return new BaseMaps(Collections.emptyList());
        }
        List<BaseMap> maps = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (JsonElement element : array == null ? new JsonArray() : array)
        {
            BaseMap map = map(element);
            if (map != null && ids.add(map.id))
            {
                maps.add(map);
            }
        }
        return new BaseMaps(maps);
    }

    private static BaseMap map(JsonElement element)
    {
        try
        {
            JsonObject o = element.getAsJsonObject();
            JsonArray bounds = o.getAsJsonArray("bounds");
            JsonArray min = bounds.get(0).getAsJsonArray();
            JsonArray max = bounds.get(1).getAsJsonArray();
            JsonArray center = o.has("center") ? o.getAsJsonArray("center") : null;
            int minX = min.get(0).getAsInt();
            int minY = min.get(1).getAsInt();
            int maxX = max.get(0).getAsInt();
            int maxY = max.get(1).getAsInt();
            int id = o.get("mapId").getAsInt();
            String name = o.get("name").getAsString();
            if (!inWorld(minX) || !inWorld(minY) || !inWorld(maxX) || !inWorld(maxY) || maxX <= minX || maxY <= minY
                || id < BaseMap.FULL || name.trim().isEmpty() || name.length() > MAX_NAME)
            {
                return null;
            }
            int centerX = center != null ? center.get(0).getAsInt() : (minX + maxX) / 2;
            int centerY = center != null ? center.get(1).getAsInt() : (minY + maxY) / 2;
            if (!inWorld(centerX) || !inWorld(centerY))
            {
                centerX = (minX + maxX) / 2;
                centerY = (minY + maxY) / 2;
            }
            return new BaseMap(id, name, minX, minY, maxX, maxY, centerX, centerY);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static boolean inWorld(int coordinate)
    {
        return coordinate >= 0 && coordinate <= MAX_COORDINATE;
    }

    boolean isUsable()
    {
        return byId(BaseMap.SURFACE) != null;
    }

    BaseMaps withRegions(RegionTable regions)
    {
        this.regions = regions;
        return this;
    }

    RegionTable regions()
    {
        return regions;
    }

    List<BaseMap> all()
    {
        return sorted;
    }

    BaseMap byId(int id)
    {
        return byId.get(id);
    }

    BaseMap surface()
    {
        BaseMap surface = byId.get(BaseMap.SURFACE);
        return surface != null ? surface : sorted.get(0);
    }

    /** One of the maps drawing the point's region (maps may share one), or the map {@link #find} gives. */
    boolean draws(BaseMap map, int x, int y)
    {
        if (map == null || !map.contains(x, y))
        {
            return false;
        }
        int[] owners = drawers(x, y);
        return owners != null && Arrays.stream(owners).anyMatch(id -> id == map.id) || find(x, y) == map;
    }

    /** The owners of the spot's 8×8 zone where known, else the region's owners; null when not known. */
    private int[] drawers(int x, int y)
    {
        int[] zone = regions.zoneOwners(x, y);
        return zone != null ? zone : regions.owners(RegionTable.regionId(x, y));
    }

    /** The map drawing a game point elsewhere when one does (the Kalphite Lair), else {@link #find(int, int)}. */
    BaseMap find(WorldPoint game)
    {
        WorldMapMoves.Drawn drawn = WorldMapMoves.drawn(game);
        BaseMap moved = drawn == null ? null : byId(drawn.map);
        // Some moves put a place outside the bounds of the map they name.
        if (moved != null && moved.contains(drawn.point.getX(), drawn.point.getY()))
        {
            return moved;
        }
        BaseMap found = find(game.getX(), game.getY());
        if (found != null && WorldMapMoves.covers(found.id, game.getX(), game.getY()))
        {
            // That map shows a moved place here: the smallest other map that draws the spot.
            BaseMap other = null;
            for (BaseMap map : sorted)
            {
                if (map != found && map.id != BaseMap.FULL && map.contains(game.getX(), game.getY())
                    && !WorldMapMoves.covers(map.id, game.getX(), game.getY())
                    && (other == null || map.area() < other.area()))
                {
                    other = map;
                }
            }
            return other != null ? other : byId(BaseMap.FULL);
        }
        return found;
    }

    /** Whether a map shows this game point where it is, not a moved place instead. */
    boolean drawsGame(BaseMap map, WorldPoint game)
    {
        return map != null && draws(map, game.getX(), game.getY())
            && !WorldMapMoves.covers(map.id, game.getX(), game.getY());
    }

    /** The smallest map drawing the region; the combined map when none does; else the smallest containing it. */
    BaseMap find(int x, int y)
    {
        int[] owners = drawers(x, y);
        BaseMap best = null;
        if (owners != null)
        {
            for (int id : owners)
            {
                BaseMap map = byId.get(id);
                if (map != null && map.contains(x, y) && (best == null || map.area() < best.area()))
                {
                    best = map;
                }
            }
        }
        if (best != null)
        {
            return best;
        }
        if (owners != null && owners.length == 0)
        {
            BaseMap full = byId.get(BaseMap.FULL);
            if (full != null && full.contains(x, y))
            {
                return full;
            }
        }
        for (BaseMap map : sorted)
        {
            if (map.id != BaseMap.FULL && map.contains(x, y) && (best == null || map.area() < best.area()))
            {
                best = map;
            }
        }
        return best;
    }
}
