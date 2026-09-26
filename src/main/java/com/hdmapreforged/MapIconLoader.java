package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * The game's own world map icons (shops, quest starts, map links, anvils, ...), which the wiki's map tiles show
 * baked in. Reads the bundled {@code map_icons.tsv} (built from the game cache by
 * {@code local-development/tools/map-icons}) and gives each icon something to select: one of our own icons when one
 * stands at the same place, otherwise a new {@link Poi} with a name, wiki query and, for map links, where it leads.
 */
final class MapIconLoader
{
    static final String FILE = "map_icons.tsv";

    /** One baked icon and what hovering or clicking it selects. */
    static final class Icon
    {
        /** The tile the game places the icon on, in the game. */
        final WorldPoint location;
        /**
         * Where the map's tiles draw it: {@link #location}, but for a part of the game the map draws elsewhere (the
         * Keldagrim tunnel); the wiki draws it centred on this tile's south-west corner.
         */
        final WorldPoint drawn;
        /** The map whose tiles show it. */
        final BaseMap map;
        final Poi poi;
        /** True when {@link #poi} is one of our own icons standing at the same place. */
        final boolean own;
        /** The game's map element drawn there, or -1: its sprite draws the icon larger than the tiles do. */
        final int element;

        Icon(WorldPoint location, BaseMap map, Poi poi, boolean own)
        {
            this(location, map, poi, own, -1);
        }

        Icon(WorldPoint location, BaseMap map, Poi poi, boolean own, int element)
        {
            this.location = location;
            this.drawn = MapView.shownOn(map, location);
            this.map = map;
            this.poi = poi;
            this.own = own;
            this.element = element;
        }
    }

    /** A map element: one kind of icon. */
    static final class Kind
    {
        final int id;
        /** shop, quest, link, dungeon or other. */
        final String kind;
        final String name;
        /** Wiki page, or empty. */
        final String wiki;

        Kind(int id, String kind, String name, String wiki)
        {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.wiki = wiki;
        }
    }

    /** A row of the table. */
    static final class Entry
    {
        final WorldPoint location;
        final Kind kind;
        /** Where a map link leads, or null. */
        final WorldPoint target;

        Entry(WorldPoint location, Kind kind, WorldPoint target)
        {
            this.location = location;
            this.kind = kind;
            this.target = target;
        }
    }

    /** Which of our own icon types stand for a kind of game icon, and how far apart they may be. */
    private static final class Match
    {
        final Set<PoiType> types;
        final int radius;

        Match(Set<PoiType> types, int radius)
        {
            this.types = types;
            this.radius = radius;
        }
    }

    private static final Map<String, Match> MATCHES = new HashMap<>();
    private static final Set<PoiType> PASSAGES = EnumSet.of(PoiType.DUNGEON_ENTRANCE, PoiType.MAP_EXIT);
    private static final Set<PoiType> TRANSPORTS = EnumSet.noneOf(PoiType.class);
    /** Shops are named after a settlement this close. */
    private static final int PLACE_RADIUS = 120;

    static
    {
        for (PoiType type : PoiType.values())
        {
            if (type.layer == Layer.TRANSPORTS)
            {
                TRANSPORTS.add(type);
            }
        }
        MATCHES.put("bank", new Match(EnumSet.of(PoiType.BANK), 3));
        MATCHES.put("altar", new Match(EnumSet.of(PoiType.ALTAR), 3));
        MATCHES.put("anvil", new Match(EnumSet.of(PoiType.ANVIL), 3));
        MATCHES.put("transportation", new Match(TRANSPORTS, 8));
        MATCHES.put("agility short-cut", new Match(EnumSet.of(PoiType.AGILITY_SHORTCUT), 4));
        MATCHES.put("agility shortcut (one way)", new Match(EnumSet.of(PoiType.AGILITY_SHORTCUT), 4));
        MATCHES.put("agility training", new Match(EnumSet.of(PoiType.AGILITY_COURSE), 10));
        MATCHES.put("farming patch", new Match(EnumSet.of(PoiType.FARMING_PATCH), 4));
        MATCHES.put("minigame", new Match(EnumSet.of(PoiType.MINIGAME), 14));
        MATCHES.put("raids lobby", new Match(EnumSet.of(PoiType.MINIGAME), 10));
        MATCHES.put("docking point", new Match(EnumSet.of(PoiType.MOORING), 8));
        MATCHES.put("salvaging spot", new Match(EnumSet.of(PoiType.SALVAGE), 8));
    }

