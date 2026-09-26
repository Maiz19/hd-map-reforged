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
 * The game's own map icons, baked into the wiki tiles, from {@code map_icons.tsv}: each gets something to select, our
 * own icon at the same place or a new {@link Poi}.
 */
final class MapIconLoader
{
    static final String FILE = "map_icons.tsv";

    static final class Icon
    {
        final WorldPoint location;
        /** Differs from {@link #location} where the map draws a place elsewhere (the Keldagrim tunnel). */
        final WorldPoint drawn;
        final BaseMap map;
        final Poi poi;
        final boolean own;
        /** The game's map element, or -1. */
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
            dungeonRows = new ArrayList<>();
        }
        return load(maps, own, source, places, entries, dungeonRows);
    }

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

    /** Names for icons their kind and town do not tell apart (the Barracuda Trials). */
    static final String NAMES_FILE = "icon_names.tsv";

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
            }
        }
        return entries;
    }

    static List<Icon> build(BaseMaps maps, List<Entry> entries, List<Poi> own, List<PoiLoader.Place> places)
    {
        return build(maps, entries, own, places, Collections.emptyList());
    }

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
            BaseMap map = maps.find(entry.location);
            if (map == null)
            {
                continue;
            }
            Poi match = ownMatch(entry, ownByArea);
            if (match != null && match.target == null && PASSAGES.contains(match.type))
            {
                Poi made = create(maps, map, entry, places, dungeons);
                if (made.target != null)
                {
                    Poi named = new Poi(made.type, match.name, made.location, made.map, null, Needs.NONE, match.wikiQuery,
                        made.target, null);
                    made.links().forEach(named::addLink);
                    icons.add(new Icon(entry.location, map, named, false, entry.kind.id));
                    // The tile already shows the game's icon, so ours is not drawn beside it.
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

    private static volatile SkillSpots spots = SkillSpots.NONE;

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
                return new Poi(PoiType.SHOP, name, at, map, null, Needs.NONE, place == null ? wiki : place + " " + kind.name,
                    null, null);
            }
            case "link":
            case "dungeon":
            {
                // Only where the game says where it leads: guesses too often led to the wrong entrance.
                WorldPoint target = entry.target;
                if (target == null && kind.kind.equals("dungeon"))
                {
                    // A dungeon marker has no link: use a trusted passage beside it (Waterbirth's ladder).
                    target = TrustedPassages.leadsFrom(at, p -> {
                        BaseMap drawn = maps.find(p);
                        return drawn == null || drawn.id == BaseMap.SURFACE;
                    });
                }
                BaseMap targetMap = target == null ? null : maps.find(target);
                boolean dungeon = kind.kind.equals("dungeon");
                String named = dungeonName(dungeons, at);
                if (targetMap == null)
                {
                    String plain = named != null ? named : dungeon ? "Dungeon" : "Map link";
                    return new Poi(PoiType.GAME_ICON, plain, at, map, null, Needs.NONE,
                        named != null ? named : dungeon ? "Dungeons" : null, null, null);
                }
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
                return new Poi(PoiType.GAME_ICON, place == null ? kind.name : kind.name + " (" + place + ")", at, map, null,
                    Needs.NONE, wiki, null, null);
            }
        }
    }

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

    private static final int DUNGEON_NAME_RADIUS = 6;

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
