package com.hdmapreforged;

import java.util.List;
import java.util.Locale;
import net.runelite.api.coords.WorldPoint;

/**
 * Finds a place by its wiki page name in the map's own data: a dungeon entrance, minigame or other icon of that name,
 * a town or island label, a map of that name, or a teleport there. For monsters whose wiki page lists no spawns one
 * can see (a boss fought in an instance): the place the page mentions most is where one goes.
 */
final class PlaceLookup
{
    /** A place found: the name it was found by, and where. */
    static final class Found
    {
        final String name;
        final WorldPoint point;

        Found(String name, WorldPoint point)
        {
            this.name = name;
            this.point = point;
        }
    }

    /** Where to go for a boss fought in an instance, by page name (lower case); from {@code boss_entrances.tsv}. */
    private static final java.util.Map<String, Found> BOSSES = bosses();

    private static java.util.Map<String, Found> bosses()
    {
        java.util.Map<String, Found> bosses = new java.util.HashMap<>();
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

    /** The way into a boss's lair, when known: the pool, tunnel or door one uses, not just the area. */
    static Found boss(String page)
    {
        return page == null ? null : BOSSES.get(page.toLowerCase(Locale.ROOT));
    }

    /** Icons that stand for a place of their name. */
    private static final java.util.Set<PoiType> PLACES = java.util.EnumSet.of(PoiType.DUNGEON_ENTRANCE, PoiType.MINIGAME,
        PoiType.AGILITY_COURSE, PoiType.RUNECRAFT_ALTAR, PoiType.TELEPORT, PoiType.MAP_EXIT);

    /** Names looked at at most, the most mentioned first. */
    static final int MAX_NAMES = 40;

    private PlaceLookup()
    {
    }

    /**
     * The first of {@code names} (in order) that the map knows, on a map one can see; kingdoms and other regions
     * ("Morytania", "Wilderness") only when no smaller place matches. Null when none does.
     */
    static Found find(List<String> names, BaseMaps maps, List<PoiLoader.Place> labels, List<Poi> pois)
    {
        if (maps == null)
        {
            return null;
        }
        List<String> tried = names.size() > MAX_NAMES ? names.subList(0, MAX_NAMES) : names;
        // Every icon once, with its lower-case name, for all the names tried.
        List<Poi> hosts = new java.util.ArrayList<>();
        List<Poi> icons = new java.util.ArrayList<>();
        List<String> iconNames = new java.util.ArrayList<>();
        for (Poi poi : Poi.flatten(pois))
        {
            for (Poi member : poi.members())
            {
                hosts.add(poi);
                icons.add(member);
                iconNames.add(member.name.toLowerCase(Locale.ROOT));
            }
        }
        Icons flat = new Icons(hosts, icons, iconNames);
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

    private static final java.util.regex.Pattern PAGE_KIND = java.util.regex.Pattern.compile("\\s*\\((location|area|dungeon)\\)$");

    /** The icons of one search, flattened once: each with the icon it sits in and its lower-case name. */
    private static final class Icons
    {
        final List<Poi> hosts;
        final List<Poi> members;
        final List<String> names;

        Icons(List<Poi> hosts, List<Poi> members, List<String> names)
        {
            this.hosts = hosts;
            this.members = members;
            this.names = names;
        }
    }

    private static WorldPoint place(String name, boolean regions, BaseMaps maps, List<PoiLoader.Place> labels,
        Icons icons)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!regions)
        {
            // Icons first: a dungeon's entrance, a minigame's place.
            for (int i = 0; i < icons.members.size(); i++)
            {
                Poi host = icons.hosts.get(i);
                if (named(icons.members.get(i), icons.names.get(i), lower) && onMap(maps, host.location))
                {
                    return host.location;
                }
            }
            for (PoiLoader.Place place : labels)
            {
                if (!"region".equals(place.kind) && place.name.equalsIgnoreCase(name) && onMap(maps, place.point))
                {
                    return place.point;
                }
            }
            for (BaseMap map : maps.all())
            {
                if (map.id != BaseMap.FULL && map.id != BaseMap.SURFACE && map.name.equalsIgnoreCase(name))
                {
                    WorldPoint middle = new WorldPoint((map.minX + map.maxX) / 2, (map.minY + map.maxY) / 2, 0);
                    return maps.find(middle.getX(), middle.getY()) == map ? middle : null;
                }
            }
            return null;
        }
        for (PoiLoader.Place place : labels)
        {
            if ("region".equals(place.kind) && place.name.equalsIgnoreCase(name) && onMap(maps, place.point))
            {
                return place.point;
            }
        }
        return null;
    }

    /**
     * Whether an icon is the place: its name is it ("Stalker Den"), or it is a teleport there ("Skills necklace:
     * Farming Guild", "Zul-andra teleport scroll").
     */
    static boolean named(Poi poi, String lower)
    {
        return named(poi, poi.name.toLowerCase(Locale.ROOT), lower);
    }

    private static boolean named(Poi poi, String name, String lower)
    {
        if (name.equals(lower))
        {
            // Places, not things that share a word with one (a "Flower" patch).
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
