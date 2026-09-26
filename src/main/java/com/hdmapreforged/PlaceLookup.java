package com.hdmapreforged;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * Finds a place by wiki page name in the map's own data (icon, label, map or teleport). For bosses in instances, the
 * place the page mentions most is where one goes.
 */
final class PlaceLookup
{
    @RequiredArgsConstructor
    static final class Found
    {
        final String name;
        final WorldPoint point;
    }

    /** Lower-case boss page name to lair entrance, from boss_entrances.tsv. */
    private static final Map<String, Found> BOSSES = bosses();

    private static Map<String, Found> bosses()
    {
        Map<String, Found> bosses = new java.util.HashMap<>();
        java.io.InputStream in = PlaceLookup.class.getResourceAsStream("data/boss_entrances.tsv");
        if (in == null)
        {
            return bosses;
        }
        try (java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
        {
            for (Tsv.Row row : Tsv.parse(reader))
            {
                WorldPoint at = row.isComment() ? null : row.point("Location");
                if (at != null && !row.get("Boss").isEmpty())
                {
                    bosses.put(row.get("Boss").toLowerCase(Locale.ROOT), new Found(row.get("Where"), at));
                }
            }
        }
        catch (java.io.IOException e)
        {
            // None known then.
        }
        return bosses;
    }

    /** The pool, tunnel or door into a boss's lair, when known. */
    static Found boss(String page)
    {
        return page == null ? null : BOSSES.get(page.toLowerCase(Locale.ROOT));
    }

    private static final java.util.Set<PoiType> PLACES = java.util.EnumSet.of(PoiType.DUNGEON_ENTRANCE, PoiType.MINIGAME,
        PoiType.AGILITY_COURSE, PoiType.RUNECRAFT_ALTAR, PoiType.TELEPORT, PoiType.MAP_EXIT);

    static final int MAX_NAMES = 40;

    private PlaceLookup()
    {
    }

    /** The first known name, on a visible map; regions ("Morytania") only when no smaller place matches. */
    static Found find(List<String> names, BaseMaps maps, List<PoiLoader.Place> labels, List<Poi> pois)
    {
        if (maps == null)
        {
            return null;
        }
        List<String> tried = names.size() > MAX_NAMES ? names.subList(0, MAX_NAMES) : names;
        Icons flat = new Icons();
        for (Poi poi : Poi.flatten(pois))
        {
            for (Poi member : poi.members())
            {
                flat.hosts.add(poi);
                flat.members.add(member);
                flat.names.add(member.name.toLowerCase(Locale.ROOT));
            }
        }
        for (boolean regions : new boolean[]{false, true})
        {
            for (String name : tried)
            {
                String cleaned = clean(name);
                WorldPoint at = place(cleaned, regions, maps, labels, flat);
                if (at != null)
                {
                    return new Found(cleaned, at);
                }
            }
        }
        return null;
    }

    /** "Temple of the Eye (location)" is "Temple of the Eye". */
    private static String clean(String name)
    {
        return PAGE_KIND.matcher(name).replaceAll("").trim();
    }

    private static final Pattern PAGE_KIND = Pattern.compile("\\s*\\((location|area|dungeon)\\)$");

    /** Every icon, flattened, with its host and lower-case name. */
    private static final class Icons
    {
        final List<Poi> hosts = new ArrayList<>();
        final List<Poi> members = new ArrayList<>();
        final List<String> names = new ArrayList<>();
    }

    private static WorldPoint place(String name, boolean regions, BaseMaps maps, List<PoiLoader.Place> labels,
        Icons icons)
    {
        if (!regions)
        {
            String lower = name.toLowerCase(Locale.ROOT);
            for (int i = 0; i < icons.members.size(); i++)
            {
                Poi host = icons.hosts.get(i);
                if (named(icons.members.get(i), icons.names.get(i), lower) && onMap(maps, host.location))
                {
                    return host.location;
                }
            }
        }
        for (PoiLoader.Place place : labels)
        {
            if ("region".equals(place.kind) == regions && place.name.equalsIgnoreCase(name) && onMap(maps, place.point))
            {
                return place.point;
            }
        }
        for (BaseMap map : regions ? List.<BaseMap>of() : maps.all())
        {
            if (map.id != BaseMap.FULL && map.id != BaseMap.SURFACE && map.name.equalsIgnoreCase(name))
            {
                WorldPoint middle = new WorldPoint((map.minX + map.maxX) / 2, (map.minY + map.maxY) / 2, 0);
                if (maps.find(middle.getX(), middle.getY()) == map)
                {
                    return middle;
                }
            }
        }
        return null;
    }

    /** Its name is the place ("Stalker Den"), or it is a teleport there ("Skills necklace: Farming Guild"). */
    static boolean named(Poi poi, String lower)
    {
        return named(poi, poi.name.toLowerCase(Locale.ROOT), lower);
    }

    private static boolean named(Poi poi, String name, String lower)
    {
        if (name.equals(lower))
        {
            // Places, not things sharing a word with one (a "Flower" patch).
            return PLACES.contains(poi.type);
        }
        return poi.type == PoiType.TELEPORT && (name.endsWith(": " + lower) || name.equals(lower + " teleport")
            || name.equals(lower + " teleport scroll") || name.equals("teleport to " + lower));
    }

    private static boolean onMap(BaseMaps maps, WorldPoint point)
    {
        return point != null && maps.find(point.getX(), point.getY()) != null;
    }
}