    private MapIconLoader()
    {
    }

    /** Reads the bundled table from a data source and builds the icons. */
    static List<Icon> load(BaseMaps maps, List<Poi> own, PoiLoader.Source source, List<PoiLoader.Place> places)
        throws IOException
    {
        List<Entry> entries;
        try (Reader reader = source.open(FILE))
        {
            entries = parse(reader);
        }
        List<Tsv.Row> dungeonRows;
        try
        {
            dungeonRows = PoiLoader.dungeonRows(source);
        }
        catch (IOException | RuntimeException e)
        {
            // Map links are then named after where they lead.
            dungeonRows = new ArrayList<>();
        }
        return load(maps, own, source, places, entries, dungeonRows);
    }

    /** Builds the icons from the tables already read ({@link MapData} reads each once). */
    static List<Icon> load(BaseMaps maps, List<Poi> own, PoiLoader.Source source, List<PoiLoader.Place> places,
        List<Entry> entries, List<Tsv.Row> dungeonRows)
    {
        try
        {
            spots = SkillSpots.load(source);
        }
        catch (IOException | RuntimeException e)
        {
            spots = SkillSpots.NONE;
        }
        List<PoiLoader.Place> dungeons = new ArrayList<>();
        for (Tsv.Row row : dungeonRows)
        {
            WorldPoint at = row.isComment() ? null : row.point("Location");
            if (at != null && !row.get("Name").isEmpty())
            {
                dungeons.add(new PoiLoader.Place(at, row.get("Name"), null));
            }
        }
        Map<WorldPoint, String[]> names = new HashMap<>();
        try (Reader reader = source.open(NAMES_FILE))
        {
            for (Tsv.Row row : Tsv.parse(reader))
            {
                WorldPoint at = row.isComment() ? null : row.point("Location");
                if (at != null && !row.get("Name").isEmpty())
                {
                    names.put(at, new String[]{row.get("Name"), row.get("Wiki")});
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Icons are then named after their kind and town.
        }
        List<Icon> icons = build(maps, entries, own, places, dungeons);
        List<Icon> named = new ArrayList<>(icons.size());
        for (Icon icon : icons)
        {
            String[] name = icon.own ? null : names.get(icon.location);
            if (name == null)
            {
                named.add(icon);
                continue;
            }
            Poi poi = icon.poi;
            Poi renamed = new Poi(poi.type, name[0], poi.location, poi.map, poi.group, poi.needs,
                name[1].isEmpty() ? name[0] : name[1], poi.target, poi.note);
            poi.links().forEach(renamed::addLink);
            named.add(new Icon(icon.location, icon.map, renamed, false, icon.element));
        }
        return named;
    }

    /** Names for game icons their kind and town do not tell apart (the three Barracuda Trials), from the wiki. */
    static final String NAMES_FILE = "icon_names.tsv";

    /** Parses the table; malformed rows are skipped. */
    static List<Entry> parse(Reader source) throws IOException
    {
        BufferedReader reader = new BufferedReader(source);
        Map<Integer, Kind> kinds = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null)
        {
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }
            String[] cells = line.split("\t", -1);
            try
            {
                if (cells[0].equals("K"))
                {
                    if (cells.length >= 4 && !cells[3].trim().isEmpty())
                    {
                        int id = Integer.parseInt(cells[1].trim());
                        kinds.put(id, new Kind(id, cells[2].trim(), cells[3].trim(), cells.length > 4 ? cells[4].trim() : ""));
                    }
                    continue;
                }
                WorldPoint at = Tsv.parsePoint(cells[0]);
                Kind kind = cells.length > 1 ? kinds.get(Integer.parseInt(cells[1].trim())) : null;
                if (at != null && kind != null && at.getPlane() >= 0 && at.getPlane() <= 3)
                {
                    entries.add(new Entry(at, kind, cells.length > 2 ? Tsv.parsePoint(cells[2]) : null));
                }
            }
            catch (NumberFormatException e)
            {
                // Skip the row.
            }
        }
        return entries;
    }

