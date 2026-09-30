package com.hdmapreforged;

import com.hdmapreforged.route.*;
import com.hdmapreforged.route.Route.*;
import com.hdmapreforged.route.Route.Step.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.*;
import net.runelite.api.worldmap.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.worldmap.*;
import net.runelite.client.util.*;

/**
 * "Path to here": our own route search; only shows a way, never walks, clicks or sends anything.
 * Threads: player state on the client thread; searches on their own thread, extra work on the route extras thread;
 * the controller and map drawing on Swing.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class RouteFeature
{
    /** Per account: the house's features ("box:ornate,glory,portal:Varrock") and its plan. */
    static final String HOUSE_KEY = "routeHouse";
    static final String PLAN_KEY = "housePlan";
    private static final int[] BOAT_PORTS = {VarbitID.SAILING_BOAT_1_PORT, VarbitID.SAILING_BOAT_2_PORT,
        VarbitID.SAILING_BOAT_3_PORT, VarbitID.SAILING_BOAT_4_PORT, VarbitID.SAILING_BOAT_5_PORT};
    private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
        VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
    private static final int[] POUCH_AMOUNTS = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
        VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5,
        VarbitID.RUNE_POUCH_QUANTITY_6};
    private static final Set<Integer> RUNE_POUCHES = Set.of(12791, 24416, 27281, 27509);
    private static final int STATE_TICKS = 3;
    private static final int QUEST_TICKS = 100;

    static final Color WALK = new Color(255, 233, 28);
    static final Color JUMP = new Color(190, 120, 255);
    static final Color SEA = new Color(64, 200, 255);
    private static final Set<String> LOOK_KEYS = Set.of("routeColor", "routeJumpColor", "routeTileFill",
        "routeTileBorder", "routeTileWidth", "routeMinimap", "routeInGame", "routeHdTiles", "routeClearOnArrival",
        "routeFollowPlugins");

    private static final Color[] LEG_COLORS = {
        new Color(90, 220, 130), new Color(255, 130, 200), new Color(255, 160, 70), new Color(120, 230, 255),
        new Color(200, 255, 110), new Color(255, 255, 255), new Color(180, 150, 255)};

    static final Color CANNOT = new Color(255, 112, 112);
    private static final Color SP_WALK = new Color(255, 214, 64);
    private static final Color NOTE = new Color(255, 190, 120);
    private static final Color SHADOW = new Color(0, 0, 0, 130);

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

    /**
     * Where a route tile is in the scene: the plan of one's house lies at {@link HousePlan#X}/{@link HousePlan#Y}
     * plus the house's own place, moved to where it is loaded now; null outside the scene.
     */
    private LocalPoint local(WorldView view, int point)
    {
        int x = Tiles.x(point);
        int y = Tiles.y(point);
        return !HousePlan.contains(x, y) ? LocalPoint.fromWorld(view, x, y)
            : house.own() ? LocalPoint.fromScene(x - HousePlan.X + houseX, y - HousePlan.Y + houseY, view) : null;
    }

    private static Ellipse2D circle(double x, double y, double r)
    {
        return new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
    }

    /** A route tile for HD Tile Markers; {@code alpha}: how visible (fading). Client thread. */
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
    private final ChatMessageManager chatMessageManager;
    private final HouseTracker house = new HouseTracker();
    // Nothing on the game's own world map: projecting a long route there every frame made it heavy.
    private final List<Overlay> overlays = List.of(new GameOverlay(), new MinimapOverlay());
    private final MapView.Overlay mapOverlay = this::paintMap;
    private final MapView.MenuContributor menu = this::contribute;

    private volatile ExecutorService searches;
    private volatile ExecutorService background;
    private volatile MapScreen[] screens = new MapScreen[0];
    private volatile RouteController controller;
    private volatile RouteSource source;
    private volatile Pathfinder walker;
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
        return entries.stream().filter(e -> "Shipwright".equalsIgnoreCase(e.kind.name) && e.location.getPlane() == 0)
            .mapToInt(e -> pack(e.location)).toArray();
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

    private final PluginManager pluginManager;
    private volatile Consumer<IntFunction<JComponent>> sidebar = section -> { };
    private volatile Consumer<WorldPoint> sidebarFocus = point -> { };
    private volatile Runnable sidebarTours = () -> { };
    private final ClientThread clientThread;
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
            if (name.equals(plugin.getName()))
            {
                return pluginManager.isPluginEnabled(plugin);
            }
        }
        return false;
    }

    /** Client thread. */
    private boolean warned;

    /** Both planners on is not meant to be: said once after a start, and again when either is switched on. */
    private void warnBoth(boolean again)
    {
        clientThread.invokeLater(() -> {
            warned &= !again;
            if (warned || client.getGameState() != GameState.LOGGED_IN || shortestPathPlanner() || !shortestPathOn())
            {
                return;
            }
            warned = true;
            chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).value("HD Map Reforged: its "
                + "route planner is on, and so is the Shortest Path plugin. The map draws both routes; pick one "
                + "(HD Map Reforged settings, Route planner).").build());
        });
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event)
    {
        if (event.isLoaded() && ShortestPathBridge.PLUGIN_NAME.equals(event.getPlugin().getName()))
        {
            warnBoth(true);
        }
    }

    /** Whether HD Tile Markers draws the ground tiles now (checked every 10 ticks). */
    private volatile boolean hdTiles;
    private boolean hdHome;
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
        boolean walk = Arrays.stream(event.getMenuEntries()).anyMatch(e -> e.getType() == MenuAction.WALK);
        WorldView view = client.getTopLevelWorldView();
        Tile tile = view == null ? null : view.getSelectedSceneTile();
        if (!walk || tile == null)
        {
            return;
        }
        LocalPoint at = tile.getLocalLocation();
        WorldPoint point = plan != null && house.own() ? worldPoint(planNode(at, view.getPlane()))
            : WorldPoint.fromLocalInstance(client, at);
        if (point == null)
        {
            return;
        }
        RouteController c = controller;
        if (c != null && c.target() >= 0)
        {
            menuEntry("Clear route", this::clearRoute);
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

    /** While tiles fade, HD Tile Markers gets them again ten times a second (it does not animate what it was sent). */
    @Subscribe
    public void onClientTick(ClientTick event)
    {
        long now = System.currentTimeMillis();
        if (!hdTiles || fading.isEmpty() && appearing.isEmpty() && !fadedLast || now - lastFadeSend < 100)
        {
            return;
        }
        lastFadeSend = now;
        sentAhead = null;
        updateHdTiles(sentFrom);
    }

    private void updateHdTiles(int player)
    {
        boolean home = house.own();
        if (ticks - lastHdTilesCheck >= 10 || home != hdHome)
        {
            hdHome = home;
            lastHdTilesCheck = ticks;
            // In one's house our own overlay draws them: HD Tile Markers puts an instance's tile in every room built
            // alike (the ring's garden and the tree's).
            boolean on = !home && config.routeHdTiles() && config.routeInGame() && pluginOn(HdTileMarkersBridge.PLUGIN_NAME);
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
        long now = System.currentTimeMillis();
        List<Step> steps = route == null ? Collections.<Step>emptyList() : route.steps;
        for (int index = 0; index < steps.size(); index++)
        {
            Step step = steps.get(index);
            Color color = blocked >= 0 && index >= blocked ? CANNOT : current();
            if (step.kind == Kind.WALK)
            {
                for (int point : step.points)
                {
                    if (player < 0 || Tiles.distance(point, player) <= 40)
                    {
                        taken.add(point);
                        tiles.add(hdTile(point, fadeIn(point, now), color));
                    }
                }
            }
            else if (step.isJump() && step.name != null && !Tiles.isSea(step.first()) && step.first() != step.last())
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
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            float alpha = fadeAlpha(entry.getValue(), now);
            int point = entry.getKey();
            if (alpha > 0.05f && taken.add(point))
            {
                tiles.add(hdTile(point, alpha, current()));
            }
        }
        // The nearer part wins where they cross.
        for (int i = later.size() - 1; i >= 0; i--)
        {
            Color color = legColor(later.get(i).getKey());
            for (Step step : later.get(i).getValue().steps)
            {
                for (int point : step.kind == Kind.WALK ? step.points : new int[0])
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

    /** The last message we sent Shortest Path, not taken for another plugin's. */
    private volatile PluginMessage sent;

    /**
     * Directions other plugins (Quest Helper, clues) send to Shortest Path: its route is drawn whichever planner is
     * chosen, and ours plans them too (setting). We only ask it to tell its transports, after it took the message:
     * a config in it replaces its overrides.
     */
    @Subscribe(priority = -1)
    public void onPluginMessage(PluginMessage message)
    {
        if (ShortestPathBridge.isTransports(message) && handedOver != null)
        {
            spJumps = ShortestPathBridge.jumps(message);
            walkShortestPath(spJumps, handedOver);
            SwingUtilities.invokeLater(() -> showShortestPath(false));
            return;
        }
        boolean path = ShortestPathBridge.isPath(message);
        WorldPoint target = path ? ShortestPathBridge.nearest(ShortestPathBridge.targets(message), lastLocation) : null;
        if (message == sent || (path ? target == null : !ShortestPathBridge.isClear(message)))
        {
            return;
        }
        if (target == null && handedOver != null)
        {
            forgetHandOver();
            SwingUtilities.invokeLater(() -> showShortestPath(false));
        }
        else if (target != null && shortestPathOn())
        {
            forgetHandOver();
            handedOver = target;
            eventBus.post(sent = ShortestPathBridge.path(null, ShortestPathBridge.config(message)));
            SwingUtilities.invokeLater(() -> showShortestPath(false));
        }
        if (shortestPathPlanner() || !config.routeFollowPlugins())
        {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null && target != null)
            {
                fromPlugin = true;
                routeAvoid.clear();
                c.setTarget(pack(target));
            }
            else if (c != null && fromPlugin)
            {
                fromPlugin = false;
                c.clear();
            }
        });
    }

    /** As the planner, Shortest Path's route has the card; else it is only drawn, under ours. */
    private void showShortestPath(boolean up)
    {
        if (shortestPathPlanner())
        {
            showHandedOver(handedOver == null ? null : this::handedOverPanel, up);
        }
        else
        {
            repaint();
        }
    }

    private volatile List<ShortestPathBridge.Jump> spJumps = List.of();
    /** Its walks, looked for here on foot between the points it told (it tells no tiles); per its message. */
    private volatile List<int[]> spWalks = List.of();
    private final AtomicInteger spAsked = new AtomicInteger();

    /**
     * From the player to its first transport, between its transports, from the last to its target: walking only (no
     * shortcut or transport of ours), as its own path walks between the transports it takes.
     */
    private void walkShortestPath(List<ShortestPathBridge.Jump> jumps, WorldPoint target)
    {
        int asked = spAsked.incrementAndGet();
        WorldPoint from = lastLocation;
        Pathfinder pf = walker;
        ExecutorService work = background;
        if (from == null || pf == null || work == null)
        {
            return;
        }
        List<WorldPoint> ends = new ArrayList<>(List.of(from));
        jumps.forEach(j -> ends.addAll(List.of(j.from, j.to)));
        ends.add(target);
        work.execute(() -> {
            List<int[]> walks = new ArrayList<>();
            for (int i = 0; i + 1 < ends.size() && asked == spAsked.get(); i += 2)
            {
                RouteRequest request = new RouteRequest(pack(ends.get(i)), pack(ends.get(i + 1)), List.of(), List.of(),
                    1, true, RouteRequest.DEFAULT_NODE_LIMIT);
                try
                {
                    pf.find(request, () -> asked != spAsked.get()).steps.stream().filter(s -> s.kind == Kind.WALK)
                        .forEach(s -> walks.add(s.points));
                }
                catch (RuntimeException e)
                {
                    log.debug("No walk found for Shortest Path's route", e);
                }
            }
            if (asked == spAsked.get())
            {
                spWalks = walks;
                SwingUtilities.invokeLater(this::repaint);
            }
        });
    }

    private void paintHandedOver(Graphics2D g, MapView.Projection p)
    {
        WorldPoint target = handedOver;
        if (target == null)
        {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float[] jump = {9f, 6f};
        // Beside our route (both planners on): white and dashed, to tell the two apart.
        boolean beside = !shortestPathPlanner();
        Color walked = beside ? Color.WHITE : SP_WALK;
        // Its teleports and transports as it told them, and the walks between them as found here on foot.
        for (int[] walk : spWalks)
        {
            shadowed(g, RouteText.path(p, walk, false), 2.5f, beside ? new float[]{6f, 5f} : null, walked);
        }
        for (ShortestPathBridge.Jump j : spJumps)
        {
            line(g, p, j.from, j.to, jump, beside ? Color.WHITE : new Color(180, 120, 255));
        }
        if (p.shows(target))
        {
            double x = RouteText.x(p, pack(target));
            double y = RouteText.y(p, pack(target));
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(circle(x, y, 7));
            g.setColor(walked);
            g.fill(circle(x, y, 5));
        }
    }

    private static void line(Graphics2D g, MapView.Projection p, WorldPoint a, WorldPoint b, float[] dash, Color color)
    {
        if (p.shows(a) && p.shows(b))
        {
            shadowed(g, new Line2D.Double(RouteText.x(p, pack(a)), RouteText.y(p, pack(a)), RouteText.x(p, pack(b)),
                RouteText.y(p, pack(b))), 2.5f, dash, color);
        }
    }

    /** Drawn over a dark line two pixels wider. */
    private static void shadowed(Graphics2D g, Shape shape, float width, float[] dash, Color color)
    {
        g.setStroke(new BasicStroke(width + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.setColor(SHADOW);
        g.draw(shape);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.setColor(color);
        g.draw(shape);
    }

    private JComponent handedOverPanel(int width)
    {
        JPanel panel = column();
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
        panel.add(label("Route by Shortest Path", width, Font.BOLD, Color.WHITE));
        boolean on = shortestPathOn();
        panel.add(label(on ? "Planned by Shortest Path, which draws it in the game. On this map: the teleports and "
            + "transports it takes, and the walks between them as found here on foot."
            : "The Shortest Path plugin is not on. Install or switch it on, or set Route planner to HD Map Reforged.",
            width, Font.PLAIN, on ? ColorScheme.LIGHT_GRAY_COLOR : NOTE));
        int number = 1;
        for (ShortestPathBridge.Jump j : spJumps)
        {
            panel.add(label(number++ + ". " + j.name, width, Font.PLAIN, new Color(200, 170, 255)));
        }
        panel.add(button("Clear path", this::handOverClear));
        return panel;
    }

    private void handOver(WorldPoint target)
    {
        forgetHandOver();
        handedOver = target;
        // Shortest Path reads the player's position when a message arrives: client thread.
        PluginMessage path = ShortestPathBridge.path(target);
        clientThread.invokeLater(() -> eventBus.post(sent = path));
        showShortestPath(true);
    }

    /** Shortest Path plans it as the planner (true), or also when both are on: drawn under ours, to compare. */
    private boolean toShortestPath(WorldPoint target)
    {
        boolean planner = shortestPathPlanner();
        if (planner || shortestPathOn())
        {
            handOver(target);
        }
        return planner;
    }

    private void showHandedOver(IntFunction<JComponent> panel, boolean up)
    {
        for (MapScreen screen : screens)
        {
            screen.showRoute(panel, up); // repaints
        }
        sidebar.accept(panel);
    }

    private void handOverClear()
    {
        forgetHandOver();
        clientThread.invokeLater(() -> eventBus.post(sent = ShortestPathBridge.clear()));
        showShortestPath(false);
    }

    private void forgetHandOver()
    {
        handedOver = null;
        spJumps = List.of();
        spWalks = List.of();
        spAsked.incrementAndGet();
    }

    void start(MapScreen... on)
    {
        int generation = loads.incrementAndGet();
        screens = on;
        // At the lowest priority a busy client starved searches for seconds.
        searches = daemon("HD Map Reforged route", Thread.NORM_PRIORITY - 1);
        background = daemon("HD Map Reforged route extras", Thread.MIN_PRIORITY);
        for (MapScreen screen : on)
        {
            screen.view().addOverlay(mapOverlay);
            screen.view().addMenuContributor(menu);
            screen.setRouter(this::routeTo);
            screen.setStopAdder(this::addStop);
            screen.setTours(tourActions);
        }
        overlays.forEach(overlayManager::add);
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
                    synchronized (this)
                    {
                        collision = map;
                        map.setHouse(plan);
                    }
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
        // Started while logged in: this account's house, as at login.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && generation == loads.get())
            {
                loadHouse();
            }
        });
        warnBoth(false);
    }

    private static ExecutorService daemon(String name, int priority)
    {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        });
    }

    /** Counts starts and stops, so a load finishing after a stop is dropped. */
    private final AtomicInteger loads = new AtomicInteger();

    void stop()
    {
        loads.incrementAndGet();
        eventBus.unregister(this);
        overlays.forEach(overlayManager::remove);
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
            screen.view().setAreaBounds(p -> null); // holds the collision map
        }
        sidebar.accept(null);
        sidebar = section -> { };
        sidebarFocus = point -> { };
        sidebarTours = () -> { };
        sidebarNow = null;
        RouteController running = controller;
        if (running != null)
        {
            running.clear();
        }
        controller = null;
        for (ExecutorService pool : new ExecutorService[]{searches, background})
        {
            if (pool != null)
            {
                pool.shutdownNow();
            }
        }
        searches = null;
        background = null;
        screens = new MapScreen[0];
        shown = null;
        source = null;
        walker = null;
        collision = null;
        ports = Collections.emptyMap();
        // Client thread state: reset there, before any start's own work there.
        clientThread.invokeLater(() -> {
            house.reset();
            houseOf = Long.MIN_VALUE;
            houseLook = new BufferedImage[4];
            setPlan(null);
            bank = null;
            bankItems = null;
            bankChanged = true;
            lastLocation = null;
            start = -2;
            state = PlayerState.UNKNOWN;
            sentAhead = null;
            sentLegs = null;
            sentFrom = -1;
            fadedLast = false;
            lastHdTilesCheck = Integer.MIN_VALUE / 2;
            warned = false;
        });
        // Work still on the extras thread finds it is not wanted.
        tour = null;
        orders.incrementAndGet();
        if (handedOver != null)
        {
            // Else Shortest Path keeps drawing it, with no Clear path here after a restart.
            clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.clear()));
        }
        forgetHandOver();
        why = null;
        whyFor = null;
        whyTarget = -1;
        explaining = false;
        ahead = null;
        aheadOf = null;
        aheadWalked = null;
        routeAvoid.clear();
        logged = null;
        aheadWalkedOf = null;
        lastAheadSteps = -1;
        lastAheadPoints = -1;
        bringUp = false;
        fromPlugin = false;
    }

    // ---- client thread ----

    /**
     * In a house, where the map shows the player: on its plan in one's own house once seen, else where it stands on the
     * surface (its portal); the house itself is a copy far from its town.
     */
    /** In one's house, its portal outside: where "nearest" counts from. */
    WorldPoint outside()
    {
        int exit = house.exit();
        return exit < 0 ? null : worldPoint(exit);
    }

    private WorldPoint nearFrom(int node)
    {
        return node < 0 ? null : HousePlan.contains(Tiles.x(node), Tiles.y(node)) ? outside() : worldPoint(node);
    }

    WorldPoint home()
    {
        int at = inHouse();
        int portal = at >= 0 ? at : !house.inside() ? -1 : house.exit() >= 0 ? house.exit() : house.portal();
        return portal < 0 ? null : worldPoint(portal);
    }

    /**
     * Tiles from the plan to the scene as loaded now. The game centres the scene on where one stood as it loaded (a
     * teleport in, building mode), so the house lies elsewhere in it each time; the plan keeps its south-west room at
     * chunk 2. Client thread writes.
     */
    private volatile int houseX;
    private volatile int houseY;

    private void placeHouse(WorldView view)
    {
        int[][][] chunks = view == null ? null : view.getInstanceTemplateChunks();
        int west = 13;
        int south = 13;
        for (int[][] plane : chunks == null ? new int[0][][] : chunks)
        {
            for (int x = 0; x < plane.length; x++)
            {
                for (int y = 0; y < plane[x].length; y++)
                {
                    // A room of the house: copied from the game's house templates.
                    int data = plane[x][y];
                    if (data != -1 && HouseTracker.isTemplate((data >> 14 & 0x3ff) * 8, (data >> 3 & 0x7ff) * 8))
                    {
                        west = Math.min(west, x);
                        south = Math.min(south, y);
                    }
                }
            }
        }
        houseX = west < 13 ? (west - 2) * 8 : 0;
        houseY = south < 13 ? (south - 2) * 8 : 0;
    }

    /** The player's tile on the plan of their own house once seen; else -1 (the route starts from its ways out). */
    private int inHouse()
    {
        Player player = client.getLocalPlayer();
        LocalPoint local = player == null ? null : player.getLocalLocation();
        WorldView view = client.getTopLevelWorldView();
        return plan == null || !house.own() || local == null || view == null ? -1 : planNode(local, view.getPlane());
    }

    /** A scene tile of one's house on its plan. */
    private int planNode(LocalPoint local, int plane)
    {
        return HousePlan.node(local.getSceneX() - houseX, local.getSceneY() - houseY, plane);
    }

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
        if (house.inside())
        {
            placeHouse(client.getTopLevelWorldView());
        }
        if (house.takeScan())
        {
            scanHouse();
        }
        if (house.takeChanged())
        {
            writeHouse(house.features());
        }
        int from = location == null ? -2 : house.inside() ? inHouse()
            : onBoat ? Tiles.seaAt(location.getX(), location.getY()) : node;
        start = from;
        // Not in other instances (their tiles are copies); in one's own house its plan tells where one is.
        boolean replans = !instance || house.inside() && from >= 0;
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
                c.playerMoved(from >= 0 ? from : -1, replans);
                updateAhead(c, from);
            }
        });
    }

    private volatile Route ahead;
    /** The sidebar's "Now" box and its width. Swing thread. */
    private JComponent sidebarNow;
    private int sidebarWidth;
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
        fadeTimer.start(); // no-op while running
    }

    static float fadeAlpha(long since, long now)
    {
        long elapsed = now - since;
        return elapsed <= 0 ? 1f : Math.max(0f, 1f - elapsed / (float) FADE_MS);
    }

    private void fadeStep()
    {
        long now = System.currentTimeMillis();
        // Tiles fade all the while a route is walked: a map is painted again only while it shows one of them.
        for (MapScreen screen : screens)
        {
            if (showsFading(screen.view()))
            {
                screen.view().repaint();
            }
        }
        fading.values().removeIf(since -> now - since >= FADE_MS);
        appearing.values().removeIf(since -> now - since >= FADE_IN_MS);
        if (fading.isEmpty() && appearing.isEmpty())
        {
            fadeTimer.stop();
        }
    }

    /** Whether the map shows a passed tile that fades (appearing tiles fade in the game only). */
    private boolean showsFading(MapView view)
    {
        MapView.Projection p = view.projection();
        Rectangle seen = new Rectangle(p.width(), p.height());
        return view.isShowing() && fading.keySet().stream().map(RouteFeature::worldPoint).filter(p::shows).map(p::shown)
            .anyMatch(at -> at.getPlane() == p.plane() && seen.contains(p.screenX(at.getX() + 0.5), p.screenY(at.getY() + 0.5)));
    }

    private static void eachWalked(Route route, IntConsumer tile)
    {
        for (Step step : route.steps)
        {
            if (step.kind == Kind.WALK)
            {
                for (int point : step.points)
                {
                    tile.accept(point);
                }
            }
        }
    }

    private static Set<Integer> walked(Route route)
    {
        Set<Integer> tiles = new HashSet<>();
        if (route != null)
        {
            eachWalked(route, tiles::add);
        }
        return tiles;
    }

    private static Set<Integer> minus(Set<Integer> a, Set<Integer> b)
    {
        Set<Integer> left = new HashSet<>(a);
        left.removeAll(b);
        return left;
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
        fadeTimer.start();
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

    private static long jumps(Route route)
    {
        return route.steps.stream().filter(Step::isJump).count();
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
        if (route != null && route == aheadOf && before != null && now != null && player >= 0 && !Tiles.isSea(player)
            && !now.steps.isEmpty() && jumps(now) >= jumps(before) && Tiles.distance(player, now.steps.get(0).first()) > 2)
        {
            // Off the way: the route stays as it was, nothing passed (walking away beside it is not walking it) and
            // nothing back, until the player is on it again or a new search's route fades in. A teleport or
            // transport just taken is passed, wherever it put the player.
            return;
        }
        aheadOf = route;
        if (before != null && before != now)
        {
            fadeOut(minus(walkedBefore, walkedNow));
            fadeInTiles(minus(walkedNow, walkedBefore));
        }
        ahead = now;
        aheadWalkedOf = now;
        // Not wrapped again: on Java 11 the wrappers would nest every tick until the stack overflows.
        aheadWalked = walkedNow == walkedBefore ? walkedBefore : Collections.unmodifiableSet(walkedNow);
        shown = config.routeInGame() ? now : null;
        int steps = now == null ? -1 : now.steps.size();
        int points = now == null || now.steps.isEmpty() ? -1 : now.steps.get(0).points.length;
        if (steps != lastAheadSteps && lastAheadSteps >= 0 && steps >= 0)
        {
            showSteps(c, false);
        }
        else if (points != lastAheadPoints && steps >= 0)
        {
            // The sidebar's "Now" counts down the tiles left of this walk: only that box is built again.
            JComponent box = sidebarNow;
            Container list = box == null ? null : box.getParent();
            if (list != null && list.getParent() != null && !now.steps.isEmpty())
            {
                int i = list.getComponentZOrder(box);
                list.remove(i);
                list.add(sidebarNow = nowBox(now, sidebarWidth, current(), jump()), i);
                list.revalidate();
                list.repaint();
            }
            else
            {
                sidebar.accept(width -> steps(c, width, sidebarFocus, true));
            }
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
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        boolean pouch = inventory != null && Arrays.stream(inventory.getItems()).anyMatch(i -> RUNE_POUCHES.contains(i.getId()));
        if (inventory != null)
        {
            count(inventory, carried);
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
        int[] boatTiles = Arrays.stream(BOAT_PORTS).mapToObj(varbit -> ports.get(client.getVarbitValue(varbit)))
            .filter(Objects::nonNull).distinct().mapToInt(Integer::intValue).toArray();
        if (bankChanged || bankItems == null)
        {
            bankItems = ItemSnapshot.of(Collections.emptyMap(), Collections.emptySet(), bank);
            bankChanged = false;
        }
        return new PlayerState(ItemSnapshot.withBankOf(carried, unlimited, bankItems), client.getRealSkillLevel(Skill.SAILING),
            pandemonium, boatTiles, config.routeRunning(), house.inside(), house.inside() ? house.exit() : house.portal(),
            house.own(), house.features(), client.getVarbitValue(VarbitID.POH_TELE_TOGGLE) == 0);
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
        // Not while a scene loads: the scan sees those, and going into building mode its spaces load before it shows.
        if (house.own() && client.getGameState() == GameState.LOGGED_IN)
        {
            house.seen(name(event.getGameObject().getId()));
        }
    }

    private String name(int id)
    {
        ObjectComposition object = client.getObjectDefinition(id);
        if (object != null && object.getImpostorIds() != null)
        {
            object = object.getImpostor();
        }
        return object == null || "null".equals(object.getName()) ? null : object.getName();
    }

    /** The game's list of portal nexus teleports (an enum of structs), and the param naming each. */
    private static final int NEXUS_TELEPORTS = 1377;
    private static final int NEXUS_NAME = 660;
    /** The nexus' 45 slots: runs of varbits one after another, from each run's first. */
    private static final int[][] NEXUS_SLOTS = {{VarbitID.POH_NEXUS_TELE_1, 15}, {VarbitID.POH_NEXUS_TELE_16, 3},
        {VarbitID.POH_NEXUS_TELE_19, 12}, {VarbitID.POH_NEXUS_TELE_31, 5}, {VarbitID.POH_NEXUS_TELE_36, 10}};
    private static final Pattern STAIRS = Pattern.compile(".*(tair|adder|rapdoor).*");

    /**
     * One's own house, looked through once per visit (its objects spawn while it loads, before a tick finds the player
     * inside): its portals, nexus and such for routes, and its plan, drawn at the surface map's edge. Kept per account.
     */
    private void scanHouse()
    {
        WorldView view = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        LocalPoint me = player == null ? null : player.getLocalLocation();
        if (view == null || me == null)
        {
            house.scanAgain();
            return;
        }
        int size = HousePlan.SIZE;
        int dx = houseX;
        int dy = houseY;
        Tile[][][] scene = view.getScene().getTiles();
        CollisionData[] collision = view.getCollisionMaps();
        // Moved to the house's own place (houseX/houseY): the scene is centred on where one stood as it loaded.
        byte[][] placed = new byte[4][size * size];
        Map<Integer, String> things = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int z = 0; z < 4; z++)
        {
            int[][] flags = collision == null || collision[z] == null ? new int[0][] : collision[z].getFlags();
            for (int x = Math.max(0, dx); x < Math.min(size, size + dx); x++)
            {
                for (int y = Math.max(0, dy); y < Math.min(size, size + dy); y++)
                {
                    Tile tile = scene[z][x][y];
                    // No ground (the void round the house) is no floor, though the game sets no flag there.
                    int f = tile == null || tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null
                        || x >= flags.length || y >= flags[x].length ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : flags[x][y];
                    int node = HousePlan.node(x - dx, y - dy, z);
                    placed[z][(x - dx) * size + y - dy] = (byte) (((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0 ? 1 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0 ? 2 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0 ? 4 : 0));
                    List<TileObject> objects = new ArrayList<>(tile == null ? List.of() : Arrays.asList(tile.getGameObjects()));
                    if (tile != null)
                    {
                        // A mounted glory hangs on a wall.
                        objects.add(tile.getWallObject());
                        objects.add(tile.getDecorativeObject());
                    }
                    for (TileObject object : objects)
                    {
                        // Once per object, at its south-west tile.
                        Point base = object instanceof GameObject ? ((GameObject) object).getSceneMinLocation() : null;
                        String name = object == null || base != null && (base.getX() != x || base.getY() != y) ? null
                            : name(object.getId());
                        if (name == null)
                        {
                            continue;
                        }
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(" space"))
                        {
                            // Building mode's empty spaces (its varbit can come before the normal house loads).
                            house.scanAgain();
                            return;
                        }
                        names.add(name);
                        if (object instanceof WallObject && lower.contains("door"))
                        {
                            door(placed[z], x - dx, y - dy, ((WallObject) object).getOrientationA());
                        }
                        else if (lower.contains("nexus") || name.equals("Portal") || HouseTracker.feature(name) != null
                            || STAIRS.matcher(name).matches())
                        {
                            things.put(node, lower.contains("nexus") ? RouteSource.NEXUS : name);
                        }
                    }
                }
            }
        }
        house.replaceFeatures(Set.of());
        names.forEach(house::seen);
        things.replaceAll((node, name) -> name.equals(RouteSource.NEXUS) ? nexus() : name);
        int arrival = HousePlan.node(me.getSceneX() - dx, me.getSceneY() - dy, view.getPlane());
        List<Integer> from = new ArrayList<>(List.of(arrival));
        things.forEach((node, name) -> {
            for (int d = 0; STAIRS.matcher(name).matches() && d < 9; d++)
            {
                // From where the player stands; stairs lead to the floors above and below.
                from.add(Tiles.pack(Tiles.x(node) + d / 3 - 1, Tiles.y(node) + d % 3 - 1, Tiles.z(node)));
            }
        });
        HousePlan.keepReachable(placed, from);
        String exit = "Portal";
        for (Map.Entry<Integer, String> thing : things.entrySet())
        {
            for (int d = 1; exit.equals(thing.getValue()) && d <= 3; d++)
            {
                int north = thing.getKey() + d;
                int y = Tiles.y(north) - HousePlan.Y;
                if (y < size && (placed[Tiles.z(north)][(Tiles.x(north) - HousePlan.X) * size + y] & 1) != 0)
                {
                    arrival = north;
                    exit = "";
                }
            }
        }
        // One of fairy ring, spirit tree and spiritual fairy tree: the one nearest where one arrives.
        int spawn = arrival;
        things.entrySet().stream().filter(t -> garden(t.getValue()))
            .min(Comparator.comparingInt(t -> Tiles.distance(t.getKey(), spawn))).map(Map.Entry::getKey).ifPresent(at -> {
                String centre = things.get(at);
                things.keySet().removeIf(node -> node != at && garden(things.get(node)));
                Set<String> kept = new LinkedHashSet<>(house.features());
                kept.removeIf(Arrays.asList(HouseSettings.GARDENS)::contains);
                kept.add(HouseTracker.feature(centre));
                house.replaceFeatures(kept);
            });
        HousePlan seen = new HousePlan(placed, things, arrival);
        // The game's own minimap of each floor.
        BufferedImage[] look = new BufferedImage[4];
        for (int z = 0; z < 4; z++)
        {
            // Only floors the house has: each picture is drawn for the whole scene.
            byte[] floor = placed[z];
            SpritePixels drawn = IntStream.range(0, floor.length).anyMatch(i -> (floor[i] & 1) != 0)
                ? client.drawInstanceMap(z) : null;
            look[z] = drawn == null ? null : sceneOf(drawn.toBufferedImage(), floor, dx, dy);
        }
        keepLook(look);
        String text = seen.encode();
        if (text != null)
        {
            configManager.setRSProfileConfiguration(HdMapReforgedConfig.GROUP, PLAN_KEY, text);
        }
        setPlan(seen);
        writeHouse(house.features());
    }

    private static boolean garden(String thing)
    {
        String feature = HouseTracker.feature(thing);
        return feature != null && Arrays.asList(HouseSettings.GARDENS).contains(feature);
    }

    /** A door counts as open (orientation 1 west, 2 north, 4 east, 8 south): its wall's bit becomes a door's. */
    private static void door(byte[] tiles, int x, int y, int orientation)
    {
        int wall = orientation == 2 || orientation == 8 ? 2 : orientation == 1 || orientation == 4 ? 4 : 0;
        int tx = orientation == 1 ? x - 1 : x;
        int ty = orientation == 8 ? y - 1 : y;
        int i = tx * HousePlan.SIZE + ty;
        if (wall != 0 && tx >= 0 && ty >= 0)
        {
            tiles[i] = (byte) (tiles[i] & ~wall | wall * 4);
        }
    }

    static int nexusSlot(int slot)
    {
        for (int[] run : NEXUS_SLOTS)
        {
            if (slot < run[1])
            {
                return run[0] + slot;
            }
            slot -= run[1];
        }
        return -1;
    }

    /** "Portal Nexus: Varrock, Falador": its places from the game's varbits (slot, teleport, its name). */
    private String nexus()
    {
        List<String> places = new ArrayList<>();
        EnumComposition teleports = client.getEnum(NEXUS_TELEPORTS);
        for (int slot = 0; slot < 45 && teleports != null; slot++)
        {
            int id = client.getVarbitValue(nexusSlot(slot));
            int struct = id <= 0 ? -1 : teleports.getIntValue(id);
            StructComposition teleport = struct < 0 ? null : client.getStructComposition(struct);
            String name = teleport == null ? null : teleport.getStringValue(NEXUS_NAME);
            String place = name == null ? null : Text.removeTags(name);
            if (place != null && !place.isEmpty())
            {
                places.add(place);
                house.seen(RouteSource.NEXUS + ": " + place);
            }
        }
        return RouteSource.NEXUS + (places.isEmpty() ? "" : ": " + String.join(", ", places));
    }

    /** Which house a house portal leads to, as the player chose it there. */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        Widget widget = event.getWidget();
        String what = (event.getMenuOption() + " " + Text.removeTags(event.getMenuTarget()) + " " + (widget == null
            || widget.getText() == null ? "" : Text.removeTags(widget.getText()))).toLowerCase(Locale.ROOT);
        // Also a tablet, the spell or a cape into it (not their Outside): at a portal that would look like a friend's.
        if (what.startsWith("home ") || what.startsWith("build mode ") || what.contains("your house")
            || what.startsWith("tele to poh") || what.matches("(break|inside|cast) teleport to house.*"))
        {
            house.chose(true);
        }
        else if (what.contains("friend's house"))
        {
            house.chose(false);
        }
    }

    /**
     * The house per floor as the game's minimap draws it, 4 pixels a tile over the scene's 104, north up; null where
     * not seen. Kept per account as one picture in the plugin's folder, the floors one under another.
     */
    private volatile BufferedImage[] houseLook = new BufferedImage[4];
    private static final int LOOK = HousePlan.SIZE * 4;
    private volatile Filepath houseFolder;
    private volatile Executor houseIo;

    void setHouseFiles(Filepath folder, Executor io)
    {
        houseFolder = folder;
        houseIo = io;
    }

    private Filepath lookFile(long account)
    {
        Filepath folder = houseFolder;
        return folder == null || houseIo == null ? null : folder.joinSegment(Long.toHexString(account) + ".png");
    }

    /**
     * The house in a minimap picture that may cover an expanded scene, moved as the plan is ({@code dx}, {@code dy}
     * tiles from the plan to the scene): of the margins a chunk apart, the one where the plan's walls are the
     * picture's white lines (else its floor is painted); null when none fits. Black (no ground) becomes clear.
     */
    static BufferedImage sceneOf(BufferedImage all, byte[] floor, int dx, int dy)
    {
        BufferedImage best = null;
        int most = 0;
        for (int margin = 0; margin + LOOK <= Math.min(all.getWidth(), all.getHeight()); margin += 32)
        {
            int painted = 0;
            for (int i = 0; i < floor.length; i++)
            {
                int x = (i / HousePlan.SIZE + dx) * 4;
                int y = (HousePlan.SIZE - 1 - i % HousePlan.SIZE - dy) * 4;
                boolean in = x >= 0 && y >= 0 && x + 4 <= LOOK && y + 4 <= LOOK;
                int rgb = in ? all.getRGB(margin + x + 2, margin + y + 2) : 0;
                painted += (floor[i] & 1) != 0 && rgb >>> 24 != 0 && (rgb & 0xffffff) != 0 ? 1 : 0;
                // A wall on the tile's north or east edge: white there.
                painted += in && (floor[i] & 2) != 0 && white(all.getRGB(margin + x + 1, margin + y)) ? 16 : 0;
                painted += in && (floor[i] & 4) != 0 && white(all.getRGB(margin + x + 3, margin + y + 1)) ? 16 : 0;
            }
            if (painted > most)
            {
                // A copy: the rest of the picture (four times its size) is not kept with it.
                most = painted;
                int[] pixels = new int[LOOK * LOOK];
                for (int i = 0; i < pixels.length; i++)
                {
                    int x = i % LOOK + dx * 4;
                    int y = i / LOOK - dy * 4;
                    int rgb = x < 0 || y < 0 || x >= LOOK || y >= LOOK ? 0 : all.getRGB(margin + x, margin + y);
                    pixels[i] = (rgb & 0xffffff) == 0 ? 0 : rgb;
                }
                best = new BufferedImage(LOOK, LOOK, BufferedImage.TYPE_INT_ARGB);
                best.setRGB(0, 0, LOOK, LOOK, pixels, 0, LOOK);
            }
        }
        return best;
    }

    private static boolean white(int rgb)
    {
        return (rgb >> 16 & 255) > 180 && (rgb >> 8 & 255) > 180 && (rgb & 255) > 180;
    }

    /** Shown at once; written on the file thread. */
    private void keepLook(BufferedImage[] look)
    {
        houseLook = look;
        Filepath file = lookFile(houseOf);
        if (file == null)
        {
            return;
        }
        houseIo.execute(() -> {
            BufferedImage all = new BufferedImage(LOOK, LOOK * 4, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = all.createGraphics();
            for (int z = 0; z < 4; z++)
            {
                g.drawImage(look[z], 0, z * LOOK, null);
            }
            g.dispose();
            // In memory: ImageIO would cache through a temporary file of its own.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try
            {
                try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
                {
                    ImageIO.write(all, "png", out);
                }
                TileCache.writeAtomically(file, bytes.toByteArray());
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not keep the house's look", e);
            }
        });
    }

    /** The account's kept look, read on the file thread; our own drawing of the plan until then. */
    private void readLook(long account)
    {
        BufferedImage[] none = new BufferedImage[4];
        houseLook = none;
        Filepath file = lookFile(account);
        if (file == null)
        {
            return;
        }
        houseIo.execute(() -> {
            BufferedImage[] look = new BufferedImage[4];
            try (ImageInputStream in = file.isFile() ? new MemoryCacheImageInputStream(new ByteArrayInputStream(
                TileCache.read(file))) : null)
            {
                BufferedImage all = in == null ? null : ImageIO.read(in);
                for (int z = 0; all != null && all.getWidth() == LOOK && all.getHeight() == LOOK * 4 && z < 4; z++)
                {
                    look[z] = all.getSubimage(0, z * LOOK, LOOK, LOOK);
                }
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not read the house's look", e);
            }
            // Not over a scan made meanwhile, nor for another account.
            if (houseLook == none && houseOf == account)
            {
                houseLook = look;
                SwingUtilities.invokeLater(this::repaint);
            }
        });
    }

    /** The account whose house is loaded (client thread); none after a log out. */
    private volatile long houseOf = Long.MIN_VALUE;
    /** For Swing: the features as last written. */
    private volatile Set<String> houseFeatures = Set.of();
    private volatile HousePlan plan;
    private volatile CollisionMap collision;

    /** The plan and the route data (loaded meanwhile) are joined under this lock. */
    private synchronized void setPlan(HousePlan seen)
    {
        plan = seen;
        if (collision != null)
        {
            collision.setHouse(seen);
        }
        houseChanged();
    }

    /** This account's house; not before RuneLite knows the account's profile: that may come just after the login. */
    private void loadHouse()
    {
        if (configManager.getRSProfileKey() == null)
        {
            return;
        }
        houseOf = client.getAccountHash();
        String saved = configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY);
        Set<String> features = new LinkedHashSet<>(Arrays.asList(saved == null ? new String[0] : saved.split(",")));
        features.remove("");
        house.reset();
        house.restore(features);
        readLook(houseOf);
        writeHouse(house.features());
        String text = configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP, PLAN_KEY);
        setPlan(text == null ? null : HousePlan.decode(text));
    }

    private void writeHouse(Set<String> features)
    {
        houseFeatures = Set.copyOf(features);
        configManager.setRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY, String.join(",", features));
        houseChanged();
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                loadHouse();
            }
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            if (client.getAccountHash() != houseOf)
            {
                loadHouse();
            }
            pandemonium = null;
            warnBoth(false);
        }
        else if (event.getGameState() == GameState.LOADING)
        {
            house.reloaded();
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            house.reset();
            houseOf = Long.MIN_VALUE;
            houseLook = new BufferedImage[4];
            setPlan(null);
            bank = null;
            bankChanged = true;
            start = -2;
            state = PlayerState.UNKNOWN;
        }
    }

    private String get(String key)
    {
        return configManager.getConfiguration(HdMapReforgedConfig.GROUP, key);
    }

    private void set(String key, Object value)
    {
        configManager.setConfiguration(HdMapReforgedConfig.GROUP, key, value);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        String key = event.getKey();
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if (!key.startsWith("route") || HOUSE_KEY.equals(key) || "routeLog".equals(key)
            || "routePlannerChosen".equals(key))
        {
            return;
        }
        if ("routePlanner".equals(key))
        {
            warnBoth(true);
            // The other planner's route would stay with no way to clear it. Our own hand-over (the first route
            // choosing Shortest Path) is kept.
            SwingUtilities.invokeLater(() -> {
                RouteController c = controller;
                stopTour();
                if (c != null && c.target() >= 0)
                {
                    c.clear();
                }
                if (handedOver != null && !shortestPathPlanner())
                {
                    handOverClear();
                }
                if (handedOver != null)
                {
                    showHandedOver(this::handedOverPanel, false);
                }
                else
                {
                    changed();
                }
            });
            return;
        }
        if (LOOK_KEYS.contains(key))
        {
            sentAhead = null;
        }
        SwingUtilities.invokeLater(LOOK_KEYS.contains(key) ? this::changed : this::settingsChanged);
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
        return request(start, target, false);
    }

    /** With {@code anything}, every requirement counts as met. Swing or the extras thread. */
    private RouteRequest request(int begin, int target, boolean anything)
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
            RouteRequest.DEFAULT_NODE_LIMIT);
    }

    /** On the first map right-click: Shortest Path becomes the planner if it is on and nothing was chosen. */
    private void choosePlannerOnce()
    {
        if (get("routePlannerChosen") != null)
        {
            return;
        }
        set("routePlannerChosen", true);
        if (get("routePlanner") == null && shortestPathOn())
        {
            set("routePlanner", HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH);
        }
    }

    /** Swing thread; nothing while the data loads. */
    void routeTo(WorldPoint point)
    {
        stopTour();
        if (toShortestPath(point))
        {
            return;
        }
        RouteController now = controller;
        if (now != null)
        {
            newRoute();
            now.setTarget(pack(point));
        }
    }

    /** The user's own route: shown at once, with nothing avoided yet. */
    private void newRoute()
    {
        bringUp = true;
        fromPlugin = false;
        routeAvoid.clear();
    }

    private void contribute(JPopupMenu popup, WorldPoint point)
    {
        // Before the entries are made, so they name the planner that will be used.
        choosePlannerOnce();
        RouteController c = controller;
        popup.addSeparator();
        item(popup, "Add to custom route", () -> addStop(Tour.Stop.place(placeName(point), point)))
            .setToolTipText("Adds this spot as a stop of \"" + editingName() + "\"");
        item(popup, "Custom routes...", this::editTours)
            .setToolTipText("Your own routes with several stops: order them, let the planner find the fastest order, run");
        item(popup, "Your house...", this::openHouse)
            .setToolTipText("What your house has for routes (this account), and its plan once you have been in it");
        if (shortestPathPlanner())
        {
            item(popup, "Path to here (Shortest Path)", () -> routeTo(point));
        }
        else
        {
            item(popup, c == null ? "Path to here (loading…)" : "Path to here", () -> routeTo(point)).setEnabled(c != null);
        }
        if (handedOver != null || c != null && c.target() >= 0)
        {
            item(popup, tour != null ? "Stop custom route" : "Clear path", this::clearRoute);
        }
    }

    /** Max tiles of a walkable area to count as one dungeon to fit the map to. */
    static final int AREA_LIMIT = 60_000;

    // ---- custom routes ----

    static final String TOURS_KEY = "customRoutes";
    static final String EDITING_KEY = "customRouteEditing";

    @RequiredArgsConstructor
    private static final class Running
    {
        final Tour tour;
        volatile int index;
        volatile int target = -1;
        /** Each stop's tile, fixed at the start (a kind: the one nearest the stop before); -1 none. */
        int[] points = new int[0];
        /** The way to each stop from the one before, planned at the start; null until found. */
        volatile Route[] legs = new Route[0];
        /** Stops skipped one after another, having no place. */
        int skipped;

    }

    /** Set on Swing; read on the client and extras threads too. */
    private volatile Running tour;

    private List<Tour> tours()
    {
        return Tour.decode(get(TOURS_KEY));
    }

    private void saveTours(List<Tour> tours, String editing)
    {
        set(TOURS_KEY, Tour.encode(tours));
        set(EDITING_KEY, editing);
    }

    /** The route stops are added to: the last one edited, else the first, else a new "My route". */
    private String editingName()
    {
        String editing = get(EDITING_KEY);
        List<Tour> tours = tours();
        return tours.stream().anyMatch(t -> t.name.equals(editing)) ? editing
            : tours.isEmpty() ? "My route" : tours.get(0).name;
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
            String place = screens[0].placeName(point);
            if (place != null)
            {
                return "Near " + place;
            }
        }
        return "A spot on the map";
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
            clearRoute();
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
        List<WorldPoint> at = resolve(running.tour.stops, nearFrom(start));
        running.points = at.stream().mapToInt(p -> p == null ? -1 : pack(p)).toArray();
        running.legs = new Route[at.size()];
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
                RouteRequest request = from < 0 || to < 0 ? null : request(from, to, false);
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
                if (leg != null && leg.outcome != Outcome.CANCELLED)
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
                later.add(Map.entry(i, legs[i]));
            }
        }
        return later;
    }

    /** Stops a custom route and clears the route, also Shortest Path's. */
    private void clearRoute()
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
        if (running.index >= running.tour.stops.size() && running.index > 1 && loops(running))
        {
            running.index = 0;
        }
        else if (running.index >= running.tour.stops.size())
        {
            fadeOut(walkedAhead(ahead));
            clearRoute();
            toast("\"" + running.tour.name + "\" done");
            return;
        }
        goToStop(at);
    }

    /** As saved, so ticking Loop counts for the route already running. */
    private boolean loops(Running running)
    {
        return tours().stream().anyMatch(t -> t.loop && t.name.equals(running.tour.name));
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
            : resolve(running.tour.stops.get(running.index), nearFrom(from));
        if (to == null)
        {
            // A kind with no place yet (icons not loaded): skipped. A loop of only such stops ends.
            if (++running.skipped > running.tour.stops.size())
            {
                clearRoute();
                return;
            }
            nextStop(from);
            return;
        }
        running.skipped = 0;
        running.target = pack(to);
        if (toShortestPath(to))
        {
            return;
        }
        newRoute();
        Route[] legs = running.legs;
        Route leg = running.index > 0 && running.index < legs.length ? legs[running.index] : null;
        if (leg != null && leg.outcome == Outcome.FOUND)
        {
            c.show(running.target, leg);
            return;
        }
        c.setTarget(running.target);
    }

    /** Each stop's place; a kind becomes the one nearest the stop before (or {@code near}); null when none. */
    private List<WorldPoint> resolve(List<Tour.Stop> stops, WorldPoint near)
    {
        List<WorldPoint> points = new ArrayList<>();
        for (Tour.Stop stop : stops)
        {
            WorldPoint p = resolve(stop, near);
            points.add(p);
            near = p != null ? p : near;
        }
        return points;
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
        JPanel box = column();
        box.setOpaque(true);
        box.setBackground(ColorScheme.DARK_GRAY_COLOR);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, walk),
            BorderFactory.createEmptyBorder(6, 8, 6, 6)));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        int inner = width - 17;
        box.add(label("Now", inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        Step now = left.steps.get(0);
        JLabel doing = label(RouteText.describe(now), inner, Font.BOLD, color(now, walk, jump));
        doing.setFont(doing.getFont().deriveFont(Font.BOLD, doing.getFont().getSize2D() + 3));
        box.add(doing);
        if (now.detail != null && !RouteText.unsure(now.detail))
        {
            box.add(label(now.detail, inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        }
        for (int i = 1; i < Math.min(3, left.steps.size()); i++)
        {
            Step then = left.steps.get(i);
            box.add(Box.createVerticalStrut(4));
            box.add(label((i == 1 ? "Then: " : "After that: ") + RouteText.describe(then), inner, Font.PLAIN,
                color(then, walk, jump)));
        }
        box.add(Box.createVerticalStrut(4));
        box.add(label(RouteText.duration(left) + " left", inner, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        JPanel spaced = column();
        spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
        spaced.add(box);
        spaced.add(Box.createVerticalStrut(8));
        return spaced;
    }

    /** "Stop 2 of 5: Catherby patch", with Skip and Stop, above a running custom route's steps. */
    private JComponent tourHeader(Running running, int width)
    {
        JPanel header = column();
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        List<Tour.Stop> stops = running.tour.stops;
        header.add(label("\u25B6 " + running.tour.name + (loops(running) ? " (loop)" : "") + ": stop "
            + (running.index + 1) + " of " + stops.size(), width,
            Font.BOLD, legColor(running.index)));
        for (int i = 0; i < stops.size(); i++)
        {
            header.add(label((i + 1) + ". " + stops.get(i).name, width, i == running.index ? Font.BOLD : Font.PLAIN,
                i < running.index ? ColorScheme.MEDIUM_GRAY_COLOR : legColor(i)));
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(button(running.index + 1 < stops.size() || stops.size() > 1 && loops(running) ? "Next stop" : "Finish",
            () -> nextStop(start)));
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(button("Stop route", this::clearRoute));
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
        points.addAll(resolve(stops, me));
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
                            else if (orders.get() != order)
                            {
                                // Another panel's order or a cancel: said, not dropped without a word.
                                toast("Stopped working out the fastest order of \"" + name + "\"");
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
            // The route edited now stays so, also when another was chosen meanwhile.
            saveTours(all, editingName());
            return;
        }
        toast("\"" + name + "\" was renamed or removed meanwhile: its order was not saved");
    }

    private static boolean sameStops(List<Tour.Stop> a, List<Tour.Stop> b)
    {
        return a.size() == b.size()
            && IntStream.range(0, a.size()).allMatch(i -> a.get(i).same(b.get(i)) && a.get(i).name.equals(b.get(i).name));
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
        RouteRequest request = request(pack(a), pack(b), false);
        if (request == null)
        {
            return -1;
        }
        Route route = pf.find(request, cancelled);
        return route.outcome == Outcome.FOUND ? route.cost : -1;
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
        if (route == null || route.outcome != Outcome.NEAREST || pool == null
            || pathfinder == null || config.routeIgnoreLevels() && config.routeIgnoreItems())
        {
            why = null;
            return;
        }
        RouteRequest request = request(start, c.target(), true);
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
                    RouteLog log = routeLog;
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
        if (why == null || why.outcome == Outcome.CANCELLED)
        {
            return null;
        }
        if (why.outcome != Outcome.FOUND)
        {
            // Only a search that tried everything may say there is no way.
            return why.exhausted ? Collections.singletonList(NO_WAY) : null;
        }
        List<String> lines = new ArrayList<>();
        for (Step step : why.steps)
        {
            if (RouteText.lacks(step))
            {
                lines.add(RouteText.describe(step) + ": needs " + RouteText.lack(step));
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
        return route != null && route.outcome == Outcome.NEAREST && explaining ? null : route;
    }

    private boolean unreachable(Route route)
    {
        Route way = why;
        return route != null && route.outcome == Outcome.NEAREST && route == whyFor && way != null
            && (way.outcome == Outcome.FOUND || way.outcome == Outcome.NEAREST && closer(way, route));
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
            RouteLog log = routeLog;
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
        // Cut again only for another route: the same one follows the player each tick (and stays while they are off it).
        if (route != aheadOf)
        {
            ahead = route == null ? null : route.ahead(start);
            aheadOf = route;
            if (before != null && before != ahead)
            {
                // The old way fades out as the new one fades in.
                Set<Integer> was = walkedAhead(before);
                Set<Integer> now = walkedAhead(ahead);
                fadeOut(minus(was, now));
                fadeInTiles(minus(now, was));
            }
        }
        shown = config.routeInGame() ? ahead : null;
        boolean up = bringUp;
        bringUp = false;
        showSteps(c, up);
        repaint();
    }

    private void showSteps(RouteController c, boolean up)
    {
        boolean none = c == null || c.target() < 0;
        for (MapScreen screen : screens)
        {
            screen.showRoute(none ? null : width -> steps(c, width, screen.view()::focus, false), !none && up);
        }
        sidebar.accept(none ? null : width -> steps(c, width, sidebarFocus, true));
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
        neverUse().forEach(name -> all.add(name.toLowerCase(Locale.ROOT)));
        return all;
    }

    interface StepActions
    {
        void avoid(String name);

        void never(String name);

        void allow(String name);

        List<String> neverUsed();

        List<String> blockers();

        boolean unreachable();
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
                set("routeNeverUse", String.join("\n", names));
            }
        }

        @Override
        public void allow(String name)
        {
            List<String> names = neverUse();
            names.removeIf(n -> n.equalsIgnoreCase(name));
            set("routeNeverUse", String.join("\n", names));
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

    /** With {@code roomy} (the sidebar): first a box with what to do now and next. */
    private JComponent steps(RouteController c, int width, Consumer<WorldPoint> focus, boolean roomy)
    {
        Route route = effective(c);
        String status = route == null && explaining ? "Cannot get there with what you have; looking for the way there…"
            : c.status();
        Route left = ahead;
        int done = route != null && route == aheadOf && left != null ? route.steps.size() - left.steps.size() : 0;
        JComponent panel = panel(route, c.target(), status, width, focus, this::clearRoute, stepActions, current(),
            jump(), done);
        if (roomy && route != null && left != null && !left.steps.isEmpty())
        {
            sidebarNow = nowBox(left, width, current(), jump());
            sidebarWidth = width;
            panel.add(sidebarNow, 1);
        }
        Running running = tour;
        if (running != null)
        {
            panel.add(tourHeader(running, width), 0);
        }
        if (handedOver != null)
        {
            panel.add(label("Dashed white on the map: Shortest Path's route", width, Font.PLAIN,
                ColorScheme.MEDIUM_GRAY_COLOR), 1);
        }
        PlayerState planned = state;
        if (route != null && route.has(Kind.SAIL) && planned != null)
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
        return panel(route, target, status, width, focus, clear, null, WALK, JUMP, 0);
    }

    static JComponent panel(Route route, int target, String status, int width, Consumer<WorldPoint> focus,
        Runnable clear, StepActions actions, Color walk, Color jump, int done)
    {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.add(label("Route" + (Tiles.z(target) > 0 ? " (floor " + Tiles.z(target) + ")" : ""), width,
            Font.BOLD, Color.WHITE));
        if (route == null)
        {
            panel.add(label(status != null ? status : "Searching…", width, Font.PLAIN, ColorScheme.LIGHT_GRAY_COLOR));
        }
        else
        {
            for (String note : RouteText.notes(route))
            {
                panel.add(label(note, width, Font.PLAIN, NOTE));
            }
            boolean unreachable = actions != null && actions.unreachable();
            int blocked = unreachable ? RouteText.blockedFrom(route) : -1;
            if (unreachable)
            {
                panel.add(label(RouteText.cannotYet(route), width, Font.BOLD, CANNOT));
            }
            List<String> blockers = actions == null || route.outcome != Outcome.NEAREST ? null
                : actions.blockers();
            if (blockers != null)
            {
                panel.add(label(blockers.isEmpty() ? "Needs something the map can't name (a quest step, a key)."
                    : blockers.contains(NO_WAY) ? NO_WAY : "Needs: " + String.join(", ", blockers), width, Font.PLAIN, NOTE));
            }
            if (!route.steps.isEmpty())
            {
                panel.add(label(RouteText.duration(route) + (route.outcome == Outcome.NEAREST
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
            for (Step step : route.steps)
            {
                boolean lacking = unreachable && RouteText.lacks(step);
                boolean cannot = blocked >= 0 && number - 1 >= blocked;
                String detail = lacking ? "Needs " + RouteText.lack(step) : step.detail;
                if (!lacking && RouteText.unsure(detail))
                {
                    unsure = true; // said once under the steps
                    detail = null;
                }
                // A passage, stairs or transport shows where it leads (the other map).
                boolean leads = step.isJump() && !Tiles.isSea(step.last()) && step.first() != step.last();
                int point = leads ? step.last() : step.first();
                JButton row = flat("<html><body style='width:" + Math.max(80, width - 40) + "px'>" + number++ + ". "
                    + RouteText.escape(RouteText.describe(step))
                    + (detail != null ? "<br><span style='color:" + (cannot ? "#ff7070" : "#a0a0a0") + "'>"
                        + RouteText.escape(detail) + "</span>" : "")
                    + "</body></html>", leads ? "Show where this leads on the map" : "Show on the map",
                    () -> focus.accept(worldPoint(point)));
                row.setForeground(number - 2 < done ? ColorScheme.MEDIUM_GRAY_COLOR : cannot ? CANNOT
                    : color(step, walk, jump));
                row.setMargin(new Insets(1, 0, 1, 0));
                boolean choice = step.name != null && (step.kind == Kind.TELEPORT
                    || step.kind == Kind.TRANSPORT || step.kind == Kind.SHIP
                    || step.kind == Kind.ENTRANCE && step.category != null);
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
                JButton allow = flat("✕  " + shown, "Use it again", () -> actions.allow(never));
                allow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                panel.add(allow);
            }
        }
        panel.add(Box.createVerticalStrut(4));
        JButton clearButton = button("Clear path", clear);
        clearButton.setAlignmentX(0);
        clearButton.setToolTipText("Remove the route from the map");
        panel.add(clearButton);
        return panel;
    }

    private static JMenuItem item(JPopupMenu menu, String text, Runnable action)
    {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return menu.add(item);
    }

    private static JPanel column()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JButton button(String text, Runnable action)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** A left-aligned button that looks like text. */
    private static JButton flat(String text, String tip, Runnable action)
    {
        JButton button = button(text, action);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setToolTipText(tip);
        button.setAlignmentX(0);
        return button;
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

    private static Color color(Step step, Color walk, Color jump)
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

    /** One's house at the surface map's edge: its picture per floor, and what stands where. */
    private void paintHouse(Graphics2D g, MapView.Projection p)
    {
        HousePlan seen = plan;
        g.setFont(FontManager.getRunescapeSmallFont());
        if (seen == null)
        {
            return;
        }
        // One picture a floor, the game's minimap of it: not thousands of shapes a frame.
        BufferedImage look = houseLook[Math.max(0, Math.min(3, p.plane()))];
        // At the surface map's edge, where the map draws the house.
        int left = (int) Math.round(p.screenX(HousePlan.DRAWN_X));
        int top = (int) Math.round(p.screenY(HousePlan.DRAWN_Y + HousePlan.SIZE));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(look, left, top, (int) Math.round(p.screenX(HousePlan.DRAWN_X
            + HousePlan.SIZE)) - left, (int) Math.round(p.screenY(HousePlan.DRAWN_Y)) - top, null);
        seen.things.forEach((node, name) -> {
            String label = name.split(":")[0];
            float x = (float) RouteText.x(p, node) - g.getFontMetrics().stringWidth(label) / 2f;
            float y = (float) RouteText.y(p, node);
            if (Tiles.z(node) == p.plane())
            {
                g.setColor(Color.BLACK);
                g.drawString(label, x + 1, y + 1);
                g.setColor(Color.WHITE);
                g.drawString(label, x, y);
            }
        });
    }

    /** "Your house" for this account: what it has for routes, set here, and its plan. Swing thread. */
    private JComponent housePanel(int width)
    {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.add(label("Your house", width, Font.BOLD, Color.WHITE));
        HousePlan seen = plan;
        Set<String> now = houseFeatures;
        if (collision == null || houseOf == Long.MIN_VALUE)
        {
            panel.add(label("Log in to see and set this account's house.", width, Font.PLAIN, NOTE));
            return panel;
        }
        Color gray = ColorScheme.LIGHT_GRAY_COLOR;
        panel.add(label(seen == null ? "Teleport into it once: its rooms, portals, nexus and jewellery box are read "
            + "then. Or set them here." : "As seen last time you were in it, for this account. Routes walk through it; "
            + "right-click in it for a route there.", width, Font.PLAIN, gray));
        JComboBox<String> box = new JComboBox<>(new String[]{"No jewellery box", "Basic jewellery box",
            "Fancy jewellery box", "Ornate jewellery box"});
        box.setSelectedIndex(Arrays.asList(HouseSettings.BOXES).indexOf(HouseSettings.box(now)));
        JCheckBox glory = new JCheckBox("Mounted amulet of glory", now.contains("glory"));
        // A superior garden has one of these.
        JComboBox<String> garden = new JComboBox<>(new String[]{"No fairy ring or spirit tree", "Fairy ring",
            "Spirit tree", "Spiritual fairy tree (both)"});
        garden.setSelectedIndex(HouseSettings.garden(now));
        JTextField portals = new JTextField(HouseSettings.places(now, HouseSettings.PORTAL));
        JTextField nexus = new JTextField(HouseSettings.places(now, HouseSettings.NEXUS));
        long account = houseOf;
        Runnable save = () -> editHouse(account, HouseSettings.features(HouseSettings.BOXES[box.getSelectedIndex()],
            glory.isSelected(), garden.getSelectedIndex(), portals.getText(), nexus.getText()));
        box.addActionListener(e -> save.run());
        glory.addActionListener(e -> save.run());
        garden.addActionListener(e -> save.run());
        for (JTextField places : new JTextField[]{portals, nexus})
        {
            places.setToolTipText("Where they lead, as the spellbooks name them, with commas; Enter saves");
            FullMapWindow.typable(places);
            places.addActionListener(e -> save.run());
        }
        for (JComponent part : new JComponent[]{box, glory, garden, label("Portals:", width, Font.PLAIN, gray), portals,
            label("Portal nexus:", width, Font.PLAIN, gray), nexus})
        {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            part.setOpaque(part instanceof JTextField);
            part.setMaximumSize(new Dimension(width, part.getPreferredSize().height));
            panel.add(part);
        }
        if (seen != null)
        {
            panel.add(Box.createVerticalStrut(6));
            panel.add(button("Show its plan", () -> {
                for (MapScreen screen : screens)
                {
                    screen.view().focus(worldPoint(seen.arrival));
                }
            }));
        }
        return panel;
    }

    /** Dropped when another account (or none) is logged in than the panel was made for. */
    private void editHouse(long account, Set<String> features)
    {
        clientThread.invokeLater(() -> {
            if (houseOf != account)
            {
                return;
            }
            house.replaceFeatures(features);
            writeHouse(features);
            state = capture();
            SwingUtilities.invokeLater(this::settingsChanged);
        });
    }

    private final IntFunction<JComponent> houseSection = this::housePanel;

    private void openHouse()
    {
        for (MapScreen screen : screens)
        {
            screen.showHouse(houseSection);
        }
    }

    /** The maps painted again, and an open house panel built again with what is known now (a scan, the other map). */
    private void houseChanged()
    {
        SwingUtilities.invokeLater(() -> {
            repaint();
            for (MapScreen screen : screens)
            {
                screen.updateHouse(houseSection);
            }
        });
    }

    private void paintMap(Graphics2D g, MapView.Projection p)
    {
        if (p.map() != null && (p.map().id == BaseMap.SURFACE || p.map().id == BaseMap.FULL))
        {
            paintHouse(g, p);
        }
        paintHandedOver(g, p);
        if (shortestPathPlanner())
        {
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
            g.fill(circle(p.screenX(at.getX() + 0.5), p.screenY(at.getY() + 0.5), r));
        }
    }

    /** The step to take now: a teleport, or a transport or passage the player stands at; or null. */
    static Step nextAction(Route route, int player)
    {
        for (Step step : route.steps)
        {
            if (step.kind == Kind.WALK)
            {
                if (step.points.length > 3)
                {
                    return null;
                }
                continue;
            }
            if (step.kind == Kind.TELEPORT || step.kind == Kind.HOUSE)
            {
                return step;
            }
            if (step.kind == Kind.SAIL || step.kind == Kind.DISEMBARK)
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
        for (Step step : route.steps)
        {
            if (step.kind != Kind.WALK)
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
        Step step = nextAction(route, player);
        return step == null ? null : RouteText.describe(step);
    }

    private void paintNextAction(Graphics2D g, Route route, Player player)
    {
        int at = start;
        Step now = nextAction(route, at);
        // A teleport one walks to (a portal, the box, a fairy ring) is named on it, as HD Tile Markers names it; over
        // the head only what is cast anywhere, and obstacles on the way.
        Step jump = route.steps.stream().filter(Step::isJump).findFirst().orElse(null);
        boolean onIt = jump != null && jump.kind != Kind.TELEPORT && jump.first() >= 0
            && (jump.kind != Kind.HOUSE || HousePlan.contains(Tiles.x(jump.first()), Tiles.y(jump.first())));
        WorldView view = client.getTopLevelWorldView();
        if (onIt && !hdTiles && view != null)
        {
            label(g, local(view, jump.first()), RouteText.describe(jump), RouteText.lacks(jump), 0);
        }
        String text = nextText(route, at);
        if (text == null || now != null && now == jump && onIt)
        {
            return;
        }
        // "Teleport to House → Jewellery box: …": the part to do now.
        int arrow = now != null && now.kind == Kind.HOUSE ? text.indexOf(" → ") : -1;
        text = arrow < 0 ? text : house.inside() ? text.substring(arrow + 3) : text.substring(0, arrow);
        label(g, player.getLocalLocation(), text, now != null && RouteText.lacks(now), player.getLogicalHeight() + 60);
    }

    private void label(Graphics2D g, LocalPoint at, String text, boolean lacks, int height)
    {
        // The font first: the text is centred with it.
        g.setFont(FontManager.getRunescapeSmallFont());
        Point spot = at == null ? null : Perspective.getCanvasTextLocation(client, g, at, text, height);
        if (spot != null)
        {
            g.setColor(Color.BLACK);
            g.drawString(text, spot.getX() + 1, spot.getY() + 1);
            g.setColor(lacks ? CANNOT : jump());
            g.drawString(text, spot.getX(), spot.getY());
        }
    }

    private static final int MINIMAP_ALPHA = 150;

    /** Where the player is for the overlays: in one's house its plan's tile. Client thread. */
    private int near()
    {
        int at = start;
        return house.own() && at >= 0 ? at : pack(client.getLocalPlayer().getWorldLocation());
    }

    /** The colour last made: tiles side by side share it, so a frame does not make a Color for every tile. */
    private static final class Tint
    {
        private Color base;
        private int alpha = -1;
        private Color made;

        Color of(Color c, float a)
        {
            int value = Math.max(0, Math.min(255, Math.round(a)));
            if (c != base || value != alpha)
            {
                base = c;
                alpha = value;
                made = new Color(c.getRed(), c.getGreen(), c.getBlue(), value);
            }
            return made;
        }
    }

    private interface TileSink
    {
        void tile(int point, Color color, float strength);
    }

    /**
     * The ground tiles of the overlays within {@code reach} of {@code me}: later parts of a custom route, the route
     * (fading in), then passed tiles. Asked every frame: far tiles are left out before anything is looked up.
     */
    private void eachTile(Route route, int me, int reach, TileSink sink)
    {
        for (Map.Entry<Integer, Route> leg : laterLegs())
        {
            Color color = legColor(leg.getKey());
            eachWalked(leg.getValue(), point -> {
                if (Tiles.distance(point, me) <= reach)
                {
                    sink.tile(point, color, 1);
                }
            });
        }
        Color normal = current();
        long now = System.currentTimeMillis();
        int blocked = RouteText.blockedFrom(route);
        List<Step> steps = route == null ? List.of() : route.steps;
        for (int index = 0; index < steps.size(); index++)
        {
            Step step = steps.get(index);
            Color walk = blocked >= 0 && index >= blocked ? CANNOT : normal;
            for (int point : step.kind == Kind.WALK ? step.points : new int[0])
            {
                if (Tiles.distance(point, me) <= reach && !fading.containsKey(point))
                {
                    sink.tile(point, walk, fadeIn(point, now));
                }
            }
        }
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            float alpha = fadeAlpha(entry.getValue(), now);
            if (alpha > 0 && Tiles.distance(entry.getKey(), me) <= reach)
            {
                sink.tile(entry.getKey(), normal, alpha);
            }
        }
    }

    /** Dots on the minimap: above the widgets, or the minimap would cover them. */
    private final class MinimapOverlay extends Overlay
    {
        private final Tint tint = new Tint();

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
            if (view == null || view.isInstance() && !house.own() || client.getLocalPlayer() == null || !config.routeInGame()
                || !config.routeMinimap())
            {
                return null;
            }
            int plane = view.getPlane();
            // The minimap shows 20 tiles around the player (Perspective.localToMinimap).
            eachTile(route, near(), 20, (point, color, strength) -> {
                LocalPoint local = Tiles.isSea(point) || Tiles.z(point) != plane ? null : local(view, point);
                Point mini = local == null ? null : Perspective.localToMinimap(client, local);
                if (mini != null)
                {
                    g.setColor(tint.of(color, MINIMAP_ALPHA * strength));
                    g.fill(circle(mini.getX(), mini.getY(), 1.25));
                }
            });
            return null;
        }
    }

    private final class GameOverlay extends Overlay
    {
        private final Tint fillTint = new Tint();
        private final Tint borderTint = new Tint();

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
            if (route == null && fading.isEmpty() || instance && !house.own() || hdTiles)
            {
                return null;
            }
            int plane = view.getPlane();
            Stroke old = g.getStroke();
            int fill = config.routeTileFill();
            int borderAlpha = config.routeTileBorder();
            double tileWidth = config.routeTileWidth();
            boolean border = borderAlpha > 0 && tileWidth > 0;
            g.setStroke(new BasicStroke((float) Math.max(0.5, Math.min(8, tileWidth))));
            // No scene, also an expanded one, reaches further.
            eachTile(route, near(), Constants.EXTENDED_SCENE_SIZE, (point, color, strength) -> {
                LocalPoint local = Tiles.z(point) != plane ? null : local(view, point);
                Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
                if (tile != null)
                {
                    g.setColor(fillTint.of(color, fill * strength));
                    g.fill(tile);
                    if (border)
                    {
                        g.setColor(borderTint.of(color, borderAlpha * strength));
                        g.draw(tile);
                    }
                }
            });
            g.setStroke(old);
            return null;
        }
    }

    static final class RouteText
    {
        /** "Can't yet: 45 Sailing, Lunar Diplomacy". */
        static String cannotYet(Route route)
        {
            Set<String> missing = new LinkedHashSet<>();
            for (Step step : route.steps)
            {
                if (lacks(step))
                {
                    for (String part : lack(step).split(",\\s*"))
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

        static boolean lacks(Step step)
        {
            return step.detail != null && step.detail.startsWith("You lack");
        }

        /** What a step {@link #lacks}, as "45 Sailing, Lunar Diplomacy". */
        static String lack(Step step)
        {
            return step.detail.replaceFirst("^You lack:?\\s*", "");
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
            if (route.outcome == Outcome.NONE)
            {
                notes.add("No route from here.");
            }
            else if (route.outcome == Outcome.NEAREST)
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
            for (Step step : route.steps)
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

        static String describe(Step step)
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
                Step step = route.steps.get(index);
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
                        g.setColor(color(step, walk, jump));
                        g.draw(new Line2D.Double(x(p, a), y(p, a), x(p, b), y(p, b)));
                    }
                    g.setColor(jump);
                    if (showA)
                    {
                        g.fill(circle(x(p, a), y(p, a), 4));
                    }
                    if (showB)
                    {
                        g.fill(circle(x(p, b), y(p, b), 4));
                    }
                    continue;
                }
                Path2D line = path(p, step.points, false);
                g.setStroke(outline);
                g.setColor(OUTLINE);
                g.draw(line);
                g.setStroke(solid);
                g.setColor(color(step, walk, jump));
                g.draw(line);
            }
            if (route.end >= 0 && visible(p, route.end))
            {
                Ellipse2D end = circle(x(p, route.end), y(p, route.end), 6);
                g.setStroke(new BasicStroke(2f));
                g.setColor(END);
                g.fill(end);
                g.setColor(Color.WHITE);
                g.draw(end);
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

        /** The points' line where shown, broken where not; {@code anyFloor}: also on other floors than the one drawn. */
        static Path2D path(MapView.Projection p, int[] points, boolean anyFloor)
        {
            Path2D line = new Path2D.Double();
            boolean drawing = false;
            for (int point : points)
            {
                if (!(anyFloor ? p.shows(worldPoint(point)) : visible(p, point)))
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
            return line;
        }

        private static boolean visible(MapView.Projection p, int node)
        {
            WorldPoint game = worldPoint(node);
            if (Tiles.isSea(node))
            {
                return p.shows(game);
            }
            // Where the map draws it: a part drawn elsewhere (the Kalphite Lair) there, on the floor drawn.
            return p.shown(game).getPlane() == p.plane() && p.shows(game);
        }

        private static double x(MapView.Projection p, int node)
        {
            return p.screenX(Tiles.isSea(node) ? Tiles.x(node) : p.shown(worldPoint(node)).getX() + 0.5);
        }

        private static double y(MapView.Projection p, int node)
        {
            return p.screenY(Tiles.isSea(node) ? Tiles.y(node) : p.shown(worldPoint(node)).getY() + 0.5);
        }
    }
}
