package com.hdmapreforged;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** Builds map icons from the bundled travel tables, game map icons and RuneLite lists; same-place rows merge. */
final class PoiLoader
{
    interface Source
    {
        Reader open(String file) throws IOException;
    }

    static final String TELEPORTS = "teleports.tsv";
    static final String NETWORKS = "transport_networks.tsv";
    static final String ROUTES = "transport_routes.tsv";
    static final String SHORTCUTS = "shortcuts.tsv";

    static final class Place
    {
        final WorldPoint point;
        final String name;
        /** "region", "island" or "settlement" for map labels; null for places only used for naming. */
        final String kind;

        Place(WorldPoint point, String name, String kind)
        {
            this.point = point;
            this.name = name;
            this.kind = kind;
        }
    }

    private static final class Cluster
    {
        final String key;
        final String name;
        final List<WorldPoint> points = new ArrayList<>();
        final List<Poi.Link> links = new ArrayList<>();
        final List<Needs> trips = new ArrayList<>();
        Tsv.Row first;
        String shown;

        Cluster(String key, String name)
        {
            this.key = key;
            this.name = name;
        }

        boolean near(WorldPoint p, int radius)
        {
            for (WorldPoint q : points)
            {
                if (q.getPlane() == p.getPlane() && chebyshev(q, p) <= radius)
                {
                    return true;
                }
            }
            return false;
        }

        /** Nearest the middle, so the icon sits on a real tile. */
        WorldPoint center()
        {
            double x = 0;
            double y = 0;
            for (WorldPoint p : points)
            {
                x += p.getX();
                y += p.getY();
            }
            x /= points.size();
            y /= points.size();
            WorldPoint best = points.get(0);
            double bestDistance = Double.MAX_VALUE;
            for (WorldPoint p : points)
            {
                double d = (p.getX() - x) * (p.getX() - x) + (p.getY() - y) * (p.getY() - y);
                if (d < bestDistance)
                {
                    best = p;
                    bestDistance = d;
                }
            }
            return best;
        }
    }

    private static final Map<String, PoiType> NETWORK_TYPES = new HashMap<>();
    private static final Map<String, PoiType> ROUTE_TYPES = new HashMap<>();

    static
    {
        NETWORK_TYPES.put("fairy_ring", PoiType.FAIRY_RING);
        NETWORK_TYPES.put("spirit_tree", PoiType.SPIRIT_TREE);
        NETWORK_TYPES.put("gnome_glider", PoiType.GNOME_GLIDER);
        NETWORK_TYPES.put("balloon", PoiType.BALLOON);
        NETWORK_TYPES.put("quetzal", PoiType.QUETZAL);
        NETWORK_TYPES.put("mushtree", PoiType.MUSHTREE);
        NETWORK_TYPES.put("obelisk", PoiType.OBELISK);
        ROUTE_TYPES.put("boat", PoiType.BOAT);
        ROUTE_TYPES.put("charter", PoiType.CHARTER);
        ROUTE_TYPES.put("canoe", PoiType.CANOE);
        ROUTE_TYPES.put("carpet", PoiType.CARPET);
        ROUTE_TYPES.put("minecart", PoiType.MINECART);
        ROUTE_TYPES.put("portal", PoiType.PORTAL);
        ROUTE_TYPES.put("lever", PoiType.LEVER);
    }

    private static final int PLACE_RADIUS = 60;

    private final BaseMaps maps;
    private final Source source;
    private final List<Poi> pois = new ArrayList<>();
    private final List<Place> places = new ArrayList<>();
    private List<Tsv.Row> dungeonRows;
    private static final Pattern LEVEL = Pattern.compile("\\d{1,3}");

    private PoiLoader(BaseMaps maps, Source source)
    {
        this.maps = maps;
        this.source = source;
    }

    static List<Poi> load(BaseMaps maps, Source source) throws IOException
    {
        List<MapIconLoader.Entry> icons;
        try (Reader reader = source.open(MapIconLoader.FILE))
        {
            icons = MapIconLoader.parse(reader);
        }
        return load(maps, source, labels(source), icons, dungeonRows(source));
    }