    /**
     * Gives every icon on a known map something to select.
     *
     * @param own our own icons; one of a matching type close by is selected instead of a new one
     * @param places place names, to tell shops of the same kind apart
     */
    static List<Icon> build(BaseMaps maps, List<Entry> entries, List<Poi> own, List<PoiLoader.Place> places)
    {
        return build(maps, entries, own, places, Collections.emptyList());
    }

    /** {@code dungeons}: RuneLite's names of dungeon entrances, which name the game's map links beside them. */
    static List<Icon> build(BaseMaps maps, List<Entry> entries, List<Poi> own, List<PoiLoader.Place> places,
        List<PoiLoader.Place> dungeons)
    {
        Map<Long, List<Poi>> ownByArea = new HashMap<>();
        for (Poi poi : own)
        {
            ownByArea.computeIfAbsent(area(poi.location.getX(), poi.location.getY()), k -> new ArrayList<>()).add(poi);
        }
        List<Icon> icons = new ArrayList<>();
        for (Entry entry : entries)
        {
            // The map that draws it, also where it is drawn elsewhere than it is (the Keldagrim tunnel).
            BaseMap map = maps.find(entry.location);
            if (map == null)
            {
                continue;
            }
            Poi match = ownMatch(entry, ownByArea);
            if (match != null && match.target == null && PASSAGES.contains(match.type))
            {
                // Our entrance knows its name but not where it leads; the game's map link does.
                Poi made = create(maps, map, entry, places, dungeons);
                if (made.target != null)
                {
                    Poi named = new Poi(made.type, match.name, made.location, made.map, null, Needs.NONE, match.wikiQuery,
                        made.target, null);
                    made.links().forEach(named::addLink);
                    icons.add(new Icon(entry.location, map, named, false, entry.kind.id));
                    // Ours stands on the game's icon too: the tile shows that one, so ours is not drawn beside it.
                    icons.add(new Icon(entry.location, map, match, true, entry.kind.id));
                    continue;
                }
            }
            Poi poi = match != null ? match : create(maps, map, entry, places, dungeons);
            icons.add(new Icon(entry.location, map, poi, match != null, entry.kind.id));
        }
        return icons;
    }

