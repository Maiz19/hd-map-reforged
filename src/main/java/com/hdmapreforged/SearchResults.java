package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * What a search found, on the map and in the card: where a monster or NPC is (its wiki page's location maps), where
 * an item can be had (its spawns, the shops with it in stock, the monsters dropping it), or every place of a kind
 * (all herb patches, all shark fishing spots). Each place is marked with arrows and labelled, and listed in the card.
 * Swing thread only.
 */
final class SearchResults implements MapView.Overlay
{
    /** Shows the list in the card, or removes it (null). */
    interface Card
    {
        void show(IntFunction<JComponent> section);
    }

    /** Something found: places to mark, and more for the card. */
    static final class Result
    {
        final String title;
        /** The wiki page for the Wiki button and the picture, or null. */
        final String page;
        final String summary;
        final List<NpcSpawns.Group> groups;
        /** More of the card after the places (the monsters dropping an item), or null. */
        final IntFunction<JComponent> extra;
        /** Where the places come from, at the bottom of the card. */
        final String credit;
        /** The kind of place shown ("Yew trees"), which can be a stop of a custom route; or null. */
        String kind;
        /**
         * The lists to pick from ("Spawns", "Shops"…) with how many each has, so a result with many sources shows
         * one at a time, in the card and on the map; empty for one list.
         */
        final java.util.LinkedHashMap<String, Integer> tabs = new java.util.LinkedHashMap<>();
        /** The list shown, or null. */
        String tab;

        Result(String title, String page, String summary, List<NpcSpawns.Group> groups, IntFunction<JComponent> extra,
            String credit)
        {
            this.title = title;
            this.page = page;
            this.summary = summary;
            this.groups = Collections.unmodifiableList(groups);
            this.extra = extra;
            this.credit = credit;
        }

        /** Whether a place is in the list shown. */
        boolean shows(NpcSpawns.Group group)
        {
            return tab == null || group.category == null || group.category.equals(tab);
        }
    }

    static final String SPAWNS = "Spawns";
    static final String SHOPS = "Shops";
    static final String DROPS = "Dropped by";
    static final String OTHER = "Other";

    static final Color DOT = new Color(70, 220, 90);
    /** Shops selling a searched item. */
    static final Color SHOP = new Color(255, 196, 60);
    private static final Color DOT_EDGE = new Color(0, 50, 10, 230);
    private static final Color LABEL_BACKGROUND = new Color(16, 16, 20, 215);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;
    /** Places, and monsters dropping an item, listed before "Show all". */
    static final int SHOWN = 25;

    private final MapView view;
    private final WikiClient wiki;
    private final Card card;
    /**
     * Works out which of overlapping maps shows the given points (in the background), then runs the callback on the
     * Swing thread; set by the plugin.
     */
    private java.util.function.BiConsumer<List<WorldPoint>, java.util.function.Consumer<java.util.Map<WorldPoint, BaseMap>>>
        regionCheck = (points, done) -> done.accept(Collections.emptyMap());
    /** The map found to show each place's middle point. */
    private java.util.Map<WorldPoint, BaseMap> placeMaps = Collections.emptyMap();
    private Result result;
    /** What was shown before a monster was looked up from it (an item's drops), for Back; or null. */
    private Result previous;
    /** What is being looked up, while the wiki answers. */
    private String loading;
    /** Counts lookups, so an answer to an older one is dropped. */
    private int request;
    private String failed;
    /** The picture from the result's wiki page, or null. */
    private java.awt.image.BufferedImage image;
    /** Whether the long lists are shown whole. */
    private boolean allPlaces;
    private boolean allDrops;

    SearchResults(MapView view, WikiClient wiki, Card card)
    {
        this.view = view;
        this.wiki = wiki;
        this.card = card;
        view.addOverlay(this);
    }

    /** Adds a stop to the custom route being made, or null. */
    private java.util.function.Consumer<Tour.Stop> stopAdder;

    void setStopAdder(java.util.function.Consumer<Tour.Stop> stopAdder)
    {
        this.stopAdder = stopAdder;
    }

    void setRegionCheck(java.util.function.BiConsumer<List<WorldPoint>, java.util.function.Consumer<java.util.Map<WorldPoint, BaseMap>>> regionCheck)
    {
        this.regionCheck = regionCheck;
    }

    /** Looks a monster or NPC up on the wiki and shows where it is. */
    void findMonster(String name)
    {
        previous = null;
        lookUpMonster(name);
    }

    private void lookUpMonster(String name)
    {
        int id = startLoading(name);
        // Read here, on the Swing thread; the result is worked out where the wiki's answer arrives, since looking
        // through the map's places for those the page names takes a while (these lists are replaced, never changed).
        BaseMaps maps = view.maps();
        List<PoiLoader.Place> labels = view.labels();
        List<Poi> pois = view.pois();
        wiki.spawns(name, found -> {
            Result shown = found == null ? null : monsterResult(found, maps, labels, pois);
            SwingUtilities.invokeLater(() -> {
                if (id != request)
                {
                    return;
                }
                if (found == null)
                {
                    fail("The wiki could not be reached.");
                }
                else if (shown == null)
                {
                    fail("The wiki page \"" + found.page + "\" lists no locations.");
                }
                else
                {
                    show(shown);
                }
            });
        });
    }

    /**
     * A monster's places: its spawns; when none is on a map one can see (a boss fought in an instance, a page about
     * several kinds of monster without its own), first the place to go (the way into the boss's lair when known,
     * else the place its page mentions most that the map knows), then the spawns only the wiki's map of everything
     * draws. Null when there is nothing to show. Pure: safe off the Swing thread with lists that are not changed.
     */
    static Result monsterResult(NpcSpawns found, BaseMaps maps, List<PoiLoader.Place> labels, List<Poi> pois)
    {
        boolean seen = false;
        for (NpcSpawns.Group group : found.groups)
        {
            WorldPoint c = group.center();
            seen |= maps == null || maps.find(c.getX(), c.getY()) != null;
        }
        if (!found.groups.isEmpty() && seen)
        {
            int places = found.groups.size();
            return new Result(found.page, found.page, found.spawnCount() + " spawns in " + places
                + (places == 1 ? " place" : " places") + ", marked with green arrows. Click a place to go there.",
                found.groups, null, null);
        }
        // Spawns only the wiki's map of everything draws (an instance, a temple of its own) are kept; the place to go
        // comes first: the way into the boss's lair when known, else the place its page names most.
        PlaceLookup.Found place = PlaceLookup.boss(found.page);
        if (place == null)
        {
            place = PlaceLookup.find(found.mentioned, maps, labels, pois);
        }
        if (place == null)
        {
            return found.groups.isEmpty() ? null : new Result(found.page, found.page, found.spawnCount() + " spawns in "
                + found.groups.size() + (found.groups.size() == 1 ? " place" : " places") + ", only on the wiki's map "
                + "of everything.", found.groups, null, null);
        }
        List<NpcSpawns.Group> groups = new ArrayList<>();
        groups.add(new NpcSpawns.Group(place.name, place.name, "", false, -1, Collections.singletonList(place.point), null,
            "Where to go", null));
        groups.addAll(found.groups);
        return new Result(found.page, found.page, "The wiki shows no spawns on a map of their own (fought in an "
            + "instance, or found on the pages of its kinds). First is where to go: " + place.name + ".", groups, null,
            null);
    }

    /** Looks an item up on the wiki: its spawns, the shops with it in stock, and what drops it. */
    void findItem(String name)
    {
        previous = null;
        int id = startLoading(name);
        wiki.item(name, found -> SwingUtilities.invokeLater(() -> {
            if (id != request)
            {
                return;
            }
            if (found == null)
            {
                fail("The wiki could not be reached.");
            }
            else if (found.isEmpty())
            {
                fail("The wiki lists no spawns, shops with stock, or drops for \"" + found.page + "\".");
            }
            else
            {
                show(itemResult(found, view.player()));
                loadDropPictures(found.drops);
            }
        }));
    }

    /** Every place of a kind, the nearest to the player first; {@code placeName} names a point's surroundings. */
    void showKind(KindIndex.Kind kind, Function<WorldPoint, String> placeName)
    {
        previous = null;
        request++;
        List<NpcSpawns.Group> groups = kindGroups(kind, placeName, view.player());
        int n = groups.size();
        Result shown = new Result(kind.label, kind.page, n + (n == 1 ? " place" : " places")
            + ", marked with green arrows" + (view.player() != null ? ", nearest first" : "") + ". Click one to go there.",
            groups, null, kind.type == null ? "Skilling spots: RuneLite (BSD 2-Clause)" : null);
        shown.kind = kind.label;
        show(shown);
    }

    /** Places of one kind this close together, in the same named place, are listed and labelled as one. */
    static final int TOGETHER = 24;

    /**
     * The places of a kind: those close together in the same town are one place with several arrows ("Catherby, 4
     * spots"). Skilling spots are named by where they are, other things by their own name and where.
     */
    static List<NpcSpawns.Group> kindGroups(KindIndex.Kind kind, Function<WorldPoint, String> placeName, WorldPoint player)
    {
        List<String> names = new ArrayList<>();
        List<Poi> icons = new ArrayList<>();
        List<String> places = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<List<WorldPoint>> points = new ArrayList<>();
        for (KindIndex.Entry entry : kind.entries)
        {
            String place = placeName.apply(entry.point);
            place = place == null ? "" : place;
            String name = kind.type == null && !place.isEmpty() ? place : entry.name;
            int together = -1;
            for (int i = 0; i < names.size() && !place.isEmpty(); i++)
            {
                WorldPoint first = points.get(i).get(0);
                if (places.get(i).equals(place) && names.get(i).equals(name) && first.getPlane() == entry.point.getPlane()
                    && Math.abs(first.getX() - entry.point.getX()) <= TOGETHER
                    && Math.abs(first.getY() - entry.point.getY()) <= TOGETHER)
                {
                    together = i;
                    break;
                }
            }
            if (together >= 0)
            {
                points.get(together).add(entry.point);
                continue;
            }
            names.add(name);
            icons.add(entry.poi);
            places.add(place);
            notes.add(entry.detail == null ? "" : entry.detail);
            points.add(new ArrayList<>(Collections.singletonList(entry.point)));
        }
        List<NpcSpawns.Group> groups = new ArrayList<>();
        for (int i = 0; i < names.size(); i++)
        {
            int n = points.get(i).size();
            String note = notes.get(i);
            if (n > 1)
            {
                note = n + " spots" + (note.isEmpty() ? "" : " · " + note);
            }
            // A note, even an empty one, instead of "1 spawn".
            NpcSpawns.Group group = new NpcSpawns.Group(names.get(i), places.get(i), "", false, -1, points.get(i), null,
                note, null);
            group.poi = icons.get(i);
            groups.add(group);
        }
        return nearestFirst(groups, player);
    }

    /** An item's places: spawns, then shops (gold), each the nearest first; its drops in the card below. */
    Result itemResult(ItemSources found, WorldPoint player)
    {
        List<NpcSpawns.Group> groups = new ArrayList<>();
        for (NpcSpawns.Group spawn : nearestFirst(found.spawns, player))
        {
            groups.add(spawn.as(SPAWNS, null, null));
        }
        List<NpcSpawns.Group> shops = new ArrayList<>();
        List<ItemSources.Store> unplaced = new ArrayList<>();
        for (ItemSources.Store store : found.stores)
        {
            if (store.point == null)
            {
                unplaced.add(store);
                continue;
            }
            shops.add(new NpcSpawns.Group(store.shop, "", "", false, store.mapId, Collections.singletonList(store.point),
                SHOPS, storeNote(store), SHOP));
        }
        groups.addAll(nearestFirst(shops, player));
        int npcs = 0;
        for (ItemSources.Drop drop : found.drops)
        {
            npcs += drop.npc ? 1 : 0;
        }
        Result item = new Result(found.page, found.page, "Where to get it. Pick a list: the map marks only that one "
            + "(spawns green, shops gold).", groups, width -> itemExtra(unplaced, found.drops, width),
            null);
        int spawnCount = 0;
        for (NpcSpawns.Group spawn : found.spawns)
        {
            spawnCount += spawn.points.size();
        }
        item.tabs.put(SPAWNS, spawnCount);
        item.tabs.put(SHOPS, found.stores.size());
        item.tabs.put(DROPS, npcs);
        item.tabs.put(OTHER, found.drops.size() - npcs);
        return item;
    }

    static String storeNote(ItemSources.Store store)
    {
        String stock = store.stock.equals("∞") ? "Endless stock" : "Stock " + store.stock;
        return store.price.isEmpty() ? stock : stock + " · " + store.price;
    }

    /** Places sorted by how far their middle is from the player (straight line, any floor); unchanged without one. */
    static List<NpcSpawns.Group> nearestFirst(List<NpcSpawns.Group> groups, WorldPoint player)
    {
        List<NpcSpawns.Group> sorted = new ArrayList<>(groups);
        if (player != null)
        {
            sorted.sort(java.util.Comparator.comparingLong(g -> distance(g.center(), player)));
        }
        return sorted;
    }

    private static long distance(WorldPoint a, WorldPoint b)
    {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }

    private int startLoading(String name)
    {
        loading = name;
        failed = null;
        result = null;
        image = null;
        allPlaces = false;
        allDrops = false;
        request++;
        card.show(this::section);
        view.repaint();
        return request;
    }

    private void fail(String message)
    {
        loading = null;
        failed = message;
        card.show(this::section);
        view.repaint();
    }

    /** Shows a result: at once, each place on the map its region belongs to; which of overlapping maps really shows
     * it is checked on the tiles after, and the card and arrows follow when that is known. */
    void show(Result found)
    {
        loading = null;
        failed = null;
        placeMaps = Collections.emptyMap();
        result = found;
        if (found.tab == null)
        {
            for (java.util.Map.Entry<String, Integer> tab : found.tabs.entrySet())
            {
                if (tab.getValue() > 0)
                {
                    found.tab = tab.getKey();
                    break;
                }
            }
        }
        image = null;
        allPlaces = false;
        allDrops = false;
        // Stay where the map is: the places are listed, and clicking one goes there.
        if (found.page != null)
        {
            wiki.pageImage(found.page, 56, loaded -> SwingUtilities.invokeLater(() -> {
                if (result == found)
                {
                    image = loaded;
                    card.show(this::section);
                }
            }));
        }
        card.show(this::section);
        view.repaint();
        List<WorldPoint> centers = new ArrayList<>();
        for (NpcSpawns.Group group : found.groups)
        {
            centers.add(group.center());
        }
        if (centers.isEmpty())
        {
            return;
        }
        regionCheck.accept(centers, maps -> {
            if (result != found)
            {
                return;
            }
            placeMaps = maps;
            card.show(this::section);
            view.repaint();
        });
    }

    void clear()
    {
        result = null;
        previous = null;
        loading = null;
        failed = null;
        request++;
        card.show(null);
        view.repaint();
    }

    /** Whether no wiki map draws this place yet (such as a new dungeon): going there would show only black. */
    private boolean undrawn(NpcSpawns.Group group)
    {
        WorldPoint c = group.center();
        // Checked and drawn by no map; or on no map at all, not even by region (going there would show black).
        return placeMaps.containsKey(c) ? placeMaps.get(c) == null : mapOf(group) == null;
    }

    private void look(NpcSpawns.Group group)
    {
        if (undrawn(group))
        {
            return;
        }
        BaseMap map = mapOf(group);
        WorldPoint center = group.center();
        if (map != null)
        {
            view.setFollowing(false);
            view.rememberForBack(map);
            // Where the map draws it: a place the map draws elsewhere (the Dagannoth Kings' lair) would otherwise put the
            // view at the edge of the map, in a corner.
            view.showMap(map, MapView.shownOn(map, center), Math.max(view.targetZoom(), 2));
        }
        else
        {
            view.focus(center);
        }
        // Its details in the card, with a route there: the map's own icon when there is one.
        view.select(iconOf(group, map));
    }

    /**
     * The icon whose details a place shows: its own (a patch, a bank), the map's shop icon beside a shop, else one
     * made for it (a monster's or item's spawn place) with what the card lists about it.
     */
    private Poi iconOf(NpcSpawns.Group group, BaseMap map)
    {
        if (group.poi != null)
        {
            return group.poi;
        }
        WorldPoint c = group.center();
        boolean shop = SHOPS.equals(group.category);
        if (shop)
        {
            Poi best = null;
            int bestDistance = SHOP_ICON_RADIUS + 1;
            for (Poi poi : view.searchExtras())
            {
                int d = poi.location.getPlane() == c.getPlane() ? PoiLoader.chebyshev(poi.location, c) : Integer.MAX_VALUE;
                if (poi.type == PoiType.SHOP && d < bestDistance)
                {
                    best = poi;
                    bestDistance = d;
                }
            }
            if (best != null)
            {
                return best;
            }
        }
        String title = result == null ? group.name : result.title;
        String name = placeName(group, title);
        List<String> facts = new ArrayList<>();
        if (!group.levels.isEmpty())
        {
            facts.add("Level " + group.levels);
        }
        if (group.note != null && !group.note.isEmpty())
        {
            facts.add(group.note);
        }
        else if (group.note == null)
        {
            facts.add(group.points.size() + (group.points.size() == 1 ? " spawn" : " spawns"));
        }
        String wiki = shop ? group.name : result != null && result.page != null ? result.page : group.name;
        return new Poi(shop ? PoiType.SHOP : PoiType.FOUND, name.equals(title) || shop ? name : title + ": " + name,
            c, map, null, Needs.NONE, wiki, null, facts.isEmpty() ? null : String.join(" · ", facts));
    }

    /** Shows one list of the result ("Dropped by"), as its button does. */
    void showTab(String tab)
    {
        if (result != null && result.tabs.containsKey(tab))
        {
            result.tab = tab;
            card.show(this::section);
            view.repaint();
        }
    }

    /** Goes to the listed place with a point at {@code at} (a kind's place picked in the search). */
    void lookAtPoint(WorldPoint at)
    {
        if (result == null || at == null)
        {
            return;
        }
        for (NpcSpawns.Group group : result.groups)
        {
            if (group.points.contains(at))
            {
                look(group);
                return;
            }
        }
    }

    /** Goes to the listed place of that number (of the list shown), as a click on it does. */
    void lookAt(int index)
    {
        if (result == null)
        {
            return;
        }
        int n = 0;
        for (NpcSpawns.Group group : result.groups)
        {
            if (result.shows(group) && n++ == index)
            {
                look(group);
                return;
            }
        }
    }

    /** How far the map's shop icon may be from where a shop's wiki page puts it. */
    private static final int SHOP_ICON_RADIUS = 12;

    /** Each place's map, worked out once per result and tile check, not in every frame. */
    private final java.util.Map<NpcSpawns.Group, java.util.Optional<BaseMap>> groupMaps = new java.util.IdentityHashMap<>();
    private java.util.Map<WorldPoint, BaseMap> groupMapsFor;
    private BaseMaps groupMapsFrom;

    private BaseMap mapOf(NpcSpawns.Group group)
    {
        BaseMaps maps = view.maps();
        if (groupMapsFor != placeMaps || groupMapsFrom != maps)
        {
            groupMaps.clear();
            groupMapsFor = placeMaps;
            groupMapsFrom = maps;
        }
        return groupMaps.computeIfAbsent(group, g -> java.util.Optional.ofNullable(mapOf(g, maps, placeMaps)))
            .orElse(null);
    }

    /** Each place's bounds (min x, min y, max x, max y), for skipping those out of view. */
    private final java.util.Map<NpcSpawns.Group, int[]> groupBounds = new java.util.IdentityHashMap<>();

    private int[] bounds(NpcSpawns.Group group)
    {
        return groupBounds.computeIfAbsent(group, g -> {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (WorldPoint p : g.points)
            {
                b[0] = Math.min(b[0], p.getX());
                b[1] = Math.min(b[1], p.getY());
                b[2] = Math.max(b[2], p.getX());
                b[3] = Math.max(b[3], p.getY());
            }
            return b;
        });
    }

    /** The map a place opens on: the one the tile check found, else the one its region belongs to, else its map id. */
    static BaseMap mapOf(NpcSpawns.Group group, BaseMaps maps, java.util.Map<WorldPoint, BaseMap> placeMaps)
    {
        if (maps == null)
        {
            return null;
        }
        // By where the spawns are: the wiki's map ids on these pages do not always match its map list.
        WorldPoint c = group.center();
        BaseMap checked = placeMaps.get(c);
        if (checked != null || placeMaps.containsKey(c))
        {
            return checked;
        }
        BaseMap found = maps.find(c);
        if (found != null)
        {
            return found;
        }
        return group.mapId >= 0 ? maps.byId(group.mapId) : null;
    }

    /** A green arrow with its tip at {@code (x, y)}, pointing down; {@code r} sets its size. */
    static java.awt.geom.Path2D arrow(double x, double y, double r)
    {
        java.awt.geom.Path2D arrow = new java.awt.geom.Path2D.Double();
        arrow.moveTo(x, y);
        arrow.lineTo(x - r * 1.2, y - r * 1.4);
        arrow.lineTo(x - r * 0.45, y - r * 1.4);
        arrow.lineTo(x - r * 0.45, y - r * 2.6);
        arrow.lineTo(x + r * 0.45, y - r * 2.6);
        arrow.lineTo(x + r * 0.45, y - r * 1.4);
        arrow.lineTo(x + r * 1.2, y - r * 1.4);
        arrow.closePath();
        return arrow;
    }

    /** A small green arrow, for monsters in lists and the search button. */
    static java.awt.image.BufferedImage dotIcon()
    {
        return arrowIcon(DOT);
    }

    /** A small gold arrow, for items in lists and the search button. */
    static java.awt.image.BufferedImage itemIcon()
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(14, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        // A little sack: a round body with a tied neck.
        g.setColor(SHOP);
        g.fill(new java.awt.geom.Ellipse2D.Double(1.5, 4.5, 11, 9));
        g.fill(new java.awt.geom.Rectangle2D.Double(5, 2, 4, 4));
        g.setColor(new Color(90, 60, 10));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Ellipse2D.Double(1.5, 4.5, 11, 9));
        g.draw(new java.awt.geom.Line2D.Double(4.5, 5, 9.5, 5));
        g.dispose();
        return image;
    }

    private static java.awt.image.BufferedImage arrowIcon(Color color)
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(14, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.geom.Path2D arrow = arrow(7, 13, 4.4);
        g.setColor(color);
        g.fill(arrow);
        g.setColor(DOT_EDGE);
        g.draw(arrow);
        g.dispose();
        return image;
    }

    // ---- the card ----

    private JComponent section(int width)
    {
        dropIcons.clear();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (loading != null)
        {
            panel.add(text("Looking up " + loading + " on the wiki…", Color.WHITE, true, width));
            return panel;
        }
        if (result == null)
        {
            panel.add(text(failed != null ? failed : "Nothing found.", ColorScheme.LIGHT_GRAY_COLOR, false, width));
            panel.add(Box.createVerticalStrut(6));
            panel.add(InfoCard.wrap(buttons(null), width));
            return panel;
        }
        JLabel title = text(result.title, Color.WHITE, true, width - 70);
        if (image != null)
        {
            // The monster's or item's picture beside its name.
            JPanel head = new JPanel(new java.awt.BorderLayout(8, 0));
            head.setOpaque(false);
            head.setAlignmentX(Component.LEFT_ALIGNMENT);
            head.add(new JLabel(new javax.swing.ImageIcon(image)), java.awt.BorderLayout.WEST);
            head.add(title, java.awt.BorderLayout.CENTER);
            panel.add(head);
        }
        else
        {
            panel.add(title);
        }
        panel.add(text(result.summary, ColorScheme.LIGHT_GRAY_COLOR, false, width));
        panel.add(Box.createVerticalStrut(6));
        panel.add(InfoCard.wrap(buttons(result.page), width));
        panel.add(Box.createVerticalStrut(4));
        if (!result.tabs.isEmpty())
        {
            panel.add(Box.createVerticalStrut(4));
            panel.add(tabs(result));
        }
        String category = null;
        int shown = 0;
        List<NpcSpawns.Group> listed = new ArrayList<>();
        for (NpcSpawns.Group group : result.groups)
        {
            if (result.shows(group))
            {
                listed.add(group);
            }
        }
        for (NpcSpawns.Group group : listed)
        {
            if (!allPlaces && shown == SHOWN)
            {
                int more = listed.size() - SHOWN;
                panel.add(Box.createVerticalStrut(4));
                panel.add(link("Show all " + listed.size() + " places (" + more + " more)", () -> {
                    allPlaces = true;
                    card.show(this::section);
                }, width));
                break;
            }
            if (result.tabs.isEmpty() && group.category != null && !group.category.equals(category))
            {
                category = group.category;
                panel.add(Box.createVerticalStrut(6));
                panel.add(heading(category, width));
            }
            panel.add(Box.createVerticalStrut(4));
            panel.add(place(group, width));
            shown++;
        }
        if (result.extra != null)
        {
            panel.add(result.extra.apply(width));
        }
        if (result.credit != null)
        {
            panel.add(Box.createVerticalStrut(10));
            panel.add(text(result.credit, ColorScheme.MEDIUM_GRAY_COLOR, false, width));
        }
        return panel;
    }

    /**
     * The picked list's part beside the places: the shops the wiki gives no place for, or the monsters and other
     * sources dropping the item.
     */
    private JComponent itemExtra(List<ItemSources.Store> unplaced, List<ItemSources.Drop> drops, int width)
    {
        String tab = result == null ? null : result.tab;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (SHOPS.equals(tab) && !unplaced.isEmpty())
        {
            panel.add(Box.createVerticalStrut(8));
            panel.add(heading("Shops not on the map", width));
            for (ItemSources.Store store : unplaced)
            {
                panel.add(Box.createVerticalStrut(4));
                panel.add(link(store.shop, () -> LinkBrowser.browse(WikiClient.pageUrl(store.shop)), width));
                panel.add(text(storeNote(store), ColorScheme.LIGHT_GRAY_COLOR, false, width));
            }
        }
        List<ItemSources.Drop> npcs = new ArrayList<>();
        List<ItemSources.Drop> other = new ArrayList<>();
        for (ItemSources.Drop drop : drops)
        {
            (drop.npc ? npcs : other).add(drop);
        }
        if (DROPS.equals(tab))
        {
            dropList(panel, npcs, true, width);
        }
        else if (OTHER.equals(tab))
        {
            dropList(panel, other, false, width);
        }
        return panel;
    }

    /** Pictures of the monsters and other sources dropping the item shown, by page, as they arrive. */
    private final java.util.Map<String, java.awt.image.BufferedImage> dropPictures = new java.util.LinkedHashMap<String,
        java.awt.image.BufferedImage>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, java.awt.image.BufferedImage> eldest)
        {
            // The pictures of the last few items looked up, not of every one since the plugin started.
            return size() > MAX_DROP_PICTURES;
        }
    };
    private static final int MAX_DROP_PICTURES = 200;

    private void loadDropPictures(List<ItemSources.Drop> drops)
    {
        List<String> pages = new ArrayList<>();
        for (ItemSources.Drop drop : drops)
        {
            if (!dropPictures.containsKey(drop.monster) && !pages.contains(drop.monster) && pages.size() < 50)
            {
                pages.add(drop.monster);
            }
        }
        wiki.pageImages(pages, 64, (page, image) -> SwingUtilities.invokeLater(() -> {
            if (image != null)
            {
                dropPictures.put(page, image);
                // Into the row already shown: rebuilding the whole card for each picture made the map stutter.
                JLabel shown = dropIcons.get(page);
                if (shown != null)
                {
                    shown.setIcon(fitted(image));
                }
            }
        }));
    }

    /** Room for a picture in a row. */
    private static final int PICTURE = 36;
    /** The picture squares of the rows shown, by page, to fill in when a picture arrives. */
    private final java.util.Map<String, JLabel> dropIcons = new java.util.HashMap<>();

    /** A picture made to fit its square, never enlarged. */
    static javax.swing.Icon fitted(java.awt.image.BufferedImage picture)
    {
        double k = Math.min(1.0, (PICTURE - 2.0) / Math.max(picture.getWidth(), picture.getHeight()));
        return new javax.swing.ImageIcon(k >= 1 ? picture : picture.getScaledInstance(
            (int) Math.round(picture.getWidth() * k), (int) Math.round(picture.getHeight() * k),
            java.awt.Image.SCALE_SMOOTH));
    }

    /** A row with a picture (or an empty square while it loads) on the left and the words beside it. */
    private JComponent pictureRow(String key, java.awt.image.BufferedImage picture, JComponent name, JComponent facts)
    {
        JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = new JLabel();
        icon.setPreferredSize(new java.awt.Dimension(PICTURE, PICTURE));
        icon.setHorizontalAlignment(JLabel.CENTER);
        icon.setVerticalAlignment(JLabel.TOP);
        icon.setOpaque(true);
        icon.setBackground(new Color(255, 255, 255, 12));
        if (picture != null)
        {
            icon.setIcon(fitted(picture));
        }
        dropIcons.put(key, icon);
        JPanel iconBox = new JPanel(new java.awt.BorderLayout());
        iconBox.setOpaque(false);
        iconBox.add(icon, java.awt.BorderLayout.NORTH);
        row.add(iconBox, java.awt.BorderLayout.WEST);
        JPanel words = new JPanel();
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        words.setOpaque(false);
        words.add(name);
        words.add(facts);
        row.add(words, java.awt.BorderLayout.CENTER);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Math.max(PICTURE, row.getPreferredSize().height)));
        return row;
    }

    /**
     * Drop sources under a heading, the likeliest first: monsters link to their places (with Back to the item),
     * other sources (chests, packs, rocks) to their wiki page.
     */
    private void dropList(JPanel panel, List<ItemSources.Drop> drops, boolean npcs, int width)
    {
        if (drops.isEmpty())
        {
            return;
        }
        panel.add(Box.createVerticalStrut(4));
        panel.add(text(npcs ? "The likeliest first. Click one to see where it is." : "Chests, packs, rocks and other "
            + "sources, the likeliest first.", ColorScheme.LIGHT_GRAY_COLOR, false, width));
        int shown = 0;
        for (ItemSources.Drop drop : drops)
        {
            if (!allDrops && shown == SHOWN)
            {
                panel.add(Box.createVerticalStrut(4));
                panel.add(link("Show all " + drops.size() + " (" + (drops.size() - SHOWN) + " more)", () -> {
                    allDrops = true;
                    card.show(this::section);
                }, width));
                break;
            }
            panel.add(Box.createVerticalStrut(5));
            int textWidth = width - PICTURE - 8;
            JLabel name = link(drop.monster, npcs ? () -> {
                // Where that monster is: its own search, with Back to this item.
                previous = result;
                lookUpMonster(drop.monster);
            } : () -> LinkBrowser.browse(WikiClient.pageUrl(drop.monster)), textWidth);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            List<String> facts = new ArrayList<>();
            if (!drop.how.isEmpty())
            {
                facts.add(drop.how);
            }
            facts.add(String.join(", ", drop.lines));
            panel.add(pictureRow(drop.monster, dropPictures.get(drop.monster), name,
                text(String.join(" · ", facts), ColorScheme.LIGHT_GRAY_COLOR, false, textWidth)));
            shown++;
        }
    }

    /** The lists to pick from, two to a row, each with how many it has; empty ones cannot be picked. */
    private JComponent tabs(Result shown)
    {
        JPanel row = new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        for (java.util.Map.Entry<String, Integer> tab : shown.tabs.entrySet())
        {
            javax.swing.JToggleButton button = new javax.swing.JToggleButton(tab.getKey() + " (" + tab.getValue() + ")",
                tab.getKey().equals(shown.tab));
            button.setFocusable(false);
            button.setMargin(new java.awt.Insets(2, 4, 2, 4));
            button.setEnabled(tab.getValue() > 0);
            button.addActionListener(e -> {
                shown.tab = tab.getKey();
                allPlaces = false;
                allDrops = false;
                card.show(this::section);
                view.repaint();
            });
            group.add(button);
            row.add(button);
        }
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** One place: its name as a link, and below it in grey the levels, spawns or note, members and which map. */
    private JComponent place(NpcSpawns.Group group, int width)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        String where = placeName(group, result.title);
        boolean undrawn = undrawn(group);
        JLabel name = undrawn ? text(where, Color.WHITE, true, width) : link(where, () -> look(group), width);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        row.add(name);
        if (undrawn)
        {
            row.add(text("Not on the wiki map yet", new Color(255, 190, 120), false, width));
        }
        List<String> facts = new ArrayList<>();
        if (!group.levels.isEmpty())
        {
            facts.add("Level " + group.levels);
        }
        if (group.note != null)
        {
            if (!group.note.isEmpty())
            {
                facts.add(group.note);
            }
        }
        else
        {
            facts.add(group.points.size() + (group.points.size() == 1 ? " spawn" : " spawns"));
        }
        if (group.members)
        {
            facts.add("Members");
        }
        BaseMap map = mapOf(group);
        if (map != null && map.id != BaseMap.SURFACE)
        {
            facts.add(map.name);
        }
        if (group.points.get(0).getPlane() > 0)
        {
            facts.add("Floor " + group.points.get(0).getPlane());
        }
        if (!facts.isEmpty())
        {
            row.add(text(String.join(" · ", facts), ColorScheme.LIGHT_GRAY_COLOR, false, width));
        }
        return row;
    }

    /** "Lava Maze"; with the group's own name when it differs from the title ("Hill Giant – Lava Maze"). */
    static String placeName(NpcSpawns.Group group, String title)
    {
        if (group.location.isEmpty())
        {
            return group.name.isEmpty() ? "Unnamed place" : group.name;
        }
        if (group.name.equalsIgnoreCase(title) || group.name.equalsIgnoreCase(group.location))
        {
            return group.location;
        }
        return group.name + " – " + group.location;
    }

    private JPanel buttons(String page)
    {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        Result back = previous;
        if (back != null)
        {
            buttons.add(button("Back", () -> {
                previous = null;
                request++;
                show(back);
            }));
            buttons.add(Box.createHorizontalStrut(4));
        }
        if (result != null && result.kind != null && stopAdder != null)
        {
            String kind = result.kind;
            JButton add = button("+ Stop", () -> stopAdder.accept(Tour.Stop.kind(kind)));
            add.setToolTipText("Add \"the nearest of these\" to your custom route");
            buttons.add(add);
            buttons.add(Box.createHorizontalStrut(4));
        }
        NpcSpawns.Group closest = closest();
        if (closest != null)
        {
            JButton near = button("Closest to me", () -> look(closest));
            near.setToolTipText("Go to the place nearest to you");
            buttons.add(near);
            buttons.add(Box.createHorizontalStrut(4));
        }
        if (page != null)
        {
            buttons.add(button("Wiki", () -> LinkBrowser.browse(WikiClient.pageUrl(page))));
            buttons.add(Box.createHorizontalStrut(4));
        }
        buttons.add(button("Clear", this::clear));
        return buttons;
    }

    /**
     * The shown place nearest the player (on the same floor first, as the crow flies), that a map draws; null
     * without a player or places.
     */
    private NpcSpawns.Group closest()
    {
        WorldPoint me = view.player();
        if (me == null || result == null)
        {
            return null;
        }
        List<NpcSpawns.Group> shown = new ArrayList<>();
        for (NpcSpawns.Group group : result.groups)
        {
            if (result.shows(group) && !undrawn(group))
            {
                shown.add(group);
            }
        }
        return closest(shown, me);
    }

    /** The place with a point nearest {@code me}: any point of it counts, not only its middle. */
    static NpcSpawns.Group closest(List<NpcSpawns.Group> groups, WorldPoint me)
    {
        NpcSpawns.Group best = null;
        long bestDistance = Long.MAX_VALUE;
        for (NpcSpawns.Group group : groups)
        {
            for (WorldPoint p : group.points)
            {
                long d = distance(p, me);
                if (d < bestDistance)
                {
                    best = group;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    private static JButton button(String text, Runnable action)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JLabel heading(String text, int width)
    {
        JLabel label = text(text, Color.WHITE, true, width);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 255, 255, 40)),
            BorderFactory.createEmptyBorder(1, 0, 2, 0)));
        return label;
    }

    private static JLabel text(String text, Color color, boolean bold, int width)
    {
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + InfoCard.escape(text) + "</div></html>");
        label.setForeground(color);
        if (bold)
        {
            label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1));
        }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return label;
    }

    private static JLabel link(String text, Runnable action, int width)
    {
        JLabel label = text(text, LINK, false, width);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                action.run();
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                label.setForeground(Color.WHITE);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                label.setForeground(LINK);
            }
        });
        return label;
    }

    // ---- the map ----

    private static final BasicStroke EDGE = new BasicStroke(1.2f);
    /** Arrows and labels as pictures, made once each: drawing hundreds of shapes and texts every frame was slow. */
    private final java.util.Map<String, java.awt.image.BufferedImage> sprites = new java.util.HashMap<>();
    private final java.util.Map<NpcSpawns.Group, WorldPoint> centers = new java.util.IdentityHashMap<>();

    private WorldPoint center(NpcSpawns.Group group)
    {
        if (centers.size() > 5000)
        {
            centers.clear();
        }
        return centers.computeIfAbsent(group, NpcSpawns.Group::center);
    }

    /** A place's label picture as last drawn: made again only when its colour or the screen's scale changes. */
    private static final class Tag
    {
        final Color color;
        final double device;
        final java.awt.image.BufferedImage image;

        Tag(Color color, double device, java.awt.image.BufferedImage image)
        {
            this.color = color;
            this.device = device;
            this.image = image;
        }
    }

    /** The label picture of each place, so no text or key is put together for every place in every frame. */
    private final java.util.Map<NpcSpawns.Group, Tag> tags = new java.util.IdentityHashMap<>();
    /** The arrows by colour, size and screen scale packed in one number (see {@link #arrowSprite}). */
    private final java.util.Map<Long, java.awt.image.BufferedImage> arrows = new java.util.HashMap<>();

    private java.awt.image.BufferedImage groupTag(NpcSpawns.Group group, Color color, FontMetrics metrics, double device)
    {
        Tag tag = tags.get(group);
        if (tag == null || !tag.color.equals(color) || tag.device != device)
        {
            String label = (group.location.isEmpty() ? group.name : group.location)
                + (group.levels.isEmpty() ? "" : " (" + group.levels + ")");
            tag = new Tag(color, device, labelSprite(label, color, metrics, device));
            tags.put(group, tag);
        }
        return tag.image;
    }

    /** An arrow of a colour and size, tip at the bottom middle, 2 pixels above the picture's edge. */
    private java.awt.image.BufferedImage arrowSprite(Color fill, double r, double device)
    {
        long key = (long) fill.getRGB() << 32 | (Math.round(r * 4) & 0xffffL) << 16 | Math.round(device * 10) & 0xffffL;
        if (arrows.size() > 500)
        {
            arrows.clear();
        }
        return arrows.computeIfAbsent(key, k -> {
            double w = r * 2.4 + 4;
            double h = r * 2.6 + 4;
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) Math.ceil(w * device),
                (int) Math.ceil(h * device), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(device, device);
            java.awt.geom.Path2D arrow = arrow(w / 2, h - 2, r);
            g.setColor(fill);
            g.fill(arrow);
            g.setColor(DOT_EDGE);
            g.setStroke(EDGE);
            g.draw(arrow);
            g.dispose();
            return image;
        });
    }

    /** A place's label: white text in a dark box with an edge of its colour, with a pixel of room around. */
    private java.awt.image.BufferedImage labelSprite(String label, Color color, FontMetrics metrics, double device)
    {
        String key = "l" + color.getRGB() + "/" + Math.round(device * 10) + "/" + label;
        return sprites.computeIfAbsent(key, k -> {
            int width = metrics.stringWidth(label) + 10;
            int height = metrics.getHeight() + 2;
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) Math.ceil((width + 2) * device),
                (int) Math.ceil((height + 2) * device), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(device, device);
            g.setFont(LABEL_FONT);
            RoundRectangle2D box = new RoundRectangle2D.Double(1, 1, width, height, 8, 8);
            g.setColor(LABEL_BACKGROUND);
            g.fill(box);
            g.setColor(color);
            g.draw(box);
            g.setColor(Color.WHITE);
            g.drawString(label, 6f, (float) (1 + metrics.getHeight() - metrics.getDescent()));
            g.dispose();
            return image;
        });
    }
    private static final java.util.Map<Color, Color> FADED = new java.util.concurrent.ConcurrentHashMap<>();

    /** A colour see-through, for places on another floor. */
    private static Color faded(Color color)
    {
        return FADED.computeIfAbsent(color, c -> new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
    }

    @Override
    public void paint(Graphics2D g, MapView.Projection projection)
    {
        Result shown = result;
        BaseMap viewMap = projection.map();
        if (shown == null || viewMap == null)
        {
            return;
        }
        double zoom = projection.zoom();
        // Arrows grow a little when zoomed in, so a spawn tile stays easy to spot.
        double r = zoom < -1 ? 3 : zoom < 2 ? 4.5 : 4.5 + Math.min(4, (zoom - 2) * 1.5);
        g.setFont(LABEL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        double device = Math.max(1, Math.min(4, Math.abs(g.getTransform().getScaleX())));
        if (sprites.size() > 3000)
        {
            sprites.clear();
        }
        if (groupBounds.size() > 5000)
        {
            groupBounds.clear();
        }
        if (tags.size() > 5000)
        {
            tags.clear();
        }
        for (NpcSpawns.Group group : shown.groups)
        {
            // Out of view (with room for the arrows and the label above): nothing to work out.
            int[] b = bounds(group);
            // A place the map draws elsewhere (the Kalphite Lair) is shown on that drawing: the whole group moves
            // as its first point does.
            WorldPoint first = group.points.get(0);
            WorldPoint moved = projection.shown(first);
            int dx = moved.getX() - first.getX();
            int dy = moved.getY() - first.getY();
            double left = projection.screenX(b[0] + dx);
            double right = projection.screenX(b[2] + 1 + dx);
            double top = projection.screenY(b[3] + 1 + dy);
            double bottom = projection.screenY(b[1] + dy);
            if (right < -150 || left > projection.width() + 150 || bottom < -20 || top > projection.height() + 60)
            {
                continue;
            }
            BaseMap map = mapOf(group);
            if (!shown.shows(group) || map == null || viewMap.id != BaseMap.FULL && map != viewMap)
            {
                continue;
            }
            Color color = group.color != null ? group.color : DOT;
            boolean otherFloor = moved.getPlane() != projection.plane();
            Color fill = otherFloor ? faded(color) : color;
            java.awt.image.BufferedImage arrow = arrowSprite(fill, r, device);
            double half = arrow.getWidth() / device / 2;
            double tall = arrow.getHeight() / device - 2;
            for (WorldPoint p : group.points)
            {
                double x = projection.screenX(p.getX() + dx + 0.5);
                double y = projection.screenY(p.getY() + dy + 0.5);
                if (x < -r * 2 || y < -r || x > projection.width() + r * 2 || y > projection.height() + r * 3)
                {
                    continue;
                }
                // An arrow pointing down at the tile, from a picture made once.
                PoiIcons.blit(g, arrow, x - half, y - tall);
            }
            if (zoom < -1.5)
            {
                continue;
            }
            // One label per place, above its middle point.
            WorldPoint c = center(group);
            double x = projection.screenX(c.getX() + dx + 0.5);
            double y = projection.screenY(c.getY() + dy + 0.5) - r * 3 - 4;
            java.awt.image.BufferedImage tag = groupTag(group, color, metrics, device);
            double width = tag.getWidth() / device;
            if (x + width / 2.0 < 0 || x - width / 2.0 > projection.width() || y < -20 || y > projection.height() + 20)
            {
                continue;
            }
            PoiIcons.blit(g, tag, x - width / 2.0 - 1, y - metrics.getHeight() - 1);
        }
    }
}
