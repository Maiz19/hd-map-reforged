package com.hdmapreforged;

import java.util.concurrent.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.*;
import javax.swing.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

/** What a search found (a monster, an item's sources, a kind of place), marked on the map and listed. Swing thread. */
final class SearchResults implements MapView.Overlay
{
    interface Card
    {
        void show(IntFunction<JComponent> section);

        /** A background answer (a picture, a tile check): re-render only while this search is what is shown. */
        default void update(IntFunction<JComponent> section)
        {
            show(section);
        }
    }

    static final class Result
    {
        final String title;
        final String page;
        final String summary;
        final List<NpcSpawns.Group> groups;
        final IntFunction<JComponent> extra;
        final String credit;
        String kind;
        final LinkedHashMap<String, Integer> tabs = new LinkedHashMap<>();
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
    static final Color SHOP = new Color(255, 196, 60);
    private static final Color DOT_EDGE = new Color(0, 50, 10, 230);
    private static final Color LABEL_BACKGROUND = new Color(16, 16, 20, 215);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;
    static final int SHOWN = 25;

    private final MapView view;
    private final WikiClient wiki;
    private final Card card;
    private BiConsumer<List<WorldPoint>, Consumer<Map<WorldPoint, BaseMap>>>
        regionCheck = (points, done) -> done.accept(Collections.emptyMap());
    private Map<WorldPoint, BaseMap> placeMaps = Collections.emptyMap();
    private Result result;
    private Result previous;
    private String loading;
    /** Counts lookups, so an answer to an older one is dropped. */
    private int request;
    private String failed;
    private BufferedImage image;
    private boolean allPlaces;
    private boolean allDrops;

    SearchResults(MapView view, WikiClient wiki, Card card)
    {
        this.view = view;
        this.wiki = wiki;
        this.card = card;
        view.addOverlay(this);
    }

    private Consumer<Tour.Stop> stopAdder;

    void setStopAdder(Consumer<Tour.Stop> stopAdder)
    {
        this.stopAdder = stopAdder;
    }

    void setRegionCheck(BiConsumer<List<WorldPoint>, Consumer<Map<WorldPoint, BaseMap>>> regionCheck)
    {
        this.regionCheck = regionCheck;
    }

    void findMonster(String name)
    {
        previous = null;
        lookUpMonster(name);
    }

    private void lookUpMonster(String name)
    {
        int id = startLoading(name);
        // Read on the Swing thread; the result is worked out off it (slow), and these lists are replaced, never changed.
        BaseMaps maps = view.maps();
        List<PoiLoader.Place> labels = view.labels();
        List<Poi> pois = view.pois();
        wiki.spawns(name, found -> {
            Result shown = found == null ? null : monsterResult(found, maps, labels, pois);
            String empty = found != null && shown == null ? "The wiki page \"" + found.page + "\" lists no locations."
                : null;
            SwingUtilities.invokeLater(() -> answer(id, found, empty, () -> show(shown)));
        });
    }

    /** A wiki answer, unless a newer lookup started; {@code empty}: why it shows nothing. */
    private void answer(int id, Object found, String empty, Runnable show)
    {
        if (id != request)
        {
            return;
        }
        if (found == null || empty != null)
        {
            fail(found == null ? "The wiki could not be reached." : empty);
        }
        else
        {
            show.run();
        }
    }

    /** A monster's spawns; when none is on a visible map (instanced boss), first the place to go. Pure. */
    static Result monsterResult(NpcSpawns found, BaseMaps maps, List<PoiLoader.Place> labels, List<Poi> pois)
    {
        String spawns = found.spawnCount() + " spawns in " + places(found.groups.size());
        if (found.groups.stream().map(NpcSpawns.Group::center)
            .anyMatch(c -> maps == null || maps.find(c.getX(), c.getY()) != null))
        {
            return new Result(found.page, found.page, spawns + ", marked with green arrows. Click a place to go there.",
                found.groups, null, null);
        }
        PlaceLookup.Found place = PlaceLookup.boss(found.page);
        if (place == null)
        {
            place = PlaceLookup.find(found.mentioned, maps, labels, pois);
        }
        if (place == null)
        {
            return found.groups.isEmpty() ? null : new Result(found.page, found.page,
                spawns + ", only on the wiki's map of everything.", found.groups, null, null);
        }
        List<NpcSpawns.Group> groups = new ArrayList<>();
        groups.add(new NpcSpawns.Group(place.name, place.name, "", false, -1, Collections.singletonList(place.point), null,
            "Where to go", null));
        groups.addAll(found.groups);
        return new Result(found.page, found.page, "The wiki shows no spawns on a map of their own (fought in an "
            + "instance, or found on the pages of its kinds). First is where to go: " + place.name + ".", groups, null,
            null);
    }