    private static Poi ownMatch(Entry entry, Map<Long, List<Poi>> ownByArea)
    {
        Match match = entry.kind.kind.equals("link") || entry.kind.kind.equals("dungeon") ? new Match(PASSAGES, 6)
            : entry.kind.name.startsWith("Fairy ring") ? new Match(EnumSet.of(PoiType.FAIRY_RING), 4)
            : MATCHES.get(entry.kind.name.toLowerCase(Locale.ROOT));
        if (match == null)
        {
            return null;
        }
        WorldPoint at = entry.location;
        Poi best = null;
        int bestDistance = match.radius + 1;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                List<Poi> near = ownByArea.get(area(at.getX() + dx * 64, at.getY() + dy * 64));
                if (near == null)
                {
                    continue;
                }
                for (Poi poi : near)
                {
                    int d = chebyshev(poi.location, at);
                    if (d < bestDistance && poi.location.getPlane() == at.getPlane() && match.types.contains(poi.type))
                    {
                        best = poi;
                        bestDistance = d;
                    }
                }
            }
        }
        return best;
    }

    /** What the skilling icons stand for; set by {@link #load}. */
    private static volatile SkillSpots spots = SkillSpots.NONE;

    /** What the skilling icons stand for, once loaded. */
    static SkillSpots skillSpots()
    {
        return spots;
    }

    private static Poi create(BaseMaps maps, BaseMap map, Entry entry, List<PoiLoader.Place> places,
        List<PoiLoader.Place> dungeons)
    {
        Kind kind = entry.kind;
        WorldPoint at = entry.location;
        String wiki = kind.wiki.isEmpty() ? kind.name : kind.wiki;
        switch (kind.kind)
        {
            case "quest":
                return new Poi(PoiType.QUEST_START, kind.name, at, map, null, Needs.NONE, kind.name, null, null);
            case "shop":
            {
                String place = map.id == BaseMap.SURFACE ? settlement(places, at) : null;
                String name = place == null ? kind.name : kind.name + " (" + place + ")";
                // The wiki search finds the particular shop from its town and kind, such as "Lumbridge General Store".
                return new Poi(PoiType.SHOP, name, at, map, null, Needs.NONE, place == null ? wiki : place + " " + kind.name,
                    null, null);
            }
            case "link":
            case "dungeon":
            {
                // Only where the game itself says where it leads; a guess (a way down 6400 tiles north, a nearby pair)
                // too often led to the wrong entrance, so without one the icon stays a plain game icon.
                WorldPoint target = entry.target;
                if (target == null && kind.kind.equals("dungeon"))
                {
                    // The game's dungeon marker has no map link of its own: where a trusted passage beside it leads
                    // (Waterbirth's ladder down to the sub-levels), else it stays a plain icon without a way in.
                    target = TrustedPassages.leadsFrom(at, p -> {
                        BaseMap drawn = maps.find(p);
                        return drawn == null || drawn.id == BaseMap.SURFACE;
                    });
                }
                // The map that draws where it leads, also where that is drawn elsewhere (Keldagrim's tunnel).
                BaseMap targetMap = target == null ? null : maps.find(target);
                boolean dungeon = kind.kind.equals("dungeon");
                String named = dungeonName(dungeons, at);
                if (targetMap == null)
                {
                    String plain = named != null ? named : dungeon ? "Dungeon" : "Map link";
                    // Nowhere to go: a plain icon, never a way in that leads nowhere.
                    return new Poi(PoiType.GAME_ICON, plain, at, map, null, Needs.NONE,
                        named != null ? named : dungeon ? "Dungeons" : null, null, null);
                }
                // A link up to the surface is named after the town it shows, one down after the dungeon's map.
                String place = targetMap.id == BaseMap.SURFACE ? settlement(places, target) : null;
                String where = place != null ? place : targetMap != map ? targetMap.name : null;
                String name = named != null && (targetMap != map || dungeon) && targetMap.id != BaseMap.SURFACE ? named
                    : where != null ? "To " + where : dungeon ? "Dungeon" : "Map link";
                PoiType type = dungeon && targetMap != map ? PoiType.DUNGEON_ENTRANCE : PoiType.MAP_LINK;
                Poi poi = new Poi(type, name, at, map, null, Needs.NONE, where, targetMap, null);
                poi.addLink(new Poi.Link(place != null ? place : "Where it leads", target, targetMap, Needs.NONE));
                return poi;
            }
            default:
            {
                String place = map.id == BaseMap.SURFACE ? settlement(places, at) : null;
                SkillSpots.Spot spot = spots.near(kind.name, at);
                if (spot != null)
                {
                    return SkillSpots.poi(spot, at, map, place);
                }
                // Named after the town, like shops, so a list of furnaces or slayer masters can be told apart.
                return new Poi(PoiType.GAME_ICON, place == null ? kind.name : kind.name + " (" + place + ")", at, map, null,
                    Needs.NONE, wiki, null, null);
            }
        }
    }

    /** RuneLite's name for the dungeon entrance beside a map link (the same floor, a few tiles off), or null. */
    static String dungeonName(List<PoiLoader.Place> dungeons, WorldPoint at)
    {
        String best = null;
        int bestDistance = DUNGEON_NAME_RADIUS + 1;
        for (PoiLoader.Place dungeon : dungeons)
        {
            int d = chebyshev(dungeon.point, at);
            if (dungeon.point.getPlane() == at.getPlane() && d < bestDistance)
            {
                best = dungeon.name;
                bestDistance = d;
            }
        }
        return best;
    }

    /** How far RuneLite's dungeon name may be from the game's map link it names. */
    private static final int DUNGEON_NAME_RADIUS = 6;

    /** The nearest settlement or island name, if one is close. */
    static String settlement(List<PoiLoader.Place> places, WorldPoint at)
    {
        String best = null;
        int bestDistance = PLACE_RADIUS + 1;
        for (PoiLoader.Place place : places)
        {
            if (!"settlement".equals(place.kind) && !"island".equals(place.kind))
            {
                continue;
            }
            int d = chebyshev(place.point, at);
            if (d < bestDistance)
            {
                best = place.name;
                bestDistance = d;
            }
        }
        return best;
    }

    static List<Icon> none()
    {
        return Collections.emptyList();
    }

    private static long area(int x, int y)
    {
        return ((long) (x >> 6) << 32) | ((y >> 6) & 0xffffffffL);
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
}
