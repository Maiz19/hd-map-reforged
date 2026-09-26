package com.hdmapreforged;

import com.hdmapreforged.route.CollisionMap;
import com.hdmapreforged.route.Edge;
import com.hdmapreforged.route.PlayerState;
import com.hdmapreforged.route.RouteRequest;
import com.hdmapreforged.route.SeaMap;
import com.hdmapreforged.route.Tiles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the map's icons into the jumps a route may take for this player: teleports (from wherever the route starts)
 * and transports, passages and ships (from their icon's spot), each only when its skill, quest and unlock
 * requirements are met and the items it needs are carried (teleports) or carried or banked (fares, tools). Also
 * boarding and leaving the player's own boat, and the ways out of the player-owned house. Thread-safe: what a request
 * goes by is kept in its own {@link Call}; requests come from Swing and the route extras thread alike.
 */
final class RouteSource
{
    /** How far an icon's spot may be moved to the nearest walkable tile. */
    private static final int SNAP = 5;
    /** How far from a dock a boat can be boarded or left. */
    private static final int DOCK_REACH = 18;
    /** A boat at a known port counts for docks this close to it. */
    private static final int PORT_RADIUS = 24;
    private static final Pattern SAILING = Pattern.compile("(\\d+)\\s+Sailing", Pattern.CASE_INSENSITIVE);

    /** What the route may use. */
    static final class Options
    {
        final boolean teleports;
        final boolean sailing;
        /** The teleport focus on the player's boat. */
        final HdMapReforgedConfig.BoatFocus focus;
        /** Packed tiles of the shipwrights, who bring the boat to their port. */
        final int[] shipwrights;

        Options(boolean teleports, boolean sailing)
        {
            this(teleports, sailing, HdMapReforgedConfig.BoatFocus.NONE, new int[0]);
        }

        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights)
        {
            this(teleports, sailing, focus, shipwrights, false, false);
        }

        /** Ignoring levels (with quests and unlocks) or items shows the fastest way anyone could go. */
        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems)
        {
            this(teleports, sailing, focus, shipwrights, ignoreLevels, ignoreItems, java.util.Collections.emptySet());
        }

        /** {@code avoid}: names of teleports and transports not to use (lower case). */
        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems, java.util.Set<String> avoid)
        {
            this(teleports, sailing, focus, shipwrights, ignoreLevels, ignoreItems, avoid, Saving.NONE);
        }

        /** {@code saving}: how many tiles each kind of trip must save to be taken. */
        Options(boolean teleports, boolean sailing, HdMapReforgedConfig.BoatFocus focus, int[] shipwrights,
            boolean ignoreLevels, boolean ignoreItems, java.util.Set<String> avoid, Saving saving)
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
        final java.util.Set<String> avoid;
        final Saving saving;
        /** The way anyone could go, shown when the player's way does not arrive (not the "ignore" settings). */
        boolean fallback;

        /** Marks these as the way anyone could go. */
        Options fallback()
        {
            fallback = true;
            return this;
        }

        /** {@link #fallback()} when {@code anything}, as the plugin plans the way anyone could go. */
        Options fallbackIf(boolean anything)
        {
            return anything ? fallback() : this;
        }
    }

    /**
     * How many tiles of running a kind of trip must save before a route takes it: a teleport for a few tiles costs
     * runes and a click for nothing. Weighed in the search only; the route's time stays what the trips take.
     */
    static final class Saving
    {
        static final Saving NONE = new Saving(0, 0, 0, 0, 0, 0);

        final int teleport;
        final int shortcut;
        final int transport;
        final int canoe;
        final int carpet;
        final int ship;

        Saving(int teleport, int shortcut, int transport, int canoe, int carpet, int ship)
        {
            this.teleport = teleport;
            this.shortcut = shortcut;
            this.transport = transport;
            this.canoe = canoe;
            this.carpet = carpet;
            this.ship = ship;
        }

        /** Tiles a trip of this kind must save; portals and levers none. */
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

    /** Half ticks per tile saved: running one tile. */
    private static final int PER_TILE = 1;

    /** A shipwright this close to a dock can bring the boat there. */
    private static final int SHIPWRIGHT_REACH = 40;

    private final CollisionMap map;
    private final SeaMap sea;

    RouteSource(CollisionMap map, SeaMap sea)
    {
        this.map = map;
        this.sea = sea;
    }

    /**
     * A search from {@code start} (packed tile or sea block, -1 in the house) to {@code target}, or null when there
     * is no start at all.
     */
    RouteRequest request(int start, int target, List<Poi> pois, Unlocks unlocks, PlayerState state, Options options,
        int nodeLimit)
    {
        Call call = new Call(options, unlocks, state);
        if (options.ignoreLevels)
        {
            // Every level, quest and unlock counts as met; also a boat and the top Sailing level.
            unlocks = null;
            state = new PlayerState(state.items, 99, true, state.boats, state.running, state.inHouse, state.houseExit,
                state.ownHouse, state.houseFeatures);
        }
        if (options.ignoreItems)
        {
            state = new PlayerState(com.hdmapreforged.route.ItemSnapshot.EVERYTHING, state.sailingLevel, state.sailing, state.boats, state.running,
                state.inHouse, state.houseExit, state.ownHouse, state.houseFeatures);
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
        if (state.inHouse)
        {
            house(pois, unlocks, state, teleportsByName, startEdges);
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

    /** What one request goes by besides what it may use; its own, as requests come from more than one thread. */
    private static final class Call
    {
        /** Teleports and transports the route must not use, by name in lower case. */
        final java.util.Set<String> avoiding;
        final Saving saving;
        /** The player's unlocks as known (null when logged out), for steps whose requirements could not all be checked. */
        final Unlocks realUnlocks;
        /** The player's real unlocks while a route ignores them; null when not ignored. */
        final Unlocks missingLevels;
        /** The player's real items while a route ignores them; null when not ignored. */
        final com.hdmapreforged.route.ItemSnapshot missingItems;
        /** The player's own Sailing level (0 without Sailing), when levels are ignored; 99 otherwise. */
        final int realSailing;
        /** Planning the way anyone could go (see {@link Options#fallback}). */
        final boolean fallback;

        Call(Options options, Unlocks unlocks, PlayerState state)
        {
            avoiding = options.avoid;
            saving = options.saving;
            realUnlocks = unlocks;
            // What the player really has, for naming what an ignored requirement leaves missing.
            missingLevels = options.ignoreLevels ? unlocks : null;
            missingItems = options.ignoreItems ? state.items : null;
            // The player's own Sailing, for saying so on the boat's steps when the way anyone could go sails.
            realSailing = !options.ignoreLevels ? 99 : state.sailing ? state.sailingLevel : 0;
            fallback = options.fallback;
        }
    }

    /** A door or gate that opens after a quest (route/gates.tsv). */
    private static final class Gate
    {
        final net.runelite.api.coords.WorldPoint from;
        final net.runelite.api.coords.WorldPoint to;
        final String name;
        final Needs needs;

        Gate(net.runelite.api.coords.WorldPoint from, net.runelite.api.coords.WorldPoint to, String name, Needs needs)
        {
            this.from = from;
            this.to = to;
            this.name = name;
            this.needs = needs;
        }
    }

    /** The gates, read once, with their requirements made once (the unlocks cache results per requirement set). */
    private static final List<Gate> GATES = readGates();

    private static List<Gate> readGates()
    {
        java.io.InputStream in = RouteSource.class.getResourceAsStream("/com/hdmapreforged/route/gates.tsv");
        if (in == null)
        {
            return java.util.Collections.emptyList();
        }
        try (java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
        {
            List<Gate> gates = new ArrayList<>();
            for (Tsv.Row row : Tsv.parse(reader))
            {
                net.runelite.api.coords.WorldPoint from = row.point("Origin");
                net.runelite.api.coords.WorldPoint to = row.point("Destination");
                if (from != null && to != null)
                {
                    gates.add(new Gate(from, to, row.get("Name"), Needs.of(row)));
                }
            }
            return java.util.Collections.unmodifiableList(gates);
        }
        catch (java.io.IOException e)
        {
            return java.util.Collections.emptyList();
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

    /**
     * The game's own passages that are Agility shortcuts (the same object as a shortcut of shortcuts.tsv): only for a
     * player who meets the shortcut's requirements, and named when a route ignores them.
     */
    private void shortcuts(Call call, Unlocks unlocks, List<Edge> into)
    {
        List<com.hdmapreforged.route.ShortcutPassage> passages =
            com.hdmapreforged.route.Pathfinder.shortcutPassages(map);
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

    /** The shortcut passages with their requirements, made once for the (fixed) list of them. */
    private static final class ShortcutNeeds
    {
        final List<com.hdmapreforged.route.ShortcutPassage> passages;
        final List<Needs> needs;

        ShortcutNeeds(List<com.hdmapreforged.route.ShortcutPassage> passages)
        {
            this.passages = passages;
            List<Needs> made = new ArrayList<>(passages.size());
            for (com.hdmapreforged.route.ShortcutPassage passage : passages)
            {
                made.add(new Needs(passage.skills, passage.items, passage.quests, passage.varbits));
            }
            this.needs = java.util.Collections.unmodifiableList(made);
        }
    }

    private volatile ShortcutNeeds shortcutNeeds;

    private List<Needs> shortcutNeeds(List<com.hdmapreforged.route.ShortcutPassage> passages)
    {
        ShortcutNeeds known = shortcutNeeds;
        if (known == null || known.passages != passages)
        {
            known = new ShortcutNeeds(passages);
            shortcutNeeds = known;
        }
        return known.needs;
    }

    static boolean isTransport(PoiType type)
    {
        switch (type)
        {
            case FAIRY_RING:
            case SPIRIT_TREE:
            case GNOME_GLIDER:
            case BALLOON:
            case QUETZAL:
            case MUSHTREE:
            case BOAT:
            case CHARTER:
            case CANOE:
            case CARPET:
            case MINECART:
            case PORTAL:
            case LEVER:
            case TRANSPORT:
            case AGILITY_SHORTCUT:
                return true;
            default:
                // Obelisks lead to a random other obelisk. Where entrances lead comes from the game's own map links and
                // cited hand links (Pathfinder), not from our icons of them.
                return false;
        }
    }

    private boolean usable(Unlocks unlocks, Needs needs)
    {
        return unlocks == null || unlocks.usable(needs);
    }

    /** The walkable tile nearest to a spot, packed, or -1. */
    private int snap(net.runelite.api.coords.WorldPoint point)
    {
        return map.nearestWalkable(point.getX(), point.getY(), point.getPlane(), SNAP);
    }

    /** Like {@link #snap(net.runelite.api.coords.WorldPoint)}, on the side away from {@code other}. */
    private int snap(net.runelite.api.coords.WorldPoint point, net.runelite.api.coords.WorldPoint other)
    {
        return map.nearestWalkable(point.getX(), point.getY(), point.getPlane(), SNAP, other.getX(), other.getY());
    }

    private void teleport(Call call, Poi member, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        if (HouseSpots.insideHouse(member.location) || !usable(unlocks, member.needs)
            || !state.items.has(member.needs.items, false))
        {
            return;
        }
        // The way anyone could go takes a teleport the player has no item for only when nothing else gets there: a
        // missing rune or amulet is rarely why a place is out of reach, and it turned the whole route red from its
        // first step.
        int lacking = call.fallback && call.missingItems != null && !member.needs.items.isEmpty()
            && !call.missingItems.has(member.needs.items, false) ? LACKING_ITEM : 0;
        int to = snap(member.location);
        if (to < 0)
        {
            return;
        }
        String name = member.name;
        String category = member.group != null && !member.group.isEmpty() ? member.group : "Teleports";
        if (call.avoiding.contains(name.toLowerCase(Locale.ROOT)) || call.avoiding.contains(typeKey(category)))
        {
            return;
        }
        boolean home = name.toLowerCase(Locale.ROOT).contains("home teleport");
        into.add(new Edge(Edge.ANYWHERE, to, Edge.Kind.TELEPORT, name, needsText(call, member.needs, false),
            teleportTime(home, member.group), category).weighed(call.saving.teleport * PER_TILE + lacking));
    }

    private void transport(Call call, Poi poi, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        if (!usable(unlocks, poi.needs) || !state.items.has(poi.needs.items, true))
        {
            return;
        }
        if (snap(poi.location) < 0)
        {
            return;
        }
        Edge.Kind kind = kind(poi.type);
        List<Poi.Link> links = poi.links();
        for (Poi.Link link : links)
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
            com.hdmapreforged.route.Fees.Fee fee = com.hdmapreforged.route.Fees.at(poi.location.getX(),
                poi.location.getY(), poi.location.getPlane());
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

    /** "Never use" and "Avoid" of a whole kind are kept as "type:" and the kind's name. */
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
                // Where it goes is what matters ("Gnome glider: Feldip Hills"); where it starts, the player is at.
                return poi.type.displayName + ": " + (label != null ? label : poi.name);
        }
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

    /**
     * Half ticks a teleport takes: a spell 4 ticks and jewellery 3 with its choice (the wiki's cast times), about 5;
     * a home teleport 19 to 25 ticks; minigame teleports about 10 seconds to cast, and a long cooldown after, so they
     * are a last resort.
     */
    static int teleportTime(boolean home, String group)
    {
        return home ? 44 : "Minigame teleports".equals(group) ? 60 : 10;
    }

    /**
     * Half ticks a trip takes, roughly, with talking to someone, the menu and the animation or ride: a fairy ring,
     * spirit tree or mushtree about 5 ticks, a quetzal 6, an obelisk 8 (it waits a moment), a glider, balloon or
     * minecart about 10 with the ride, a charter ship 15, a boat 20 with its crossing, a canoe 30 (shaping it and
     * paddling), a magic carpet about 50 (the ride is long).
     */
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
                return 8;
            case OBELISK:
                return 16;
            case PORTAL:
                return 6;
            case AGILITY_SHORTCUT:
                return 8;
            case GNOME_GLIDER:
            case BALLOON:
            case MINECART:
            case TRANSPORT:
                return 20;
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
     * A dock: leaving the boat there needs the dock's Sailing level; boarding needs the boat to be there (a known
     * port), or, when no boat's port is known, is assumed possible at any dock. {@code onBoat}: the route starts at
     * sea, on the player's boat, so the boat is not lying at some dock.
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
        // The way anyone could go sails; the player without the Sailing for it sees the boat's steps as what they lack.
        String lacking = call.realSailing < needed
            ? "You lack: " + (call.realSailing <= 0 ? "Sailing" : needed + " Sailing")
            : null;
        boolean boatHere = false;
        for (int boat : state.boats)
        {
            boatHere |= Tiles.distance(boat, land) <= PORT_RADIUS;
        }
        if (boatHere)
        {
            into.add(new Edge(land, water, Edge.Kind.BOARD, "Board your boat at " + poi.name + dockLevel(poi), lacking,
                10));
        }
        else if (options.focus != HdMapReforgedConfig.BoatFocus.NONE)
        {
            // Summon Boat brings the boat to the dock you stand at: a cast and a short wait.
            into.add(new Edge(land, water, Edge.Kind.BOARD, "Summon your boat and board it at " + poi.name,
                lacking != null ? lacking : "Summon Boat (needs the teleport focus on your boat)", 16));
        }
        else if (nearShipwright(land, options))
        {
            // A shipwright brings the boat to their port, for a fee; talking and paying take a while.
            into.add(new Edge(land, water, Edge.Kind.BOARD, "Board your boat at " + poi.name,
                lacking != null ? lacking : "Ask the shipwright here to bring your boat (fee)", 60));
        }
        else if (state.boats.length == 0 && !onBoat)
        {
            // Where the boat lies is not known (not logged in yet): any dock, with a hint. Not when the player is on
            // it at sea: left there, it is at no dock.
            into.add(new Edge(land, water, Edge.Kind.BOARD, "Board your boat at " + poi.name,
                lacking != null ? lacking : "If your boat is moored here (a shipwright can bring it)", 10));
        }
    }

    /** " (20 Sailing)" for a dock that needs a level, so the step shows why this dock can be used. */
    private static String dockLevel(Poi poi)
    {
        int level = level(poi.needs);
        return level > 1 ? " (" + level + " Sailing)" : "";
    }

    private static boolean nearShipwright(int land, Options options)
    {
        for (int shipwright : options.shipwrights)
        {
            if (Tiles.distance(shipwright, land) <= SHIPWRIGHT_REACH)
            {
                return true;
            }
        }
        return false;
    }

    /** With a greater teleport focus: Teleport to Boat, from anywhere, to the dock where each boat lies. */
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

    /** The ways out of the house: its exit portal, and in the player's own house what it has. */
    private void house(List<Poi> pois, Unlocks unlocks, PlayerState state, Map<String, Poi> teleports, List<Edge> into)
    {
        if (state.houseExit >= 0)
        {
            int exit = map.nearestWalkable(Tiles.x(state.houseExit), Tiles.y(state.houseExit), 0, SNAP);
            if (exit >= 0)
            {
                into.add(new Edge(Edge.ANYWHERE, exit, Edge.Kind.HOUSE, "Leave the house by its portal", null, 6));
            }
        }
        if (!state.ownHouse)
        {
            return;
        }
        for (String feature : state.houseFeatures)
        {
            if (feature.startsWith("portal:"))
            {
                Poi teleport = portalTeleport(feature.substring("portal:".length()), teleports);
                if (teleport != null)
                {
                    addHouse(into, teleport.location, "House portal: " + teleport.name, 6);
                }
            }
            else if (feature.startsWith("box:") || feature.equals("glory"))
            {
                List<String> groups = new ArrayList<>();
                groups.add("Amulet of glory");
                if (feature.startsWith("box:"))
                {
                    groups.clear();
                    groups.add("Ring of dueling");
                    groups.add("Games necklace");
                    if (!feature.equals("box:basic"))
                    {
                        groups.add("Combat bracelet");
                        groups.add("Skills necklace");
                    }
                    if (feature.equals("box:ornate"))
                    {
                        groups.add("Ring of wealth");
                        groups.add("Amulet of glory");
                    }
                }
                for (Poi teleport : teleports.values())
                {
                    if (teleport.group != null && groups.contains(teleport.group) && usable(unlocks, teleport.needs))
                    {
                        addHouse(into, teleport.location, (feature.equals("glory") ? "Mounted glory: " : "Jewellery box: ")
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
                            + poi.name, 10);
                    }
                }
            }
        }
    }

    /**
     * The spellbook teleport a house portal to {@code place} stands for: the one named just so ("Varrock" or "Varrock
     * Teleport"), else the shortest name starting with it, ties by name: the same one every time.
     */
    static Poi portalTeleport(String place, Map<String, Poi> teleports)
    {
        String wanted = place.trim().toLowerCase(Locale.ROOT);
        String bestName = null;
        Poi best = null;
        for (Map.Entry<String, Poi> entry : teleports.entrySet())
        {
            String name = entry.getKey();
            Poi teleport = entry.getValue();
            if (!name.startsWith(wanted) || teleport.group == null || !teleport.group.contains("Spellbook"))
            {
                continue;
            }
            if (name.equals(wanted) || name.equals(wanted + " teleport"))
            {
                return teleport;
            }
            if (bestName == null || name.length() < bestName.length()
                || name.length() == bestName.length() && name.compareTo(bestName) < 0)
            {
                bestName = name;
                best = teleport;
            }
        }
        return best;
    }

    private void addHouse(List<Edge> into, net.runelite.api.coords.WorldPoint point, String name, int cost)
    {
        int to = snap(point);
        if (to >= 0)
        {
            into.add(new Edge(Edge.ANYWHERE, to, Edge.Kind.HOUSE, name, null, cost));
        }
    }

    /** Extra weight (half ticks) of a teleport whose item the player lacks, on the way anyone could go. */
    static final int LACKING_ITEM = 2000;

    /**
     * What a step needs that the player does not have. Only while the route ignores levels or items: otherwise
     * every step is one the player can take, and listing its requirements is just noise.
     */
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
            for (Requirements.Line line : Requirements.describe(new Needs(needs.skills, "", needs.quests, ""), id -> null))
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

    /**
     * The requirements of a step when not all of them could be checked (logged out, a quest or item the plugin does
     * not know): then the player can see what the step needs. Null when everything was checked.
     */
    private static String uncheckedText(Unlocks realUnlocks, Needs needs)
    {
        List<Requirements.Line> lines = Requirements.describe(new Needs(needs.skills, "", needs.quests, ""), id -> null);
        boolean unchecked = false;
        for (Requirements.Line line : lines)
        {
            unchecked |= realUnlocks == null || realUnlocks.met(line) == null;
        }
        // Items named rather than numbered (keys, tools) are not checked.
        boolean namedItems = false;
        for (String token : needs.items.split("&&|\\|\\|"))
        {
            String t = token.split("=")[0].trim();
            namedItems |= !t.isEmpty() && !t.chars().allMatch(Character::isDigit);
        }
        if (!unchecked && !namedItems)
        {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Requirements.Line line : lines)
        {
            parts.add(line.text);
        }
        if (namedItems || !needs.items.trim().isEmpty() && realUnlocks == null)
        {
            parts.add("items");
        }
        return parts.isEmpty() ? null : "Needs: " + String.join(", ", parts);
    }

    /** Spots inside the player-owned house (its template area), which a route cannot use as places. */
    static final class HouseSpots
    {
        private HouseSpots()
        {
        }

        static boolean insideHouse(net.runelite.api.coords.WorldPoint point)
        {
            return com.hdmapreforged.route.HouseTracker.isTemplate(point.getX(), point.getY());
        }
    }
}