    void findItem(String name)
    {
        previous = null;
        int id = startLoading(name);
        wiki.item(name, found -> SwingUtilities.invokeLater(() -> answer(id, found, found != null && found.isEmpty()
            ? "The wiki lists no spawns, shops with stock, or drops for \"" + found.page + "\"." : null, () -> {
                show(itemResult(found, view.near()));
                loadDropPictures(found.drops);
            })));
    }

    void showKind(KindIndex.Kind kind, Function<WorldPoint, String> placeName)
    {
        previous = null;
        request++;
        List<NpcSpawns.Group> groups = kindGroups(kind, placeName, view.near());
        Result shown = new Result(kind.label, kind.page, places(groups.size()) + ", marked with green arrows" + (view.near() != null ? ", nearest first" : "") + ". Click one to go there.",
            groups, null, kind.type == null ? "Skilling spots: RuneLite (BSD 2-Clause)" : null);
        shown.kind = kind.label;
        show(shown);
    }

    static final int TOGETHER = 24;

    /** A kind's places, those close together in one town merged ("Catherby, 4 spots"). */
    static List<NpcSpawns.Group> kindGroups(KindIndex.Kind kind, Function<WorldPoint, String> placeName, WorldPoint player)
    {
        List<NpcSpawns.Group> groups = new ArrayList<>();
        entries:
        for (KindIndex.Entry entry : kind.entries)
        {
            String place = placeName.apply(entry.point);
            place = place == null ? "" : place;
            String name = kind.type == null && !place.isEmpty() ? place : entry.name;
            for (NpcSpawns.Group group : groups)
            {
                WorldPoint first = group.points.get(0);
                if (!place.isEmpty() && group.location.equals(place) && group.name.equals(name)
                    && first.getPlane() == entry.point.getPlane() && Math.abs(first.getX() - entry.point.getX()) <= TOGETHER
                    && Math.abs(first.getY() - entry.point.getY()) <= TOGETHER)
                {
                    group.points.add(entry.point);
                    continue entries;
                }
            }
            // A note, even an empty one, instead of "1 spawn".
            NpcSpawns.Group group = new NpcSpawns.Group(name, place, "", false, -1,
                new ArrayList<>(Collections.singletonList(entry.point)), null, entry.detail == null ? "" : entry.detail,
                null);
            group.poi = entry.poi;
            groups.add(group);
        }
        groups.replaceAll(g -> {
            int n = g.points.size();
            NpcSpawns.Group group = n > 1 ? g.as(null, n + " spots" + (g.note.isEmpty() ? "" : " · " + g.note), null) : g;
            group.poi = g.poi;
            return group;
        });
        return nearestFirst(groups, player);
    }

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
        int npcs = (int) found.drops.stream().filter(drop -> drop.npc).count();
        Result item = new Result(found.page, found.page, "Where to get it. Pick a list: the map marks only that one "
            + "(spawns green, shops gold).", groups, width -> itemExtra(unplaced, found.drops, width),
            null);
        item.tabs.put(SPAWNS, found.spawns.stream().mapToInt(spawn -> spawn.points.size()).sum());
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

    static List<NpcSpawns.Group> nearestFirst(List<NpcSpawns.Group> groups, WorldPoint player)
    {
        List<NpcSpawns.Group> sorted = new ArrayList<>(groups);
        if (player != null)
        {
            sorted.sort(Comparator.comparingLong(g -> distance(g.center(), player)));
        }
        return sorted;
    }

    private static String places(int n)
    {
        return n + (n == 1 ? " place" : " places");
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
        refresh();
        return request;
    }

    private void fail(String message)
    {
        loading = null;
        failed = message;
        refresh();
    }

    private void refresh()
    {
        card.show(this::section);
        view.repaint();
    }

