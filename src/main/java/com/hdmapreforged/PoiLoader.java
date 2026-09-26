package com.hdmapreforged;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/** Builds map icons from the bundled travel tables, game map icons and RuneLite lists; same-place rows merge. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
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

    @RequiredArgsConstructor
    static final class Place
    {
        final WorldPoint point;
        final String name;
        /** "region", "island" or "settlement" for map labels; null for places only used for naming. */
        final String kind;
    }

    @RequiredArgsConstructor
    private static final class Cluster
    {
        final String key;
        final String name;
        final List<WorldPoint> points = new ArrayList<>();
        final List<Poi.Link> links = new ArrayList<>();
        final List<Needs> trips = new ArrayList<>();
        Tsv.Row first;
        String shown;

        boolean near(WorldPoint p, int radius)
        {
            return points.stream().anyMatch(q -> PoiLoader.near(q, p, radius));
        }

        /** Nearest the middle, so the icon sits on a real tile. */
        WorldPoint center()
        {
            double x = points.stream().mapToInt(WorldPoint::getX).average().orElse(0);
            double y = points.stream().mapToInt(WorldPoint::getY).average().orElse(0);
            return Collections.min(points, Comparator.comparingDouble(
                p -> (p.getX() - x) * (p.getX() - x) + (p.getY() - y) * (p.getY() - y)));
        }
    }

    /** Named as their {@link PoiType}, in lower case. */
    private static final List<String> NETWORK_TYPES = List.of("fairy_ring", "spirit_tree", "gnome_glider", "balloon",
        "quetzal", "mushtree", "obelisk");
    private static final List<String> ROUTE_TYPES = List.of("boat", "charter", "canoe", "carpet", "minecart", "portal",
        "lever");

    private static final int PLACE_RADIUS = 60;

    private final BaseMaps maps;
    private final Source source;
    private final List<Poi> pois = new ArrayList<>();
    private final List<Place> places = new ArrayList<>();
    private final List<Tsv.Row> dungeonRows;
    private static final Pattern LEVEL = Pattern.compile("\\d{1,3}");

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
        return new PoiLoader(maps, source, dungeons).build(labels, icons);
    }

    private List<Poi> build(List<Place> labels, List<MapIconLoader.Entry> icons) throws IOException
    {
        places.addAll(labels);
        teleports();
        services(icons, "Bank", PoiType.BANK);
        services(icons, "Altar", PoiType.ALTAR);
        services(icons, "Anvil", PoiType.ANVIL);
        networks();
        routes();
        passages(icons);
        runeliteDungeons();
        runeliteTransports();
        linkRowboats(pois);
        runeliteList("runelite_moorings.tsv", PoiType.MOORING, "Sailing");
        runeliteList("runelite_salvage.tsv", PoiType.SALVAGE, "Sailing");
        runeliteList("runelite_runecraft_altars.tsv", PoiType.RUNECRAFT_ALTAR, "Runecraft");
        runeliteList("runelite_agility_courses.tsv", PoiType.AGILITY_COURSE, null);
        runeliteList("runelite_farming_patches.tsv", PoiType.FARMING_PATCH, null);
        runeliteList("runelite_minigames.tsv", PoiType.MINIGAME, null);
        shortcuts();
        nameDuplicateTeleports();
        stackTeleports();
        return pois;
    }

    private static final int ROWBOAT_REACH = 500;
    private static final int ROWBOAT_PAIR = 60;

    /**
     * RuneLite names multi-stop rowboats by the other stops ("Rowboat to Molch/Shayzien"), so a boat's own stop is the
     * network name it does not list; it then links to the boats of the stops it names.
     */
    static void linkRowboats(List<Poi> pois)
    {
        // Poi keeps identity equality.
        Map<Poi, List<String>> names = new LinkedHashMap<>();
        for (Poi poi : pois)
        {
            if (poi.type == PoiType.BOAT && poi.name.startsWith("Rowboat to "))
            {
                names.put(poi, split(poi.name.substring("Rowboat to ".length()), "/"));
            }
        }
        List<List<Poi>> networks = new ArrayList<>();
        for (Poi boat : names.keySet())
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
                networks.add(new ArrayList<>(List.of(boat)));
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
            if (name.contains(": "))
            {
                addPlace(destination, name.substring(name.indexOf(": ") + 2));
            }
            pois.add(new Poi(PoiType.TELEPORT, name, destination, map(destination), row.or("Group", null), Needs.of(row),
                row.or("Wiki", name), null, null));
        }
    }

    private void networks() throws IOException
    {
        Map<String, List<Tsv.Row>> byNetwork = new LinkedHashMap<>();
        for (Tsv.Row row : read(NETWORKS))
        {
            if (NETWORK_TYPES.contains(row.get("Network")) && row.point("Arrival") != null)
            {
                byNetwork.computeIfAbsent(row.get("Network"), k -> new ArrayList<>()).add(row);
            }
        }
        byNetwork.forEach((key, network) -> {
            PoiType type = PoiType.valueOf(key.toUpperCase(Locale.ROOT));
            for (Tsv.Row stop : network)
            {
                WorldPoint location = stop.point("Location");
                WorldPoint at = location != null ? location : stop.point("Arrival");
                String name = stop.or("Stop", type.displayName);
                addPlace(at, name.substring(name.lastIndexOf(':') + 1).replaceFirst("^.*– ", ""));
                Poi poi = new Poi(type, name, at, map(at), type.name(), Needs.of(stop), type.wikiPage, null,
                    stop.or("Note", location == null ? "You can only arrive here" : null));
                if (location != null)
                {
                    for (Tsv.Row other : network)
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
        });
    }

    private void routes() throws IOException
    {
        Map<PoiType, List<Cluster>> byType = new LinkedHashMap<>();
        for (Tsv.Row row : read(ROUTES))
        {
            String kind = row.get("Type");
            WorldPoint origin = row.point("Origin");
            WorldPoint destination = row.point("Destination");
            if (!ROUTE_TYPES.contains(kind) || origin == null || destination == null)
            {
                continue;
            }
            List<Cluster> clusters = byType.computeIfAbsent(PoiType.valueOf(kind.toUpperCase(Locale.ROOT)),
                k -> new ArrayList<>());
            Cluster cluster = add(clusters, row.get("From"), row.get("From"), origin, row, 8);
            cluster.trips.add(Needs.of(row));
            if (cluster.links.stream().noneMatch(link -> link.point.getPlane() == destination.getPlane()
                && link.point.distanceTo2D(destination) <= 3))
            {
                cluster.links.add(new Poi.Link(row.get("To"), destination, map(destination), Needs.of(row)));
            }
        }
        byType.forEach((type, clusters) -> {
            for (Cluster cluster : clusters)
            {
                cluster.shown = cluster.name.isEmpty() ? type.displayName + " – " + nameNear(cluster.center()) : cluster.name;
            }
            disambiguate(clusters);
            for (Cluster cluster : clusters)
            {
                WorldPoint at = cluster.center();
                String wikiQuery = type.wikiPage != null && type != PoiType.LEVER ? type.wikiPage : cluster.shown;
                Poi poi = new Poi(type, cluster.shown, at, map(at), null, sharedQuests(cluster.trips), wikiQuery, null,
                    cluster.first.or("Note", null));
                for (Poi.Link link : cluster.links)
                {
                    String label = link.label.isEmpty() ? nameNear(link.point) : link.label;
                    poi.addLink(new Poi.Link(label, link.point, link.map, link.needs));
                }
                pois.add(poi);
            }
        });
    }

    private static Needs sharedQuests(List<Needs> trips)
    {
        List<String> shared = null;
        for (Needs trip : trips)
        {
            List<String> quests = split(trip.quests, ";");
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

    /** Trimmed, without empty parts. */
    private static List<String> split(String text, String separator)
    {
        List<String> parts = new ArrayList<>();
        for (String part : text.split(separator))
        {
            if (!part.trim().isEmpty())
            {
                parts.add(part.trim());
            }
        }
        return parts;
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
            Poi poi = new Poi(PoiType.AGILITY_SHORTCUT, row.or("Name", PoiType.AGILITY_SHORTCUT.displayName), origin,
                map(origin), null, Needs.of(row), row.or("Wiki", PoiType.AGILITY_SHORTCUT.wikiPage), null, null);
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
            pois.add(poi(type, name, at, wikiQuery));
        }
    }

    /** Passages near a RuneLite dungeon take its name; the others get an icon of their own. */
    private void runeliteDungeons() throws IOException
    {
        for (Tsv.Row row : dungeonRows)
        {
            WorldPoint at = row.point("Location");
            if (at == null)
            {
                continue;
            }
            String name = row.get("Name");
            boolean named = false;
            for (int i = 0; i < pois.size(); i++)
            {
                Poi poi = pois.get(i);
                if ((poi.type == PoiType.DUNGEON_ENTRANCE || poi.type == PoiType.MAP_EXIT) && near(poi.location, at, 6))
                {
                    named = true;
                    pois.set(i, renamed(poi, name, name));
                }
            }
            if (!named)
            {
                pois.add(poi(PoiType.DUNGEON_ENTRANCE, name, at, name));
            }
        }
    }

    static Poi renamed(Poi poi, String name, String wikiQuery)
    {
        Poi copy = new Poi(poi.type, name, poi.location, poi.map, poi.group, poi.needs, wikiQuery, poi.target, poi.note);
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
            WorldPoint at = row.point("Location");
            if (at == null || covered(at, Layer.TRANSPORTS, 8))
            {
                continue;
            }
            String name = row.get("Name");
            Poi poi = poi(transportType(name), name, at, name);
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
        for (int i = 0; i < TRANSPORT_WORDS.length; i++)
        {
            for (String word : TRANSPORT_WORDS[i].split(" "))
            {
                if (lower.contains(word))
                {
                    return TRANSPORT_TYPES[i];
                }
            }
        }
        return PoiType.TRANSPORT;
    }

    private static final String[] TRANSPORT_WORDS = {"cart", "ship boat ferry", "canoe", "carpet", "glider", "portal teleport"};
    private static final PoiType[] TRANSPORT_TYPES = {PoiType.MINECART, PoiType.BOAT, PoiType.CANOE, PoiType.CARPET,
        PoiType.GNOME_GLIDER, PoiType.PORTAL};

    private void runeliteList(String file, PoiType type, String levelSkill) throws IOException
    {
        for (Tsv.Row row : read(file))
        {
            WorldPoint at = row.point("Location");
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
                    pois.set(i, renamed(poi, poi.name + " – " + place, poi.wikiQuery));
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
                if (near(stack.get(0).location, poi.location, STACK_RADIUS))
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
        return lower.endsWith(" scroll") ? "scroll" : lower.endsWith(" tablet") ? "tablet" : null;
    }

    private boolean covered(WorldPoint at, Layer layer, int radius)
    {
        return pois.stream().anyMatch(poi -> poi.type.layer == layer && near(poi.location, at, radius));
    }

    private void addPlace(WorldPoint at, String name)
    {
        String clean = NUMBERING.matcher(name.trim()).replaceFirst("");
        String lower = clean.toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.length() > 32 || clean.contains("(") || lower.contains("teleport") || lower.contains("poh")
            || lower.contains("player owned") || Set.of("home", "house", "outside", "inside").contains(lower))
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

    static boolean near(WorldPoint a, WorldPoint b, int radius)
    {
        return a.getPlane() == b.getPlane() && chebyshev(a, b) <= radius;
    }

    private Poi poi(PoiType type, String name, WorldPoint at, String wikiQuery)
    {
        return new Poi(type, name, at, map(at), null, Needs.NONE, wikiQuery, null, null);
    }

    private BaseMap map(WorldPoint point)
    {
        return maps.find(point.getX(), point.getY());
    }
}
