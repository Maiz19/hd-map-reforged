package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.worldmap.MapElementConfig;

/** The game's own world map icons, read from the client, so our icons look like those in the tiles. */
final class GameIconSprites
{
    /** The game map elements whose icons stand for our types. */
    private static final Map<PoiType, Integer> ELEMENTS = new EnumMap<>(PoiType.class);

    static
    {
        for (String element : ("BANK=5 ANVIL=10 DUNGEON_ENTRANCE=12 MAP_EXIT=13 MAP_LINK=13 ALTAR=21 MINIGAME=40"
            + " AGILITY_COURSE=51 FARMING_PATCH=55 AGILITY_SHORTCUT=71 FAIRY_RING=965 SALVAGE=1050 MOORING=1055").split(" "))
        {
            ELEMENTS.put(PoiType.valueOf(element.split("=")[0]), Integer.valueOf(element.split("=")[1]));
        }
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
            MapElementConfig config = config(client, id);
            if (config == null)
            {
                if (waiting(client))
                {
                    return false;
                }
                continue;
            }
            BufferedImage image = icon(config);
            if (image != null)
            {
                ELEMENT_SPRITES.put(id, image);
            }
        }
        return true;
    }

    /** Null for an id the game does not know (or not yet). */
    private static MapElementConfig config(Client client, int id)
    {
        try
        {
            return client.getMapElementConfig(id);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Still null once logged in: an id gone after a game update, not worth retrying every tick. */
    private static boolean waiting(Client client)
    {
        return client.getGameState() != GameState.LOGGED_IN;
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
            MapElementConfig config = config(client, entry.getValue());
            if (config == null)
            {
                if (waiting(client))
                {
                    return false;
                }
                continue;
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