    void show(Result found)
    {
        loading = null;
        failed = null;
        placeMaps = Collections.emptyMap();
        checkedCenters.clear();
        tags.clear();
        centers.clear();
        groupBounds.clear();
        groupMaps.clear();
        result = found;
        if (found.tab == null)
        {
            found.tab = found.tabs.entrySet().stream().filter(tab -> tab.getValue() > 0).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        }
        image = null;
        allPlaces = false;
        allDrops = false;
        if (found.page != null)
        {
            wiki.pageImage(found.page, 56, loaded -> SwingUtilities.invokeLater(() -> {
                if (result == found)
                {
                    image = loaded;
                    card.update(this::section);
                }
            }));
        }
        refresh();
        checkListed();
    }

    /** Centres already sent to the tile check for the current result. */
    private final Set<WorldPoint> checkedCenters = new HashSet<>();

    /**
     * Sends the listed places of the current result (the tab's first {@link #SHOWN}, or all once asked for) not yet
     * checked to the tile check; an answer for a result no longer shown is dropped.
     */
    private void checkListed()
    {
        Result found = result;
        if (found == null)
        {
            return;
        }
        List<WorldPoint> points = found.groups.stream().filter(found::shows).limit(allPlaces ? Long.MAX_VALUE : SHOWN)
            .map(NpcSpawns.Group::center).filter(checkedCenters::add).collect(Collectors.toList());
        if (points.isEmpty())
        {
            return;
        }
        int id = request;
        regionCheck.accept(points, maps -> {
            if (result == found && id == request && !maps.isEmpty())
            {
                Map<WorldPoint, BaseMap> merged = new HashMap<>(placeMaps);
                merged.putAll(maps);
                placeMaps = merged;
                card.update(this::section);
                view.repaint();
            }
        });
    }

    void clear()
    {
        result = null;
        previous = null;
        loading = null;
        failed = null;
        request++;
        placeMaps = Collections.emptyMap();
        checkedCenters.clear();
        tags.clear();
        centers.clear();
        groupBounds.clear();
        groupMaps.clear();
        card.show(null);
        view.repaint();
    }

    /** Whether no wiki map draws this place yet (a new dungeon): going there would show only black. */
    private boolean undrawn(NpcSpawns.Group group)
    {
        WorldPoint c = group.center();
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
            // Where the map draws it: a place drawn elsewhere (Dagannoth Kings' lair) would otherwise land in a corner.
            view.showMap(map, MapView.shownOn(map, center), Math.max(view.targetZoom(), 2));
        }
        else
        {
            view.focus(center);
        }
        view.select(iconOf(group, map));
    }

    private Poi iconOf(NpcSpawns.Group group, BaseMap map)
    {
        if (group.poi != null)
        {
            return group.poi;
        }
        WorldPoint c = group.center();
        boolean shop = SHOPS.equals(group.category);
        Optional<Poi> icon = view.searchExtras().stream().filter(poi -> shop && poi.type == PoiType.SHOP
            && poi.location.getPlane() == c.getPlane() && PoiLoader.chebyshev(poi.location, c) <= SHOP_ICON_RADIUS)
            .min(Comparator.comparingInt(poi -> PoiLoader.chebyshev(poi.location, c)));
        if (icon.isPresent())
        {
            return icon.get();
        }
        String title = result == null ? group.name : result.title;
        String name = placeName(group, title);
        List<String> facts = facts(group);
        String wiki = shop ? group.name : result != null && result.page != null ? result.page : group.name;
        return new Poi(shop ? PoiType.SHOP : PoiType.FOUND, name.equals(title) || shop ? name : title + ": " + name,
            c, map, null, Needs.NONE, wiki, null, facts.isEmpty() ? null : String.join(" · ", facts));
    }

    void lookAtPoint(WorldPoint at)
    {
        if (result != null && at != null)
        {
            result.groups.stream().filter(group -> group.points.contains(at)).findFirst().ifPresent(this::look);
        }
    }

    void lookAt(int index)
    {
        if (result != null && index >= 0)
        {
            result.groups.stream().filter(result::shows).skip(index).findFirst().ifPresent(this::look);
        }
    }

    private static final int SHOP_ICON_RADIUS = 12;

