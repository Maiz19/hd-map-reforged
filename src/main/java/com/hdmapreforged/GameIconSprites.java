package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.worldmap.MapElementConfig;

/**
 * The game's own world map icons for the kinds of places we mark, read from the client, so our icons look like the
 * ones in the map tiles (a dungeon entrance shows the game's dungeon icon). Teleports and transport networks keep
 * our own coloured icons: the game shows most of them with one shared "Transportation" icon.
 */
final class GameIconSprites
{
    /** The game's map element standing for each of our types (ids from {@code map_icons.tsv}). */
    static final Map<PoiType, Integer> ELEMENTS = new EnumMap<>(PoiType.class);

    static
    {
        ELEMENTS.put(PoiType.BANK, 5);
        ELEMENTS.put(PoiType.ANVIL, 10);
        ELEMENTS.put(PoiType.DUNGEON_ENTRANCE, 12);
        ELEMENTS.put(PoiType.MAP_EXIT, 13);
        ELEMENTS.put(PoiType.MAP_LINK, 13);
        ELEMENTS.put(PoiType.ALTAR, 21);
        ELEMENTS.put(PoiType.MINIGAME, 40);
        ELEMENTS.put(PoiType.AGILITY_COURSE, 51);
        ELEMENTS.put(PoiType.FARMING_PATCH, 55);
        ELEMENTS.put(PoiType.AGILITY_SHORTCUT, 71);
        ELEMENTS.put(PoiType.FAIRY_RING, 965);
        ELEMENTS.put(PoiType.SALVAGE, 1050);
        ELEMENTS.put(PoiType.MOORING, 1055);
    }

    private static final Map<PoiType, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    /** Every map element's icon read so far, by element id, for drawing the tiles' icons larger. */
    private static final Map<Integer, BufferedImage> ELEMENT_SPRITES = new ConcurrentHashMap<>();

    /** Whether any map element's icon has been read: then the tiles' icons are drawn over at the icon size. */
    static boolean hasElements()
    {
        return !ELEMENT_SPRITES.isEmpty();
    }

    /** The game's icon of a map element, or null when not read (yet). */
    static BufferedImage element(int id)
    {
        return id < 0 ? null : ELEMENT_SPRITES.get(id);
    }

    /** For development previews without a client. */
    static void putElement(int id, BufferedImage sprite)
    {
        ELEMENT_SPRITES.put(id, sprite);
    }

    /**
     * Reads the icons of these map elements from the game's data. Client thread only; true once done, false while
     * the game has not loaded its data yet.
     */
    static boolean loadElements(Client client, java.util.Collection<Integer> ids)
    {
        for (int id : ids)
        {
            if (ELEMENT_SPRITES.containsKey(id))
            {
                continue;
            }
            MapElementConfig config;
            try
            {
                config = client.getMapElementConfig(id);
            }
            catch (RuntimeException e)
            {
                // An id the game does not know (any more): no sprite.
                continue;
            }
            if (config == null)
            {
                return false;
            }
            SpritePixels pixels = config.getMapIcon(false);
            BufferedImage image = pixels == null ? null : pixels.toBufferedImage();
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0)
            {
                ELEMENT_SPRITES.put(id, image);
            }
        }
        return true;
    }

    private GameIconSprites()
    {
    }

    /** The game's icon for a type, or null (not loaded yet, or a type we draw ourselves). */
    static BufferedImage get(PoiType type)
    {
        return SPRITES.get(type);
    }

    /** For development previews without a client. */
    static void put(PoiType type, BufferedImage sprite)
    {
        SPRITES.put(type, sprite);
    }

    static void clear()
    {
        SPRITES.clear();
        ELEMENT_SPRITES.clear();
    }

    /**
     * Reads the icons from the game's data. Client thread only; true once done, false while the game has not
     * loaded its data yet (call again later).
     */
    static boolean load(Client client)
    {
        for (Map.Entry<PoiType, Integer> entry : ELEMENTS.entrySet())
        {
            MapElementConfig config = client.getMapElementConfig(entry.getValue());
            if (config == null)
            {
                return false;
            }
            SpritePixels pixels = config.getMapIcon(false);
            if (pixels == null)
            {
                continue;
            }
            BufferedImage image = pixels.toBufferedImage();
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0)
            {
                SPRITES.put(entry.getKey(), image);
            }
        }
        return true;
    }
}
