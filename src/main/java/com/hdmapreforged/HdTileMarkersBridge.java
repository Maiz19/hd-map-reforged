package com.hdmapreforged;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;

/**
 * Drawing the route's ground tiles with the HD Tile Markers plugin, when the player has it: through its public
 * plugin-message format ({@code hd-tile-markers}: {@code tiles} and {@code clear} for one owner), no code of it.
 */
final class HdTileMarkersBridge
{
    static final String NAMESPACE = "hd-tile-markers";
    static final String OWNER = "hd-map-reforged-route";
    /** The plugin's name in RuneLite's plugin list. */
    static final String PLUGIN_NAME = "HD Tile Markers";
    /** It takes at most this many tiles from one sender. */
    static final int MAX_TILES = 1000;

    private HdTileMarkersBridge()
    {
    }

    /** One tile to mark: where, and optionally a label (a teleport's name where it lands). */
    static final class Tile
    {
        final WorldPoint point;
        final Color color;
        final String label;
        /** The fill, or null for a faint one after the colour's transparency. */
        final Color fill;
        final int width;

        Tile(WorldPoint point, Color color, String label)
        {
            this(point, color, null, 2, label);
        }

        Tile(WorldPoint point, Color color, Color fill, int width, String label)
        {
            this.point = point;
            this.color = color;
            this.fill = fill;
            this.width = width;
            this.label = label;
        }
    }

    static PluginMessage tiles(List<Tile> tiles)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tile tile : tiles)
        {
            if (list.size() >= MAX_TILES)
            {
                break;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("point", tile.point);
            entry.put("color", tile.color);
            // The fill follows the colour's own transparency, so fading tiles fade as a whole.
            entry.put("fill", tile.fill != null ? tile.fill : new Color(tile.color.getRed(), tile.color.getGreen(),
                tile.color.getBlue(), tile.color.getAlpha() * 50 / 255));
            entry.put("width", tile.width);
            if (tile.label != null)
            {
                entry.put("label", tile.label);
            }
            list.add(entry);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("owner", OWNER);
        data.put("tiles", list);
        return new PluginMessage(NAMESPACE, "tiles", data);
    }

    static PluginMessage clear()
    {
        Map<String, Object> data = new HashMap<>();
        data.put("owner", OWNER);
        return new PluginMessage(NAMESPACE, "clear", data);
    }
}
