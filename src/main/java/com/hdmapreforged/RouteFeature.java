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
import com.hdmapreforged.route.WalkableArea;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

/**
 * "Path to here": our own route search; only shows a way, never walks, clicks or sends anything.
 * Threads: player state on the client thread; searches on their own thread, extra work on the route extras thread;
 * the controller and map drawing on Swing.
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
    private static final Set<String> LOOK_KEYS = new HashSet<>(Arrays.asList(
        "routeColor", "routeJumpColor", "routeTileFill", "routeTileBorder", "routeTileWidth", "routeMinimap",
        "routeInGame", "routeHdTiles", "routeClearOnArrival", "routeFollowPlugins", "routePlannerChosen"));

    private static final Color[] LEG_COLORS = {
        new Color(90, 220, 130), new Color(255, 130, 200), new Color(255, 160, 70), new Color(120, 230, 255),
        new Color(200, 255, 110), new Color(255, 255, 255), new Color(180, 150, 255)};

    static final Color CANNOT = new Color(255, 112, 112);

    private Color walk()
    {
        return opaque(config.routeColor(), WALK);
    }

    private Color jump()
    {
        return opaque(config.routeJumpColor(), JUMP);
    }

    private static Color opaque(Color c, Color fallback)
    {
        return c == null ? fallback : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    private static WorldPoint worldPoint(int node)
    {
        return new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node));
    }

    private static int pack(WorldPoint p)
    {
        return Tiles.pack(p.getX(), p.getY(), p.getPlane());
    }

    private static Color alpha(Color c, float alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(alpha))));
    }

    /** A route tile for HD Tile Markers; {@code alpha}: how visible (fading). */
    private HdTileMarkersBridge.Tile hdTile(int point, float alpha)
    {
        return hdTile(point, alpha, current());
    }

    private HdTileMarkersBridge.Tile hdTile(int point, float alpha, Color walk)
    {
        int border = config.routeTileBorder();
        return new HdTileMarkersBridge.Tile(worldPoint(point), alpha(walk, border * alpha),
            alpha(walk, config.routeTileFill() * alpha),
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
    private volatile RouteController controller;
    private volatile RouteSource source;
    private volatile Pathfinder walker;
    private volatile List<int[]> spWalks = Collections.emptyList();
    /** Changed together with {@link #spWalks}, under {@link #spLock}. */
    private final AtomicInteger spGeneration = new AtomicInteger();
    private final Object spLock = new Object();

    /** Forgets the walks and cancels any filling in; returns the new generation. */
    private int dropWalks()
    {
        synchronized (spLock)
        {
            spWalks = Collections.emptyList();
            return spGeneration.incrementAndGet();
        }
    }

    /** Shortest Path only tells its transports; the walks between them are found here, in the background. */
    private void fillInWalks(List<ShortestPathBridge.Jump> jumps, WorldPoint from, WorldPoint target)
    {
        RouteSource s = source;
        Pathfinder p = walker;
        ExecutorService pool = background;
        int generation = dropWalks();
        if (s == null || p == null || pool == null || from == null || target == null)
        {
            return;
        }
        List<WorldPoint[]> legs = new ArrayList<>();
        WorldPoint at = from;
        for (ShortestPathBridge.Jump j : jumps)
        {
            legs.add(new WorldPoint[]{at, j.from});
            at = j.to;
        }
        legs.add(new WorldPoint[]{at, target});
        pool.execute(() -> {
            List<int[]> walks = new ArrayList<>();
            for (WorldPoint[] leg : legs)
            {
                if (generation != spGeneration.get())
                {
                    return;
                }
                RouteRequest request = s.request(pack(leg[0]), pack(leg[1]), Collections.emptyList(), null,
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
                for (Route.Step step : walk == null ? Collections.<Route.Step>emptyList() : walk.steps)
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
            SwingUtilities.invokeLater(this::repaint);
        });
    }

    private void repaint()
    {
        for (MapScreen screen : screens)
        {
            screen.view().repaint();
        }
    }
    private volatile Map<Integer, Integer> ports = Collections.emptyMap();
    private volatile int[] shipwrights = new int[0];

    void setIconEntries(List<MapIconLoader.Entry> entries)
    {
        shipwrights = shipwrights(entries);
    }

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
    private volatile PlayerState state = PlayerState.UNKNOWN;
    /** A tile, a sea block, -1 in the house, -2 unknown. Written on the client thread. */
    private volatile int start = -2;
    private volatile Route shown;
    private boolean bringUp;
    private Map<Integer, Long> bank;
    private ItemSnapshot bankItems;
    private boolean bankChanged = true;
    private Boolean pandemonium;
    private int ticks;
    private int lastQuest = -QUEST_TICKS;

    @Inject
    RouteFeature(Client client, HdMapReforgedConfig config, ConfigManager configManager, OverlayManager overlayManager,
        EventBus eventBus, PluginManager pluginManager, ClientThread clientThread, WorldMapOverlay worldMapOverlay)
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

    private final PluginManager pluginManager;
    private volatile Consumer<IntFunction<JComponent>> sidebar = section -> { };
    private volatile Consumer<WorldPoint> sidebarFocus = point -> { };
    private volatile Runnable sidebarTours = () -> { };
    private final ClientThread clientThread;
    private final WorldMapOverlay worldMapOverlay;
    /** The target handed to Shortest Path, or null. */
    private volatile WorldPoint handedOver;
    private volatile WorldPoint lastLocation;
    /** Whether the current route was asked for by another plugin. */
    private volatile boolean fromPlugin;

    private boolean shortestPathPlanner()
    {
        return config.routePlanner() == HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH;
    }

    private boolean shortestPathOn()
    {
        return pluginOn(ShortestPathBridge.PLUGIN_NAME);
    }

    private boolean pluginOn(String name)
    {
        for (Plugin plugin : pluginManager.getPlugins())
        {
            PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
            if (descriptor != null && name.equals(descriptor.name()))
            {
                return pluginManager.isPluginEnabled(plugin);
            }
        }
        return false;
    }

    /** Whether HD Tile Markers draws the ground tiles now (checked every 10 ticks). */
    private volatile boolean hdTiles;
    private Route[] sentLegs;
    private int lastHdTilesCheck = Integer.MIN_VALUE / 2;
    private Route sentAhead;
    private boolean fadedLast;
    private int sentFrom = -1;

    /** Shift + right-click on the ground. RuneLite entries; nothing is sent to the game. */
    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        if (shortestPathPlanner() || !client.isKeyPressed(KeyCode.KC_SHIFT))
        {
            return;
        }
        boolean walk = false;
        for (MenuEntry entry : event.getMenuEntries())
        {
            walk |= entry.getType() == MenuAction.WALK;
        }
        WorldView view = client.getTopLevelWorldView();
        Tile tile = view == null ? null : view.getSelectedSceneTile();
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
            menuEntry("Clear route", () -> {
                RouteController now = controller;
                stopTour();
                if (now != null)
                {
                    now.clear();
                }
            });
        }
        menuEntry("Route here", () -> routeTo(point));
    }

    private void menuEntry(String option, Runnable action)
    {
        client.getMenu().createMenuEntry(1)
            .setOption(option)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(e -> SwingUtilities.invokeLater(action));
    }

    private long lastFadeSend;

    /** While tiles fade, HD Tile Markers gets them again every few frames (it does not animate what it was sent). */
    @Subscribe
    public void onClientTick(ClientTick event)
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
        List<HdTileMarkersBridge.Tile> tiles = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        int blocked = RouteText.blockedFrom(route);
        List<Route.Step> steps = route == null ? Collections.<Route.Step>emptyList() : route.steps;
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
                int at = step.first();
                if (player < 0 || Tiles.distance(at, player) <= 40)
                {
                    tiles.add(new HdTileMarkersBridge.Tile(worldPoint(at),
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
        // The nearer part wins where they cross.
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

    /** Directions other plugins send to Shortest Path. Only read, never answered. */
    @Subscribe
    public void onPluginMessage(PluginMessage message)
    {
        if (shortestPathPlanner())
        {
            if (ShortestPathBridge.isTransports(message) && handedOver != null)
            {
                spJumps = ShortestPathBridge.jumps(message);
                fillInWalks(spJumps, lastLocation, handedOver);
                SwingUtilities.invokeLater(() -> showHandedOver(this::handedOverPanel, false));
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
                        c.setTarget(pack(target));
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

    private volatile List<ShortestPathBridge.Jump> spJumps = Collections.emptyList();

    private void paintHandedOver(Graphics2D g, MapView.Projection p)
    {
        WorldPoint target = handedOver;
        if (target == null)
        {
            return;
        }
        WorldPoint me = lastLocation;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BasicStroke walk = new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{2f, 5f}, 0f);
        BasicStroke jump = new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{9f, 6f}, 0f);
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
        BasicStroke path = new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        for (int[] points : walks)
        {
            Path2D line = new Path2D.Double();
            boolean started = false;
            for (int node : points)
            {
                WorldPoint wp = worldPoint(node);
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
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
            g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
            g.setColor(new Color(255, 214, 64));
            g.fill(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
        }
    }

    private static void line(Graphics2D g, MapView.Projection p, WorldPoint a, WorldPoint b, BasicStroke stroke, Color color)
    {
        if (!p.shows(a) || !p.shows(b))
        {
            return;
        }
        WorldPoint from = p.shown(a);
        WorldPoint to = p.shown(b);
        Line2D line = new Line2D.Double(p.screenX(from.getX() + 0.5),
            p.screenY(from.getY() + 0.5), p.screenX(to.getX() + 0.5), p.screenY(to.getY() + 0.5));
        g.setStroke(new BasicStroke(stroke.getLineWidth() + 2f, stroke.getEndCap(), stroke.getLineJoin(),
            stroke.getMiterLimit(), stroke.getDashArray(), 0f));
        g.setColor(new Color(0, 0, 0, 130));
        g.draw(line);
        g.setStroke(stroke);
        g.setColor(color);
        g.draw(line);
    }

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
        fillInWalks(Collections.emptyList(), lastLocation, target);
        // Shortest Path reads the player's position when a message arrives: client thread.
        clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.path(target)));
        showHandedOver(this::handedOverPanel, true);
    }

    private void showHandedOver(IntFunction<JComponent> panel, boolean up)
    {
        for (MapScreen screen : screens)
        {
            screen.showRoute(panel, up);
            screen.view().repaint();
        }
        sidebar.accept(panel);
    }

    private void handOverClear()
    {
        handedOver = null;
        spJumps = Collections.emptyList();
        dropWalks();
        clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.clear()));
        showHandedOver(null, false);
    }


    void start(MapScreen... on)
    {
        int generation = loads.incrementAndGet();
        screens = on;
        // At the lowest priority a busy client starved searches for seconds.
        searches = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "HD Map Reforged route");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
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
                        return; // stopped (maybe restarted) while loading
                    }
                    ports = loadedPorts;
                    source = new RouteSource(map, sea);
                    for (MapScreen screen : screens)
                    {
                        // Entering a dungeon fits the map to its walkable area.
                        screen.view().setAreaBounds(p -> WalkableArea.bounds(map, p.getX(), p.getY(), p.getPlane(),
                            AREA_LIMIT));
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
        // Started while logged in: the house from the "Your house" settings, as at login.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && generation == loads.get())
            {
                house.restore(houseSettings());
            }
        });
    }

    /** Counts starts and stops, so a load finishing after a stop is dropped. */
    private final AtomicInteger loads = new AtomicInteger();

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
        // Work still on the extras thread finds it is not wanted.
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
        int node = location == null ? -1 : pack(location);
        house.update(node, instance, client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION),
            client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1);
        if (house.takeChanged())
        {
            writeHouse(house.features());
        }
        int from = location == null ? -2 : house.inside() ? -1
            : onBoat ? Tiles.seaAt(location.getX(), location.getY()) : node;
        start = from;
        updateHdTiles(from);
        if (ticks % STATE_TICKS == 0 || state == PlayerState.UNKNOWN)
        {
            state = capture();
        }
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null)
            {
                Running running = tour;
                if (running != null && running.target >= 0 && (Tiles.z(from) == Tiles.z(running.target)
                    && Tiles.distance(from, running.target) <= RouteController.ARRIVED
                    || c.target() == running.target && c.arrived(from)))
                {
                    nextStop(from);
                    return;
                }
                if (config.routeClearOnArrival() && c.arrived(from))
                {
                    fadeOut(walkedAhead(ahead));
                    c.clear();
                    return;
                }
                c.playerMoved(from >= 0 ? from : -1, !instance);
                updateAhead(c, from);
            }
        });
    }

    private volatile Route ahead;
    private int lastAheadSteps = -1;
    private int lastAheadPoints = -1;

    /** Passed tiles fade at once: a step or two behind the player, not a trail. */
    static final long FADE_MS = 600;
    private final ConcurrentHashMap<Integer, Long> fading = new ConcurrentHashMap<>();
    private final Timer fadeTimer = new Timer(40, e -> fadeStep());

    private final ConcurrentHashMap<Integer, Long> appearing = new ConcurrentHashMap<>();
    static final long FADE_IN_MS = 400;

    private float fadeIn(int point, long now)
    {
        Long since = appearing.get(point);
        return since == null ? 1f : Math.max(0.05f, Math.min(1f, (now - since) / (float) FADE_IN_MS));
    }

    private void fadeInTiles(Set<Integer> tiles)
    {
        if (tiles.isEmpty() || tiles.size() > 1000)
        {
            return;
        }
        long t = System.currentTimeMillis();
        for (int point : tiles)
        {
            // Fading out a moment ago: it comes back from where it was.
            Long out = fading.remove(point);
            float was = out == null ? 0f : fadeAlpha(out, t);
            appearing.put(point, t - (long) (was * FADE_IN_MS));
        }
        if (!fadeTimer.isRunning())
        {
            fadeTimer.start();
        }
    }

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
        repaint();
    }

    private static Set<Integer> walked(Route route)
    {
        Set<Integer> tiles = new HashSet<>();
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

    private void fadeOut(Set<Integer> tiles)
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

    private Route aheadOf;
    private Route aheadWalkedOf;
    private Set<Integer> aheadWalked;

    /** {@link #walked}, cached: each tick asks for the same route again. */
    private Set<Integer> walkedAhead(Route route)
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
            now = before; // the same cut as last tick
        }
        Set<Integer> walkedBefore = walkedAhead(before);
        Set<Integer> walkedNow = now == before ? walkedBefore : walked(now);
        if (route != null && route == aheadOf && before != null && now != null && player >= 0
            && walkedNow.size() > walkedBefore.size() && !now.steps.isEmpty()
            && Tiles.distance(player, now.steps.get(0).first()) > 2)
        {
            // Off the way (a new search is coming): what was passed stays gone instead of fading back in.
            return;
        }
        aheadOf = route;
        if (before != null && before != now)
        {
            Set<Integer> gone = new HashSet<>(walkedBefore);
            gone.removeAll(walkedNow);
            fadeOut(gone);
            Set<Integer> back = new HashSet<>(walkedNow);
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
            showSteps(c, false);
        }
        else if (points != lastAheadPoints && steps >= 0)
        {
            // The sidebar's "Now" counts down the tiles left of this walk.
            sidebar.accept(width -> steps(c, width, sidebarFocus, true));
        }
        if (steps != lastAheadSteps || points != lastAheadPoints)
        {
            lastAheadSteps = steps;
            lastAheadPoints = points;
            repaint();
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
            count(inventory, carried);
            for (Item item : inventory.getItems())
            {
                pouch |= RUNE_POUCHES.contains(item.getId());
            }
        }
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn != null)
        {
            count(worn, carried);
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
            count(event.getItemContainer(), items);
            bank = items;
            bankChanged = true;
        }
        state = capture();
    }

    private static void count(ItemContainer container, Map<Integer, Long> into)
    {
        for (Item item : container.getItems())
        {
            if (item.getId() >= 0)
            {
                into.merge(item.getId(), (long) item.getQuantity(), Long::sum);
            }
        }
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
            // The "Your house" settings (once migrated from the per-account key of earlier versions).
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

    private Set<String> houseSettings()
    {
        return HouseSettings.features(config.houseJewelleryBox(), config.houseGlory(), config.houseFairyRing(),
            config.houseSpiritTree(), config.housePortals());
    }

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
            clientThread.invokeLater(() -> {
                Set<String> features = houseSettings();
                if (features.equals(house.features()))
                {
                    return;
                }
                house.replaceFeatures(features);
                state = capture();
                SwingUtilities.invokeLater(this::settingsChanged);
            });
            return;
        }
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()) || !event.getKey().startsWith("route")
            || HOUSE_KEY.equals(event.getKey()) || "routeLog".equals(event.getKey()))
        {
            return;
        }
        if (LOOK_KEYS.contains(event.getKey()))
        {
            SwingUtilities.invokeLater(this::changed);
            sentAhead = null;
            return;
        }
        SwingUtilities.invokeLater(this::settingsChanged);
    }

    private void settingsChanged()
    {
        RouteController c = controller;
        if (c != null)
        {
            c.settingsChanged();
        }
    }

    private void toast(String text)
    {
        for (MapScreen screen : screens)
        {
            screen.showToast(text);
        }
    }

    // ---- Swing ----

    private RouteRequest request(int target)
    {
        return request(start, target, false, RouteRequest.DEFAULT_NODE_LIMIT);
    }

    /** Node limit on the extras thread: lower, so two searches at once hold less memory. */
    static final int EXTRAS_NODE_LIMIT = 1_500_000;

    /** With {@code anything}, every requirement counts as met. Swing or the extras thread. */
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

    /** On the first route: Shortest Path becomes the planner if it is on and nothing was chosen. */
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

    /** Swing thread; nothing while the data loads. */
    void routeTo(WorldPoint point)
    {
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
            now.setTarget(pack(point));
        }
    }

    private void contribute(JPopupMenu popup, WorldPoint point)
    {
        choosePlannerOnce();
        RouteController c = controller;
        popup.addSeparator();
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

    /** Max tiles of a walkable area to count as one dungeon to fit the map to. */
    static final int AREA_LIMIT = 60_000;

    // ---- custom routes ----

    static final String TOURS_KEY = "customRoutes";
    static final String EDITING_KEY = "customRouteEditing";

    private static final class Running
    {
        final Tour tour;
        volatile int index;
        volatile int target = -1;
        /** Each stop's tile, fixed at the start (a kind: the one nearest the stop before); -1 none. */
        int[] points = new int[0];
        /** The way to each stop from the one before, planned at the start; null until found. */
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

    /** Set on Swing; read on the client and extras threads too. */
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
        toast("Added to \"" + name + "\" (" + target.stops.size() + " stops)");
    }

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

    private void editTours()
    {
        for (MapScreen screen : screens)
        {
            screen.openTours();
        }
    }

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
            orders.incrementAndGet();
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

    private void refreshTours()
    {
        for (MapScreen screen : screens)
        {
            screen.refreshTours();
        }
        sidebarTours.run();
    }

    void runTour(Tour chosen)
    {
        if (chosen.stops.isEmpty())
        {
            return;
        }
        // A stop right after the same one is skipped.
        Running running = new Running(chosen.withoutRepeats());
        tour = running;
        int n = running.tour.stops.size();
        running.points = new int[n];
        running.legs = new Route[n];
        WorldPoint near = start >= 0 ? worldPoint(start) : null;
        for (int i = 0; i < n; i++)
        {
            WorldPoint p = resolve(running.tour.stops.get(i), near);
            running.points[i] = p == null ? -1 : pack(p);
            near = p != null ? p : near;
        }
        goToStop(start);
        planLegs(running);
        refreshTours();
    }

    /** Plans every leg in the background, so the whole route is drawn at once. */
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
                        repaint();
                    });
                }
            }
        });
    }

    TourPanel.Actions sidebarTours(Runnable refresh)
    {
        sidebarTours = refresh;
        return tourActions;
    }

    /** Also shows the steps in the sidebar; a step clicked there goes to {@code focus}. */
    void setSidebar(Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus)
    {
        sidebar = show;
        sidebarFocus = focus;
        changed();
    }

    private Color legColor(int leg)
    {
        return leg <= 0 ? walk() : LEG_COLORS[(leg - 1) % LEG_COLORS.length];
    }

    private Color current()
    {
        Running running = tour;
        return running == null ? walk() : legColor(running.index);
    }

    /** The custom route's parts after the current one, last first (drawn under the nearer ones). */
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
                later.add(new AbstractMap.SimpleImmutableEntry<>(i, legs[i]));
            }
        }
        return later;
    }

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
            toast("\"" + running.tour.name + "\" done");
            return;
        }
        goToStop(at);
    }

    /** Routes to the current stop; a kind becomes the one nearest {@code from}. */
    private void goToStop(int from)
    {
        Running running = tour;
        RouteController c = controller;
        if (running == null || c == null)
        {
            return;
        }
        int planned = running.index < running.points.length ? running.points[running.index] : -1;
        WorldPoint to = planned >= 0 ? worldPoint(planned)
            : resolve(running.stop(), from >= 0 ? worldPoint(from) : null);
        if (to == null)
        {
            // A kind with no place yet (icons not loaded): skipped.
            nextStop(from);
            return;
        }
        running.target = pack(to);
        if (shortestPathPlanner())
        {
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
            c.show(running.target, leg);
            return;
        }
        c.setTarget(running.target);
    }

    private WorldPoint resolve(Tour.Stop stop, WorldPoint near)
    {
        if (!stop.isKind())
        {
            return stop.point;
        }
        return Tour.nearest(screens.length == 0 ? null : screens[0].kind(stop.kind), near);
    }

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
     * Orders the stops fastest from the player, by routes between every two (in the background), and saves it unless
     * the stops changed meanwhile. {@code done} always runs on Swing.
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
        String name = chosen.name;
        List<Tour.Stop> stops = Collections.unmodifiableList(new ArrayList<>(chosen.stops));
        int order = orders.incrementAndGet();
        WorldPoint me = from >= 0 ? worldPoint(from) : null;
        List<WorldPoint> points = new ArrayList<>();
        points.add(me);
        WorldPoint previous = me;
        for (Tour.Stop stop : stops)
        {
            WorldPoint p = resolve(stop, previous);
            points.add(p);
            previous = p != null ? p : previous;
        }
        toast("Working out the fastest order of " + stops.size() + " stops…");
        BooleanSupplier cancelled = () -> background == null || orders.get() != order;
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
        catch (RejectedExecutionException e)
        {
            done.run();
        }
    }

    /** Saves the order if the route's stops are unchanged. */
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
                toast("\"" + name + "\" changed meanwhile: its order was left as it is");
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

    /** Which fastest-order run is current; any other stops and keeps nothing. */
    private final AtomicInteger orders = new AtomicInteger();

    private long between(Pathfinder pf, WorldPoint a, WorldPoint b, BooleanSupplier cancelled)
    {
        if (b == null)
        {
            return -1;
        }
        if (a == null)
        {
            return 0; // player's place unknown: every first stop is as good
        }
        RouteRequest request = request(pack(a), pack(b), false, EXTRAS_NODE_LIMIT);
        if (request == null)
        {
            return -1;
        }
        Route route = pf.find(request, cancelled);
        return route.outcome == Route.Outcome.FOUND ? route.cost : -1;
    }

    /** Even with every requirement met there is no way: the data lacks a passage. */
    static final String NO_WAY = "No way found, even with everything.";

    /** Swing thread: for an unreachable target, the way with every requirement met. */
    private Route why;
    private volatile Route whyFor;
    private int whyTarget = -1;
    private boolean explaining;

    /** Looks in the background for what is missing. Swing thread. */
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
        // Whenever the way does not arrive (also at the search limit), so the whole route shows with what is missing.
        if (route == null || route.outcome != Route.Outcome.NEAREST || pool == null
            || pathfinder == null || config.routeIgnoreLevels() && config.routeIgnoreItems())
        {
            why = null;
            return;
        }
        RouteRequest request = request(start, c.target(), true, EXTRAS_NODE_LIMIT);
        if (request == null)
        {
            whyFor = null; // retry once the start is known
            return;
        }
        explaining = true;
        try
        {
            pool.execute(() -> explained(c, route, request, pathfinder));
        }
        catch (RejectedExecutionException e)
        {
            explaining = false;
        }
    }

    /** Extras thread; always hands back to Swing, so nothing waits on it. */
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

    /** The steps that need what the player lacks. Null when nothing to say; empty when nothing nameable. */
    static List<String> blockers(Route why)
    {
        if (why == null || why.outcome == Route.Outcome.CANCELLED)
        {
            return null;
        }
        if (why.outcome != Route.Outcome.FOUND)
        {
            // Only a search that tried everything may say there is no way.
            return why.exhausted ? Collections.singletonList(NO_WAY) : null;
        }
        List<String> lines = new ArrayList<>();
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

    private Route effective(RouteController c)
    {
        Route route = c.route();
        if (unreachable(route))
        {
            return why;
        }
        // Not the way to the nearest spot while the real way is looked for.
        return route != null && route.outcome == Route.Outcome.NEAREST && explaining ? null : route;
    }

    private boolean unreachable(Route route)
    {
        Route way = why;
        return route != null && route.outcome == Route.Outcome.NEAREST && route == whyFor && way != null
            && (way.outcome == Route.Outcome.FOUND || way.outcome == Route.Outcome.NEAREST && closer(way, route));
    }

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
            Set<Integer> gone = new HashSet<>(walkedAhead(before));
            gone.removeAll(walkedAhead(ahead));
            fadeOut(gone);
        }
        shown = config.routeInGame() ? ahead : null;
        boolean up = bringUp;
        bringUp = false;
        showSteps(c, up);
        repaint();
    }

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

    /** Steps avoided for the current route only (lower case). */
    private final Set<String> routeAvoid = ConcurrentHashMap.newKeySet();

    private List<String> neverUse()
    {
        List<String> names = new ArrayList<>();
        for (String line : config.routeNeverUse().split("[\\r\\n]+"))
        {
            if (!line.trim().isEmpty())
            {
                names.add(line.trim());
            }
        }
        return names;
    }

    private Set<String> avoided()
    {
        Set<String> all = new HashSet<>(routeAvoid);
        for (String name : neverUse())
        {
            all.add(name.toLowerCase(Locale.ROOT));
        }
        return all;
    }

    interface StepActions
    {
        void avoid(String name);

        void never(String name);

        void allow(String name);

        List<String> neverUsed();

        default List<String> blockers()
        {
            return null;
        }

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
            routeAvoid.add(name.toLowerCase(Locale.ROOT));
            settingsChanged();
        }

        @Override
        public void never(String name)
        {
            List<String> names = neverUse();
            if (!names.contains(name))
            {
                names.add(name);
                configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routeNeverUse", String.join("\n", names));
            }
        }

        @Override
        public void allow(String name)
        {
            List<String> names = neverUse();
            names.removeIf(n -> n.equalsIgnoreCase(name));
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, "routeNeverUse", String.join("\n", names));
        }

        @Override
        public List<String> neverUsed()
        {
            return neverUse();
        }

        @Override
        public List<String> blockers()
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

    private JComponent steps(RouteController c, int width, Consumer<WorldPoint> focus)
    {
        return steps(c, width, focus, false);
    }

    /** With {@code roomy} (the sidebar): first a box with what to do now and next. */
    private JComponent steps(RouteController c, int width, Consumer<WorldPoint> focus, boolean roomy)
    {
        Route route = effective(c);
        String status = route == null && explaining ? "Cannot get there with what you have; looking for the way there…"
            : c.status();
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
            // So a wrong dock choice can be told apart from a wrong level.
            String text = config.routeIgnoreLevels() ? "Planned ignoring levels"
                : "Planned for Sailing level " + planned.sailingLevel;
            panel.add(label(text, width, Font.PLAIN, ColorScheme.MEDIUM_GRAY_COLOR), 1);
        }
        return panel;
    }

    /** The step list: title, notes, steps (click to see on the map) and "Clear path". */
    static JComponent panel(Route route, int target, String status, int width, Consumer<WorldPoint> focus,
        Runnable clear)
    {
        return panel(route, target, status, width, focus, clear, null);
    }

    static JComponent panel(Route route, int target, String status, int width, Consumer<WorldPoint> focus,
        Runnable clear, StepActions actions)
    {
        return panel(route, target, status, width, focus, clear, actions, WALK, JUMP, 0);
    }

    static JComponent panel(Route route, int target, String status, int width, Consumer<WorldPoint> focus,
        Runnable clear, StepActions actions, Color walk, Color jump, int done)
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
                panel.add(label(RouteText.cannotYet(route), width, Font.BOLD, CANNOT));
            }
            List<String> blockers = actions == null || route.outcome != Route.Outcome.NEAREST ? null
                : actions.blockers();
            if (blockers != null)
            {
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
                // Vines, jungle and webs do not stop the route: what to take along.
                panel.add(label("Bring: " + bring, width, Font.PLAIN, new Color(150, 220, 150)));
            }
            int number = 1;
            boolean unsure = false;
            for (Route.Step step : route.steps)
            {
                boolean lacking = unreachable && RouteText.lacks(step);
                boolean cannot = blocked >= 0 && number - 1 >= blocked;
                String detail = lacking ? "Needs " + step.detail.replaceFirst("^You lack:?\\s*", "") : step.detail;
                if (!lacking && RouteText.unsure(detail))
                {
                    unsure = true; // said once under the steps
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
                row.setForeground(number - 2 < done ? ColorScheme.MEDIUM_GRAY_COLOR : cannot ? CANNOT
                    : color(step, walk, jump));
                row.setMargin(new Insets(1, 0, 1, 0));
                // A passage, stairs or transport shows where it leads (the other map).
                boolean leads = step.isJump() && !Tiles.isSea(step.last()) && step.first() != step.last();
                int point = leads ? step.last() : step.first();
                row.setToolTipText(leads ? "Show where this leads on the map" : "Show on the map");
                row.addActionListener(a -> focus.accept(worldPoint(point)));
                row.setAlignmentX(0);
                boolean choice = step.name != null && (step.kind == Route.Step.Kind.TELEPORT
                    || step.kind == Route.Step.Kind.TRANSPORT || step.kind == Route.Step.Kind.SHIP
                    || step.kind == Route.Step.Kind.ENTRANCE && step.category != null);
                if (actions == null || !choice)
                {
                    panel.add(row);
                    continue;
                }
                JPanel line = new JPanel(new BorderLayout());
                line.setOpaque(false);
                line.setAlignmentX(0);
                line.add(row, BorderLayout.CENTER);
                JButton more = new JButton("⋯");
                more.setFocusable(false);
                more.setMargin(new Insets(0, 4, 0, 4));
                more.setToolTipText("Leave this out of the route");
                String name = step.name;
                String category = step.category;
                more.addActionListener(a -> {
                    JPopupMenu menu = new JPopupMenu();
                    item(menu, "Avoid for this route", () -> actions.avoid(name));
                    if (category != null)
                    {
                        item(menu, "Avoid every " + category + " for this route",
                            () -> actions.avoid(RouteSource.TYPE + category));
                    }
                    menu.addSeparator();
                    item(menu, "Never use \"" + name + "\"", () -> actions.never(name));
                    if (category != null)
                    {
                        item(menu, "Never use any " + category, () -> actions.never(RouteSource.TYPE + category));
                    }
                    menu.show(more, 0, more.getHeight());
                });
                line.add(more, BorderLayout.EAST);
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

    private static void item(JPopupMenu menu, String text, Runnable action)
    {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        menu.add(item);
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
            WorldPoint wp = worldPoint(point);
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

    private final class WorldMapRoute extends Overlay
    {
        WorldMapRoute()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.MANUAL);
            drawAfterInterface(InterfaceID.WORLDMAP);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
            if (route == null || map == null || map.isHidden())
            {
                return null;
            }
            Shape clip = g.getClip();
            g.setClip(map.getBounds());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (Route.Step step : route.steps)
            {
                boolean walking = step.kind == Route.Step.Kind.WALK;
                if (walking || step.kind == Route.Step.Kind.SAIL)
                {
                    g.setColor(walking ? walk() : SEA);
                    // Every other walked tile keeps it light on a zoomed-out map.
                    for (int i = 0; i < step.points.length; i += walking ? 2 : 1)
                    {
                        Point p = onMap(step.points[i]);
                        if (p != null)
                        {
                            g.fillRect(p.getX() - 1, p.getY() - 1, 3, 3);
                        }
                    }
                }
                else if (step.points.length >= 2)
                {
                    Point a = step.first() >= 0 && step.kind != Route.Step.Kind.TELEPORT ? onMap(step.first()) : null;
                    Point b = onMap(step.points[step.points.length - 1]);
                    g.setColor(step.kind == Route.Step.Kind.BOARD || step.kind == Route.Step.Kind.DISEMBARK ? SEA : jump());
                    if (a != null && b != null)
                    {
                        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{6f, 5f}, 0f));
                        g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
                    }
                    for (Point p : new Point[]{a, b})
                    {
                        if (p != null)
                        {
                            g.setStroke(new BasicStroke(2f));
                            g.drawOval(p.getX() - 5, p.getY() - 5, 10, 10);
                        }
                    }
                }
            }
            g.setClip(clip);
            return null;
        }

        private Point onMap(int node)
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

    /** The step to take now: a teleport, or a transport or passage the player stands at; or null. */
    static Route.Step nextAction(Route route, int player)
    {
        for (Route.Step step : route.steps)
        {
            if (step.kind == Route.Step.Kind.WALK)
            {
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

    private static final int OBSTACLE_AHEAD = 5;

    /** What to do now, such as "Chop-down Vines (bring an axe)", or null. */
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
        return RouteText.describe(step);
    }

    private void paintNextAction(Graphics2D g, Route route, Player player)
    {
        WorldPoint me = lastLocation;
        String text = nextText(route, me == null ? -1 : pack(me));
        LocalPoint local = player.getLocalLocation();
        if (text == null || local == null)
        {
            return;
        }
        // The font first: the text is centred with it.
        g.setFont(FontManager.getRunescapeSmallFont());
        Point at = Perspective.getCanvasTextLocation(client, g, local, text, player.getLogicalHeight() + 60);
        if (at == null)
        {
            return;
        }
        int x = at.getX();
        int y = at.getY();
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        Route.Step next = nextAction(route, me == null ? -1 : pack(me));
        g.setColor(next != null && RouteText.lacks(next) ? CANNOT : jump());
        g.drawString(text, x, y);
    }

    private static final int MINIMAP_ALPHA = 150;

    /** Dots on the minimap: above the widgets, or the minimap would cover them. */
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
                    for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0])
                    {
                        dot(g, view, plane, point, color);
                    }
                }

            }
            Color normal = alpha(current(), MINIMAP_ALPHA);
            long now = System.currentTimeMillis();
            int blocked = RouteText.blockedFrom(route);
            List<Route.Step> steps = route == null ? Collections.<Route.Step>emptyList() : route.steps;
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
            Point mini = local == null ? null : Perspective.localToMinimap(client, local);
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
            if (!config.routeInGame() || player == null)
            {
                return null;
            }
            WorldView view = client.getTopLevelWorldView();
            boolean instance = view == null || view.isInstance();
            if (route != null && (!instance || house.inside()))
            {
                // Not in other instances (their tiles copy a template); in the house the portal is the next thing.
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
            int fill = config.routeTileFill();
            int borderAlpha = config.routeTileBorder();
            double tileWidth = config.routeTileWidth();
            boolean border = borderAlpha > 0 && tileWidth > 0;
            g.setStroke(new BasicStroke((float) Math.max(0.5, Math.min(8, tileWidth))));
            for (Map.Entry<Integer, Route> leg : laterLegs())
            {
                Color color = legColor(leg.getKey());
                Color fillColor = alpha(color, fill);
                Color borderColor = border ? alpha(color, borderAlpha) : null;
                for (Route.Step step : leg.getValue().steps)
                {
                    for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0])
                    {
                        if (Tiles.z(point) == plane)
                        {
                            tile(g, view, point, fillColor, borderColor);
                        }
                    }
                }
            }
            int blocked = RouteText.blockedFrom(route);
            List<Route.Step> steps = route == null ? Collections.<Route.Step>emptyList() : route.steps;
            long now = System.currentTimeMillis();
            for (int index = 0; index < steps.size(); index++)
            {
                Route.Step step = steps.get(index);
                Color walk = blocked >= 0 && index >= blocked ? CANNOT : normal;
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
                    float in = fadeIn(point, now);
                    tile(g, view, point, in < 1 ? alpha(walk, fill * in) : fillColor,
                        !border ? null : in < 1 ? alpha(walk, borderAlpha * in) : borderColor);
                }
            }
            for (Map.Entry<Integer, Long> entry : fading.entrySet())
            {
                int point = entry.getKey();
                float alpha = fadeAlpha(entry.getValue(), now);
                if (alpha > 0 && Tiles.z(point) == plane)
                {
                    tile(g, view, point, alpha(normal, alpha * fill), border ? alpha(normal, alpha * borderAlpha) : null);
                }
            }
            g.setStroke(old);
            return null;
        }

        private void tile(Graphics2D g, WorldView view, int point, Color fill, Color border)
        {
            LocalPoint local = LocalPoint.fromWorld(view, Tiles.x(point), Tiles.y(point));
            Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
            if (tile != null)
            {
                g.setColor(fill);
                g.fill(tile);
                if (border != null)
                {
                    g.setColor(border);
                    g.draw(tile);
                }
            }
        }
    }

    static final class RouteText
    {
        /** "Can't yet: 45 Sailing, Lunar Diplomacy". */
        static String cannotYet(Route route)
        {
            Set<String> missing = new LinkedHashSet<>();
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

        static boolean lacks(Route.Step step)
        {
            return step.detail != null && step.detail.startsWith("You lack");
        }

        /** The first step the player cannot take yet (drawn red from there on), or -1. */
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
            List<String> notes = new ArrayList<>();
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

        private static final Pattern BRING = Pattern.compile("\\(bring (.+)\\)$");

        /** As "an axe, a machete", or null. */
        static String bring(Route route)
        {
            Set<String> tools = new LinkedHashSet<>();
            for (Route.Step step : route.steps)
            {
                for (Route.Obstacle obstacle : step.obstacles)
                {
                    Matcher m = BRING.matcher(obstacle.text);
                    if (m.find())
                    {
                        tools.add(m.group(1));
                    }
                }
            }
            return tools.isEmpty() ? null : String.join(", ", tools);
        }

        /** Whether a detail is only the caution that it may need something unknown. */
        static boolean unsure(String detail)
        {
            return detail != null && detail.startsWith("May need");
        }

        static String duration(Route route)
        {
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
                    return step.name != null ? step.name : step.kind.name().toLowerCase(Locale.ROOT);
            }
        }

        static void paint(Graphics2D g, MapView.Projection p, Route route)
        {
            paint(g, p, route, WALK, JUMP);
        }

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
            for (int index = 0; index < route.steps.size(); index++)
            {
                Route.Step step = route.steps.get(index);
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
            if (route.end >= 0
 && visible(p, route.end))
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
            WorldPoint game = worldPoint(node);
            // Where the map draws it: a part drawn elsewhere (the Kalphite Lair) there, on the floor drawn.

            return p.shown(game).getPlane() == p.plane() && p.shows(game);
        }

        private static double x(MapView.Projection p, int node)
        {
            return Tiles.isSea(node) ? p.screenX(Tiles.x(node))
                : p.screenX(p.shown(worldPoint(node)).getX() + 0.5);
        }

        private static double y(MapView.Projection p, int node)
        {
            return Tiles.isSea(node) ? p.screenY(Tiles.y(node))
                : p.screenY(p.shown(worldPoint(node)).getY() + 0.5);
        }
    }
}
