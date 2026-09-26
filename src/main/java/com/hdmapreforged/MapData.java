package com.hdmapreforged;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything the map shows and the planner uses, loaded in one place by the plugin and by the tests that build the map
 * outside the game (see local-development/APPROACH.md).
 *
 * <p>Places and ways in come from the game's own world map ({@code map_icons.tsv}): its icons, and its map links, which
 * say where they lead. Our own icons are added only where the game has none: teleports and transport (boats, fairy
 * rings, spirit trees, gliders and the like), shortcuts and the like. Our own dungeon entrances and passages stay in the
 * list for the route planner but are not drawn: the game's icon stands there, and only its map link says where "go in"
 * leads.
 */
@Slf4j
final class MapData
{
    /** Our icons in order of layers, with the planner's passages among them (see {@link #hidden}). */
    final List<Poi> pois;
    /** Of {@link #pois}, those the map does not draw: our own entrances and passages. */
    final Set<Poi> hidden;
    final List<PoiLoader.Place> labels;
    /** The game's own world map icons, each with what clicking it selects. */
    final List<MapIconLoader.Icon> icons;
    /** The rows of {@code map_icons.tsv}, read once for everyone (the route planner's map links too). */
    final List<MapIconLoader.Entry> iconEntries;

    private MapData(List<Poi> pois, Set<Poi> hidden, List<PoiLoader.Place> labels, List<MapIconLoader.Icon> icons,
        List<MapIconLoader.Entry> iconEntries)
    {
        this.pois = pois;
        this.hidden = hidden;
        this.labels = labels;
        this.icons = icons;
        this.iconEntries = iconEntries;
    }

    /**
     * Every place the map must know the right map for (which wiki map draws it where maps overlap): our icons and where
     * they lead, the game's icons and where its map links lead.
     */
    List<net.runelite.api.coords.WorldPoint> points()
    {
        List<net.runelite.api.coords.WorldPoint> points = new ArrayList<>();
        for (Poi poi : pois)
        {
            points.add(poi.location);
            for (Poi.Link link : poi.links())
            {
                points.add(link.point);
            }
        }
        for (MapIconLoader.Icon icon : icons)
        {
            points.add(icon.location);
            for (Poi.Link link : icon.poi.links())
            {
                points.add(link.point);
            }
        }
        return points;
    }

    /** Our entrances and passages: the planner's, not drawn; the game's map links stand for them on the map. */
    static boolean passage(Poi poi)
    {
        return poi.type == PoiType.DUNGEON_ENTRANCE || poi.type == PoiType.MAP_EXIT;
    }

    static MapData load(BaseMaps maps, PoiLoader.Source source) throws IOException
    {
        // Each table read once, then shared.
        List<PoiLoader.Place> labels = Collections.unmodifiableList(PoiLoader.labels(source));
        List<MapIconLoader.Entry> entries;
        try (java.io.Reader reader = source.open(MapIconLoader.FILE))
        {
            entries = Collections.unmodifiableList(MapIconLoader.parse(reader));
        }
        List<Tsv.Row> dungeons = Collections.unmodifiableList(PoiLoader.dungeonRows(source));
        List<Poi> pois = Colocate.merge(PoiLoader.load(maps, source, labels, entries, dungeons));
        Set<Poi> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Poi> shown = new ArrayList<>();
        for (Poi poi : pois)
        {
            if (passage(poi))
            {
                hidden.add(poi);
            }
            else
            {
                shown.add(poi);
            }
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

    /** Tiles within which our icons of one station (both tables name it) are one with the game's icon for it. */
    static final int STATION = 16;

    /**
     * A station both our tables and RuneLite's list name ("Keldagrim" and "Keldagrim Minecart System") is one place: the
     * game's icon there selects the one of ours that knows the most destinations, and the others are not drawn.
     */
    static List<MapIconLoader.Icon> oneStation(List<Poi> pois, List<MapIconLoader.Icon> icons, Set<Poi> hidden)
    {
        Set<Poi> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MapIconLoader.Icon icon : icons)
        {
            if (icon.own)
            {
                covered.add(icon.poi);
            }
        }
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
                    && other.location.getPlane() == mine.location.getPlane()
                    && Math.max(Math.abs(other.location.getX() - mine.location.getX()),
                    Math.abs(other.location.getY() - mine.location.getY())) <= STATION && sameStation(mine, other))
                {
                    same.add(other);
                    if (other.links().size() > best.links().size())
                    {
                        best = other;
                    }
                }
            }
            for (Poi other : same)
            {
                if (other != best)
                {
                    hidden.add(other);
                }
            }
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

    /** Two names of one station: the same, one the start of the other, or one only the kind's name. */
    static boolean sameStation(Poi a, Poi b)
    {
        String x = stationKey(a.name);
        String y = stationKey(b.name);
        return x.equals(y) || x.startsWith(y) || y.startsWith(x)
            || a.name.equalsIgnoreCase(a.type.displayName) || b.name.equalsIgnoreCase(b.type.displayName);
    }

    private static String stationKey(String name)
    {
        return TRAILING_BRACKETS.matcher(name).replaceAll("").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static final java.util.regex.Pattern TRAILING_BRACKETS =
        java.util.regex.Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    /**
     * An agility shortcut is one thing, but our tables have a row (and so an icon) for each end: the end the game's
     * own icon stands on is drawn (as that icon), the other not; without a game icon, the first end only.
     */
    static Set<Poi> otherEnds(List<Poi> pois, List<MapIconLoader.Icon> icons)
    {
        Set<Poi> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MapIconLoader.Icon icon : icons)
        {
            if (icon.own)
            {
                covered.add(icon.poi);
            }
        }
        List<Poi> shortcuts = new ArrayList<>();
        for (Poi poi : pois)
        {
            if (poi.type == PoiType.AGILITY_SHORTCUT)
            {
                shortcuts.add(poi);
            }
        }
        Set<Poi> ends = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < shortcuts.size(); i++)
        {
            Poi a = shortcuts.get(i);
            for (int j = i + 1; j < shortcuts.size(); j++)
            {
                Poi b = shortcuts.get(j);
                if (!ends.contains(a) && !ends.contains(b) && a.name.equals(b.name) && leadsTo(a, b) && leadsTo(b, a))
                {
                    // Keep the end a game icon stands on (it shows as that icon); else the first.
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
            if (link.point.getPlane() == to.location.getPlane() && Math.max(Math.abs(link.point.getX()
                - to.location.getX()), Math.abs(link.point.getY() - to.location.getY())) <= 2)
            {
                return true;
            }
        }
        return false;
    }
}
