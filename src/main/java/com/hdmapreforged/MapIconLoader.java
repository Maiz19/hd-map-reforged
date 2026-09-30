package com.hdmapreforged;

import java.io.*;
import java.util.*;
import lombok.*;
import net.runelite.api.coords.*;

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

    @RequiredArgsConstructor
    static final class Kind
    {
        final int id;
        /** shop, quest, link, dungeon or other. */
        final String kind;
        final String name;
        /** Wiki page, or empty. */
        final String wiki;
    }

    @RequiredArgsConstructor
    static final class Entry
    {
        final WorldPoint location;
        final Kind kind;
        /** Where a map link leads, or null. */
        final WorldPoint target;
    }

    @RequiredArgsConstructor
    private static final class Match
    {
        final Set<PoiType> types;
        final int radius;
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
        MATCHES.put("transportation", new Match(TRANSPORTS, 8));
        match(PoiType.BANK, 3, "bank");
        match(PoiType.ALTAR, 3, "altar");
        match(PoiType.ANVIL, 3, "anvil");
        match(PoiType.AGILITY_SHORTCUT, 4, "agility short-cut", "agility shortcut (one way)");
        match(PoiType.AGILITY_COURSE, 10, "agility training");
        match(PoiType.FARMING_PATCH, 4, "farming patch");
        match(PoiType.MINIGAME, 14, "minigame");
        match(PoiType.MINIGAME, 10, "raids lobby");
        match(PoiType.MOORING, 8, "docking point");
        match(PoiType.SALVAGE, 8, "salvaging spot");
    }

    private static void match(PoiType type, int radius, String... kinds)
    {
        for (String kind : kinds)
        {
            MATCHES.put(kind, new Match(EnumSet.of(type), radius));
        }
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
            WorldPoint at = row.point("Location");
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
                String name = row.get("Name");
                if (at != null && !name.isEmpty())
                {
                    names.put(at, new String[]{name, row.or("Wiki", name)});
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
        }
        List<Icon> named = new ArrayList<>();
        for (Icon icon : build(maps, entries, own, places, dungeons))
        {
            String[] name = icon.own ? null : names.get(icon.location);
            named.add(name == null ? icon
                : new Icon(icon.location, icon.map, PoiLoader.renamed(icon.poi, name[0], name[1]), false, icon.element));
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
                    icons.add(new Icon(entry.location, map, PoiLoader.renamed(made, match.name, match.wikiQuery), false,
                        entry.kind.id));
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
                for (Poi poi : ownByArea.getOrDefault(area(at.getX() + dx * 64, at.getY() + dy * 64), List.of()))
                {
                    int d = PoiLoader.chebyshev(poi.location, at);
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
        boolean dungeon = kind.kind.equals("dungeon");
        if (kind.kind.equals("quest"))
        {
            return icon(PoiType.QUEST_START, kind.name, at, map, kind.name, null);
        }
        if (!dungeon && !kind.kind.equals("link"))
        {
            String place = map.id == BaseMap.SURFACE ? settlement(places, at) : null;
            String name = place == null ? kind.name : kind.name + " (" + place + ")";
            if (kind.kind.equals("shop"))
            {
                return icon(PoiType.SHOP, name, at, map, place == null ? wiki : place + " " + kind.name, null);
            }
            SkillSpots.Spot spot = spots.near(kind.name, at);
            return spot != null ? SkillSpots.poi(spot, at, map, place) : icon(PoiType.GAME_ICON, name, at, map, wiki, null);
        }
        // Only where the game says where it leads: guesses too often led to the wrong entrance.
        WorldPoint target = entry.target;
        if (target == null && dungeon)
        {
            // A dungeon marker has no link: use a trusted passage beside it (Waterbirth's ladder).
            target = TrustedPassages.leadsFrom(at, p -> {
                BaseMap drawn = maps.find(p);
                return drawn == null || drawn.id == BaseMap.SURFACE;
            });
        }
        BaseMap targetMap = target == null ? null : maps.find(target);
        String named = dungeonName(dungeons, at);
        if (targetMap == null)
        {
            return icon(PoiType.GAME_ICON, named != null ? named : dungeon ? "Dungeon" : "Map link", at, map,
                named != null ? named : dungeon ? "Dungeons" : null, null);
        }
        String place = targetMap.id == BaseMap.SURFACE ? settlement(places, target) : null;
        String where = place != null ? place : targetMap != map ? targetMap.name : null;
        String name = named != null && (targetMap != map || dungeon) && targetMap.id != BaseMap.SURFACE ? named
            : where != null ? "To " + where : dungeon ? "Dungeon" : "Map link";
        Poi poi = icon(dungeon && targetMap != map ? PoiType.DUNGEON_ENTRANCE : PoiType.MAP_LINK, name, at, map, where,
            targetMap);
        poi.addLink(new Poi.Link(place != null ? place : "Where it leads", target, targetMap, Needs.NONE));
        return poi;
    }

    private static Poi icon(PoiType type, String name, WorldPoint at, BaseMap map, String wiki, BaseMap target)
    {
        return new Poi(type, name, at, map, null, Needs.NONE, wiki, target, null);
    }

    static String dungeonName(List<PoiLoader.Place> dungeons, WorldPoint at)
    {
        String best = null;
        int bestDistance = DUNGEON_NAME_RADIUS + 1;
        for (PoiLoader.Place dungeon : dungeons)
        {
            int d = PoiLoader.chebyshev(dungeon.point, at);
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
            int d = PoiLoader.chebyshev(place.point, at);
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
}