    /** Each place's map, worked out once per result and tile check, not in every frame. */
    private final Map<NpcSpawns.Group, Optional<BaseMap>> groupMaps = new IdentityHashMap<>();
    private Map<WorldPoint, BaseMap> groupMapsFor;
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
        return groupMaps.computeIfAbsent(group, g -> Optional.ofNullable(mapOf(g, maps, placeMaps))).orElse(null);
    }

    private final Map<NpcSpawns.Group, int[]> groupBounds = new IdentityHashMap<>();

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

    static BaseMap mapOf(NpcSpawns.Group group, BaseMaps maps, Map<WorldPoint, BaseMap> placeMaps)
    {
        if (maps == null)
        {
            return null;
        }
        // By where the spawns are: the wiki's map ids on these pages do not always match its map list.
        WorldPoint c = group.center();
        if (placeMaps.containsKey(c))
        {
            return placeMaps.get(c);
        }
        BaseMap found = maps.find(c);
        return found != null || group.mapId < 0 ? found : maps.byId(group.mapId);
    }

    /** The corners after the tip, in radii: right, up. */
    private static final double[][] ARROW = {{-1.2, 1.4}, {-0.45, 1.4}, {-0.45, 2.6}, {0.45, 2.6}, {0.45, 1.4},
        {1.2, 1.4}};

    static Path2D arrow(double x, double y, double r)
    {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(x, y);
        for (double[] corner : ARROW)
        {
            arrow.lineTo(x + r * corner[0], y - r * corner[1]);
        }
        arrow.closePath();
        return arrow;
    }

    static BufferedImage dotIcon()
    {
        return sprite(14, 14, 1, g -> paintArrow(g, arrow(7, 13, 4.4), DOT, new BasicStroke()));
    }

    static BufferedImage itemIcon()
    {
        return sprite(14, 14, 1, g -> {
            g.setColor(SHOP);
            g.fill(new Ellipse2D.Double(1.5, 4.5, 11, 9));
            g.fill(new Rectangle2D.Double(5, 2, 4, 4));
            g.setColor(new Color(90, 60, 10));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Ellipse2D.Double(1.5, 4.5, 11, 9));
            g.draw(new Line2D.Double(4.5, 5, 9.5, 5));
        });
    }

    private static void paintArrow(Graphics2D g, Path2D arrow, Color fill, BasicStroke edge)
    {
        g.setColor(fill);
        g.fill(arrow);
        g.setColor(DOT_EDGE);
        g.setStroke(edge);
        g.draw(arrow);
    }

    /** A picture drawn antialiased at {@code scale}. */
    private static BufferedImage sprite(int width, int height, double scale, Consumer<Graphics2D> paint)
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(scale, scale);
        paint.accept(g);
        g.dispose();
        return image;
    }

    private JComponent section(int width)
    {
        dropIcons.clear();
        JPanel panel = column();
        if (loading != null)
        {
            panel.add(text("Looking up " + loading + " on the wiki…", Color.WHITE, true, width));
            return panel;
        }
        if (result == null)
        {
            panel.add(grey(failed != null ? failed : "Nothing found.", width));
            add(panel, 6, InfoCard.wrap(buttons(null), width));
            return panel;
        }
        JLabel title = text(result.title, Color.WHITE, true, width - 70);
        if (image != null)
        {
            JPanel head = panel(new BorderLayout(8, 0));
            head.add(new JLabel(new ImageIcon(image)), BorderLayout.WEST);
            head.add(title, BorderLayout.CENTER);
            panel.add(head);
        }
        else
        {
            panel.add(title);
        }
        panel.add(grey(result.summary, width));
        add(panel, 6, InfoCard.wrap(buttons(result.page), width));
        panel.add(Box.createVerticalStrut(4));
        if (!result.tabs.isEmpty())
        {
            add(panel, 4, tabs(result));
        }
        String category = null;
        List<NpcSpawns.Group> listed = result.groups.stream().filter(result::shows).collect(Collectors.toList());
        for (int i = 0; i < listed.size(); i++)
        {
            if (!allPlaces && i == SHOWN)
            {
                more(panel, "Show all " + listed.size() + " places (" + (listed.size() - SHOWN) + " more)",
                    () -> allPlaces = true, width);
                break;
            }
            NpcSpawns.Group group = listed.get(i);
            if (result.tabs.isEmpty() && group.category != null && !group.category.equals(category))
            {
                category = group.category;
                add(panel, 6, heading(category, width));
            }
            add(panel, 4, place(group, width));
        }
        if (result.extra != null)
        {
            panel.add(result.extra.apply(width));
        }
        if (result.credit != null)
        {
            add(panel, 10, text(result.credit, ColorScheme.MEDIUM_GRAY_COLOR, false, width));
        }
        return panel;
    }

    private void more(JPanel panel, String text, Runnable all, int width)
    {
        add(panel, 4, link(text, () -> {
            all.run();
            card.show(this::section);
            checkListed();
        }, width));
    }

    private JComponent itemExtra(List<ItemSources.Store> unplaced, List<ItemSources.Drop> drops, int width)
    {
        String tab = result == null ? null : result.tab;
        JPanel panel = column();
        if (SHOPS.equals(tab) && !unplaced.isEmpty())
        {
            add(panel, 8, heading("Shops not on the map", width));
            for (ItemSources.Store store : unplaced)
            {
                add(panel, 4, link(store.shop, () -> LinkBrowser.browse(WikiClient.pageUrl(store.shop)), width));
                panel.add(grey(storeNote(store), width));
            }
        }
        if (DROPS.equals(tab) || OTHER.equals(tab))
        {
            boolean npcs = DROPS.equals(tab);
            dropList(panel, drops.stream().filter(drop -> drop.npc == npcs).collect(Collectors.toList()), npcs, width);
        }
        return panel;
    }

    /** Pictures by name, the least recently used dropped beyond {@code max}. */
    static Map<String, BufferedImage> pictureCache(int max)
    {
        return new LinkedHashMap<String, BufferedImage>(64, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
            {
                return size() > max;
            }
        };
    }

    private final Map<String, BufferedImage> dropPictures = pictureCache(200);

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
        wiki.pageImages(pages, 64, (page, image) -> SwingUtilities.invokeLater(
            () -> pictureLoaded(dropPictures, dropIcons, page, image)));
    }

    /** Keeps a loaded picture and puts it into its row if shown: rebuilding the card for each made the map stutter. */
    static void pictureLoaded(Map<String, BufferedImage> pictures, Map<String, JLabel> shown, String key,
        BufferedImage picture)
    {
        if (picture != null)
        {
            pictures.put(key, picture);
            JLabel label = shown.get(key);
            if (label != null)
            {
                label.setIcon(fitted(picture));
            }
        }
    }

    static final int PICTURE = 36;
    private final Map<String, JLabel> dropIcons = new HashMap<>();

    static Icon fitted(BufferedImage picture)
    {
        double k = Math.min(1.0, (PICTURE - 2.0) / Math.max(picture.getWidth(), picture.getHeight()));
        return new ImageIcon(k >= 1 ? picture : picture.getScaledInstance(
            (int) Math.round(picture.getWidth() * k), (int) Math.round(picture.getHeight() * k),
            Image.SCALE_SMOOTH));
    }

    /**
     * The square a picture shows in, empty until it has one. No background: an opaque see-through one was painted
     * over itself each time a picture arrived, and flashed until the card was drawn again.
     */
    static JLabel pictureIcon(BufferedImage picture)
    {
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(PICTURE, PICTURE));
        icon.setHorizontalAlignment(JLabel.CENTER);
        if (picture != null)
        {
            icon.setIcon(fitted(picture));
        }
        return icon;
    }

    /** A picture at the left, words stacked beside it. */
    static JPanel pictureRow(JComponent icon, JComponent... words)
    {
        JPanel row = panel(new BorderLayout(8, 0));
        row.add(icon, BorderLayout.WEST);
        JPanel column = column();
        for (JComponent word : words)
        {
            column.add(word);
        }
        row.add(column, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(PICTURE, row.getPreferredSize().height)));
        return row;
    }

    private void dropList(JPanel panel, List<ItemSources.Drop> drops, boolean npcs, int width)
    {
        if (drops.isEmpty())
        {
            return;
        }
        add(panel, 4, grey(npcs ? "The likeliest first. Click one to see where it is." : "Chests, packs, rocks and other "
            + "sources, the likeliest first.", width));
        int textWidth = width - PICTURE - 8;
        for (int i = 0; i < drops.size(); i++)
        {
            if (!allDrops && i == SHOWN)
            {
                more(panel, "Show all " + drops.size() + " (" + (drops.size() - SHOWN) + " more)", () -> allDrops = true,
                    width);
                break;
            }
            ItemSources.Drop drop = drops.get(i);
            JLabel name = bold(link(drop.monster, npcs ? () -> {
                previous = result;
                lookUpMonster(drop.monster);
            } : () -> LinkBrowser.browse(WikiClient.pageUrl(drop.monster)), textWidth));
            JLabel icon = pictureIcon(dropPictures.get(drop.monster));
            icon.setVerticalAlignment(JLabel.TOP);
            dropIcons.put(drop.monster, icon);
            JPanel iconBox = panel(new BorderLayout());
            iconBox.add(icon, BorderLayout.NORTH);
            add(panel, 5, pictureRow(iconBox, name, grey((drop.how.isEmpty() ? "" : drop.how + " · ")
                + String.join(", ", drop.lines), textWidth)));
        }
    }

    private JComponent tabs(Result shown)
    {
        JPanel row = panel(new GridLayout(0, 2, 4, 4));
        ButtonGroup group = new ButtonGroup();
        for (Map.Entry<String, Integer> tab : shown.tabs.entrySet())
        {
            JToggleButton button = new JToggleButton(tab.getKey() + " (" + tab.getValue() + ")",
                tab.getKey().equals(shown.tab));
            button.setFocusable(false);
            button.setMargin(new Insets(2, 4, 2, 4));
            button.setEnabled(tab.getValue() > 0);
            button.addActionListener(e -> {
                shown.tab = tab.getKey();
                allPlaces = false;
                allDrops = false;
                refresh();
                checkListed();
            });
            group.add(button);
            row.add(button);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private JComponent place(NpcSpawns.Group group, int width)
    {
        JPanel row = column();
        String where = placeName(group, result.title);
        boolean undrawn = undrawn(group);
        row.add(bold(undrawn ? text(where, Color.WHITE, true, width) : link(where, () -> look(group), width)));
        if (undrawn)
        {
            row.add(text("Not on the wiki map yet", new Color(255, 190, 120), false, width));
        }
        List<String> facts = facts(group);
        if (group.members)
        {
            facts.add("Members");
        }
        BaseMap map = mapOf(group);
        if (map != null && map.id != BaseMap.SURFACE)
        {
            facts.add(map.name);
        }
        int floor = group.points.get(0).getPlane();
        if (floor > 0)
        {
            facts.add("Floor " + floor);
        }
        if (!facts.isEmpty())
        {
            row.add(grey(String.join(" · ", facts), width));
        }
        return row;
    }

    private static List<String> facts(NpcSpawns.Group group)
    {
        List<String> facts = new ArrayList<>();
        if (!group.levels.isEmpty())
        {
            facts.add("Level " + group.levels);
        }
        if (group.note == null)
        {
            facts.add(group.points.size() + (group.points.size() == 1 ? " spawn" : " spawns"));
        }
        else if (!group.note.isEmpty())
        {
            facts.add(group.note);
        }
        return facts;
    }

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

    /** Only for {@link InfoCard#wrap}, which lays the buttons out itself. */
    private JPanel buttons(String page)
    {
        JPanel buttons = new JPanel();
        Result back = previous;
        if (back != null)
        {
            buttons.add(button("Back", () -> {
                previous = null;
                request++;
                show(back);
            }));
        }
        if (result != null && result.kind != null && stopAdder != null)
        {
            String kind = result.kind;
            buttons.add(tip(button("+ Stop", () -> stopAdder.accept(Tour.Stop.kind(kind))),
                "Add \"the nearest of these\" to your custom route"));
        }
        NpcSpawns.Group closest = closest();
        if (closest != null)
        {
            buttons.add(tip(button("Closest to me", () -> look(closest)), "Go to the place nearest to you"));
        }
        if (page != null)
        {
            buttons.add(button("Wiki", () -> LinkBrowser.browse(WikiClient.pageUrl(page))));
        }
        buttons.add(button("Clear", this::clear));
        return buttons;
    }

    private NpcSpawns.Group closest()
    {
        WorldPoint me = view.near();
        return me == null || result == null ? null : closest(result.groups.stream()
            .filter(group -> result.shows(group) && !undrawn(group)).collect(Collectors.toList()), me);
    }

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

    /** A see-through panel, left-aligned in a column. */
    static JPanel panel(LayoutManager layout)
    {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    static JPanel column()
    {
        JPanel panel = panel(null);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static void add(JPanel panel, int space, Component c)
    {
        panel.add(Box.createVerticalStrut(space));
        panel.add(c);
    }

    static JButton button(String text, Runnable action)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    static <T extends JComponent> T tip(T c, String tip)
    {
        c.setToolTipText(tip);
        return c;
    }

    static JLabel bold(JLabel label)
    {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel grey(String text, int width)
    {
        return text(text, ColorScheme.LIGHT_GRAY_COLOR, false, width);
    }

    private static JLabel heading(String text, int width)
    {
        JLabel label = text(text, Color.WHITE, true, width);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 255, 255, 40)),
            BorderFactory.createEmptyBorder(1, 0, 2, 0)));
        return label;
    }

    static JLabel text(String text, Color color, boolean bold, int width)
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
        return clickable(text(text, LINK, false, width), action, true);
    }

    /** A label that runs {@code action} when clicked; {@code hover}: a link that turns white under the mouse. */
    static JLabel clickable(JLabel label, Runnable action, boolean hover)
    {
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
                if (hover)
                {
                    label.setForeground(Color.WHITE);
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                if (hover)
                {
                    label.setForeground(LINK);
                }
            }
        });
        return label;
    }

    private static final BasicStroke EDGE = new BasicStroke(1.2f);
    private final Map<NpcSpawns.Group, WorldPoint> centers = new IdentityHashMap<>();

    /**
     * Arrows and each place's label as pictures made once: drawing hundreds of shapes and texts every frame was slow.
     * Labels for {@link #tagsDevice}; a label's colour follows from its place.
     */
    private final Map<NpcSpawns.Group, BufferedImage> tags = new IdentityHashMap<>();
    private double tagsDevice;
    private final Map<Long, BufferedImage> arrows = new HashMap<>();

    private static void cap(Map<?, ?> cache, int max)
    {
        if (cache.size() > max)
        {
            cache.clear();
        }
    }

    private BufferedImage arrowSprite(Color fill, double r, double device)
    {
        long key = (long) fill.getRGB() << 32 | (Math.round(r * 4) & 0xffffL) << 16 | Math.round(device * 10) & 0xffffL;
        cap(arrows, 500);
        return arrows.computeIfAbsent(key, k -> {
            double w = r * 2.4 + 4;
            double h = r * 2.6 + 4;
            return sprite((int) Math.ceil(w * device), (int) Math.ceil(h * device), device,
                g -> paintArrow(g, arrow(w / 2, h - 2, r), fill, EDGE));
        });
    }

    private static BufferedImage labelSprite(String label, Color color, FontMetrics metrics, double device)
    {
        int width = metrics.stringWidth(label) + 10;
        int height = metrics.getHeight() + 2;
        return sprite((int) Math.ceil((width + 2) * device), (int) Math.ceil((height + 2) * device), device, g -> {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(LABEL_FONT);
            RoundRectangle2D box = new RoundRectangle2D.Double(1, 1, width, height, 8, 8);
            g.setColor(LABEL_BACKGROUND);
            g.fill(box);
            g.setColor(color);
            g.draw(box);
            g.setColor(Color.WHITE);
            g.drawString(label, 6f, (float) (1 + metrics.getHeight() - metrics.getDescent()));
        });
    }

    private static final Map<Color, Color> FADED = new ConcurrentHashMap<>();

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
        cap(groupBounds, 5000);
        cap(tags, device != tagsDevice ? -1 : 5000);
        tagsDevice = device;
        for (NpcSpawns.Group group : shown.groups)
        {
            int[] b = bounds(group);
            // A place the map draws elsewhere (the Kalphite Lair) moves as its first point does.
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
            Color fill = moved.getPlane() != projection.plane()
                ? FADED.computeIfAbsent(color, c -> new Color(c.getRed(), c.getGreen(), c.getBlue(), 110)) : color;
            BufferedImage arrow = arrowSprite(fill, r, device);
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
                PoiIcons.blit(g, arrow, x - half, y - tall);
            }
            if (zoom < -1.5)
            {
                continue;
            }
            cap(centers, 5000);
            WorldPoint c = centers.computeIfAbsent(group, NpcSpawns.Group::center);
            double x = projection.screenX(c.getX() + dx + 0.5);
            double y = projection.screenY(c.getY() + dy + 0.5) - r * 3 - 4;
            BufferedImage tag = tags.computeIfAbsent(group, k -> labelSprite((group.location.isEmpty() ? group.name
                : group.location) + (group.levels.isEmpty() ? "" : " (" + group.levels + ")"), color, metrics, device));
            double width = tag.getWidth() / device;
            if (x + width / 2.0 < 0 || x - width / 2.0 > projection.width() || y < -20 || y > projection.height() + 20)
            {
                continue;
            }
            PoiIcons.blit(g, tag, x - width / 2.0 - 1, y - metrics.getHeight() - 1);
        }
    }
}
