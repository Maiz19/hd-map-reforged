package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import java.util.stream.*;
import com.hdmapreforged.route.*;
import java.util.*;
import java.util.regex.*;
import lombok.*;
import net.runelite.api.coords.*;

/** The jumps a route may take for this player. Thread-safe: requests come from Swing and the extras thread. */
@RequiredArgsConstructor
final class RouteSource
{
    private static final int SNAP = 5;
    private static final int DOCK_REACH = 18;
    private static final int PORT_RADIUS = 24;
    private static final Pattern SAILING = Pattern.compile("(\\d+)\\s+Sailing", Pattern.CASE_INSENSITIVE);

    static final class Options
    {
        final boolean teleports;
        final boolean sailing;
        final HdMapReforgedConfig.BoatFocus focus;
        final int[] shipwrights;

        Options(boolean teleports, boolean sailing)
        {
            this(teleports, sailing, HdMapReforgedConfig.BoatFocus.NONE, new int[0]);
        }

        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights)
        {
            this(teleports, sailing, focus, shipwrights, false, false);
        }

        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems)
        {
            this(teleports, sailing, focus, shipwrights, ignoreLevels, ignoreItems, Collections.emptySet());
        }

        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems, Set<String> avoid)
        {
            this(teleports, sailing, focus, shipwrights, ignoreLevels, ignoreItems, avoid, Saving.NONE);
        }

        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems, Set<String> avoid, Saving saving)
        {
            this.saving = saving;
            this.avoid = avoid;
            this.teleports = teleports;
            this.sailing = sailing;
            this.focus = focus;
            this.shipwrights = shipwrights.clone();
            this.ignoreLevels = ignoreLevels;
            this.ignoreItems = ignoreItems;
        }

        final boolean ignoreLevels;
        final boolean ignoreItems;
        final Set<String> avoid;
        final Saving saving;
        /** The way anyone could go, shown when the player's way does not arrive (not the "ignore" settings). */
        boolean fallback;

        Options fallback()
        {
            fallback = true;
            return this;
        }
    }

    /** Tiles a kind of trip must save to be taken (a teleport for a few tiles wastes runes); search weight only. */
    @RequiredArgsConstructor
    static final class Saving
    {
        static final Saving NONE = new Saving(0, 0, 0, 0, 0, 0);

        final int teleport;
        final int shortcut;
        final int transport;
        final int canoe;
        final int carpet;
        final int ship;

        int tiles(PoiType type)
        {
            switch (type)
            {
                case AGILITY_SHORTCUT:
                    return shortcut;
                case CANOE:
                    return canoe;
                case CARPET:
                    return carpet;
                case BOAT:
                case CHARTER:
                    return ship;
                case PORTAL:
                case LEVER:
                    return 0;
                default:
                    return transport;
            }
        }
    }

    private static final int PER_TILE = 1;

    private static final int SHIPWRIGHT_REACH = 40;

    private final CollisionMap map;
    private final SeaMap sea;

    RouteRequest request(int start, int target, List<Poi> pois, Unlocks unlocks, PlayerState state, Options options,
        int nodeLimit)
    {
        Call call = new Call(options, unlocks, state);
        if (options.ignoreLevels)
        {
            unlocks = null;
            state = state.with(state.items, 99, true);
        }
        if (options.ignoreItems)
        {
            state = state.with(ItemSnapshot.EVERYTHING, state.sailingLevel, state.sailing);
        }
        List<Edge> startEdges = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, Poi> teleportsByName = new HashMap<>();
        for (Poi poi : Poi.flatten(pois))
        {
            if (poi.type == PoiType.TELEPORT)
            {
                for (Poi member : poi.members())
                {
                    teleportsByName.putIfAbsent(member.name.toLowerCase(Locale.ROOT), member);
                    if (options.teleports)
                    {
                        teleport(call, member, unlocks, state, startEdges);
                    }
                }
            }
            else if (poi.type == PoiType.MOORING)
            {
                if (options.sailing)
                {
                    mooring(call, poi, unlocks, state, options, start >= 0 && Tiles.isSea(start), edges);
                }
            }
            else if (isTransport(poi.type))
            {
                transport(call, poi, unlocks, state, edges);
            }
        }
        gates(call, unlocks, edges);
        shortcuts(call, unlocks, edges);
        if (options.sailing && options.teleports)
        {
            teleportToBoat(state, options, startEdges);
        }
        if (state.inHouse || options.teleports)
        {
            house(call, pois, unlocks, state, teleportsByName, startEdges, edges);
        }
        if (start < 0 && startEdges.isEmpty())
        {
            return null;
        }
        int level = options.sailing && state.sailing ? state.sailingLevel : -1;
        if (start >= 0 && Tiles.isSea(start) && level < 0)
        {
            // On a boat without the Sailing data: still let the route sail home.
            level = Math.max(1, state.sailingLevel);
        }
        return new RouteRequest(start, target, startEdges, edges, level, state.running, nodeLimit);
    }

    private static final class Call
    {
        final Set<String> avoiding;
        final Saving saving;
        final Unlocks realUnlocks;
        final Unlocks missingLevels;
        final ItemSnapshot missingItems;
        final int realSailing;
        final boolean fallback;

        Call(Options options, Unlocks unlocks, PlayerState state)
        {
            avoiding = options.avoid;
            saving = options.saving;
            realUnlocks = unlocks;
            missingLevels = options.ignoreLevels ? unlocks : null;
            missingItems = options.ignoreItems ? state.items : null;
            realSailing = !options.ignoreLevels ? 99 : state.sailing ? state.sailingLevel : 0;
            fallback = options.fallback;
        }
    }

    @RequiredArgsConstructor
    private static final class Gate
    {
        final WorldPoint from;
        final WorldPoint to;
        final String name;
        final Needs needs;
    }

    /** Read once, so the unlocks' per-requirement cache hits. */
    private static final List<Gate> GATES = readGates();

    private static List<Gate> readGates()
    {
        InputStream in = RouteSource.class.getResourceAsStream("/com/hdmapreforged/route/gates.tsv");
        if (in == null)
        {
            return Collections.emptyList();
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
        {
            List<Gate> gates = new ArrayList<>();
            for (Tsv.Row row : Tsv.parse(reader))
            {
                WorldPoint from = row.point("Origin");
                WorldPoint to = row.point("Destination");
                if (from != null && to != null)
                {
                    gates.add(new Gate(from, to, row.get("Name"), Needs.of(row)));
                }
            }
            return Collections.unmodifiableList(gates);
        }
        catch (IOException e)
        {
            return Collections.emptyList();
        }
    }

    private void gates(Call call, Unlocks unlocks, List<Edge> into)
    {
        for (Gate gate : GATES)
        {
            if (!usable(unlocks, gate.needs))
            {
                continue;
            }
            int a = snap(gate.from, gate.to);
            int b = snap(gate.to, gate.from);
            if (a >= 0 && b >= 0 && a != b)
            {
                into.add(new Edge(a, b, Edge.Kind.ENTRANCE, "Open " + gate.name, needsText(call, gate.needs, false), 4));
            }
        }
    }

    private void shortcuts(Call call, Unlocks unlocks, List<Edge> into)
    {
        List<ShortcutPassage> passages =
            Pathfinder.shortcutPassages(map);
        List<Needs> needs = shortcutNeeds(passages);
        for (int i = 0; i < passages.size(); i++)
        {
            if (usable(unlocks, needs.get(i)))
            {
                Edge e = passages.get(i).edge;
                into.add(new Edge(e.from, e.to, e.kind, e.name, needsText(call, needs.get(i), false), e.cost));
            }
        }
    }

    private static final class ShortcutNeeds
    {
        final List<ShortcutPassage> passages;
        final List<Needs> needs;

        ShortcutNeeds(List<ShortcutPassage> passages)
        {
            this.passages = passages;
            List<Needs> made = new ArrayList<>(passages.size());
            for (ShortcutPassage passage : passages)
            {
                made.add(new Needs(passage.skills, passage.items, passage.quests, passage.varbits));
            }
            this.needs = Collections.unmodifiableList(made);
        }
    }

    private volatile ShortcutNeeds shortcutNeeds;

    private List<Needs> shortcutNeeds(List<ShortcutPassage> passages)
    {
        ShortcutNeeds known = shortcutNeeds;
        if (known == null || known.passages != passages)
        {
            known = new ShortcutNeeds(passages);
            shortcutNeeds = known;
        }
        return known.needs;
    }

    // Not obelisks (they lead to a random other obelisk) nor entrances (map links, in Pathfinder, not our icons).
    private static final Set<PoiType> TRANSPORTS = EnumSet.of(PoiType.FAIRY_RING, PoiType.SPIRIT_TREE,
        PoiType.GNOME_GLIDER, PoiType.BALLOON, PoiType.QUETZAL, PoiType.MUSHTREE, PoiType.BOAT, PoiType.CHARTER,
        PoiType.CANOE, PoiType.CARPET, PoiType.MINECART, PoiType.PORTAL, PoiType.LEVER, PoiType.TRANSPORT,
        PoiType.AGILITY_SHORTCUT);

    static boolean isTransport(PoiType type)
    {
        return TRANSPORTS.contains(type);
    }

    private boolean usable(Unlocks unlocks, Needs needs)
    {
        return unlocks == null || unlocks.usable(needs);
    }

    private int snap(WorldPoint point)
    {
        return map.nearestWalkable(point.getX(), point.getY(), point.getPlane(), SNAP);
    }

    private int snap(WorldPoint point, WorldPoint other)
    {
        return map.nearestWalkable(point.getX(), point.getY(), point.getPlane(), SNAP, other.getX(), other.getY());
    }

    private void teleport(Call call, Poi member, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        if (HouseTracker.isTemplate(member.location.getX(), member.location.getY())
            || !usable(unlocks, member.needs) || !state.items.has(member.needs.items, false))
        {
            return;
        }
        int lacking = lacking(call, member.needs);
        int to = snap(member.location);
        // Outside one's own house only (a friend's house tells nothing of it).
        if (to < 0 || member.name.contains("(Outside)") && (state.houseExit < 0 || state.inHouse && !state.ownHouse
            || Tiles.distance(to, state.houseExit) > PORT_RADIUS))
        {
            return;
        }
        String name = member.name.toLowerCase(Locale.ROOT);
        String category = member.group != null && !member.group.isEmpty() ? member.group : "Teleports";
        if (call.avoiding.contains(name) || call.avoiding.contains(typeKey(category)))
        {
            return;
        }
        into.add(new Edge(Edge.ANYWHERE, to, Edge.Kind.TELEPORT, member.name, needsText(call, member.needs, false),
            teleportTime(name.contains("home teleport"), member.group), category)
            .weighed(call.saving.teleport * PER_TILE + lacking));
    }

    private void transport(Call call, Poi poi, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        if (!usable(unlocks, poi.needs) || !state.items.has(poi.needs.items, true) || snap(poi.location) < 0)
        {
            return;
        }
        Edge.Kind kind = kind(poi.type);
        for (Poi.Link link : poi.links())
        {
            if (!usable(unlocks, link.needs) || !state.items.has(link.needs.items, true))
            {
                continue;
            }
            // Each end on its own side: stepping stones stand on lava or water, with pockets between them.
            boolean sameFloor = link.point.getPlane() == poi.location.getPlane();
            int from = sameFloor ? snap(poi.location, link.point) : snap(poi.location);
            int to = sameFloor ? snap(link.point, poi.location) : snap(link.point);
            if (to < 0 || to == from)
            {
                continue;
            }
            String name = describe(poi, link);
            if (call.avoiding.contains(name.toLowerCase(Locale.ROOT))
                || call.avoiding.contains(typeKey(poi.type.displayName)))
            {
                continue;
            }
            Fees.Fee fee = Fees.at(poi.location.getX(), poi.location.getY(), poi.location.getPlane());
            String needs = needsText(call, link.needs, true);
            if (fee != null)
            {
                // The fee apart: it cannot be checked, so it is not something the player lacks.
                needs = needs == null ? "Needs: " + fee.text : needs + ". Also needs: " + fee.text;
            }
            into.add(new Edge(from, to, kind, name, needs, cost(poi.type) + (fee == null ? 0 : fee.cost),
                poi.type.displayName).weighed(call.saving.tiles(poi.type) * PER_TILE));
        }
    }

    static final String TYPE = "type:";

    static String typeKey(String category)
    {
        return TYPE + category.toLowerCase(Locale.ROOT);
    }

    private static String describe(Poi poi, Poi.Link link)
    {
        String label = link.label == null || link.label.isEmpty() ? null : link.label;
        switch (poi.type)
        {
            case AGILITY_SHORTCUT:
            case LEVER:
            case PORTAL:
                return poi.name + (label != null && !poi.name.contains(label) ? " → " + label : "");
            default:
                return poi.type.displayName + ": " + code(poi.type, label != null ? label : poi.name);
        }
    }

    /** A fairy ring by the code one dials ("BIP"), not its whole title. */
    private static String code(PoiType type, String name)
    {
        return type == PoiType.FAIRY_RING ? name.split(" – ")[0] : name;
    }

    static Edge.Kind kind(PoiType type)
    {
        switch (type)
        {
            case BOAT:
            case CHARTER:
                return Edge.Kind.SHIP;
            case AGILITY_SHORTCUT:
            case PORTAL:
            case LEVER:
                return Edge.Kind.ENTRANCE;
            default:
                return Edge.Kind.TRANSPORT;
        }
    }

    /** Half ticks a teleport takes (wiki cast times); minigame teleports are slow with a cooldown, a last resort. */
    static int teleportTime(boolean home, String group)
    {
        return home ? 44 : "Minigame teleports".equals(group) ? 60 : 10;
    }

    static int cost(PoiType type)
    {
        switch (type)
        {
            case FAIRY_RING:
            case SPIRIT_TREE:
            case MUSHTREE:
                return 10;
            case QUETZAL:
                return 12;
            case LEVER:
            case AGILITY_SHORTCUT:
                return 8;
            case OBELISK:
                return 16;
            case PORTAL:
                return 6;
            case CHARTER:
                return 30;
            case BOAT:
                return 40;
            case CANOE:
                return 60;
            case CARPET:
                return 100;
            default:
                return 20;
        }
    }

    /**
     * A dock: leaving the boat needs its Sailing level; boarding needs the boat there, or any dock when no boat's port
     * is known. {@code onBoat}: the route starts at sea on the boat, so it lies at no dock.
     */
    private void mooring(Call call, Poi poi, Unlocks unlocks, PlayerState state, Options options, boolean onBoat, List<Edge> into)
    {
        if (!state.sailing || state.sailingLevel < level(poi.needs) || !usable(unlocks, poi.needs))
        {
            return;
        }
        int x = poi.location.getX();
        int y = poi.location.getY();
        int land = map.nearestWalkable(x, y, 0, 8);
        int water = sea.nearestBlock(x, y, DOCK_REACH, state.sailingLevel);
        if (land < 0 || water < 0)
        {
            return;
        }
        into.add(new Edge(water, land, Edge.Kind.DISEMBARK, "Dock at " + poi.name + dockLevel(poi), null, 10));
        int needed = Math.max(1, level(poi.needs));
        String lacking = call.realSailing < needed
            ? "You lack: " + (call.realSailing <= 0 ? "Sailing" : needed + " Sailing")
            : null;
        String name = "Board your boat at " + poi.name;
        String hint = null;
        int cost = 10;
        if (Arrays.stream(state.boats).anyMatch(boat -> Tiles.distance(boat, land) <= PORT_RADIUS))
        {
            name += dockLevel(poi);
        }
        else if (options.focus != HdMapReforgedConfig.BoatFocus.NONE)
        {
            name = "Summon your boat and board it at " + poi.name;
            hint = "Summon Boat (needs the teleport focus on your boat)";
            cost = 16;
        }
        else if (Arrays.stream(options.shipwrights).anyMatch(s -> Tiles.distance(s, land) <= SHIPWRIGHT_REACH))
        {
            hint = "Ask the shipwright here to bring your boat (fee)";
            cost = 60;
        }
        else if (state.boats.length == 0 && !onBoat)
        {
            // Where the boat lies is not known yet (not logged in): any dock, with a hint.
            hint = "If your boat is moored here (a shipwright can bring it)";
        }
        else
        {
            return;
        }
        into.add(new Edge(land, water, Edge.Kind.BOARD, name, lacking != null || hint == null ? lacking : hint, cost));
    }

    private static String dockLevel(Poi poi)
    {
        int level = level(poi.needs);
        return level > 1 ? " (" + level + " Sailing)" : "";
    }

    private void teleportToBoat(PlayerState state, Options options, List<Edge> into)
    {
        if (options.focus != HdMapReforgedConfig.BoatFocus.GREATER || !state.sailing)
        {
            return;
        }
        for (int boat : state.boats)
        {
            int land = map.nearestWalkable(Tiles.x(boat), Tiles.y(boat), 0, 12);
            if (land >= 0)
            {
                into.add(new Edge(Edge.ANYWHERE, land, Edge.Kind.TELEPORT, "Teleport to Boat",
                    "Spell or Sailing cape (greater teleport focus)", 10));
            }
        }
    }

    static int level(Needs needs)
    {
        Matcher m = SAILING.matcher(needs.skills);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /** The fallback takes a teleport whose item the player lacks only as a last resort (else all of it was red). */
    private static int lacking(Call call, Needs needs)
    {
        return call.fallback && call.missingItems != null && !needs.items.isEmpty()
            && !call.missingItems.has(needs.items, false) ? LACKING_ITEM : 0;
    }

    private static boolean stairs(String thing)
    {
        return HouseTracker.feature(thing) == null && !thing.equals("Portal") && !thing.startsWith(NEXUS);
    }

    /** Into one's own house, best first: the capes cost nothing. */
    private static final String[] INTO_HOUSE = {"Construction cape: Tele to POH", "Max cape: Home", "Teleport to House",
        "Teleport to house tablet"};
    private static final Needs[] INTO_HOUSE_NEEDS = {new Needs("", "9789||9790", "", ""),
        new Needs("", "13280||13342", "", ""), new Needs("40 Magic", "563=1&&556=1&&557=1", "", "4070=0"),
        new Needs("", "8013", "", "")};

    /**
     * The house's ways out. With its plan seen (own house) they stand where they are on it, routes walk to them and
     * teleport in to its arrival; else, in the house, the route starts with them, and from outside a teleport in or
     * its portal leads to them at once.
     */
    private void house(Call call, List<Poi> pois, Unlocks unlocks, PlayerState state, Map<String, Poi> teleports,
        List<Edge> starts, List<Edge> edges)
    {
        HousePlan plan = state.inHouse && !state.ownHouse ? null : map.house();
        List<Edge> into = new ArrayList<>();
        WorldPoint portal = state.houseExit < 0 ? null
            : new WorldPoint(Tiles.x(state.houseExit), Tiles.y(state.houseExit), 0);
        if (state.inHouse && portal != null && (plan == null || !plan.things.containsValue("Portal")))
        {
            addHouse(into, portal, "Leave the house by its portal", 6);
        }
        Set<String> placed = new HashSet<>();
        if (plan != null)
        {
            plan.things.forEach((node, name) -> {
                int x = Tiles.x(node);
                int y = Tiles.y(node);
                int z = Tiles.z(node);
                int at = plan.approach(node);
                List<Edge> out = new ArrayList<>();
                List<String> features = name.startsWith(NEXUS) ? new ArrayList<>()
                    : new ArrayList<>(Collections.singletonList(HouseTracker.feature(name)));
                for (String place : name.startsWith(NEXUS + ": ") ? name.substring(NEXUS.length() + 2).split(", ")
                    : new String[0])
                {
                    features.add("nexus:" + place);
                }
                // As set for the account (the panel may have taken one off); one not reachable is used from the arrival.
                for (String feature : features)
                {
                    if (feature != null && at >= 0 && state.houseFeatures.contains(feature) && placed.add(feature))
                    {
                        ways(feature, pois, unlocks, teleports, out);
                    }
                }
                if (name.equals("Portal") && portal != null)
                {
                    addHouse(out, portal, "Leave the house by its portal", 6);
                }
                for (int dz = -1; stairs(name) && dz <= 1; dz += 2)
                {
                    int other = z + dz;
                    // Only to a floor with stairs where these stand: they go up or down, seldom both.
                    if (plan.things.entrySet().stream().anyMatch(t -> Tiles.z(t.getKey()) == other
                        && Tiles.distance(t.getKey(), node) <= 3 && stairs(t.getValue())))
                    {
                        out.add(new Edge(Edge.ANYWHERE, map.nearestWalkable(x, y, other, 3), Edge.Kind.STAIRS, name, null, 5));
                    }
                }
                for (Edge e : out)
                {
                    if (at >= 0 && e.to >= 0)
                    {
                        edges.add(new Edge(at, e.to, e.kind, e.name, e.detail, e.cost));
                    }
                }
            });
        }
        // Not seen on the plan (typed in by hand), or no plan: usable at once in the house.
        for (String feature : state.inHouse && !state.ownHouse ? Set.<String>of() : state.houseFeatures)
        {
            if (!placed.contains(feature))
            {
                ways(feature, pois, unlocks, teleports, into);
            }
        }
        if (plan != null)
        {
            into.forEach(e -> edges.add(new Edge(plan.arrival, e.to, e.kind, e.name, e.detail, e.cost)));
            into.clear();
        }
        if (state.inHouse)
        {
            starts.addAll(into);
            return;
        }
        int best = -1;
        int lacking = 0;
        for (int i = 0; i < INTO_HOUSE.length; i++)
        {
            Needs needs = INTO_HOUSE_NEEDS[i];
            int lack = lacking(call, needs);
            // Set to land outside, the capes do so; the spell and the tablet keep an option to land inside.
            if ((state.landsInside || i >= 2) && usable(unlocks, needs) && state.items.has(needs.items, false)
                && (best < 0 || lack < lacking)
                && !call.avoiding.contains(INTO_HOUSE[i].toLowerCase(Locale.ROOT) + (state.landsInside ? "" : " (inside)"))
                && !call.avoiding.contains(typeKey("Teleports")))
            {
                best = i;
                lacking = lack;
            }
        }
        int door = portal == null ? -1 : snap(portal);
        String teleport = best < 0 ? null : INTO_HOUSE[best] + (state.landsInside ? "" : " (Inside)");
        if (plan != null)
        {
            if (best >= 0)
            {
                starts.add(new Edge(Edge.ANYWHERE, plan.arrival, Edge.Kind.TELEPORT, teleport,
                    needsText(call, INTO_HOUSE_NEEDS[best], false), 10, "Teleports")
                    .weighed(call.saving.teleport * PER_TILE + lacking));
            }
            if (door >= 0)
            {
                edges.add(new Edge(door, plan.arrival, Edge.Kind.ENTRANCE, "Enter your house", null, 6));
            }
            return;
        }
        for (Edge e : into)
        {
            if (best >= 0)
            {
                starts.add(new Edge(Edge.ANYWHERE, e.to, Edge.Kind.HOUSE, teleport + " → " + e.name,
                    needsText(call, INTO_HOUSE_NEEDS[best], false), 10 + e.cost)
                    .weighed(call.saving.teleport * PER_TILE + lacking));
            }
            if (door >= 0)
            {
                edges.add(new Edge(door, e.to, Edge.Kind.HOUSE, "Enter your house → " + e.name, null, 6 + e.cost));
            }
        }
    }

    static final String NEXUS = "Portal Nexus";

    /** Where a feature of the house ("portal:Varrock", "box:ornate", "glory", "fairy ring") leads, from anywhere. */
    private void ways(String feature, List<Poi> pois, Unlocks unlocks, Map<String, Poi> teleports, List<Edge> into)
    {
        if (feature.startsWith("portal:") || feature.startsWith("nexus:"))
        {
            Poi teleport = portalTeleport(feature.substring(feature.indexOf(':') + 1), teleports);
            if (teleport != null)
            {
                addHouse(into, teleport.location, (feature.startsWith("nexus:") ? "Portal nexus: " : "House portal: ")
                    + teleport.name, 6);
            }
        }
        else if (feature.startsWith("box:") || feature.equals("glory"))
        {
            boolean glory = feature.equals("glory");
            List<String> groups = glory ? List.of("Amulet of glory") : new ArrayList<>(List.of("Ring of dueling",
                "Games necklace", "Combat bracelet", "Skills necklace", "Ring of wealth", "Amulet of glory"))
                .subList(0, feature.equals("box:basic") ? 2 : feature.equals("box:ornate") ? 6 : 4);
            for (Poi teleport : teleports.values())
            {
                if (teleport.group != null && groups.contains(teleport.group) && usable(unlocks, teleport.needs))
                {
                    addHouse(into, teleport.location, (glory ? "Mounted glory: " : "Jewellery box: ")
                        + teleport.name, 8);
                }
            }
        }
        if (feature.contains("fairy ring") || feature.contains("spirit tree"))
        {
            for (Poi poi : pois)
            {
                boolean ring = poi.type == PoiType.FAIRY_RING && feature.contains("fairy ring");
                boolean tree = poi.type == PoiType.SPIRIT_TREE && feature.contains("spirit tree");
                if ((ring || tree) && usable(unlocks, poi.needs))
                {
                    addHouse(into, poi.location, "House " + poi.type.displayName.toLowerCase(Locale.ROOT) + ": "
                        + code(poi.type, poi.name), 10);
                }
            }
        }
    }

    /** Portal and nexus places the spellbooks name otherwise. */
    private static final Map<String, String> PORTAL_NAMES = Map.of("lunar isle", "moonclan", "marim",
        "ape atoll teleport (standard)", "ape atoll dungeon", "ape atoll teleport (arceuus)", "waterbirth island",
        "waterbirth", "carrallangar", "carrallanger");

    /** A spell starting with the place's name first, else a teleport to it by name ("Stony basalt: Troll Stronghold"). */
    static Poi portalTeleport(String place, Map<String, Poi> teleports)
    {
        String given = place.trim().toLowerCase(Locale.ROOT);
        String wanted = PORTAL_NAMES.getOrDefault(given, given);
        int bestRank = 0;
        String bestName = null;
        Poi best = null;
        for (Map.Entry<String, Poi> entry : teleports.entrySet())
        {
            String name = entry.getKey();
            Poi teleport = entry.getValue();
            boolean spell = name.startsWith(wanted) && teleport.group != null && teleport.group.contains("Spellbook");
            if (!spell && !name.contains(": " + wanted))
            {
                continue;
            }
            if (name.equals(wanted) || name.equals(wanted + " teleport"))
            {
                return teleport;
            }
            // Spells first, then the shortest name, then the first alphabetically.
            int rank = (spell ? 0 : 1 << 16) + name.length();
            if (best == null || rank < bestRank || rank == bestRank && name.compareTo(bestName) < 0)
            {
                bestRank = rank;
                bestName = name;
                best = teleport;
            }
        }
        return best;
    }

    private void addHouse(List<Edge> into, WorldPoint point, String name, int cost)
    {
        int to = snap(point);
        if (to >= 0)
        {
            into.add(new Edge(Edge.ANYWHERE, to, Edge.Kind.HOUSE, name, null, cost));
        }
    }

    static final int LACKING_ITEM = 2000;

    private static String needsText(Call call, Needs needs, boolean bankToo)
    {
        if (needs.isEmpty())
        {
            return null;
        }
        if (call.missingLevels == null && call.missingItems == null)
        {
            return uncheckedText(call.realUnlocks, needs);
        }
        List<String> parts = new ArrayList<>();
        if (call.missingLevels != null)
        {
            for (Requirements.Line line : levelLines(needs))
            {
                if (Boolean.FALSE.equals(call.missingLevels.met(line)))
                {
                    parts.add(line.text);
                }
            }
        }
        if (call.missingItems != null && !call.missingItems.has(needs.items, bankToo))
        {
            parts.add("items you do not have");
        }
        return parts.isEmpty() ? null : "You lack: " + String.join(", ", parts);
    }

    /** A step's requirements when not all could be checked (logged out, unknown quest or item); else null. */
    private static String uncheckedText(Unlocks realUnlocks, Needs needs)
    {
        List<Requirements.Line> lines = levelLines(needs);
        boolean unchecked = lines.stream().anyMatch(line -> realUnlocks == null || realUnlocks.met(line) == null);
        // Items named rather than numbered (keys, tools) are not checked.
        boolean namedItems = Arrays.stream(needs.items.split("&&|\\|\\|"))
            .map(token -> token.split("=")[0].trim()).anyMatch(t -> !t.isEmpty() && !t.chars().allMatch(Character::isDigit));
        if (!unchecked && !namedItems)
        {
            return null;
        }
        List<String> parts = lines.stream().map(line -> line.text).collect(Collectors.toList());
        if (namedItems || !needs.items.trim().isEmpty() && realUnlocks == null)
        {
            parts.add("items");
        }
        return parts.isEmpty() ? null : "Needs: " + String.join(", ", parts);
    }

    private static List<Requirements.Line> levelLines(Needs needs)
    {
        return Requirements.describe(new Needs(needs.skills, "", needs.quests, ""), id -> null);
    }
}