    /** With the shared tables already parsed ({@link MapData} reads each once). */
    static List<Poi> load(BaseMaps maps, Source source, List<Place> labels, List<MapIconLoader.Entry> icons,
        List<Tsv.Row> dungeons) throws IOException
    {
        PoiLoader loader = new PoiLoader(maps, source);
        loader.dungeonRows = dungeons;
        loader.places.addAll(labels);
        loader.teleports();
        loader.services(icons, "Bank", PoiType.BANK);
        loader.services(icons, "Altar", PoiType.ALTAR);
        loader.services(icons, "Anvil", PoiType.ANVIL);
        loader.networks();
        loader.routes();
        loader.passages(icons);
        loader.runeliteDungeons();
        loader.runeliteTransports();
        linkRowboats(loader.pois);
        loader.runeliteList("runelite_moorings.tsv", PoiType.MOORING, "Sailing");
        loader.runeliteList("runelite_salvage.tsv", PoiType.SALVAGE, "Sailing");
        loader.runeliteList("runelite_runecraft_altars.tsv", PoiType.RUNECRAFT_ALTAR, "Runecraft");
        loader.runeliteList("runelite_agility_courses.tsv", PoiType.AGILITY_COURSE, null);
        loader.runeliteList("runelite_farming_patches.tsv", PoiType.FARMING_PATCH, null);
        loader.runeliteList("runelite_minigames.tsv", PoiType.MINIGAME, null);
        loader.shortcuts();
        loader.nameDuplicateTeleports();
        loader.stackTeleports();
        return loader.pois;
    }

    private static final int ROWBOAT_REACH = 500;
    private static final int ROWBOAT_PAIR = 60;

    /**
     * RuneLite names multi-stop rowboats by the other stops ("Rowboat to Molch/Shayzien"), so a boat's own stop is the
     * network name it does not list; it then links to the boats of the stops it names.
     */
    static void linkRowboats(List<Poi> pois)
    {
        List<Poi> boats = new ArrayList<>();
        Map<Poi, List<String>> names = new IdentityHashMap<>();
        for (Poi poi : pois)
        {
            if (poi.type == PoiType.BOAT && poi.name.startsWith("Rowboat to "))
            {
                List<String> stops = new ArrayList<>();
                for (String stop : poi.name.substring("Rowboat to ".length()).split("/"))
                {
                    if (!stop.trim().isEmpty())
                    {
                        stops.add(stop.trim());
                    }
                }
                boats.add(poi);
                names.put(poi, stops);
            }
        }
        List<List<Poi>> networks = new ArrayList<>();
        for (Poi boat : boats)
        {
            List<Poi> joined = null;
            for (List<Poi> network : networks)
            {
                for (Poi other : network)
                {
                    int d = chebyshev(other.location, boat.location);
                    if (d <= ROWBOAT_PAIR || d <= ROWBOAT_REACH
                        && !Collections.disjoint(names.get(other), names.get(boat)))
                    {
                        if (joined == null)
                        {
                            network.add(boat);
                            joined = network;
                        }
                        else if (joined != network)
                        {
                            joined.addAll(network);
                            network.clear();
                        }
                        break;
                    }
                }
            }
            if (joined == null)
            {
                networks.add(new ArrayList<>(Collections.singletonList(boat)));
            }
        }
        for (List<Poi> network : networks)
        {
            Set<String> all = new LinkedHashSet<>();
            for (Poi boat : network)
            {
                all.addAll(names.get(boat));
            }
            Map<String, Poi> at = new HashMap<>();
            for (Poi boat : network)
            {
                Set<String> own = new LinkedHashSet<>(all);
                own.removeAll(names.get(boat));
                if (own.size() == 1)
                {
                    at.putIfAbsent(own.iterator().next(), boat);
                }
            }
            for (Poi boat : network)
            {
                if (!at.containsValue(boat) || !boat.links().isEmpty())
                {
                    continue;
                }
                for (String stop : names.get(boat))
                {
                    Poi to = at.get(stop);
                    if (to != null && to != boat)
                    {
                        boat.addLink(new Poi.Link(stop, to.location, to.map, Needs.NONE));
                    }
                }
            }
        }
    }

