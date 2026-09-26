package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.worldmap.MapElementConfig;

/** The game's own world map icons, read from the client, so our icons look like those in the tiles. */
final class GameIconSprites
{
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
    private static final Map<Integer, BufferedImage> ELEMENT_SPRITES = new ConcurrentHashMap<>();

    static boolean hasElements()
    {
        return !ELEMENT_SPRITES.isEmpty();
    }

    static BufferedImage element(int id)
    {
        return id < 0 ? null : ELEMENT_SPRITES.get(id);
    }

    /** For development previews without a client. */
    static void putElement(int id, BufferedImage sprite)
    {
        ELEMENT_SPRITES.put(id, sprite);
    }

    /** Client thread only; false while the game has not loaded its data yet. */
    static boolean loadElements(Client client, Collection<Integer> ids)
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
                // An id the game no longer knows.
                continue;
            }
            if (config == null)
            {
                return false;
            }
            BufferedImage image = icon(config);
            if (image != null)
            {
                ELEMENT_SPRITES.put(id, image);
            }
        }
        return true;
    }

    private static BufferedImage icon(MapElementConfig config)
    {
        SpritePixels pixels = config.getMapIcon(false);
        BufferedImage image = pixels == null ? null : pixels.toBufferedImage();
        return image != null && image.getWidth() > 0 && image.getHeight() > 0 ? image : null;
    }

    private GameIconSprites()
    {
    }

    /** Null when not loaded yet, or a type we draw ourselves. */
    static BufferedImage get(PoiType type)
    {
        return SPRITES.get(type);
    }

    static void put(PoiType type, BufferedImage sprite)
    {
        SPRITES.put(type, sprite);
    }

    static void clear()
    {
        SPRITES.clear();
        ELEMENT_SPRITES.clear();
    }

    /** Client thread only; false while the game has not loaded its data yet. */
    static boolean load(Client client)
    {
        for (Map.Entry<PoiType, Integer> entry : ELEMENTS.entrySet())
        {
            MapElementConfig config = client.getMapElementConfig(entry.getValue());
            if (config == null)
            {
                return false;
            }
            BufferedImage image = icon(config);
            if (image != null)
            {
                SPRITES.put(entry.getKey(), image);
            }
        }
        return true;
    }
}
