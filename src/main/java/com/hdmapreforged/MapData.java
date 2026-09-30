package com.hdmapreforged;

import lombok.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;

/**
 * Everything the map shows and the planner uses, loaded in one place by the plugin and tests. Places and ways in come
 * from the game's map_icons.tsv; ours only where the game has none. Our entrances and passages are the planner's only.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class MapData
{
    /** In order of layers, with the planner's passages among them. */
    final List<Poi> pois;
    /** Of {@link #pois}, those not drawn. */
    final Set<Poi> hidden;
    final List<PoiLoader.Place> labels;
    final List<MapIconLoader.Icon> icons;
    final List<MapIconLoader.Entry> iconEntries;


    /** Every place the map must know the right wiki map for. */
    List<WorldPoint> points()
    {
        List<WorldPoint> points = new ArrayList<>();
        pois.forEach(poi -> points(points, poi.location, poi));
        icons.forEach(icon -> points(points, icon.location, icon.poi));
        return points;
    }

    private static void points(List<WorldPoint> points, WorldPoint at, Poi poi)
    {
        points.add(at);
        poi.links().forEach(link -> points.add(link.point));
    }

    static boolean passage(Poi poi)
    {
        return poi.type == PoiType.DUNGEON_ENTRANCE || poi.type == PoiType.MAP_EXIT;
    }

    static MapData load(BaseMaps maps, PoiLoader.Source source) throws IOException
    {
        List<PoiLoader.Place> labels = Collections.unmodifiableList(PoiLoader.labels(source));
        List<MapIconLoader.Entry> entries;
        try (Reader reader = source.open(MapIconLoader.FILE))
        {
            entries = Collections.unmodifiableList(MapIconLoader.parse(reader));
        }
        List<Tsv.Row> dungeons = Collections.unmodifiableList(PoiLoader.dungeonRows(source));
        List<Poi> pois = Colocate.merge(PoiLoader.load(maps, source, labels, entries, dungeons));
        Set<Poi> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Poi> shown = new ArrayList<>();
        for (Poi poi : pois)
        {
            (passage(poi) ? hidden : shown).add(poi);
        }
        List<MapIconLoader.Icon> icons;
        try
        {
            // Matched against the icons the map draws only: a game map link is never ours in disguise.
            icons = MapIconLoader.load(maps, shown, source, labels, entries, dungeons);
        }
        catch (RuntimeException e)
        {
            log.warn("Could not build the game's map icons; the map shows only its own", e);
            icons = MapIconLoader.none();
        }
        hidden.addAll(otherEnds(shown, icons));
        icons = oneStation(shown, icons, hidden);
        return new MapData(pois, hidden, labels, icons, entries);
    }

    static final int STATION = 16;

    /** A station named twice ("Keldagrim", "Keldagrim Minecart System"): the game's icon selects ours with most links. */
    static List<MapIconLoader.Icon> oneStation(List<Poi> pois, List<MapIconLoader.Icon> icons, Set<Poi> hidden)
    {
        Set<Poi> covered = covered(icons);
        List<MapIconLoader.Icon> result = new ArrayList<>(icons.size());
        for (MapIconLoader.Icon icon : icons)
        {
            Poi mine = icon.poi;
            if (!icon.own || mine.type.layer != Layer.TRANSPORTS)
            {
                result.add(icon);
                continue;
            }
            Poi best = mine;
            List<Poi> same = new ArrayList<>();
            for (Poi other : pois)
            {
                if (other != mine && other.type == mine.type && !covered.contains(other) && !hidden.contains(other)
                    && PoiLoader.near(other.location, mine.location, STATION) && sameStation(mine, other))
                {
                    same.add(other);
                    if (other.links().size() > best.links().size())
                    {
                        best = other;
                    }
                }
            }
            same.remove(best);
            hidden.addAll(same);
            if (best != mine)
            {
                hidden.add(mine);
                result.add(new MapIconLoader.Icon(icon.location, icon.map, best, true, icon.element));
            }
            else
            {
                result.add(icon);
            }
        }
        return result;
    }

    static boolean sameStation(Poi a, Poi b)
    {
        String x = stationKey(a.name);
        String y = stationKey(b.name);
        return x.equals(y) || x.startsWith(y) || y.startsWith(x)
            || a.name.equalsIgnoreCase(a.type.displayName) || b.name.equalsIgnoreCase(b.type.displayName);
    }

    private static String stationKey(String name)
    {
        return TRAILING_BRACKETS.matcher(name).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern TRAILING_BRACKETS = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    /** Our pois the game's icons stand for. */
    private static Set<Poi> covered(List<MapIconLoader.Icon> icons)
    {
        Set<Poi> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        icons.stream().filter(icon -> icon.own).forEach(icon -> covered.add(icon.poi));
        return covered;
    }

    /** A shortcut has an icon per end: only the end a game icon stands on is drawn, else the first. */
    static Set<Poi> otherEnds(List<Poi> pois, List<MapIconLoader.Icon> icons)
    {
        Set<Poi> covered = covered(icons);
        List<Poi> shortcuts = pois.stream().filter(poi -> poi.type == PoiType.AGILITY_SHORTCUT).collect(Collectors.toList());
        Set<Poi> ends = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < shortcuts.size(); i++)
        {
            Poi a = shortcuts.get(i);
            for (int j = i + 1; j < shortcuts.size(); j++)
            {
                Poi b = shortcuts.get(j);
                if (!ends.contains(a) && !ends.contains(b) && a.name.equals(b.name) && leadsTo(a, b) && leadsTo(b, a))
                {
                    ends.add(covered.contains(b) && !covered.contains(a) ? a : b);
                }
            }
        }
        return ends;
    }

    private static boolean leadsTo(Poi from, Poi to)
    {
        for (Poi.Link link : from.links())
        {
            if (PoiLoader.near(link.point, to.location, 2))
            {
                return true;
            }
        }
        return false;
    }
}
