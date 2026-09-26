package com.hdmapreforged;

import com.hdmapreforged.route.CollisionMap;
import com.hdmapreforged.route.HouseTracker;
import com.hdmapreforged.route.ItemSnapshot;
import com.hdmapreforged.route.Pathfinder;
import com.hdmapreforged.route.PlayerState;
import com.hdmapreforged.route.Ports;
import com.hdmapreforged.route.Route;
import com.hdmapreforged.route.RouteController;
import com.hdmapreforged.route.RouteRequest;
import com.hdmapreforged.route.SeaMap;
import com.hdmapreforged.route.Tiles;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * "Path to here": our own route search on our own collision map (see {@code com.hdmapreforged.route}). Right-click the
 * map for the menu entries; the route is drawn on both maps, its steps shown in the card, and optionally on the game's
 * ground and minimap. It only shows a way; it never walks, clicks or sends anything.
 *
 * <p>Threads: the player's state is read on the client thread each tick; the search runs on its own background thread,
 * work besides it (the way anyone could go, a custom route's parts, the fastest order) on the route extras thread;
 * everything else (the controller, drawing on the map) on Swing. Requests are built on Swing and the extras thread.
 */
@Slf4j
public final class RouteFeature
{
    static final String HOUSE_KEY = "routeHouse";
    private static final int[] BOAT_PORTS = {VarbitID.SAILING_BOAT_1_PORT, VarbitID.SAILING_BOAT_2_PORT,
        VarbitID.SAILING_BOAT_3_PORT, VarbitID.SAILING_BOAT_4_PORT, VarbitID.SAILING_BOAT_5_PORT};
    private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
        VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
    private static final int[] POUCH_AMOUNTS = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
        VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5,
        VarbitID.RUNE_POUCH_QUANTITY_6};
    private static final Set<Integer> RUNE_POUCHES = new HashSet<>(Arrays.asList(12791, 24416, 27281, 27509));
    private static final int STATE_TICKS = 3;
    private static final int QUEST_TICKS = 100;

    static final Color WALK = new Color(255, 233, 28);
    static final Color JUMP = new Color(190, 120, 255);
    static final Color SEA = new Color(64, 200, 255);
    /** Settings that change only how the route looks, not the way. */
    private static final java.util.Set<String> LOOK_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
        "routeColor", "routeJumpColor", "routeTileFill", "routeTileBorder", "routeTileWidth", "routeMinimap",
        "routeInGame", "routeHdTiles", "routeClearOnArrival", "routeFollowPlugins", "routePlannerChosen"));

    /** The parts of a custom route after the first, one colour each (the first has the route colour). */
    private static final Color[] LEG_COLORS = {
        new Color(90, 220, 130), new Color(255, 130, 200), new Color(255, 160, 70), new Color(120, 230, 255),
        new Color(200, 255, 110), new Color(255, 255, 255), new Color(180, 150, 255)};

    /** Steps the player cannot take. */
    static final Color CANNOT = new Color(255, 112, 112);

    private Color walk()
    {
        Color c = config.routeColor();
        return c == null ? WALK : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    private Color jump()
    {
        Color c = config.routeJumpColor();
        return c == null ? JUMP : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color alpha(Color c, float alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(alpha))));
    }

    /** A route tile for HD Tile Markers, in the chosen style, {@code alpha} visible (fading). */
    private HdTileMarkersBridge.Tile hdTile(int point, float alpha)
    {
        return hdTile(point, alpha, current());
    }

    private HdTileMarkersBridge.Tile hdTile(int point, float alpha, Color walk)
    {
        int border = config.routeTileBorder();
        return new HdTileMarkersBridge.Tile(new WorldPoint(Tiles.x(point), Tiles.y(point), Tiles.z(point)),
            alpha(walk, border * alpha), alpha(walk, config.routeTileFill() * alpha),
            border == 0 ? 0 : (int) Math.max(1, Math.round(config.routeTileWidth())), null);
    }
    static final Color END = new Color(255, 90, 70);

    private final Client client;
    private final HdMapReforgedConfig config;
    private final ConfigManager configManager;
    private final OverlayManager overlayManager;
    private final EventBus eventBus;
    private final HouseTracker house = new HouseTracker();
    private final GameOverlay gameOverlay = new GameOverlay();
    private final MinimapOverlay minimapOverlay = new MinimapOverlay();
    private final WorldMapRoute worldMapRoute = new WorldMapRoute();
    private final MapView.Overlay mapOverlay = this::paintMap;
    private final MapView.MenuContributor menu = this::contribute;

    private volatile ExecutorService searches;
    private volatile ExecutorService background;
    private volatile MapScreen[] screens = new MapScreen[0];
    /** Set once the data is loaded; read on Swing. */
    private volatile RouteController controller;
    private volatile RouteSource source;
    /** Our pathfinder, also used to fill in the walks of Shortest Path's route. */
    private volatile Pathfinder walker;
    /** The walks between Shortest Path's transports, as our pathfinder finds them (packed tiles each). */
    private volatile List<int[]> spWalks = Collections.emptyList();
    /** Which filling in of the walks is current; changed together with {@link #spWalks}, under {@link #spLock}. */
    private final java.util.concurrent.atomic.AtomicInteger spGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final Object spLock = new Object();

    /** Forgets the walks and drops any filling in still running; returns the new generation. */
    private int dropWalks()
    {
        synchronized (spLock)
        {
            spWalks = Collections.emptyList();
            return spGeneration.incrementAndGet();
        }
    }

    /**
     * Shortest Path only tells which transports its route takes; the walks between them are found here, on foot
     * (with stairs and doors), so the whole route can be drawn. In the background.
     */
    private void fillInWalks(List<ShortestPathBridge.Jump> jumps, WorldPoint from, WorldPoint target)
    {
        RouteSource s = source;
        Pathfinder p = walker;
        java.util.concurrent.ExecutorService pool = background;
        int generation = dropWalks();
        if (s == null || p == null || pool == null || from == null || target == null)
        {
            return;
        }
        List<WorldPoint[]> legs = new java.util.ArrayList<>();
        WorldPoint at = from;
        for (ShortestPathBridge.Jump j : jumps)
        {
            legs.add(new WorldPoint[]{at, j.from});
            at = j.to;
        }
        legs.add(new WorldPoint[]{at, target});
        pool.execute(() -> {
            List<int[]> walks = new java.util.ArrayList<>();
            for (WorldPoint[] leg : legs)
            {
                if (generation != spGeneration.get())
                {
                    return;
                }
                RouteRequest request = s.request(Tiles.pack(leg[0].getX(), leg[0].getY(), leg[0].getPlane()),
                    Tiles.pack(leg[1].getX(), leg[1].getY(), leg[1].getPlane()), Collections.emptyList(), null,
                    PlayerState.UNKNOWN, new RouteSource.Options(false, false), 400_000);
                Route walk;
                try
                {
                    walk = request == null ? null : p.find(request, () -> generation != spGeneration.get());
                }
                catch (Throwable e)
                {
                    log.warn("Could not fill in Shortest Path's walks", e);
                    return;
                }
                if (walk == null)
                {
                    continue;
                }
                for (Route.Step step : walk.steps)
                {
                    if (step.kind == Route.Step.Kind.WALK)
                    {
                        walks.add(step.points);
                    }
                }
            }
            synchronized (spLock)
            {
                if (generation != spGeneration.get())
                {
                    return;
                }
                spWalks = walks;
            }
            SwingUtilities.invokeLater(() -> {
                for (MapScreen screen : screens)
                {
                    screen.view().repaint();
                }
            });
        });
    }
    private volatile Map<Integer, Integer> ports = Collections.emptyMap();
    /** Where the shipwrights are (packed tiles), from the game's map icons. */
    private volatile int[] shipwrights = new int[0];

    /** The game's map icons, as the map data parsed them: where the shipwrights are comes from them. Any thread. */
    void setIconEntries(List<MapIconLoader.Entry> entries)
    {
        shipwrights = shipwrights(entries);
    }

    /** The shipwrights among the game's map icons, as packed tiles. */
    static int[] shipwrights(List<MapIconLoader.Entry> entries)
    {
        List<Integer> found = new ArrayList<>();
        for (MapIconLoader.Entry entry : entries)
        {
            if ("Shipwright".equalsIgnoreCase(entry.kind.name) && entry.location.getPlane() == 0)
            {
                found.add(Tiles.pack(entry.location.getX(), entry.location.getY(), 0));
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }
    /** What the player has; written on the client thread. */
    private volatile PlayerState state = PlayerState.UNKNOWN;
    /** Where the route starts: a tile, a sea block, -1 in the house, -2 unknown. Written on the client thread. */
    private volatile int start = -2;
    private volatile boolean automatic = true;
    /** The route as drawn in the game view; read on the client thread. */
    private volatile Route shown;
    private boolean bringUp;
    private Map<Integer, Long> bank;
    /** The bank as the last capture copied it, and whether it changed since (client thread). */
    private ItemSnapshot bankItems;
    private boolean bankChanged = true;
    private Boolean pandemonium;
    private int ticks;
    private int lastQuest = -QUEST_TICKS;

    @Inject
    RouteFeature(Client client, HdMapReforgedConfig config, ConfigManager configManager, OverlayManager overlayManager,
        EventBus eventBus, net.runelite.client.plugins.PluginManager pluginManager,
        net.runelite.client.callback.ClientThread clientThread,
        net.runelite.client.ui.overlay.worldmap.WorldMapOverlay worldMapOverlay)
    {
        this.clientThread = clientThread;
        this.worldMapOverlay = worldMapOverlay;
        this.client = client;
        this.config = config;
        this.configManager = configManager;
        this.overlayManager = overlayManager;
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;
    }

    private final net.runelite.client.plugins.PluginManager pluginManager;
    /** The sidebar's route list: shown there too, a step clicked opens the map there. */
    private volatile java.util.function.Consumer<java.util.function.IntFunction<JComponent>> sidebar = section -> { };
    private volatile java.util.function.Consumer<WorldPoint> sidebarFocus = point -> { };
    private volatile Runnable sidebarTours = () -> { };
    private final net.runelite.client.callback.ClientThread clientThread;
    private final net.runelite.client.ui.overlay.worldmap.WorldMapOverlay worldMapOverlay;
    /** The target handed to Shortest Path, while it is the route planner; null when none. */
    private volatile WorldPoint handedOver;
    /** Where the player was on the last tick, for choosing among several targets. */
    private volatile WorldPoint lastLocation;
    /** Whether the current route was asked for by another plugin (a message to Shortest Path). */
    private volatile boolean fromPlugin;

    private boolean shortestPathPlanner()
    {
        return config.routePlanner() == HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH;
    }

    /** Whether the Shortest Path plugin is installed and switched on. */
    private boolean shortestPathOn()
    {
        return pluginOn(ShortestPathBridge.PLUGIN_NAME);
    }

    private boolean pluginOn(String name)
    {
        for (net.runelite.client.plugins.Plugin plugin : pluginManager.getPlugins())
        {
            net.runelite.client.plugins.PluginDescriptor descriptor =
                plugin.getClass().getAnnotation(net.runelite.client.plugins.PluginDescriptor.class);
            if (descriptor != null && name.equals(descriptor.name()))
            {
                return pluginManager.isPluginEnabled(plugin);
            }
        }
        return false;
    }

    /** Whether HD Tile Markers draws the ground tiles now (checked now and then, not every frame). */
    private volatile boolean hdTiles;
    /** The custom route's parts last sent to HD Tile Markers. */
    private Route[] sentLegs;
    /** The tick HD Tile Markers was last checked for; far back: at once. */
    private int lastHdTilesCheck = Integer.MIN_VALUE / 2;
    /** What was last sent to HD Tile Markers, to send only changes. */
    private Route sentAhead;
    private boolean fadedLast;
    private int sentFrom = -1;

    /**
     * Shift + right-click on the ground: "Route here" (and "Clear route") for this plugin's planner. RuneLite menu
     * entries: choosing one only sets the route, nothing is sent to the game.
     */
    @Subscribe
    public void onMenuOpened(net.runelite.api.events.MenuOpened event)
    {
        if (shortestPathPlanner() || !client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
        {
            return;
        }
        boolean walk = false;
        for (net.runelite.api.MenuEntry entry : event.getMenuEntries())
        {
            walk |= entry.getType() == net.runelite.api.MenuAction.WALK;
        }
        WorldView view = client.getTopLevelWorldView();
        net.runelite.api.Tile tile = view == null ? null : view.getSelectedSceneTile();
        if (!walk || tile == null)
        {
            return;
        }
        WorldPoint point = WorldPoint.fromLocalInstance(client, tile.getLocalLocation());
        if (point == null)
        {
            return;
        }
        RouteController c = controller;
        if (c != null && c.target() >= 0)
        {
            client.getMenu().createMenuEntry(1)
                .setOption("Clear route")
                .setTarget("")
                .setType(net.runelite.api.MenuAction.RUNELITE)
                .onClick(e -> SwingUtilities.invokeLater(() -> {
                    RouteController now = controller;
                    stopTour();
                    if (now != null)
                    {
                        now.clear();
                    }
                }));
        }
        // Only added to the menu: the game's own entries stay as they are.
        client.getMenu().createMenuEntry(1)
            .setOption("Route here")
            .setTarget("")
            .setType(net.runelite.api.MenuAction.RUNELITE)
            .onClick(e -> SwingUtilities.invokeLater(() -> routeTo(point)));
    }

    private long lastFadeSend;

    /**
     * While tiles fade, HD Tile Markers gets them again every few frames with less colour, so the fade is smooth there
     * too (it draws what it was sent, without animating it).
     */
    @Subscribe
    public void onClientTick(net.runelite.api.events.ClientTick event)
    {
        long now = System.currentTimeMillis();
        if (!hdTiles || fading.isEmpty() && appearing.isEmpty() && !fadedLast || now - lastFadeSend < 50)
        {
            return;
        }
        lastFadeSend = now;
        sentAhead = null;
        updateHdTiles(sentFrom);
    }

    /** Hands the route's tiles near the player to HD Tile Markers, or takes them back. Client thread. */
    private void updateHdTiles(int player)
    {
        if (ticks - lastHdTilesCheck >= 10)
        {
            lastHdTilesCheck = ticks;
            boolean on = config.routeHdTiles() && config.routeInGame() && pluginOn(HdTileMarkersBridge.PLUGIN_NAME);
            if (!on && hdTiles)
            {
                eventBus.post(HdTileMarkersBridge.clear());
                sentAhead = null;
            }
            hdTiles = on;
        }
        if (!hdTiles)
        {
            return;
        }
        Route route = shown;
        // Moved far, or the position became known or unknown; not every tick while it stays unknown (the house).
        boolean moved = player != sentFrom && (player < 0 || sentFrom < 0 || Tiles.distance(player, sentFrom) > 16);
        // While tiles fade, they are sent again each tick with less colour.
        Running running = tour;
        Route[] legs = running == null ? null : running.legs;
        if (route == sentAhead && legs == sentLegs && !moved && fading.isEmpty() && appearing.isEmpty() && !fadedLast)
        {
            return;
        }
        fadedLast = !fading.isEmpty() || !appearing.isEmpty();
        sentAhead = route;
        sentLegs = legs;
        sentFrom = player;
        List<Map.Entry<Integer, Route>> later = laterLegs();
        if (route == null && fading.isEmpty() && later.isEmpty())
        {
            eventBus.post(HdTileMarkersBridge.clear());
            return;
        }
        List<HdTileMarkersBridge.Tile> tiles = new java.util.ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        int blocked = RouteText.blockedFrom(route);
        List<Route.Step> steps = route == null ? java.util.Collections.<Route.Step>emptyList() : route.steps;
        for (int index = 0; index < steps.size(); index++)
        {
            Route.Step step = steps.get(index);
            Color color = blocked >= 0 && index >= blocked ? CANNOT : current();
            if (step.kind == Route.Step.Kind.WALK)
            {
                for (int point : step.points)
                {
                    if (player < 0 || Tiles.distance(point, player) <= 40)
                    {
                        taken.add(point);
                        tiles.add(hdTile(point, fadeIn(point, System.currentTimeMillis()), color));
                    }
                }
            }
            else if (step.isJump() && step.name != null && !Tiles.isSea(step.first()) && step.kind != Route.Step.Kind.TELEPORT)
            {
                // Where a transport or passage is taken: its name on the tile.
                int at = step.first();
                if (player < 0 || Tiles.distance(at, player) <= 40)
                {
                    tiles.add(new HdTileMarkersBridge.Tile(new WorldPoint(Tiles.x(at), Tiles.y(at), Tiles.z(at)),
                        blocked >= 0 && index >= blocked ? CANNOT : jump(), step.name));
                }
            }
        }
        // Fading tiles last: HD Tile Markers takes 1000, and the route near the player matters more.
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            float alpha = fadeAlpha(entry.getValue(), now);
            int point = entry.getKey();
            if (alpha > 0.05f && taken.add(point))
            {
                tiles.add(hdTile(point, alpha));
            }
        }
        // A custom route's later parts, each in its own colour; the nearer part wins where they cross.
        for (int i = later.size() - 1; i >= 0; i--)
        {
            Color color = legColor(later.get(i).getKey());
            for (Route.Step step : later.get(i).getValue().steps)
            {
                for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0])
                {
                    if ((player < 0 || Tiles.distance(point, player) <= 40) && taken.add(point))
                    {
                        tiles.add(hdTile(point, 1, color));
                    }
                }
            }
        }
        eventBus.post(HdTileMarkersBridge.tiles(tiles));
    }

    /**
     * Directions other plugins send to Shortest Path. With this plugin as route planner, the map plans a route to
     * them as well; its "clear" removes such a route again. Messages are only read, never answered.
     */
    @Subscribe
    public void onPluginMessage(net.runelite.client.events.PluginMessage message)
    {
        if (shortestPathPlanner())
        {
            // Shortest Path's own route, as far as it shares it: which transports it takes.
            if (ShortestPathBridge.isTransports(message) && handedOver != null)
            {
                spJumps = ShortestPathBridge.jumps(message);
                fillInWalks(spJumps, lastLocation, handedOver);
                SwingUtilities.invokeLater(() -> {
                    for (MapScreen screen : screens)
                    {
                        screen.showRoute(this::handedOverPanel, false);
                        screen.view().repaint();
                    }
                    sidebar.accept(this::handedOverPanel);
                });
            }
            return;
        }
        if (!config.routeFollowPlugins())
        {
            return;
        }
        if (ShortestPathBridge.isPath(message))
        {
            WorldPoint target = ShortestPathBridge.nearest(ShortestPathBridge.targets(message), lastLocation);
            if (target != null)
            {
                SwingUtilities.invokeLater(() -> {
                    RouteController c = controller;
                    if (c != null)
                    {
                        fromPlugin = true;
                        routeAvoid.clear();
                        c.setTarget(Tiles.pack(target.getX(), target.getY(), target.getPlane()));
                    }
                });
            }
        }
        else if (ShortestPathBridge.isClear(message))
        {
            SwingUtilities.invokeLater(() -> {
                RouteController c = controller;
                if (c != null && fromPlugin)
                {
                    fromPlugin = false;
                    c.clear();
                }
            });
        }
    }

    /** Shortest Path's route as far as it tells: its transports as dashed jumps, straight lines between them. */
    private volatile List<ShortestPathBridge.Jump> spJumps = Collections.emptyList();

    private void paintHandedOver(Graphics2D g, MapView.Projection p)
    {
        WorldPoint target = handedOver;
        if (target == null)
        {
            return;
        }
        java.util.List<WorldPoint> stops = new java.util.ArrayList<>();
        WorldPoint me = lastLocation;
        if (me != null)
        {
            stops.add(me);
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.BasicStroke walk = new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
            10f, new float[]{2f, 5f}, 0f);
        java.awt.BasicStroke jump = new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
            10f, new float[]{9f, 6f}, 0f);
        List<int[]> walks = spWalks;
        WorldPoint at = me;
        for (ShortestPathBridge.Jump j : spJumps)
        {
            if (at != null && walks.isEmpty())
            {
                line(g, p, at, j.from, walk, new Color(255, 214, 64));
            }
            line(g, p, j.from, j.to, jump, new Color(180, 120, 255));
            at = j.to;
        }
        if (at != null && walks.isEmpty())
        {
            line(g, p, at, target, walk, new Color(255, 214, 64));
        }
        // The walks, found on foot by our pathfinder along Shortest Path's transports.
        java.awt.BasicStroke path = new java.awt.BasicStroke(3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
        for (int[] points : walks)
        {
            java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
            boolean started = false;
            for (int node : points)
            {
                WorldPoint wp = new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node));
                if (!p.shows(wp))
                {
                    started = false;
                    continue;
                }
                WorldPoint drawnAt = p.shown(wp);
                double x = p.screenX(drawnAt.getX() + 0.5);
                double y = p.screenY(drawnAt.getY() + 0.5);
                if (started)
                {
                    line.lineTo(x, y);
                }
                else
                {
                    line.moveTo(x, y);
                    started = true;
                }
            }
            g.setStroke(new java.awt.BasicStroke(5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 130));
            g.draw(line);
            g.setStroke(path);
            g.setColor(new Color(255, 214, 64));
            g.draw(line);
        }
        if (p.shows(target))
        {
            WorldPoint drawnAt = p.shown(target);
            double x = p.screenX(drawnAt.getX() + 0.5);
            double y = p.screenY(drawnAt.getY() + 0.5);
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(new java.awt.geom.Ellipse2D.Double(x - 7, y - 7, 14, 14));
            g.setColor(new Color(255, 214, 64));
            g.fill(new java.awt.geom.Ellipse2D.Double(x - 5, y - 5, 10, 10));
        }
    }

    private static void line(Graphics2D g, MapView.Projection p, WorldPoint a, WorldPoint b, java.awt.BasicStroke stroke, Color color)
    {
        if (!p.shows(a) || !p.shows(b))
        {
            return;
        }
        WorldPoint from = p.shown(a);
        WorldPoint to = p.shown(b);
        java.awt.geom.Line2D line = new java.awt.geom.Line2D.Double(p.screenX(from.getX() + 0.5),
            p.screenY(from.getY() + 0.5), p.screenX(to.getX() + 0.5), p.screenY(to.getY() + 0.5));
        g.setStroke(new java.awt.BasicStroke(stroke.getLineWidth() + 2f, stroke.getEndCap(), stroke.getLineJoin(),
            stroke.getMiterLimit(), stroke.getDashArray(), 0f));
        g.setColor(new Color(0, 0, 0, 130));
        g.draw(line);
        g.setStroke(stroke);
        g.setColor(color);
        g.draw(line);
    }

    /** The card while Shortest Path plans the route. */
    private JComponent handedOverPanel(int width)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        WorldPoint target = handedOver;
        if (target == null)
        {
            return panel;
        }
        Running running = tour;
        if (running != null)
        {
            panel.add(tourHeader(running, width));
        }
        panel.add(label("Route to " + target.getX() + ", " + target.getY(), width, Font.BOLD, Color.WHITE));
        boolean on = shortestPathOn();
        panel.add(label(on ? "Planned by Shortest Path (shown in the game). On this map: its teleports and transports, "
            + "with the walks between them as this map finds them."
            : "The Shortest Path plugin is not on. Install or switch it on, or set Route planner to HD Map Reforged.",
            width, Font.PLAIN, on ? ColorScheme.LIGHT_GRAY_COLOR : new Color(255, 190, 120)));
        int number = 1;
        for (ShortestPathBridge.Jump j : spJumps)
        {
            panel.add(label(number++ + ". " + j.name, width, Font.PLAIN, new Color(200, 170, 255)));
        }
        JButton clear = new JButton("Clear path");
        clear.setFocusable(false);
        clear.addActionListener(a -> handOverClear());
        panel.add(clear);
        return panel;
    }

    private void handOver(WorldPoint target)
    {
        handedOver = target;
        spJumps = Collections.emptyList();
        // Until Shortest Path names its transports: the walk straight there, if it is one.
        fillInWalks(Collections.emptyList(), lastLocation, target);
        // Shortest Path reads the player's position when a message arrives: that has to be on the client thread.
        clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.path(target)));
        for (MapScreen screen : screens)
        {
            screen.showRoute(this::handedOverPanel, true);
            screen.view().repaint();
        }
        sidebar.accept(this::handedOverPanel);
    }

    private void handOverClear()
    {
        handedOver = null;
        spJumps = Collections.emptyList();
        dropWalks();
        clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.clear()));
        for (MapScreen screen : screens)
        {
            screen.showRoute(null, false);
            screen.view().repaint();
        }
        sidebar.accept(null);
    }

    /** Hooks into both maps and loads the collision data in the background. */
    void start(MapScreen... on)
    {
        int generation = loads.incrementAndGet();
        screens = on;
        // Just below the client's own threads: at the lowest priority a busy client starved searches for seconds.
        searches = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "HD Map Reforged route");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        // Work besides the route asked for (why a place is out of reach, Shortest Path's walks) never delays it.
        background = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "HD Map Reforged route extras");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        for (MapScreen screen : on)
        {
            screen.view().addOverlay(mapOverlay);
            screen.view().addMenuContributor(menu);
            screen.setRouter(this::routeTo);
            screen.setStopAdder(this::addStop);
            screen.setTours(tourActions);
        }
        overlayManager.add(gameOverlay);
        overlayManager.add(minimapOverlay);
        overlayManager.add(worldMapRoute);
        eventBus.register(this);
        searches.execute(() -> {
            try
            {
                CollisionMap map = CollisionMap.load();
                SeaMap sea = SeaMap.build(map);
                Pathfinder pathfinder = new Pathfinder(map, sea);
                Map<Integer, Integer> loadedPorts = Ports.load();
                SwingUtilities.invokeLater(() -> {
                    if (generation != loads.get() || searches == null)
                    {
                        // Stopped (and maybe started again) while loading: that start loads its own.
                        return;
                    }
                    ports = loadedPorts;
                    source = new RouteSource(map, sea);
                    for (MapScreen screen : screens)
                    {
                        // Entering a dungeon fits the map to it: its walkable area, known from the route data.
                        screen.view().setAreaBounds(p -> com.hdmapreforged.route.WalkableArea.bounds(map, p.getX(),
                            p.getY(), p.getPlane(), AREA_LIMIT));
                    }
                    walker = pathfinder;
                    controller = new RouteController(searches, SwingUtilities::invokeLater, pathfinder, this::request,
                        c -> changed(), System::currentTimeMillis);
                });
            }
            catch (Exception e)
            {
                log.warn("Could not load the route data", e);
            }
        });
        // Started again while logged in: what the house has comes from the "Your house" settings, as at login.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && generation == loads.get())
            {
                house.restore(houseSettings());
            }
        });
    }

    /** Counts starts and stops, so what a start loaded is dropped when the plugin stopped meanwhile. */
    private final java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();

    void stop()
    {
        loads.incrementAndGet();
        eventBus.unregister(this);
        overlayManager.remove(gameOverlay);
        overlayManager.remove(minimapOverlay);
        overlayManager.remove(worldMapRoute);
        fadeTimer.stop();
        fading.clear();
        appearing.clear();
        if (hdTiles)
        {
            // Our tiles leave HD Tile Markers with us.
            hdTiles = false;
            clientThread.invokeLater(() -> eventBus.post(HdTileMarkersBridge.clear()));
        }
        for (MapScreen screen : screens)
        {
            screen.view().removeOverlay(mapOverlay);
            screen.view().removeMenuContributor(menu);
            screen.showRoute(null, false);
        }
        sidebar.accept(null);
        RouteController running = controller;
        if (running != null)
        {
            running.clear();
        }
        controller = null;
        if (searches != null)
        {
            searches.shutdownNow();
            searches = null;
        }
        if (background != null)
        {
            background.shutdownNow();
            background = null;
        }
        screens = new MapScreen[0];
        shown = null;
        house.reset();
        bank = null;
        bankChanged = true;
        // Nothing of this run is left for the next: work still running on the extras thread finds it is not wanted.
        tour = null;
        orders.incrementAndGet();
        handedOver = null;
        spJumps = Collections.emptyList();
        dropWalks();
        why = null;
        whyFor = null;
        whyTarget = -1;
        explaining = false;
        ahead = null;
        aheadOf = null;
        aheadWalked = null;
        routeAvoid.clear();
        logged = null;
        sentAhead = null;
        sentLegs = null;
        sentFrom = -1;
        fadedLast = false;
        lastHdTilesCheck = Integer.MIN_VALUE / 2;
        lastAheadSteps = -1;
        lastAheadPoints = -1;
        bringUp = false;
        fromPlugin = false;
    }

    // ---- client thread ----

    /** Every game tick, with where the map shows the player. */
    void tick(WorldPoint location)
    {
        ticks++;
        lastLocation = location;
        Player player = client.getLocalPlayer();
        if (player == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        WorldView view = player.getWorldView();
        boolean onBoat = view != null && !view.isTopLevel();
        boolean instance = client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
        int node = location == null ? -1 : Tiles.pack(location.getX(), location.getY(), location.getPlane());
        houseLocation = client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION);
        house.update(node, instance, houseLocation,
            client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1);
        if (house.takeChanged())
        {
            // What was seen in the house, into the "Your house" settings.
            writeHouse(house.features());
        }
        int from;
        if (location == null)
        {
            from = -2;
        }
        else if (house.inside())
        {
            from = -1;
        }
        else if (onBoat)
        {
            from = Tiles.seaAt(location.getX(), location.getY());
        }
        else
        {
            from = node;
        }
        start = from;
        automatic = !instance;
        updateHdTiles(from);
        if (ticks % STATE_TICKS == 0 || state == PlayerState.UNKNOWN)
        {
            state = capture();
        }
        int moved = from;
        boolean auto = automatic;
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null)
            {
                Running running = tour;
                if (running != null && running.target >= 0 && (Tiles.z(moved) == Tiles.z(running.target)
                    && Tiles.distance(moved, running.target) <= RouteController.ARRIVED
                    || c.target() == running.target && c.arrived(moved)))
                {
                    // A custom route: at this stop (by where the player is, whoever plans the way), on to the next,
                    // planned from here.
                    nextStop(moved);
                    return;
                }
                if (config.routeClearOnArrival() && c.arrived(moved))
                {
                    // There: the route is done, and what is left of it fades away.
                    fadeOut(walkedAhead(ahead));
                    c.clear();
                    return;
                }
                c.playerMoved(moved >= 0 ? moved : -1, auto);
                updateAhead(c, moved);
            }
        });
    }

    /** The part of the route still ahead of the player; what is drawn. */
    private volatile Route ahead;
    private int lastAheadSteps = -1;
    private int lastAheadPoints = -1;

    /** Tiles the player has passed fade away at once, over FADE_MS: a step or two behind them, not a trail. */
    static final long FADE_MS = 600;
    /** Tiles that dropped off the route, by packed tile, with when they started to fade. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> fading = new java.util.concurrent.ConcurrentHashMap<>();
    private final javax.swing.Timer fadeTimer = new javax.swing.Timer(40, e -> fadeStep());

    /** Tiles that came back onto the route (the player walked back), with when they started to fade in. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> appearing = new java.util.concurrent.ConcurrentHashMap<>();
    static final long FADE_IN_MS = 400;

    /** How visible a route tile is now: below 1 while it fades back in. */
    private float fadeIn(int point, long now)
    {
        Long since = appearing.get(point);
        return since == null ? 1f : Math.max(0.05f, Math.min(1f, (now - since) / (float) FADE_IN_MS));
    }

    private void fadeInTiles(java.util.Set<Integer> tiles)
    {
        if (tiles.isEmpty() || tiles.size() > 1000)
        {
            return;
        }
        long t = System.currentTimeMillis();
        for (int point : tiles)
        {
            // Fading out a moment ago: it comes back from where it was, not from nothing.
            Long out = fading.remove(point);
            float was = out == null ? 0f : fadeAlpha(out, t);
            appearing.put(point, t - (long) (was * FADE_IN_MS));
        }
        if (!fadeTimer.isRunning())
        {
            fadeTimer.start();
        }
    }

    /** How visible a fading tile is now: 1 just passed, 0 gone. */
    static float fadeAlpha(long since, long now)
    {
        long elapsed = now - since;
        return elapsed <= 0 ? 1f : Math.max(0f, 1f - elapsed / (float) FADE_MS);
    }

    private void fadeStep()
    {
        long now = System.currentTimeMillis();
        fading.values().removeIf(since -> now - since >= FADE_MS);
        appearing.values().removeIf(since -> now - since >= FADE_IN_MS);
        if (fading.isEmpty() && appearing.isEmpty())
        {
            fadeTimer.stop();
        }
        for (MapScreen screen : screens)
        {
            screen.view().repaint();
        }
    }

    /** The walked tiles of a route, for finding which ones the player has passed. */
    private static java.util.Set<Integer> walked(Route route)
    {
        java.util.Set<Integer> tiles = new java.util.HashSet<>();
        if (route != null)
        {
            for (Route.Step step : route.steps)
            {
                if (step.kind == Route.Step.Kind.WALK)
                {
                    for (int point : step.points)
                    {
                        tiles.add(point);
                    }
                }
            }
        }
        return tiles;
    }

    /** Tiles that dropped off the route (passed, or the route done or replaced) fade away. */
    private void fadeOut(java.util.Set<Integer> tiles)
    {
        long t = System.currentTimeMillis();
        if (tiles.isEmpty() || tiles.size() > 1000)
        {
            return;
        }
        for (int point : tiles)
        {
            fading.putIfAbsent(point, t);
        }
        if (!fadeTimer.isRunning())
        {
            fadeTimer.start();
        }
    }

    /** The route {@link #ahead} was cut from, so a later cut of the same route can be told apart from a new one. */
    private Route aheadOf;
    /** The walked tiles of {@link #ahead}, worked out once (see {@link #walkedAhead}). Swing thread. */
    private Route aheadWalkedOf;
    private java.util.Set<Integer> aheadWalked;

    /** {@link #walked} of a route, kept for the last one asked: each tick asks for the same route again. */
    private java.util.Set<Integer> walkedAhead(Route route)
    {
        if (route != aheadWalkedOf || aheadWalked == null)
        {
            aheadWalkedOf = route;
            aheadWalked = Collections.unmodifiableSet(walked(route));
        }
        return aheadWalked;
    }

    private void updateAhead(RouteController c, int player)
    {
        Route route = effective(c);
        Route now = route == null ? null : route.ahead(player);
        Route before = ahead;
        if (route != null && route == aheadOf && before != null && now != null && now.steps.size() == before.steps.size()
            && (now.steps.isEmpty() || now.steps.get(0).points.length == before.steps.get(0).points.length))
        {
            // The same cut of the same route as on the tick before (most ticks): nothing changed.
            now = before;
        }
        java.util.Set<Integer> walkedBefore = walkedAhead(before);
        java.util.Set<Integer> walkedNow = now == before ? walkedBefore : walked(now);
        if (route != null && route == aheadOf && before != null && now != null && player >= 0
            && walkedNow.size() > walkedBefore.size() && !now.steps.isEmpty()
            && Tiles.distance(player, now.steps.get(0).first()) > 2)
        {
            // Off the way (a new search is coming): what was passed stays gone instead of fading back in. Walking
            // back along the route itself does bring it back.
            return;
        }
        aheadOf = route;
        if (before != null && before != now)
        {
            // What dropped off fades away instead of vanishing.
            java.util.Set<Integer> gone = new java.util.HashSet<>(walkedBefore);
            gone.removeAll(walkedNow);
            fadeOut(gone);
            // Walked back: the tiles that return fade in.
            java.util.Set<Integer> back = new java.util.HashSet<>(walkedNow);
            back.removeAll(walkedBefore);
            fadeInTiles(back);
        }
        ahead = now;
        aheadWalkedOf = now;
        aheadWalked = Collections.unmodifiableSet(walkedNow);
        shown = config.routeInGame() ? now : null;
        int steps = now == null ? -1 : now.steps.size();
        int points = now == null || now.steps.isEmpty() ? -1 : now.steps.get(0).points.length;
        if (steps != lastAheadSteps && lastAheadSteps >= 0 && steps >= 0)
        {
            // A step further along: the steps passed show greyed out.
            showSteps(c, false);
        }
        else if (points != lastAheadPoints && steps >= 0)
        {
            // The sidebar's "Now" counts the tiles left of this walk down.
            sidebar.accept(width -> steps(c, width, sidebarFocus, true));
        }
        if (steps != lastAheadSteps || points != lastAheadPoints)
        {
            lastAheadSteps = steps;
            lastAheadPoints = points;
            for (MapScreen screen : screens)
            {
                screen.view().repaint();
            }
        }
    }

    private PlayerState capture()
    {
        Map<Integer, Long> carried = new HashMap<>();
        Set<Integer> unlimited = new HashSet<>();
        boolean pouch = false;
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null)
        {
            for (Item item : inventory.getItems())
            {
                if (item.getId() >= 0)
                {
                    carried.merge(item.getId(), (long) item.getQuantity(), Long::sum);
                    pouch |= RUNE_POUCHES.contains(item.getId());
                }
            }
        }
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn != null)
        {
            for (Item item : worn.getItems())
            {
                if (item.getId() >= 0)
                {
                    carried.merge(item.getId(), (long) item.getQuantity(), Long::sum);
                }
            }
            for (EquipmentInventorySlot slot : new EquipmentInventorySlot[]{EquipmentInventorySlot.WEAPON,
                EquipmentInventorySlot.SHIELD})
            {
                Item item = worn.getItem(slot.getSlotIdx());
                if (item != null && item.getId() >= 0)
                {
                    unlimited.addAll(ItemSnapshot.runesFromWeapon(client.getItemDefinition(item.getId()).getName()));
                }
            }
        }
        if (pouch)
        {
            EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
            for (int i = 0; i < POUCH_TYPES.length; i++)
            {
                int type = client.getVarbitValue(POUCH_TYPES[i]);
                int amount = client.getVarbitValue(POUCH_AMOUNTS[i]);
                int id = type > 0 && runes != null ? runes.getIntValue(type) : -1;
                if (id > 0 && amount > 0)
                {
                    carried.merge(id, (long) amount, Long::sum);
                }
            }
        }
        if (pandemonium == null || ticks - lastQuest >= QUEST_TICKS)
        {
            lastQuest = ticks;
            pandemonium = Quest.PANDEMONIUM.getState(client) == QuestState.FINISHED;
        }
        Set<Integer> boats = new LinkedHashSet<>();
        for (int varbit : BOAT_PORTS)
        {
            Integer at = ports.get(client.getVarbitValue(varbit));
            if (at != null)
            {
                boats.add(at);
            }
        }
        int[] boatTiles = boats.stream().mapToInt(Integer::intValue).toArray();
        if (bankChanged || bankItems == null)
        {
            // The bank is copied only when it changed, not with every capture.
            bankItems = ItemSnapshot.of(Collections.emptyMap(), Collections.emptySet(), bank);
            bankChanged = false;
        }
        return new PlayerState(ItemSnapshot.withBankOf(carried, unlimited, bankItems), client.getRealSkillLevel(Skill.SAILING),
            pandemonium, boatTiles, config.routeRunning(), house.inside(), house.exit(), house.own(), house.features());
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.BANK)
        {
            Map<Integer, Long> items = new HashMap<>();
            for (Item item : event.getItemContainer().getItems())
            {
                if (item.getId() >= 0)
                {
                    items.merge(item.getId(), (long) item.getQuantity(), Long::sum);
                }
            }
            bank = items;
            bankChanged = true;
        }
        state = capture();
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        if (!house.own())
        {
            return;
        }
        ObjectComposition object = client.getObjectDefinition(event.getGameObject().getId());
        if (object != null && object.getImpostorIds() != null)
        {
            object = object.getImpostor();
        }
        if (object != null)
        {
            house.seen(object.getName());
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // What this player's house has: the "Your house" settings (once taken over from where earlier versions
            // kept it per account).
            String saved = configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY);
            Set<String> features = houseSettings();
            if (saved != null && !saved.isEmpty() && features.isEmpty())
            {
                features.addAll(Arrays.asList(saved.split(",")));
                writeHouse(features);
            }
            if (saved != null)
            {
                configManager.unsetRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY);
            }
            if (!features.equals(house.features()))
            {
                house.reset();
                house.restore(features);
            }
            pandemonium = null;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            house.reset();
            bank = null;
            bankChanged = true;
            start = -2;
            state = PlayerState.UNKNOWN;
        }
    }

    /** What the "Your house" settings say the house has, in the house scan's words. */
    private Set<String> houseSettings()
    {
        return HouseSettings.features(config.houseJewelleryBox(), config.houseGlory(), config.houseFairyRing(),
            config.houseSpiritTree(), config.housePortals());
    }

    /** Puts what the house has into the "Your house" settings. */
    private void writeHouse(Set<String> features)
    {
        if (features.equals(houseSettings()))
        {
            return;
        }
        String group = HdMapReforgedConfig.GROUP;
        configManager.setConfiguration(group, "houseJewelleryBox", HouseSettings.box(features));
        configManager.setConfiguration(group, "houseGlory", features.contains("glory"));
        configManager.setConfiguration(group, "houseFairyRing", HouseSettings.fairyRing(features));
        configManager.setConfiguration(group, "houseSpiritTree", HouseSettings.spiritTree(features));
        configManager.setConfiguration(group, "housePortals", HouseSettings.portals(features));
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (HdMapReforgedConfig.GROUP.equals(event.getGroup()) && event.getKey().startsWith("house"))
        {
            // "Your house" changed by hand: routes use it at once.
            clientThread.invokeLater(() -> {
                Set<String> features = houseSettings();
                if (features.equals(house.features()))
                {
                    return;
                }
                house.replaceFeatures(features);
                state = capture();
                SwingUtilities.invokeLater(() -> {
                    RouteController c = controller;
                    if (c != null)
                    {
                        c.settingsChanged();
                    }
                });
            });
            return;
        }
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()) || !event.getKey().startsWith("route")
            || HOUSE_KEY.equals(event.getKey()) || "routeLog".equals(event.getKey()))
        {
            // Writing routes.log or not changes no route.
            return;
        }
        if (LOOK_KEYS.contains(event.getKey()))
        {
            // How the route looks: redraw, no new search.
            SwingUtilities.invokeLater(this::changed);
            sentAhead = null;
            return;
        }
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null)
            {
                c.settingsChanged();
            }
        });
    }

    // ---- Swing ----

    private RouteRequest request(int target)
    {
        return request(start, target, false, RouteRequest.DEFAULT_NODE_LIMIT);
    }

    /**
     * Nodes a search on the route extras thread may take: less than the route asked for, so two searches at once
     * (that one and extra work) hold less memory together.
     */
    static final int EXTRAS_NODE_LIMIT = 1_500_000;

    /**
     * With {@code anything}, every level, quest, unlock and item counts as had: the way anyone could go. Swing or the
     * route extras thread.
     */
    private RouteRequest request(int begin, int target, boolean anything, int nodeLimit)
    {
        RouteSource from = source;
        MapScreen[] on = screens;
        if (from == null || begin < -1 || on.length == 0)
        {
            return null;
        }
        MapView view = on[0].view();
        RouteSource.Options options = new RouteSource.Options(config.routeTeleports(), config.routeSailing(),
            config.routeBoatFocus(), shipwrights, anything || config.routeIgnoreLevels(),
            anything || config.routeIgnoreItems(), avoided(), anything ? RouteSource.Saving.NONE
                : new RouteSource.Saving(config.routeSaveTeleport(), config.routeSaveShortcut(),
                    config.routeSaveTransport(), config.routeSaveCanoe(), config.routeSaveCarpet(), config.routeSaveShip()));
        return from.request(begin, target, view.pois(), view.unlocks(), state, anything ? options.fallback() : options,
            nodeLimit);
    }

    /**
     * The first time a route is asked for: people who have Shortest Path switched on get it as planner, unless they
     * chose one themselves (an explicit choice is never changed).
     */
    private void choosePlannerOnce()
    {
        if (configManager.getConfiguration(HdMapReforgedConfig.GROUP, "routePlannerChosen") != null)
        {
            return;
        }
        configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routePlannerChosen", true);
        if (configManager.getConfiguration(HdMapReforgedConfig.GROUP, "routePlanner") == null && shortestPathOn())
        {
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routePlanner",
                HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH);
        }
    }

    /**
     * A route to a point, as "Path to here" asks for it: by this plugin, or handed to Shortest Path when that plans
     * routes. Swing thread; nothing happens while the route data loads.
     */
    void routeTo(WorldPoint point)
    {
        // A route asked for by hand ends a custom route being run.
        stopTour();
        choosePlannerOnce();
        if (shortestPathPlanner())
        {
            handOver(point);
            return;
        }
        RouteController now = controller;
        if (now != null)
        {
            bringUp = true;
            fromPlugin = false;
            routeAvoid.clear();
            now.setTarget(Tiles.pack(point.getX(), point.getY(), point.getPlane()));
        }
    }

    private void contribute(JPopupMenu popup, WorldPoint point)
    {
        choosePlannerOnce();
        RouteController c = controller;
        popup.addSeparator();
        // Custom routes, whichever planner: they are this plugin's own.
        JMenuItem add = new JMenuItem("Add to custom route");
        add.setToolTipText("Adds this spot as a stop of \"" + editingName() + "\"");
        add.addActionListener(a -> addStop(Tour.Stop.place(placeName(point), point)));
        popup.add(add);
        JMenuItem tours = new JMenuItem("Custom routes...");
        tours.setToolTipText("Your own routes with several stops: order them, let the planner find the fastest order, run");
        tours.addActionListener(a -> editTours());
        popup.add(tours);
        if (shortestPathPlanner())
        {
            JMenuItem path = new JMenuItem("Path to here (Shortest Path)");
            path.addActionListener(a -> routeTo(point));
            popup.add(path);
            if (handedOver != null)
            {
                JMenuItem clear = new JMenuItem(tour != null ? "Stop custom route" : "Clear path");
                clear.addActionListener(a -> endTour());
                popup.add(clear);
            }
            return;
        }
        JMenuItem path = new JMenuItem(c == null ? "Path to here (loading…)" : "Path to here");
        path.setEnabled(c != null);
        path.addActionListener(a -> routeTo(point));
        popup.add(path);
        if (c != null && c.target() >= 0)
        {
            JMenuItem clear = new JMenuItem(tour != null ? "Stop custom route" : "Clear path");
            clear.addActionListener(a -> {
                stopTour();
                c.clear();
            });
            popup.add(clear);
        }
    }

    /** Tiles a walkable area may have to count as one dungeon to fit the map to. */
    static final int AREA_LIMIT = 60_000;

    // ---- custom routes ----

    /** Where the player's own routes are kept (the plugin's settings, as text). */
    static final String TOURS_KEY = "customRoutes";
    static final String EDITING_KEY = "customRouteEditing";

    /** A custom route being run: its stops as they are gone to, and which one is next. */
    private static final class Running
    {
        final Tour tour;
        /** Changed on Swing, read on the client thread too. */
        volatile int index;
        /** Packed tile of the stop gone to now, or -1. */
        volatile int target = -1;
        /** Each stop's tile, worked out when the route starts (a kind: the one nearest the stop before); -1 none. */
        int[] points = new int[0];
        /**
         * The way to each stop from the one before, planned when the route starts, so the whole route shows at once;
         * null while planned or where none was found. The first is the way from the player (the controller's).
         */
        volatile Route[] legs = new Route[0];

        Running(Tour tour)
        {
            this.tour = tour;
        }

        Tour.Stop stop()
        {
            return tour.stops.get(index);
        }
    }

    /** The custom route being run, or null. Set on Swing; read on the client and route extras threads too. */
    private volatile Running tour;

    private List<Tour> tours()
    {
        return Tour.decode(configManager.getConfiguration(HdMapReforgedConfig.GROUP, TOURS_KEY));
    }

    private void saveTours(List<Tour> tours, String editing)
    {
        configManager.setConfiguration(HdMapReforgedConfig.GROUP, TOURS_KEY, Tour.encode(tours));
        configManager.setConfiguration(HdMapReforgedConfig.GROUP, EDITING_KEY, editing);
    }

    /** The route stops are added to: the last one edited, else the first, else a new "My route". */
    private String editingName()
    {
        String editing = configManager.getConfiguration(HdMapReforgedConfig.GROUP, EDITING_KEY);
        List<Tour> tours = tours();
        for (Tour t : tours)
        {
            if (t.name.equals(editing))
            {
                return editing;
            }
        }
        return tours.isEmpty() ? "My route" : tours.get(0).name;
    }

    /** Adds a stop to the route being made (see {@link #editingName}). Swing thread. */
    void addStop(Tour.Stop stop)
    {
        List<Tour> tours = tours();
        String name = editingName();
        Tour target = null;
        for (Tour t : tours)
        {
            if (t.name.equals(name))
            {
                target = t;
            }
        }
        if (target == null)
        {
            target = new Tour(name, new ArrayList<>());
            tours.add(target);
        }
        if (target.stops.size() >= Tour.MAX_STOPS)
        {
            JOptionPane.showMessageDialog(screens.length > 0 ? screens[0] : null, "\"" + name + "\" has "
                + Tour.MAX_STOPS + " stops, as many as a route can have. Make another route under Custom routes.",
                "Custom routes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        target.stops.add(stop);
        saveTours(tours, name);
        refreshTours();
        for (MapScreen screen : screens)
        {
            screen.showToast("Added to \"" + name + "\" (" + target.stops.size() + " stops)");
        }
    }

    /** A spot's name for a stop: the icon there, else its coordinates. */
    private String placeName(WorldPoint point)
    {
        if (screens.length > 0)
        {
            for (Poi poi : screens[0].view().pois())
            {
                if (poi.location.getPlane() == point.getPlane() && poi.location.distanceTo2D(point) <= 2)
                {
                    return poi.name;
                }
            }
        }
        return point.getX() + ", " + point.getY();
    }

    /** "Custom routes...": the routes' own panel on the map. */
    private void editTours()
    {
        for (MapScreen screen : screens)
        {
            screen.openTours();
        }
    }

    /** What the custom routes panel does. */
    private final TourPanel.Actions tourActions = new TourPanel.Actions()
    {
        @Override
        public List<Tour> tours()
        {
            return RouteFeature.this.tours();
        }

        @Override
        public String editing()
        {
            return editingName();
        }

        @Override
        public void save(List<Tour> tours, String editing)
        {
            saveTours(tours, editing);
            refreshTours();
        }

        @Override
        public void run(Tour tour)
        {
            runTour(tour);
        }

        @Override
        public void fastestOrder(Tour tour, Runnable done)
        {
            RouteFeature.this.fastestOrder(tour, done);
        }

        @Override
        public void cancelOrder()
        {
            RouteFeature.this.cancelOrder();
        }

        @Override
        public String running()
        {
            Running r = RouteFeature.this.tour;
            return r == null ? null : r.tour.name;
        }

        @Override
        public void stop()
        {
            endTour();
        }
    };

    /** Shows the custom routes again where their panel is open. */
    private void refreshTours()
    {
        for (MapScreen screen : screens)
        {
            screen.refreshTours();
        }
        sidebarTours.run();
    }

    /** Runs a custom route from its first stop; each arrival goes on to the next. Swing thread. */
    void runTour(Tour chosen)
    {
        if (chosen.stops.isEmpty())
        {
            return;
        }
        // A stop right after the same one (greyed out in the panel) is skipped.
        Running running = new Running(chosen.withoutRepeats());
        tour = running;
        int n = running.tour.stops.size();
        running.points = new int[n];
        running.legs = new Route[n];
        WorldPoint near = start >= 0 ? new WorldPoint(Tiles.x(start), Tiles.y(start), Tiles.z(start)) : null;
        for (int i = 0; i < n; i++)
        {
            WorldPoint p = resolve(running.tour.stops.get(i), near);
            running.points[i] = p == null ? -1 : Tiles.pack(p.getX(), p.getY(), p.getPlane());
            near = p != null ? p : near;
        }
        goToStop(start);
        planLegs(running);
        refreshTours();
    }

    /** Plans the way from each stop to the next in the background, so the whole route is drawn at once. */
    private void planLegs(Running running)
    {
        ExecutorService work = background;
        Pathfinder pf = walker;
        if (work == null || pf == null || shortestPathPlanner())
        {
            return;
        }
        work.execute(() -> {
            for (int i = 1; i < running.points.length; i++)
            {
                if (tour != running)
                {
                    return;
                }
                int from = running.points[i - 1];
                int to = running.points[i];
                RouteRequest request = from < 0 || to < 0 ? null : request(from, to, false, EXTRAS_NODE_LIMIT);
                Route leg;
                try
                {
                    leg = request == null ? null : pf.find(request, () -> tour != running || background == null);
                }
                catch (Throwable e)
                {
                    log.warn("Could not plan a part of the custom route", e);
                    return;
                }
                if (leg != null && leg.outcome != Route.Outcome.CANCELLED)
                {
                    int at = i;
                    SwingUtilities.invokeLater(() -> {
                        Route[] legs = running.legs.clone();
                        legs[at] = leg;
                        running.legs = legs;
                        for (MapScreen screen : screens)
                        {
                            screen.view().repaint();
                        }
                    });
                }
            }
        });
    }

    /** What the sidebar's list of custom routes does; {@code refresh} shows it again after a change. */
    TourPanel.Actions sidebarTours(Runnable refresh)
    {
        sidebarTours = refresh;
        return tourActions;
    }

    /** Also shows the route's steps in the sidebar ({@code show}); a step clicked there goes to {@code focus}. */
    void setSidebar(java.util.function.Consumer<java.util.function.IntFunction<JComponent>> show,
        java.util.function.Consumer<WorldPoint> focus)
    {
        sidebar = show;
        sidebarFocus = focus;
        changed();
    }

    /** The colour of part {@code leg} of a custom route: the route colour first, then others, one per part. */
    private Color legColor(int leg)
    {
        return leg <= 0 ? walk() : LEG_COLORS[(leg - 1) % LEG_COLORS.length];
    }

    /** The colour of the way shown now: that of its part while a custom route runs. */
    private Color current()
    {
        Running running = tour;
        return running == null ? walk() : legColor(running.index);
    }

    /** The parts of the running custom route after the one walked now, last first (drawn under the nearer ones). */
    private List<Map.Entry<Integer, Route>> laterLegs()
    {
        Running running = tour;
        List<Map.Entry<Integer, Route>> later = new ArrayList<>();
        if (running == null)
        {
            return later;
        }
        Route[] legs = running.legs;
        for (int i = legs.length - 1; i > running.index; i--)
        {
            if (legs[i] != null && !legs[i].steps.isEmpty())
            {
                later.add(new java.util.AbstractMap.SimpleImmutableEntry<>(i, legs[i]));
            }
        }
        return later;
    }

    /** Stops the custom route and clears its way, whoever planned it. */
    private void endTour()
    {
        RouteController c = controller;
        stopTour();
        if (c != null)
        {
            c.clear();
        }
        if (handedOver != null)
        {
            handOverClear();
        }
    }

    private void stopTour()
    {
        if (tour != null)
        {
            tour = null;
            refreshTours();
        }
    }

    /** Arrived at a stop: the next one, or done after the last. */
    private void nextStop(int at)
    {
        Running running = tour;
        RouteController c = controller;
        if (running == null || c == null)
        {
            return;
        }
        running.index++;
        if (running.index >= running.tour.stops.size())
        {
            fadeOut(walkedAhead(ahead));
            tour = null;
            c.clear();
            if (handedOver != null)
            {
                handOverClear();
            }
            refreshTours();
            for (MapScreen screen : screens)
            {
                screen.showToast("\"" + running.tour.name + "\" done");
            }
            return;
        }
        goToStop(at);
    }

    /** Routes to the running route's current stop; a kind becomes the one of it nearest {@code from}. */
    private void goToStop(int from)
    {
        Running running = tour;
        RouteController c = controller;
        if (running == null || c == null)
        {
            return;
        }
        int planned = running.index < running.points.length ? running.points[running.index] : -1;
        WorldPoint to = planned >= 0 ? new WorldPoint(Tiles.x(planned), Tiles.y(planned), Tiles.z(planned))
            : resolve(running.stop(), from >= 0 ? new WorldPoint(Tiles.x(from), Tiles.y(from), Tiles.z(from)) : null);
        if (to == null)
        {
            // A kind the map has no place of (its icons not loaded yet): skipped.
            nextStop(from);
            return;
        }
        running.target = Tiles.pack(to.getX(), to.getY(), to.getPlane());
        if (shortestPathPlanner())
        {
            // Shortest Path plans each leg, from where the player is.
            handOver(to);
            return;
        }
        bringUp = true;
        fromPlugin = false;
        routeAvoid.clear();
        Route[] legs = running.legs;
        Route leg = running.index > 0 && running.index < legs.length ? legs[running.index] : null;
        if (leg != null && leg.outcome == Route.Outcome.FOUND)
        {
            // Planned when the route started, from the stop before: shown at once, the same as drawn.
            c.show(running.target, leg);
            return;
        }
        c.setTarget(running.target);
    }

    /** Where a stop is: its place, or for a kind the place of it nearest {@code near} (the first when unknown). */
    private WorldPoint resolve(Tour.Stop stop, WorldPoint near)
    {
        if (!stop.isKind())
        {
            return stop.point;
        }
        return Tour.nearest(screens.length == 0 ? null : screens[0].kind(stop.kind), near);
    }

    /** What to do now, then next, and the time left: large, at the top of the sidebar's route. */
    static JComponent nowBox(Route left, int width, Color walk, Color jump)
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(ColorScheme.DARK_GRAY_COLOR);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, walk),
            BorderFactory.createEmptyBorder(6, 8, 6, 6)));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        int inner = width - 17;
        box.add(label("Now", inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        Route.Step now = left.steps.get(0);
        JLabel doing = label(RouteText.describe(now), inner, Font.BOLD, color(now, walk, jump));
        doing.setFont(doing.getFont().deriveFont(Font.BOLD, doing.getFont().getSize2D() + 3));
        box.add(doing);
        if (now.detail != null && !RouteText.unsure(now.detail))
        {
            box.add(label(now.detail, inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        }
        for (int i = 1; i < Math.min(3, left.steps.size()); i++)
        {
            Route.Step then = left.steps.get(i);
            box.add(Box.createVerticalStrut(4));
            box.add(label((i == 1 ? "Then: " : "After that: ") + RouteText.describe(then), inner, Font.PLAIN,
                color(then, walk, jump)));
        }
        box.add(Box.createVerticalStrut(4));
        box.add(label(RouteText.duration(left) + " left", inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        JPanel spaced = new JPanel();
        spaced.setLayout(new BoxLayout(spaced, BoxLayout.Y_AXIS));
        spaced.setOpaque(false);
        spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
        spaced.add(box);
        spaced.add(Box.createVerticalStrut(8));
        return spaced;
    }

    /** "Stop 2 of 5: Catherby patch", with Skip and Stop, above a running custom route's steps. */
    private JComponent tourHeader(Running running, int width)
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        // In the colour of the part walked now, as its tiles; below, every stop in the colour of the way to it.
        header.add(label("\u25B6 " + running.tour.name + ": stop " + (running.index + 1) + " of "
            + running.tour.stops.size(), width, Font.BOLD, legColor(running.index)));
        for (int i = 0; i < running.tour.stops.size(); i++)
        {
            boolean passed = i < running.index;
            boolean now = i == running.index;
            header.add(label((i + 1) + ". " + running.tour.stops.get(i).name, width, now ? Font.BOLD : Font.PLAIN,
                passed ? ColorScheme.MEDIUM_GRAY_COLOR : legColor(i)));
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton skip = new JButton(running.index + 1 < running.tour.stops.size() ? "Next stop" : "Finish");
        skip.setFocusable(false);
        skip.addActionListener(e -> nextStop(start));
        JButton stop = new JButton("Stop route");
        stop.setFocusable(false);
        stop.addActionListener(e -> endTour());
        buttons.add(skip);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(stop);
        header.add(buttons);
        header.add(Box.createVerticalStrut(6));
        return header;
    }

    /**
     * Puts a route's stops in the order that takes least from where the player is, by the planner's own routes
     * between every two of them (in the background: a dozen stops take a while), then saves it and shows it; unless
     * the route's stops changed meanwhile. {@code done} runs on Swing in any case, also when cancelled or failed.
     */
    private void fastestOrder(Tour chosen, Runnable done)
    {
        ExecutorService work = background;
        Pathfinder pf = walker;
        int from = start;
        if (work == null || pf == null)
        {
            done.run();
            return;
        }
        // The stops as they are now: the work runs on them, and its order is only kept for these same stops.
        String name = chosen.name;
        List<Tour.Stop> stops = Collections.unmodifiableList(new ArrayList<>(chosen.stops));
        int order = orders.incrementAndGet();
        WorldPoint me = from >= 0 ? new WorldPoint(Tiles.x(from), Tiles.y(from), Tiles.z(from)) : null;
        List<WorldPoint> points = new ArrayList<>();
        points.add(me);
        WorldPoint previous = me;
        for (Tour.Stop stop : stops)
        {
            WorldPoint p = resolve(stop, previous);
            points.add(p);
            previous = p != null ? p : previous;
        }
        for (MapScreen screen : screens)
        {
            screen.showToast("Working out the fastest order of " + stops.size() + " stops…");
        }
        java.util.function.BooleanSupplier cancelled = () -> background == null || orders.get() != order;
        try
        {
            work.execute(() -> {
                List<Integer> sorted = null;
                try
                {
                    int n = points.size();
                    long[][] cost = new long[n][n];
                    for (int a = 0; a < n && !cancelled.getAsBoolean(); a++)
                    {
                        for (int b = 0; b < n; b++)
                        {
                            cost[a][b] = a == b ? 0 : between(pf, points.get(a), points.get(b), cancelled);
                        }
                    }
                    sorted = cancelled.getAsBoolean() ? null : Tour.fastestOrder(cost);
                }
                catch (Throwable e)
                {
                    log.warn("Could not work out the fastest order", e);
                }
                finally
                {
                    List<Integer> result = sorted;
                    SwingUtilities.invokeLater(() -> {
                        try
                        {
                            if (result != null && orders.get() == order)
                            {
                                applyOrder(name, stops, result);
                            }
                        }
                        finally
                        {
                            done.run();
                        }
                    });
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            done.run();
        }
    }

    /** Saves the stops in {@code order}, if the route still has just the stops the order was worked out for. */
    private void applyOrder(String name, List<Tour.Stop> stops, List<Integer> order)
    {
        List<Tour> all = tours();
        for (int i = 0; i < all.size(); i++)
        {
            Tour now = all.get(i);
            if (!now.name.equals(name))
            {
                continue;
            }
            if (!sameStops(now.stops, stops))
            {
                for (MapScreen screen : screens)
                {
                    screen.showToast("\"" + name + "\" changed meanwhile: its order was left as it is");
                }
                return;
            }
            List<Tour.Stop> sorted = new ArrayList<>();
            for (int at : order)
            {
                sorted.add(now.stops.get(at - 1));
            }
            all.set(i, new Tour(name, sorted));
            saveTours(all, name);
            return;
        }
    }

    private static boolean sameStops(List<Tour.Stop> a, List<Tour.Stop> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            if (!a.get(i).same(b.get(i)) || !a.get(i).name.equals(b.get(i).name))
            {
                return false;
            }
        }
        return true;
    }

    /** Stops working out the fastest order, if it runs (the panel closed, another route picked). */
    private void cancelOrder()
    {
        orders.incrementAndGet();
    }

    /** Which working out of the fastest order is current; any other one stops and keeps nothing. */
    private final java.util.concurrent.atomic.AtomicInteger orders = new java.util.concurrent.atomic.AtomicInteger();

    /** How long the planner's route from one place to another takes (half ticks), or -1 when it finds none. */
    private long between(Pathfinder pf, WorldPoint a, WorldPoint b, java.util.function.BooleanSupplier cancelled)
    {
        if (b == null)
        {
            return -1;
        }
        if (a == null)
        {
            // The player's place is not known: every first stop is as good.
            return 0;
        }
        RouteRequest request = request(Tiles.pack(a.getX(), a.getY(), a.getPlane()), Tiles.pack(b.getX(), b.getY(),
            b.getPlane()), false, EXTRAS_NODE_LIMIT);
        if (request == null)
        {
            return -1;
        }
        Route route = pf.find(request, cancelled);
        return route.outcome == Route.Outcome.FOUND ? route.cost : -1;
    }

    /** The house location varbit, as last seen on the client thread. */
    private volatile int houseLocation;

    /** Said when not even every requirement met would get there: the map's data lacks a passage. */
    static final String NO_WAY = "No way found, even with everything.";

    /** Swing thread: for a route that cannot reach its target, the way it would go with every requirement met. */
    private Route why;
    private volatile Route whyFor;
    /** The target {@link #why} leads to, kept while a new search for the same target runs. */
    private int whyTarget = -1;
    /** Whether the search for {@link #whyFor} still runs. */
    private boolean explaining;

    /** When the target cannot be reached, looks in the background for what is missing. Swing thread. */
    private void explain(RouteController c, Route route)
    {
        if (route == whyFor)
        {
            return;
        }
        whyFor = route;
        explaining = false;
        if (c.target() != whyTarget)
        {
            why = null;
            whyTarget = c.target();
        }
        ExecutorService pool = background;
        Pathfinder pathfinder = walker;
        // Whenever the player's way does not arrive (also when it stopped at the search's limit), the way anyone could
        // go is looked for, so the whole route shows with what is missing in red.
        if (route == null || route.outcome != Route.Outcome.NEAREST || pool == null
            || pathfinder == null || config.routeIgnoreLevels() && config.routeIgnoreItems())
        {
            why = null;
            return;
        }
        RouteRequest request = request(start, c.target(), true, EXTRAS_NODE_LIMIT);
        if (request == null)
        {
            // Try again on the next change, once the start is known.
            whyFor = null;
            return;
        }
        explaining = true;
        try
        {
            pool.execute(() -> explained(c, route, request, pathfinder));
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            explaining = false;
        }
    }

    /** The way anyone could go, on the route extras thread; handed to Swing whatever happens, so nothing waits on it. */
    private void explained(RouteController c, Route route, RouteRequest request, Pathfinder pathfinder)
    {
        Route result = null;
        try
        {
            result = pathfinder.find(request, () -> whyFor != route);
        }
        catch (Throwable e)
        {
            log.warn("Could not look for the way anyone could go", e);
        }
        finally
        {
            Route found = result;
            SwingUtilities.invokeLater(() -> {
                if (whyFor == route)
                {
                    why = found;
                    explaining = false;
                    RouteLog log = config.routeLog() ? routeLog : null;
                    if (log != null && found != null)
                    {
                        log.add("The way anyone could go", start, c.target(), state, found);
                    }
                    changed();
                }
            });
        }
    }

    /**
     * Why the target cannot be reached: the steps of the way there that need what the player lacks. Null while
     * looking or when there is nothing to say; empty when the way there needs nothing this plugin can name.
     */
    static java.util.List<String> blockers(Route why)
    {
        if (why == null || why.outcome == Route.Outcome.CANCELLED)
        {
            return null;
        }
        if (why.outcome != Route.Outcome.FOUND)
        {
            // Only a search that tried everything may say there is no way; one that ran out of room knows nothing.
            return why.exhausted ? java.util.Collections.singletonList(NO_WAY) : null;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Route.Step step : why.steps)
        {
            if (step.detail != null && step.detail.startsWith("You lack"))
            {
                String lack = step.detail.replaceFirst("^You lack:?\\s*", "");
                lines.add(RouteText.describe(step) + ": needs " + lack);
            }
        }
        return lines;
    }

    /**
     * The route to show: the planned one, or when that cannot get there, the way with every requirement met (its
     * steps that need what the player lacks are marked).
     */
    private Route effective(RouteController c)
    {
        Route route = c.route();
        if (unreachable(route))
        {
            return why;
        }
        // Not the way to the nearest cave while the real way is being looked for.
        return route != null && route.outcome == Route.Outcome.NEAREST && explaining ? null : route;
    }

    /** Whether {@code route} cannot get there and the way with every requirement met is known. */
    private boolean unreachable(Route route)
    {
        Route way = why;
        return route != null && route.outcome == Route.Outcome.NEAREST && route == whyFor && way != null
            && (way.outcome == Route.Outcome.FOUND || way.outcome == Route.Outcome.NEAREST && closer(way, route));
    }

    /** Whether {@code a} ends nearer its target than {@code b} does: the way anyone could go that gets closer. */
    private static boolean closer(Route a, Route b)
    {
        if (a.end < 0 || a.steps.isEmpty())
        {
            return false;
        }
        if (b.end < 0 || b.steps.isEmpty())
        {
            return true;
        }
        return distance(a.end, a.target) < distance(b.end, b.target);
    }

    private static int distance(int from, int to)
    {
        return Tiles.distance(from, to) + (Tiles.z(from) == Tiles.z(to) ? 0 : 32);
    }

    /** The last routes planned, written for looking at afterwards (only with the setting on); null until set. */
    private volatile RouteLog routeLog;
    private Route logged;

    void setRouteLog(RouteLog log)
    {
        routeLog = log;
    }

    private void changed()
    {
        RouteController c = controller;
        if (c != null)
        {
            RouteLog log = config.routeLog() ? routeLog : null;
            Route planned = c.route();
            if (log != null && planned != null && planned != logged)
            {
                logged = planned;
                log.add("Your way", start, c.target(), state, planned);
            }
            explain(c, c.route());
        }
        Route route = c == null ? null : effective(c);
        Route before = ahead;
        ahead = route == null ? null : route.ahead(start);
        aheadOf = route;
        if (before != null && before != ahead)
        {
            // A new search replaced the way: the old way's tiles fade out instead of vanishing.
            java.util.Set<Integer> gone = new java.util.HashSet<>(walkedAhead(before));
            gone.removeAll(walkedAhead(ahead));
            fadeOut(gone);
        }
        shown = config.routeInGame() ? ahead : null;
        boolean up = bringUp;
        bringUp = false;
        showSteps(c, up);
        for (MapScreen screen : screens)
        {
            screen.view().repaint();
        }
    }

    /** The step list in the maps' cards and the sidebar, as the route is now. */
    private void showSteps(RouteController c, boolean up)
    {
        for (MapScreen screen : screens)
        {
            if (c == null || c.target() < 0)
            {
                screen.showRoute(null, false);
            }
            else
            {
                screen.showRoute(width -> steps(c, width, screen.view()::focus), up);
            }
        }
        sidebar.accept(c == null || c.target() < 0 ? null : width -> steps(c, width, sidebarFocus, true));
    }

    /** Steps avoided for the current route only (lower case), cleared with a new target. */
    private final java.util.Set<String> routeAvoid = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The "Never use" setting, one name per line. */
    private java.util.List<String> neverUse()
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String line : config.routeNeverUse().split("[\\r\\n]+"))
        {
            if (!line.trim().isEmpty())
            {
                names.add(line.trim());
            }
        }
        return names;
    }

    private java.util.Set<String> avoided()
    {
        java.util.Set<String> all = new java.util.HashSet<>(routeAvoid);
        for (String name : neverUse())
        {
            all.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return all;
    }

    /** What the ⋯ button of a step does. */
    interface StepActions
    {
        void avoid(String name);

        void never(String name);

        void allow(String name);

        java.util.List<String> neverUsed();

        /** See {@link RouteFeature#blockers}. */
        default java.util.List<String> blockers()
        {
            return null;
        }

        /** Whether the route shown is the way with every requirement met, as the target cannot be reached. */
        default boolean unreachable()
        {
            return false;
        }
    }

    private final StepActions stepActions = new StepActions()
    {
        @Override
        public void avoid(String name)
        {
            routeAvoid.add(name.toLowerCase(java.util.Locale.ROOT));
            RouteController c = controller;
            if (c != null)
            {
                c.settingsChanged();
            }
        }

        @Override
        public void never(String name)
        {
            java.util.List<String> names = neverUse();
            if (!names.contains(name))
            {
                names.add(name);
                // The settings change searches again.
                configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routeNeverUse", String.join("\n", names));
            }
        }

        @Override
        public void allow(String name)
        {
            java.util.List<String> names = neverUse();
            names.removeIf(n -> n.equalsIgnoreCase(name));
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routeNeverUse", String.join("\n", names));
        }

        @Override
        public java.util.List<String> neverUsed()
        {
            return neverUse();
        }

        @Override
        public java.util.List<String> blockers()
        {
            return RouteFeature.blockers(why);
        }

        @Override
        public boolean unreachable()
        {
            RouteController c = controller;
            return c != null && RouteFeature.this.unreachable(c.route());
        }
    };

    private JComponent steps(RouteController c, int width, java.util.function.Consumer<WorldPoint> focus)
    {
        return steps(c, width, focus, false);
    }

    /** With {@code roomy} (the sidebar, with room to spare): first a box with what to do now and next. */
    private JComponent steps(RouteController c, int width, java.util.function.Consumer<WorldPoint> focus, boolean roomy)
    {
        Route route = effective(c);
        String status = route == null && explaining ? "Cannot get there with what you have; looking for the way there…"
            : c.status();
        // The steps the player is past already (what is left of the way is cut from this route).
        Route left = ahead;
        int done = route != null && route == aheadOf && left != null ? route.steps.size() - left.steps.size() : 0;
        JComponent panel = panel(route, c.target(), status, width, focus, () -> {
            stopTour();
            c.clear();
        }, stepActions, current(), jump(), done);
        if (roomy && route != null && left != null && !left.steps.isEmpty())
        {
            panel.add(nowBox(left, width, current(), jump()), 1);
        }
        Running running = tour;
        if (running != null)
        {
            panel.add(tourHeader(running, width), 0);
        }
        PlayerState planned = state;
        if (route != null && route.has(Route.Step.Kind.SAIL) && planned != null)
        {
            // Which Sailing level the route was planned for, so a wrong dock choice can be told apart from a wrong level.
            String text = config.routeIgnoreLevels() ? "Planned ignoring levels"
                : "Planned for Sailing level " + planned.sailingLevel;
            panel.add(label(text, width, Font.PLAIN, ColorScheme.MEDIUM_GRAY_COLOR), 1);
        }
        return panel;
    }

    /** The step list: a title, notes, the steps (click one to see it on the map) and "Clear path". */
    static JComponent panel(Route route, int target, String status, int width,
        java.util.function.Consumer<WorldPoint> focus, Runnable clear)
    {
        return panel(route, target, status, width, focus, clear, null);
    }

    static JComponent panel(Route route, int target, String status, int width,
        java.util.function.Consumer<WorldPoint> focus, Runnable clear, StepActions actions)
    {
        return panel(route, target, status, width, focus, clear, actions, WALK, JUMP, 0);
    }

    /** With the colours the route is drawn in now ({@code walk}, {@code jump}), so each step reads like its tiles. */
    static JComponent panel(Route route, int target, String status, int width,
        java.util.function.Consumer<WorldPoint> focus, Runnable clear, StepActions actions, Color walk, Color jump,
        int done)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JLabel title = label("Route" + (Tiles.z(target) > 0 ? " (floor " + Tiles.z(target) + ")" : ""), width,
            Font.BOLD, Color.WHITE);
        panel.add(title);
        if (route == null)
        {
            panel.add(label(status != null ? status : "Searching…", width, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        }
        else
        {
            for (String note : RouteText.notes(route))
            {
                panel.add(label(note, width, Font.PLAIN, new Color(255, 190, 120)));
            }
            boolean unreachable = actions != null && actions.unreachable();
            int blocked = unreachable ? RouteText.blockedFrom(route) : -1;
            if (unreachable)
            {
                // The way anyone could go: one line of what the player lacks; the route from there on is red.
                panel.add(label(RouteText.cannotYet(route), width, Font.BOLD, CANNOT));
            }
            java.util.List<String> blockers = actions == null || route.outcome != Route.Outcome.NEAREST ? null
                : actions.blockers();
            if (blockers != null)
            {
                // What stands in the way, found by planning as if every requirement were met.
                panel.add(label(blockers.isEmpty() ? "Needs something the map can't name (a quest step, a key)."
                    : blockers.contains(NO_WAY) ? NO_WAY : "Needs: " + String.join(", ", blockers), width, Font.PLAIN,
                    new Color(255, 190, 120)));
            }
            if (!route.steps.isEmpty())
            {
                panel.add(label(RouteText.duration(route) + (route.outcome == Route.Outcome.NEAREST
                    ? " to where the route ends" : ""), width, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
            }
            String bring = RouteText.bring(route);
            if (bring != null)
            {
                // Vines, jungle and webs do not stop the route: a tip of what to take along.
                panel.add(label("Bring: " + bring, width, Font.PLAIN, new Color(150, 220, 150)));
            }
            int number = 1;
            boolean unsure = false;
            for (Route.Step step : route.steps)
            {
                // On the way anyone could go: the step the player cannot take, and what follows it, in red.
                boolean lacking = unreachable && RouteText.lacks(step);
                boolean cannot = blocked >= 0 && number - 1 >= blocked;
                String detail = lacking ? "Needs " + step.detail.replaceFirst("^You lack:?\\s*", "") : step.detail;
                if (!lacking && RouteText.unsure(detail))
                {
                    // Said once under the steps instead of at each.
                    unsure = true;
                    detail = null;
                }
                JButton row = new JButton("<html><body style='width:" + Math.max(80, width - 40) + "px'>" + number++ + ". "
                    + RouteText.escape(RouteText.describe(step))
                    + (detail != null ? "<br><span style='color:" + (cannot ? "#ff7070" : "#a0a0a0") + "'>"
                        + RouteText.escape(detail) + "</span>" : "")
                    + "</body></html>");
                row.setHorizontalAlignment(JButton.LEFT);
                row.setBorderPainted(false);
                row.setContentAreaFilled(false);
                row.setFocusable(false);
                // Steps already passed: grey.
                row.setForeground(number - 2 < done ? ColorScheme.MEDIUM_GRAY_COLOR : cannot ? CANNOT
                    : color(step, walk, jump));
                row.setMargin(new java.awt.Insets(1, 0, 1, 0));
                // A passage, stairs or transport shows where it leads (the dungeon, the floor above): that is the
                // other map the step goes to.
                boolean leads = step.isJump() && !Tiles.isSea(step.last()) && step.first() != step.last();
                int point = leads ? step.last() : step.first();
                row.setToolTipText(leads ? "Show where this leads on the map" : "Show on the map");
                row.addActionListener(a -> focus.accept(new WorldPoint(Tiles.x(point), Tiles.y(point), Tiles.z(point))));
                row.setAlignmentX(0);
                boolean choice = step.name != null && (step.kind == Route.Step.Kind.TELEPORT
                    || step.kind == Route.Step.Kind.TRANSPORT || step.kind == Route.Step.Kind.SHIP
                    || step.kind == Route.Step.Kind.ENTRANCE && step.category != null);
                if (actions == null || !choice)
                {
                    panel.add(row);
                    continue;
                }
                // Teleports and transports: ⋯ to leave them out of this route, or of every route.
                JPanel line = new JPanel(new java.awt.BorderLayout());
                line.setOpaque(false);
                line.setAlignmentX(0);
                line.add(row, java.awt.BorderLayout.CENTER);
                JButton more = new JButton("⋯");
                more.setFocusable(false);
                more.setMargin(new java.awt.Insets(0, 4, 0, 4));
                more.setToolTipText("Leave this out of the route");
                String name = step.name;
                String category = step.category;
                more.addActionListener(a -> {
                    javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                    javax.swing.JMenuItem avoid = new javax.swing.JMenuItem("Avoid for this route");
                    avoid.addActionListener(e -> actions.avoid(name));
                    menu.add(avoid);
                    if (category != null)
                    {
                        // A whole kind: every hot air balloon, every minigame teleport.
                        javax.swing.JMenuItem avoidAll = new javax.swing.JMenuItem("Avoid every " + category
                            + " for this route");
                        avoidAll.addActionListener(e -> actions.avoid(RouteSource.TYPE + category));
                        menu.add(avoidAll);
                    }
                    menu.addSeparator();
                    javax.swing.JMenuItem never = new javax.swing.JMenuItem("Never use \"" + name + "\"");
                    never.addActionListener(e -> actions.never(name));
                    menu.add(never);
                    if (category != null)
                    {
                        javax.swing.JMenuItem neverAll = new javax.swing.JMenuItem("Never use any " + category);
                        neverAll.addActionListener(e -> actions.never(RouteSource.TYPE + category));
                        menu.add(neverAll);
                    }
                    menu.show(more, 0, more.getHeight());
                });
                line.add(more, java.awt.BorderLayout.EAST);
                line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
                panel.add(line);
            }
            if (unsure)
            {
                panel.add(Box.createVerticalStrut(4));
                panel.add(label("Some steps may need a quest, level or item the map does not know about.", width,
                    Font.PLAIN, ColorScheme.MEDIUM_GRAY_COLOR));
            }
        }
        if (actions != null && !actions.neverUsed().isEmpty())
        {
            // What the route leaves out, with a way back.
            panel.add(Box.createVerticalStrut(6));
            panel.add(label("Never used:", width, Font.BOLD, ColorScheme.LIGHT_GRAY_COLOR));
            for (String never : actions.neverUsed())
            {
                String shown = never.regionMatches(true, 0, RouteSource.TYPE, 0, RouteSource.TYPE.length())
                    ? "Every " + never.substring(RouteSource.TYPE.length()) : never;
                JButton allow = new JButton("✕  " + shown);
                allow.setBorderPainted(false);
                allow.setContentAreaFilled(false);
                allow.setFocusable(false);
                allow.setHorizontalAlignment(JButton.LEFT);
                allow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                allow.setToolTipText("Use it again");
                allow.setAlignmentX(0);
                allow.addActionListener(a -> actions.allow(never));
                panel.add(allow);
            }
        }
        panel.add(Box.createVerticalStrut(4));
        JButton clearButton = new JButton("Clear path");
        clearButton.setFocusable(false);
        clearButton.setAlignmentX(0);
        clearButton.setToolTipText("Remove the route from the map");
        clearButton.addActionListener(a -> clear.run());
        panel.add(clearButton);
        return panel;
    }

    private static JLabel label(String text, int width, int style, Color color)
    {
        JLabel label = new JLabel("<html><body style='width:" + Math.max(80, width - 30) + "px'>" + RouteText.escape(text)
            + "</body></html>");
        label.setFont(label.getFont().deriveFont(style));
        label.setForeground(color);
        label.setAlignmentX(0);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return label;
    }

    private static Color color(Route.Step step, Color walk, Color jump)
    {
        switch (step.kind)
        {
            case WALK:
                return walk;
            case SAIL:
            case BOARD:
            case DISEMBARK:
                return SEA;
            default:
                return jump;
        }
    }

    /** The route on our map: walking lines, dashed jumps, sailing legs in a sea colour. */
    private void paintMap(Graphics2D g, MapView.Projection p)
    {
        if (shortestPathPlanner())
        {
            paintHandedOver(g, p);
            return;
        }
        Route route = ahead;
        for (Map.Entry<Integer, Route> leg : laterLegs())
        {
            // A custom route's later parts, each in its own colour, under the part walked now.
            RouteText.paint(g, p, leg.getValue(), legColor(leg.getKey()), jump());
        }
        if (route == null && fading.isEmpty())
        {
            return;
        }
        if (route != null)
        {
            RouteText.paint(g, p, route, current(), jump());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            int point = entry.getKey();
            WorldPoint wp = new WorldPoint(Tiles.x(point), Tiles.y(point), Tiles.z(point));
            float alpha = fadeAlpha(entry.getValue(), now);
            WorldPoint at = p.shown(wp);
            if (alpha <= 0 || !p.shows(wp) || at.getPlane() != p.plane())
            {
                continue;
            }
            double r = Math.max(1.5, Math.pow(2, p.zoom()) * 0.35);
            g.setColor(alpha(walk(), alpha * 200));
            g.fill(new Ellipse2D.Double(p.screenX(at.getX() + 0.5) - r, p.screenY(at.getY() + 0.5) - r, r * 2, r * 2));
        }
    }

    /**
     * The route on the game's own world map: small squares on the tiles walked, rings where a teleport or transport
     * starts and ends, dashed lines for the jumps. Uses RuneLite's world map position mapping.
     */
    private final class WorldMapRoute extends Overlay
    {
        WorldMapRoute()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.MANUAL);
            drawAfterInterface(net.runelite.api.gameval.InterfaceID.WORLDMAP);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            net.runelite.api.widgets.Widget map = client.getWidget(net.runelite.api.gameval.InterfaceID.Worldmap.MAP_CONTAINER);
            if (route == null || map == null || map.isHidden())
            {
                return null;
            }
            java.awt.Rectangle bounds = map.getBounds();
            java.awt.Shape clip = g.getClip();
            g.setClip(bounds);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            for (Route.Step step : route.steps)
            {
                if (step.kind == Route.Step.Kind.WALK)
                {
                    g.setColor(walk());
                    // Every other tile keeps it light on a zoomed-out map.
                    for (int i = 0; i < step.points.length; i += 2)
                    {
                        net.runelite.api.Point p = onMap(step.points[i]);
                        if (p != null)
                        {
                            g.fillRect(p.getX() - 1, p.getY() - 1, 3, 3);
                        }
                    }
                }
                else if (step.points.length >= 2 && step.kind != Route.Step.Kind.SAIL)
                {
                    net.runelite.api.Point a = step.first() >= 0 && step.kind != Route.Step.Kind.TELEPORT ? onMap(step.first()) : null;
                    net.runelite.api.Point b = onMap(step.points[step.points.length - 1]);
                    g.setColor(step.kind == Route.Step.Kind.BOARD || step.kind == Route.Step.Kind.DISEMBARK ? SEA : jump());
                    if (a != null && b != null)
                    {
                        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{6f, 5f}, 0f));
                        g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
                    }
                    for (net.runelite.api.Point p : new net.runelite.api.Point[]{a, b})
                    {
                        if (p != null)
                        {
                            g.setStroke(new BasicStroke(2f));
                            g.drawOval(p.getX() - 5, p.getY() - 5, 10, 10);
                        }
                    }
                }
                else if (step.kind == Route.Step.Kind.SAIL)
                {
                    g.setColor(SEA);
                    for (int point : step.points)
                    {
                        net.runelite.api.Point p = onMap(point);
                        if (p != null)
                        {
                            g.fillRect(p.getX() - 1, p.getY() - 1, 3, 3);
                        }
                    }
                }
            }
            g.setClip(clip);
            return null;
        }

        private net.runelite.api.Point onMap(int node)
        {
            if (node < 0)
            {
                return null;
            }
            int x = Tiles.isSea(node) ? Tiles.cellX(node) * 4 + 2 : Tiles.x(node);
            int y = Tiles.isSea(node) ? Tiles.cellY(node) * 4 + 2 : Tiles.y(node);
            int z = Tiles.isSea(node) ? 0 : Tiles.z(node);
            return worldMapOverlay.mapWorldPointToGraphicsPoint(new WorldPoint(x, y, z));
        }
    }

    /** The step to take right now, when it is a teleport, or a transport or passage the player stands at; or null. */
    static Route.Step nextAction(Route route, int player)
    {
        for (Route.Step step : route.steps)
        {
            if (step.kind == Route.Step.Kind.WALK)
            {
                // Still some walking before anything else.
                if (step.points.length > 3)
                {
                    return null;
                }
                continue;
            }
            if (step.kind == Route.Step.Kind.TELEPORT || step.kind == Route.Step.Kind.HOUSE)
            {
                return step;
            }
            if (step.kind == Route.Step.Kind.SAIL || step.kind == Route.Step.Kind.DISEMBARK)
            {
                return null;
            }
            int at = step.first();
            return player >= 0 && at >= 0 && Tiles.distance(player, at) <= 3 ? step : null;
        }
        return null;
    }

    /** Tiles ahead within which an obstacle to cut through is shown over the player. */
    private static final int OBSTACLE_AHEAD = 5;

    /** What to do now, such as "Cast Varrock Teleport" or "Chop-down Vines (bring an axe)", or null. */
    static String nextText(Route route, int player)
    {
        for (Route.Step step : route.steps)
        {
            if (step.kind != Route.Step.Kind.WALK)
            {
                break;
            }
            for (Route.Obstacle obstacle : step.obstacles)
            {
                for (int i = 0; i < step.points.length && i <= OBSTACLE_AHEAD; i++)
                {
                    if (step.points[i] == obstacle.at)
                    {
                        return obstacle.text;
                    }
                }
            }
            if (step.points.length > OBSTACLE_AHEAD)
            {
                break;
            }
        }
        Route.Step step = nextAction(route, player);
        if (step == null)
        {
            return null;
        }
        // On the way shown for a place out of reach, a step that cannot be taken yet is said in red (see the paint).
        return RouteText.describe(step);
    }

    /** Over the player's head: what to do now, such as "Cast Varrock Teleport". */
    private void paintNextAction(Graphics2D g, Route route, Player player)
    {
        WorldPoint me = lastLocation;
        String text = nextText(route, me == null ? -1 : Tiles.pack(me.getX(), me.getY(), me.getPlane()));
        LocalPoint local = player.getLocalLocation();
        if (text == null || local == null)
        {
            return;
        }
        // The font first: the text is centred with it.
        g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
        net.runelite.api.Point at = Perspective.getCanvasTextLocation(client, g, local, text, player.getLogicalHeight() + 60);
        if (at == null)
        {
            return;
        }
        int x = at.getX();
        int y = at.getY();
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        Route.Step next = nextAction(route, me == null ? -1 : Tiles.pack(me.getX(), me.getY(), me.getPlane()));
        g.setColor(next != null && RouteText.lacks(next) ? CANNOT : jump());
        g.drawString(text, x, y);
    }

    private static final int MINIMAP_ALPHA = 150;

    /** The route's tiles as dots on the minimap: above the widgets, or the minimap would cover them. */
    private final class MinimapOverlay extends Overlay
    {
        MinimapOverlay()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_WIDGETS);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            if (route == null && fading.isEmpty())
            {
                return null;
            }
            WorldView view = client.getTopLevelWorldView();
            if (view == null || view.isInstance() || client.getLocalPlayer() == null || !config.routeInGame()
                || !config.routeMinimap())
            {
                return null;
            }
            int plane = view.getPlane();
            for (Map.Entry<Integer, Route> leg : laterLegs())
            {
                Color color = alpha(legColor(leg.getKey()), MINIMAP_ALPHA);
                for (Route.Step step : leg.getValue().steps)
                {
                    if (step.kind != Route.Step.Kind.WALK)
                    {
                        continue;
                    }
                    for (int point : step.points)
                    {
                        dot(g, view, plane, point, color);
                    }
                }
            }
            // Subtle: the minimap has plenty of dots of its own.
            Color normal = alpha(current(), MINIMAP_ALPHA);
            long now = System.currentTimeMillis();
            int blocked = RouteText.blockedFrom(route);
            List<Route.Step> steps = route == null ? java.util.Collections.<Route.Step>emptyList() : route.steps;
            for (int index = 0; index < steps.size(); index++)
            {
                Route.Step step = steps.get(index);
                Color walk = blocked >= 0 && index >= blocked ? alpha(CANNOT, MINIMAP_ALPHA) : normal;
                if (step.kind != Route.Step.Kind.WALK)
                {
                    continue;
                }
                for (int point : step.points)
                {
                    if (!fading.containsKey(point))
                    {
                        float in = fadeIn(point, now);
                        dot(g, view, plane, point, in < 1 ? alpha(walk, walk.getAlpha() * in) : walk);
                    }
                }
            }
            for (Map.Entry<Integer, Long> entry : fading.entrySet())
            {
                float alpha = fadeAlpha(entry.getValue(), now);
                if (alpha > 0)
                {
                    dot(g, view, plane, entry.getKey(), alpha(normal, alpha * MINIMAP_ALPHA));
                }
            }
            return null;
        }

        private void dot(Graphics2D g, WorldView view, int plane, int point, Color color)
        {
            if (Tiles.isSea(point) || Tiles.z(point) != plane)
            {
                return;
            }
            LocalPoint local = LocalPoint.fromWorld(view, Tiles.x(point), Tiles.y(point));
            // Null outside the minimap's circle.
            net.runelite.api.Point mini = local == null ? null : Perspective.localToMinimap(client, local);
            if (mini != null)
            {
                g.setColor(color);
                g.fill(new Ellipse2D.Double(mini.getX() - 1.25, mini.getY() - 1.25, 2.5, 2.5));
            }
        }
    }

    private final class GameOverlay extends Overlay
    {
        GameOverlay()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            Player player = client.getLocalPlayer();
            // The settings once per frame.
            if (!config.routeInGame() || player == null)
            {
                return null;
            }
            WorldView view = client.getTopLevelWorldView();
            boolean instance = view == null || view.isInstance();
            if (route != null && (!instance || house.inside()))
            {
                // Not in other instances (their tiles copy some template); in the house its portal or teleport is the
                // next thing to do.
                paintNextAction(g, route, player);
            }
            // With the route done its tiles still fade away; HD Tile Markers draws them when it is on.
            if (route == null && fading.isEmpty() || instance || hdTiles)
            {
                return null;
            }
            int plane = view.getPlane();
            Stroke old = g.getStroke();
            Color normal = current();
            Color walk = normal;
            int fill = config.routeTileFill();
            int borderAlpha = config.routeTileBorder();
            double tileWidth = config.routeTileWidth();
            boolean border = borderAlpha > 0 && tileWidth > 0;
            g.setStroke(new BasicStroke((float) Math.max(0.5, Math.min(8, tileWidth))));
            // A custom route's later parts on the ground too, each in its own colour.
            for (Map.Entry<Integer, Route> leg : laterLegs())
            {
                Color color = legColor(leg.getKey());
                Color fillColor = alpha(color, fill);
                Color borderColor = alpha(color, borderAlpha);
                for (Route.Step step : leg.getValue().steps)
                {
                    if (step.kind != Route.Step.Kind.WALK)
                    {
                        continue;
                    }
                    for (int point : step.points)
                    {
                        LocalPoint local = Tiles.z(point) != plane ? null
                            : LocalPoint.fromWorld(view, Tiles.x(point), Tiles.y(point));
                        Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
                        if (tile != null)
                        {
                            g.setColor(fillColor);
                            g.fill(tile);
                            if (border)
                            {
                                g.setColor(borderColor);
                                g.draw(tile);
                            }
                        }
                    }
                }
            }
            int blocked = RouteText.blockedFrom(route);
            List<Route.Step> steps = route == null ? java.util.Collections.<Route.Step>emptyList() : route.steps;
            long now = System.currentTimeMillis();
            for (int index = 0; index < steps.size(); index++)
            {
                Route.Step step = steps.get(index);
                // The part the player cannot take yet, red.
                walk = blocked >= 0 && index >= blocked ? CANNOT : normal;
                if (step.kind != Route.Step.Kind.WALK)
                {
                    continue;
                }
                Color fillColor = alpha(walk, fill);
                Color borderColor = alpha(walk, borderAlpha);
                for (int point : step.points)
                {
                    if (Tiles.z(point) != plane || fading.containsKey(point))
                    {
                        continue;
                    }
                    LocalPoint local = LocalPoint.fromWorld(view, Tiles.x(point), Tiles.y(point));
                    Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
                    if (tile != null)
                    {
                        float in = fadeIn(point, now);
                        g.setColor(in < 1 ? alpha(walk, fill * in) : fillColor);
                        g.fill(tile);
                        if (border)
                        {
                            g.setColor(in < 1 ? alpha(walk, borderAlpha * in) : borderColor);
                            g.draw(tile);
                        }
                    }
                }
            }
            // Tiles just passed, fading out.
            for (Map.Entry<Integer, Long> entry : fading.entrySet())
            {
                int point = entry.getKey();
                float alpha = fadeAlpha(entry.getValue(), now);
                if (alpha <= 0 || Tiles.z(point) != plane)
                {
                    continue;
                }
                LocalPoint local = LocalPoint.fromWorld(view, Tiles.x(point), Tiles.y(point));
                if (local == null)
                {
                    continue;
                }
                Polygon tile = Perspective.getCanvasTilePoly(client, local);
                if (tile != null)
                {
                    g.setColor(alpha(normal, alpha * fill));
                    g.fill(tile);
                    if (border)
                    {
                        g.setColor(alpha(normal, alpha * borderAlpha));
                        g.draw(tile);
                    }
                }
            }
            g.setStroke(old);
            return null;
        }
    }

    /** Text and drawing shared by the map and the previews. */
    static final class RouteText
    {
        /** "Can't yet: 45 Sailing, Lunar Diplomacy": what the steps of the way anyone could go lack, in one line. */
        static String cannotYet(Route route)
        {
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            for (Route.Step step : route.steps)
            {
                if (lacks(step))
                {
                    for (String part : step.detail.replaceFirst("^You lack:?\\s*", "").split(",\\s*"))
                    {
                        if (!part.trim().isEmpty())
                        {
                            missing.add(part.trim());
                        }
                    }
                }
            }
            return missing.isEmpty() ? "Can't yet." : "Can't yet: " + String.join(", ", missing);
        }

        /** Whether a step of the way anyone could go needs what the player lacks (the teleport scroll, a level). */
        static boolean lacks(Route.Step step)
        {
            return step.detail != null && step.detail.startsWith("You lack");
        }

        /**
         * On the way shown for a place out of reach, the first step the player cannot take yet: from there on the
         * route is drawn red. -1 when every step can be taken.
         */
        static int blockedFrom(Route route)
        {
            for (int i = 0; route != null && i < route.steps.size(); i++)
            {
                if (lacks(route.steps.get(i)))
                {
                    return i;
                }
            }
            return -1;
        }

        private RouteText()
        {
        }

        static String escape(String text)
        {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        static List<String> notes(Route route)
        {
            List<String> notes = new java.util.ArrayList<>();
            if (route.outcome == Route.Outcome.NONE)
            {
                notes.add("No route from here.");
            }
            else if (route.outcome == Route.Outcome.NEAREST)
            {
                notes.add(route.exhausted ? "Can't get there." : "Too far to plan: ends as close as it got.");
            }
            else if (route.snapped())
            {
                notes.add("Ends at the nearest tile you can stand on.");
            }
            return notes;
        }

        private static final java.util.regex.Pattern BRING = java.util.regex.Pattern.compile("\\(bring (.+)\\)$");

        /** The tools the route's obstacles call for, as "an axe, a machete", or null when none. */
        static String bring(Route route)
        {
            java.util.Set<String> tools = new java.util.LinkedHashSet<>();
            for (Route.Step step : route.steps)
            {
                for (Route.Obstacle obstacle : step.obstacles)
                {
                    java.util.regex.Matcher m = BRING.matcher(obstacle.text);
                    if (m.find())
                    {
                        tools.add(m.group(1));
                    }
                }
            }
            return tools.isEmpty() ? null : String.join(", ", tools);
        }

        /** Whether a step's detail is only the caution that it may need something unknown (said once, not per step). */
        static boolean unsure(String detail)
        {
            return detail != null && detail.startsWith("May need");
        }

        static String duration(Route route)
        {
            // The time it takes, not the planner's weights (a teleport only worth it past some tiles weighs more).
            int ticks = (route.time() + 1) / 2;
            int seconds = (int) Math.round(ticks * 0.6);
            return "About " + (seconds < 90 ? seconds + " seconds" : Math.round(seconds / 60.0) + " minutes")
                + " (" + ticks + " ticks)";
        }

        static String describe(Route.Step step)
        {
            switch (step.kind)
            {
                case WALK:
                {
                    int tiles = step.points.length - 1;
                    return "Walk " + tiles + (tiles == 1 ? " tile" : " tiles")
                        + (step.doors > 0 ? " (" + step.doors + (step.doors == 1 ? " door)" : " doors)") : "");
                }
                case SAIL:
                    return "Sail about " + (step.points.length - 1) * Tiles.CELL + " tiles";
                case STAIRS:
                    return step.name + " to floor " + Tiles.z(step.last());
                default:
                    return step.name != null ? step.name : step.kind.name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        static void paint(Graphics2D g, MapView.Projection p, Route route)
        {
            paint(g, p, route, WALK, JUMP);
        }

        /** Behind each walked line, so it reads on any ground. */
        private static final Color OUTLINE = new Color(0, 0, 0, 140);

        static void paint(Graphics2D g, MapView.Projection p, Route route, Color walk, Color jump)
        {
            double scale = Math.pow(2, p.zoom());
            float width = (float) Math.max(2.5, Math.min(6, scale / 2));
            Stroke solid = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            Stroke dashed = new BasicStroke(Math.max(2f, width - 1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1,
                new float[]{7, 6}, 0);
            Stroke outline = new BasicStroke(width + 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            int blocked = blockedFrom(route);
            Color walkColor = walk;
            Color jumpColor = jump;
            for (int index = 0; index < route.steps.size(); index++)
            {
                Route.Step step = route.steps.get(index);
                // From the first step the player cannot take yet, the way is red.
                if (blocked >= 0 && index >= blocked)
                {
                    walk = CANNOT;
                    jump = CANNOT;
                }
                if (step.isJump())
                {
                    int a = step.first();
                    int b = step.last();
                    boolean showA = visible(p, a);
                    boolean showB = visible(p, b);
                    if (showA && showB && a != b)
                    {
                        g.setStroke(dashed);
                        g.setColor(step.kind == Route.Step.Kind.BOARD || step.kind == Route.Step.Kind.DISEMBARK ? SEA : jump);
                        g.draw(new Line2D.Double(x(p, a), y(p, a), x(p, b), y(p, b)));
                    }
                    g.setColor(jump);
                    if (showA)
                    {
                        g.fill(new Ellipse2D.Double(x(p, a) - 4, y(p, a) - 4, 8, 8));
                    }
                    if (showB)
                    {
                        g.fill(new Ellipse2D.Double(x(p, b) - 4, y(p, b) - 4, 8, 8));
                    }
                    continue;
                }
                Path2D.Double line = new Path2D.Double();
                boolean drawing = false;
                for (int point : step.points)
                {
                    if (!visible(p, point))
                    {
                        drawing = false;
                        continue;
                    }
                    if (drawing)
                    {
                        line.lineTo(x(p, point), y(p, point));
                    }
                    else
                    {
                        line.moveTo(x(p, point), y(p, point));
                        drawing = true;
                    }
                }
                g.setStroke(outline);
                g.setColor(OUTLINE);
                g.draw(line);
                g.setStroke(solid);
                g.setColor(step.kind == Route.Step.Kind.SAIL ? SEA : walk);
                g.draw(line);
            }
            walk = walkColor;
            jump = jumpColor;
            if (route.end >= 0 && visible(p, route.end))
            {
                double ex = x(p, route.end);
                double ey = y(p, route.end);
                g.setStroke(new BasicStroke(2f));
                g.setColor(END);
                g.fill(new Ellipse2D.Double(ex - 6, ey - 6, 12, 12));
                g.setColor(Color.WHITE);
                g.draw(new Ellipse2D.Double(ex - 6, ey - 6, 12, 12));
            }
            if (route.snapped() && visible(p, route.target))
            {
                // The spot asked for, crossed out, with a thin line to where the route ends.
                double tx = x(p, route.target);
                double ty = y(p, route.target);
                g.setStroke(new BasicStroke(2.5f));
                g.setColor(END);
                g.draw(new Line2D.Double(tx - 6, ty - 6, tx + 6, ty + 6));
                g.draw(new Line2D.Double(tx - 6, ty + 6, tx + 6, ty - 6));
                if (route.end >= 0 && visible(p, route.end))
                {
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 4}, 0));
                    g.draw(new Line2D.Double(tx, ty, x(p, route.end), y(p, route.end)));
                }
            }
        }

        private static boolean visible(MapView.Projection p, int node)
        {
            if (Tiles.isSea(node))
            {
                return p.shows(new WorldPoint(Tiles.x(node), Tiles.y(node), 0));
            }
            WorldPoint game = new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node));
            // Where the map draws it: a part drawn elsewhere (the Kalphite Lair) on its drawing, on the floor drawn.
            return p.shown(game).getPlane() == p.plane() && p.shows(game);
        }

        private static double x(MapView.Projection p, int node)
        {
            return Tiles.isSea(node) ? p.screenX(Tiles.x(node))
                : p.screenX(p.shown(new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node))).getX() + 0.5);
        }

        private static double y(MapView.Projection p, int node)
        {
            return Tiles.isSea(node) ? p.screenY(Tiles.y(node))
                : p.screenY(p.shown(new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node))).getY() + 0.5);
        }
    }
}