    static List<Place> labels(Source source) throws IOException
    {
        List<Place> labels = new ArrayList<>();
        try (Reader reader = source.open("place_names.tsv"))
        {
            for (Tsv.Row row : Tsv.parse(reader))
            {
                WorldPoint at = row.isComment() ? null : row.point("Location");
                String name = row.get("Name");
                // The wiki's "Unnamed island (…)" pages describe a spot rather than name it.
                if (at != null && !name.startsWith("Unnamed"))
                {
                    labels.add(new Place(at, name.replaceFirst("\\s*\\((?:location|Wilderness)\\)$", ""), row.get("Kind")));
                }
            }
        }
        return labels;
    }

    static List<Tsv.Row> dungeonRows(Source source) throws IOException
    {
        return read(source, "runelite_dungeons.tsv");
    }

    private List<Tsv.Row> read(String file) throws IOException
    {
        return read(source, file);
    }

    private static List<Tsv.Row> read(Source source, String file) throws IOException
    {
        List<Tsv.Row> rows = new ArrayList<>();
        try (Reader reader = source.open(file))
        {
            for (Tsv.Row row : Tsv.parse(reader))
            {
                if (!row.isComment())
                {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private void teleports() throws IOException
    {
        Set<String> seen = new HashSet<>();
        for (Tsv.Row row : read(TELEPORTS))
        {
            WorldPoint destination = row.point("Destination");
            String name = row.get("Name");
            if (destination == null || name.isEmpty() || !seen.add(name + destination))
            {
                continue;
            }
            String group = row.get("Group");
            if (name.contains(": "))
            {
                addPlace(destination, name.substring(name.indexOf(": ") + 2));
            }
            String wiki = row.get("Wiki");
            pois.add(new Poi(PoiType.TELEPORT, name, destination, map(destination), group.isEmpty() ? null : group,
                Needs.of(row), wiki.isEmpty() ? name : wiki, null, null));
        }
    }

    private void networks() throws IOException
    {
        Map<String, List<Tsv.Row>> byNetwork = new LinkedHashMap<>();
        for (Tsv.Row row : read(NETWORKS))
        {
            if (NETWORK_TYPES.containsKey(row.get("Network")) && row.point("Arrival") != null)
            {
                byNetwork.computeIfAbsent(row.get("Network"), k -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<Tsv.Row>> network : byNetwork.entrySet())
        {
            PoiType type = NETWORK_TYPES.get(network.getKey());
            for (Tsv.Row stop : network.getValue())
            {
                WorldPoint location = stop.point("Location");
                WorldPoint at = location != null ? location : stop.point("Arrival");
                String name = stop.get("Stop").isEmpty() ? type.displayName : stop.get("Stop");
                addPlace(at, name.substring(name.lastIndexOf(':') + 1).replaceFirst("^.*– ", ""));
                String note = stop.get("Note");
                if (location == null && note.isEmpty())
                {
                    note = "You can only arrive here";
                }
                Poi poi = new Poi(type, name, at, map(at), type.name(), Needs.of(stop), type.wikiPage, null,
                    note.isEmpty() ? null : note);
                if (location != null)
                {
                    for (Tsv.Row other : network.getValue())
                    {
                        WorldPoint arrival = other.point("Arrival");
                        if (other != stop && arrival.distanceTo2D(at) > 3)
                        {
                            String label = other.get("Stop").isEmpty() ? nameNear(arrival) : other.get("Stop");
                            poi.addLink(new Poi.Link(label, arrival, map(arrival), Needs.of(other)));
                        }
                    }
                }
                pois.add(poi);
            }
        }
    }

    private void routes() throws IOException
    {
        Map<PoiType, List<Cluster>> byType = new LinkedHashMap<>();
        for (Tsv.Row row : read(ROUTES))
        {
            PoiType type = ROUTE_TYPES.get(row.get("Type"));
            WorldPoint origin = row.point("Origin");
            WorldPoint destination = row.point("Destination");
            if (type == null || origin == null || destination == null)
            {
                continue;
            }
            List<Cluster> clusters = byType.computeIfAbsent(type, k -> new ArrayList<>());
            Cluster cluster = add(clusters, row.get("From"), row.get("From"), origin, row, 8);
            cluster.trips.add(Needs.of(row));
            boolean known = false;
            for (Poi.Link link : cluster.links)
            {
                known |= link.point.getPlane() == destination.getPlane() && link.point.distanceTo2D(destination) <= 3;
            }
            if (!known)
            {
                cluster.links.add(new Poi.Link(row.get("To"), destination, map(destination), Needs.of(row)));
            }
        }
        for (Map.Entry<PoiType, List<Cluster>> entry : byType.entrySet())
        {
            PoiType type = entry.getKey();
            List<Cluster> clusters = entry.getValue();
            for (Cluster cluster : clusters)
            {
                cluster.shown = cluster.name.isEmpty() ? type.displayName + " – " + nameNear(cluster.center()) : cluster.name;
            }
            disambiguate(clusters);
            for (Cluster cluster : clusters)
            {
                WorldPoint at = cluster.center();
                String wikiQuery = type.wikiPage != null && type != PoiType.LEVER ? type.wikiPage : cluster.shown;
                String note = cluster.first.get("Note");
                Poi poi = new Poi(type, cluster.shown, at, map(at), null, sharedQuests(cluster.trips), wikiQuery, null,
                    note.isEmpty() ? null : note);
                for (Poi.Link link : cluster.links)
                {
                    String label = link.label.isEmpty() ? nameNear(link.point) : link.label;
                    poi.addLink(new Poi.Link(label, link.point, link.map, link.needs));
                }
                pois.add(poi);
            }
        }
    }

    private static Needs sharedQuests(List<Needs> trips)
    {
        List<String> shared = null;
        for (Needs trip : trips)
        {
            List<String> quests = new ArrayList<>();
            for (String quest : trip.quests.split(";"))
            {
                if (!quest.trim().isEmpty())
                {
                    quests.add(quest.trim());
                }
            }
            if (shared == null)
            {
                shared = quests;
            }
            else
            {
                shared.retainAll(quests);
            }
        }
        return shared == null || shared.isEmpty() ? Needs.NONE : new Needs("", "", String.join(";", shared), "");
    }

    /** Stops that still share a name get the nearest named place added, so routes don't lead to themselves. */
    private void disambiguate(List<Cluster> clusters)
    {
        Map<String, Integer> counts = new HashMap<>();
        for (Cluster cluster : clusters)
        {
            counts.merge(cluster.shown, 1, Integer::sum);
        }
        for (Cluster cluster : clusters)
        {
            if (counts.get(cluster.shown) > 1)
            {
                cluster.shown = withPlace(cluster.shown, cluster.center());
            }
        }
    }

    private String withPlace(String name, WorldPoint at)
    {
        String place = nearest(places, at, PLACE_RADIUS);
        BaseMap map = map(at);
        if (place != null && !name.contains(place))
        {
            return name + " (near " + place + ")";
        }
        if (map != null && map.id != BaseMap.SURFACE && map.id != BaseMap.FULL && !name.contains(map.name))
        {
            return name + " (" + map.name + ")";
        }
        return name;
    }

    private String nameNear(WorldPoint point)
    {
        String place = nearest(places, point, PLACE_RADIUS);
        if (place != null)
        {
            return "Near " + place;
        }
        BaseMap map = map(point);
        return map != null ? map.name : point.getX() + ", " + point.getY();
    }

    private void passages(List<MapIconLoader.Entry> icons) throws IOException
    {
        // Only the game's own map links: guessed passage pairs too often led to the wrong place.
        List<Cluster> clusters = new ArrayList<>();
        for (MapIconLoader.Entry icon : icons)
        {
            if (icon.target == null)
            {
                continue;
            }
            BaseMap from = map(icon.location);
            BaseMap to = map(icon.target);
            if (from == null || to == null || from == to || to.id == BaseMap.FULL)
            {
                continue;
            }
            Cluster cluster = add(clusters, Integer.toString(to.id), to.name, icon.location, null, 12);
            if (cluster.links.isEmpty())
            {
                cluster.links.add(new Poi.Link(to.name, icon.target, to, Needs.NONE));
            }
        }
        for (Cluster cluster : clusters)
        {
            WorldPoint at = cluster.center();
            Poi.Link link = cluster.links.get(0);
            BaseMap from = map(at);
            PoiType type = from != null && from.id == BaseMap.SURFACE ? PoiType.DUNGEON_ENTRANCE : PoiType.MAP_EXIT;
            Poi poi = new Poi(type, cluster.name, at, from, null, Needs.NONE, cluster.name, link.map, null);
            poi.addLink(link);
            pois.add(poi);
        }
    }

    private void shortcuts() throws IOException
    {
        for (Tsv.Row row : read(SHORTCUTS))
        {
            WorldPoint origin = row.point("Origin");
            if (origin == null)
            {
                continue;
            }
            String name = row.get("Name").isEmpty() ? PoiType.AGILITY_SHORTCUT.displayName : row.get("Name");
            String wiki = row.get("Wiki");
            Poi poi = new Poi(PoiType.AGILITY_SHORTCUT, name, origin, map(origin), null, Needs.of(row),
                wiki.isEmpty() ? PoiType.AGILITY_SHORTCUT.wikiPage : wiki, null, null);
            WorldPoint destination = row.point("Destination");
            if (destination != null)
            {
                poi.addLink(new Poi.Link("Other side", destination, map(destination), Needs.of(row)));
            }
            pois.add(poi);
        }
    }

    private void services(List<MapIconLoader.Entry> icons, String kind, PoiType type)
    {
        List<Cluster> clusters = new ArrayList<>();
        for (MapIconLoader.Entry icon : icons)
        {
            if (icon.kind.name.equalsIgnoreCase(kind))
            {
                add(clusters, "", null, icon.location, null, 6);
            }
        }
        for (Cluster cluster : clusters)
        {
            WorldPoint at = cluster.center();
            BaseMap map = map(at);
            String place = map != null && map.id != BaseMap.SURFACE && map.id != BaseMap.FULL ? map.name
                : MapIconLoader.settlement(places, at);
            if (place == null)
            {
                place = nearest(places, at, PLACE_RADIUS);
            }
            String name = place == null ? type.displayName : type.displayName + " – " + place;
            String wikiQuery = type == PoiType.BANK && place != null ? place + " bank" : type.wikiPage;
            pois.add(new Poi(type, name, at, map, null, Needs.NONE, wikiQuery, null, null));
        }
    }

    /** Passages near a RuneLite dungeon take its name; the others get an icon of their own. */
    private void runeliteDungeons() throws IOException
    {
        for (Tsv.Row row : dungeonRows != null ? dungeonRows : read("runelite_dungeons.tsv"))
        {
            WorldPoint at = row.isComment() ? null : row.point("Location");
            if (at == null)
            {
                continue;
            }
            String name = row.get("Name");
            boolean named = false;
            for (int i = 0; i < pois.size(); i++)
            {
                Poi poi = pois.get(i);
                if ((poi.type == PoiType.DUNGEON_ENTRANCE || poi.type == PoiType.MAP_EXIT)
                    && poi.location.getPlane() == at.getPlane() && chebyshev(poi.location, at) <= 6)
                {
                    named = true;
                    pois.set(i, renamed(poi, name));
                }
            }
            if (!named)
            {
                pois.add(new Poi(PoiType.DUNGEON_ENTRANCE, name, at, map(at), null, Needs.NONE, name, null, null));
            }
        }
    }

    private static Poi renamed(Poi poi, String name)
    {
        Poi copy = new Poi(poi.type, name, poi.location, poi.map, poi.group, poi.needs, name, poi.target, poi.note);
        for (Poi.Link link : poi.links())
        {
            copy.addLink(link);
        }
        return copy;
    }

    private void runeliteTransports() throws IOException
    {
        for (Tsv.Row row : read("runelite_transports.tsv"))
        {
            WorldPoint at = row.isComment() ? null : row.point("Location");
            if (at == null || covered(at, Layer.TRANSPORTS, 8))
            {
                continue;
            }
            String name = row.get("Name");
            PoiType type = transportType(name);
            Poi poi = new Poi(type, name, at, map(at), null, Needs.NONE, name, null, null);
            WorldPoint destination = row.point("Destination");
            if (destination != null)
            {
                poi.addLink(new Poi.Link(name.replaceFirst("^\\S+ to ", ""), destination, map(destination), Needs.NONE));
            }
            pois.add(poi);
        }
    }

    static PoiType transportType(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("cart"))
        {
            return PoiType.MINECART;
        }
        if (lower.contains("ship") || lower.contains("boat") || lower.contains("ferry"))
        {
            return PoiType.BOAT;
        }
        if (lower.contains("canoe"))
        {
            return PoiType.CANOE;
        }
        if (lower.contains("carpet"))
        {
            return PoiType.CARPET;
        }
        if (lower.contains("glider"))
        {
            return PoiType.GNOME_GLIDER;
        }
        if (lower.contains("portal") || lower.contains("teleport"))
        {
            return PoiType.PORTAL;
        }
        return PoiType.TRANSPORT;
    }

    private void runeliteList(String file, PoiType type, String levelSkill) throws IOException
    {
        for (Tsv.Row row : read(file))
        {
            WorldPoint at = row.isComment() ? null : row.point("Location");
            if (at == null)
            {
                continue;
            }
            String name = row.get("Name");
            String note = row.get("Note");
            String shown = note.isEmpty() ? name : name + " – " + note;
            Needs needs = Needs.NONE;
            String level = row.get("Level").trim();
            if (levelSkill != null && LEVEL.matcher(level).matches())
            {
                needs = Needs.skill(Integer.parseInt(level), levelSkill);
            }
            String group = type == PoiType.SALVAGE || type == PoiType.FARMING_PATCH ? name : null;
            String wikiQuery = type == PoiType.FARMING_PATCH ? name.split("/")[0] + " patch"
                : type == PoiType.SALVAGE ? type.wikiPage : name;
            pois.add(new Poi(type, shown, at, map(at), group, needs, wikiQuery, null, null));
        }
    }

    private void nameDuplicateTeleports()
    {
        Map<String, Integer> counts = new HashMap<>();
        for (Poi poi : pois)
        {
            if (poi.type == PoiType.TELEPORT)
            {
                counts.merge(poi.name, 1, Integer::sum);
            }
        }
        for (int i = 0; i < pois.size(); i++)
        {
            Poi poi = pois.get(i);
            if (poi.type == PoiType.TELEPORT && counts.get(poi.name) > 1)
            {
                String place = nearest(places, poi.location, 120);
                if (place != null && !poi.name.contains(place))
                {
                    pois.set(i, new Poi(poi.type, poi.name + " – " + place, poi.location, poi.map, poi.group, poi.needs,
                        poi.wikiQuery, poi.target, poi.note));
                }
            }
        }
    }

    /** Teleports landing on one spot (a spell and its tablet) become one icon. */
    private void stackTeleports()
    {
        List<List<Poi>> stacks = new ArrayList<>();
        List<Poi> rest = new ArrayList<>();
        for (Poi poi : pois)
        {
            if (poi.type != PoiType.TELEPORT)
            {
                rest.add(poi);
                continue;
            }
            List<Poi> joined = null;
            for (List<Poi> stack : stacks)
            {
                Poi first = stack.get(0);
                if (first.location.getPlane() == poi.location.getPlane() && chebyshev(first.location, poi.location) <= STACK_RADIUS)
                {
                    joined = stack;
                    break;
                }
            }
            if (joined == null)
            {
                joined = new ArrayList<>();
                stacks.add(joined);
            }
            joined.add(poi);
        }
        for (List<Poi> stack : stacks)
        {
            Poi first = stack.get(0);
            if (stack.size() == 1)
            {
                rest.add(first);
                continue;
            }
            Poi merged = new Poi(PoiType.TELEPORT, stackName(stack), first.location, first.map, first.group, first.needs,
                first.wikiQuery, first.target, first.note);
            for (Poi member : stack)
            {
                merged.addMember(member);
            }
            rest.add(merged);
        }
        pois.clear();
        pois.addAll(rest);
    }

    private static final int STACK_RADIUS = 4;

    /** {@code "Cemetery Teleport / tablet"}, else {@code "Varrock Teleport (+2)"}. */
    static String stackName(List<Poi> stack)
    {
        String first = stack.get(0).name;
        String base = teleportBase(first);
        Set<String> forms = new LinkedHashSet<>();
        for (Poi other : stack.subList(1, stack.size()))
        {
            String form = teleportForm(other.name);
            if (form == null || !teleportBase(other.name).equals(base))
            {
                return first + " (+" + (stack.size() - 1) + ")";
            }
            forms.add(form);
        }
        return first + " / " + String.join(" / ", forms);
    }

    private static final Pattern DASH_NOTE = Pattern.compile("\\s*–.*$");
    private static final Pattern TELEPORT_FORM = Pattern.compile("(\\s+teleport)?(\\s+(tablet|scroll))?$");
    private static final Pattern NUMBERING = Pattern.compile("^\\d+[.:]\\s*");

    static String teleportBase(String name)
    {
        String lower = DASH_NOTE.matcher(name.toLowerCase(Locale.ROOT)).replaceFirst("").trim();
        return TELEPORT_FORM.matcher(lower).replaceFirst("").trim();
    }

    private static String teleportForm(String name)
    {
        String lower = DASH_NOTE.matcher(name.toLowerCase(Locale.ROOT)).replaceFirst("").trim();
        if (lower.endsWith(" scroll"))
        {
            return "scroll";
        }
        if (lower.endsWith(" tablet"))
        {
            return "tablet";
        }
        return null;
    }

    private boolean covered(WorldPoint at, Layer layer, int radius)
    {
        for (Poi poi : pois)
        {
            if (poi.type.layer == layer && poi.location.getPlane() == at.getPlane() && chebyshev(poi.location, at) <= radius)
            {
                return true;
            }
        }
        return false;
    }

    private void addPlace(WorldPoint at, String name)
    {
        String clean = NUMBERING.matcher(name.trim()).replaceFirst("");
        String lower = clean.toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.length() > 32 || clean.contains("(") || lower.contains("teleport") || lower.contains("poh")
            || lower.contains("player owned") || lower.equals("home") || lower.equals("house") || lower.equals("outside")
            || lower.equals("inside"))
        {
            return;
        }
        places.add(new Place(at, clean, null));
    }

    private static String nearest(List<Place> candidates, WorldPoint point, int radius)
    {
        Place best = null;
        int bestDistance = radius + 1;
        for (Place place : candidates)
        {
            int d = place.point.distanceTo2D(point) + (place.point.getPlane() == point.getPlane() ? 0 : 4);
            if (d < bestDistance)
            {
                best = place;
                bestDistance = d;
            }
        }
        return best == null ? null : best.name;
    }

    private static Cluster add(List<Cluster> clusters, String key, String name, WorldPoint point, Tsv.Row row, int radius)
    {
        for (Cluster cluster : clusters)
        {
            if (cluster.key.equals(key) && cluster.near(point, radius))
            {
                cluster.points.add(point);
                return cluster;
            }
        }
        Cluster cluster = new Cluster(key, name);
        cluster.points.add(point);
        cluster.first = row;
        clusters.add(cluster);
        return cluster;
    }

    static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private BaseMap map(WorldPoint point)
    {
        return maps.find(point.getX(), point.getY());
    }
}
