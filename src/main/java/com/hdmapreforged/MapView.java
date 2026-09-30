package com.hdmapreforged;

import com.hdmapreforged.route.HousePlan;
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
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;

/** The map: wiki tiles, icons, transport lines and the player. x grows east, y north, one unit per tile; zoom is log2 pixels per tile. */
final class MapView extends JComponent
{
    interface Listener
    {
        void selectionChanged(Poi poi);

        void viewChanged();

        void nearestRequested(WorldPoint point);
    }

    interface Chrome
    {
        void pickMap(Point at);
    }

    interface WindowControls
    {
        void close();

        void toggleMaximised();

        boolean isMaximised();
    }

    /** Where world points appear in the frame being painted; add 0.5 to a tile for its middle. */
    interface Projection
    {
        double screenX(double worldX);

        double screenY(double worldY);

        /** Log2 of screen pixels per game tile. */
        double zoom();

        BaseMap map();

        int plane();

        int width();

        int height();

        boolean shows(WorldPoint point);

        /** Where a game point is drawn on the map in view (the Kalphite Lair is drawn beside the desert caves). */
        default WorldPoint shown(WorldPoint point)
        {
            return point;
        }
    }

    interface Overlay
    {
        void paint(Graphics2D g, Projection projection);
    }

    interface Widget
    {
        void paint(Graphics2D g, int width, int height, int bottom, Clicks clicks);
    }

    /** Takes a left click before the icons do; true when used. */
    interface ClickCatcher
    {
        boolean clicked(Point at, Projection projection);
    }

    interface Clicks
    {
        void add(RoundRectangle2D shape, Runnable action, String tooltip);

        boolean hovered(RoundRectangle2D shape);
    }

    /** Which of our icons the tiles already show (baked in), so no badge is drawn. */
    interface BadgeCover
    {
        boolean covers(Poi poi, Projection projection);
    }

    interface MenuContributor
    {
        void contribute(JPopupMenu menu, WorldPoint point);
    }

    /** Clickable icons drawn by others (baked into tiles); asked only when none of ours is under the mouse. */
    interface HitLayer
    {
        Poi hit(Point point, Projection projection);

        Point2D labelAnchor(Poi poi, Projection projection);

        default Point2D iconCenter(Poi poi, Projection projection)
        {
            return null;
        }
    }

    @RequiredArgsConstructor
    private static final class Control
    {
        final RoundRectangle2D shape;
        final Runnable action;
        final String tooltip;
    }

    static final Color CONTROL_FILL = new Color(22, 24, 28, 225);
    static final Color CONTROL_HOVER = new Color(52, 56, 64, 235);
    static final Color CONTROL_EDGE = new Color(255, 255, 255, 45);
    static final Font CONTROL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    static final int CONTROL = 34;

    static int floorGroupWidth(FontMetrics metrics)
    {
        return CONTROL * 2 + 4 + metrics.stringWidth("Floor 0") + 24;
    }

    static int widgetBottom(int height)
    {
        return height - MARGIN - 18 - CONTROL - 8;
    }

    private static final int MARGIN = 12;

    static final double MIN_ZOOM = -5;
    static final double MAX_ZOOM = 6;
    private static final Color BACKGROUND = new Color(10, 12, 16);
    private static final Color LABEL_BACKGROUND = new Color(16, 16, 20, 225);
    private static final Color PLAYER = new Color(255, 214, 64);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final BasicStroke THIN = new BasicStroke(1f);
    private static final BasicStroke ICON_LINE = new BasicStroke(1.8f);
    private static final BasicStroke ROUND_LINE = new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Font REGION_FONT = new Font(Font.SERIF, Font.BOLD, 17);
    private static final Font PLACE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Color WILDERNESS_TEXT = new Color(255, 190, 180);
    private static final Color WILDERNESS_STRONG = new Color(255, 70, 50, 210);
    private static final Color WILDERNESS_FAINT = new Color(255, 70, 50, 130);
    private static final BasicStroke WILDERNESS_LINE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
        null, 0f);
    private static final BasicStroke WILDERNESS_DASHED = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
        10f, new float[]{5f, 4f}, 0f);
    private static final Color REGION_COLOR = new Color(255, 236, 190);
    private static final int[] WILDERNESS_STARTS = {3520, 9920};
    private static final double EASE = 0.28;

    @RequiredArgsConstructor
    private static final class Drawn
    {
        final Poi poi;
        final double x;
        final double y;
        final double radius;
    }

    private final TileCache tiles;
    private final HdMapReforgedConfig config;
    private Listener listener;

    private BaseMaps maps;
    private BaseMap map;
    private int plane;
    private List<Poi> pois = Collections.emptyList();
    private Set<Poi> hidden = Collections.emptySet();
    private List<Poi> searchExtras = Collections.emptyList();
    private List<PoiLoader.Place> labels = Collections.emptyList();
    private Unlocks unlocks;
    private Poi selected;
    private Poi hovered;
    private WorldPoint player;
    private WorldPoint outside;
    /** The player is in a house, shown where it stands. */
    private boolean home;
    private boolean following;

    private double centerX = 3222;
    private double centerY = 3218;
    private double zoom = 1;
    private double targetX = centerX;
    private double targetY = centerY;
    private double targetZoom = zoom;
    private Point anchorScreen;
    private Point2D anchorWorld;
    private final Timer animator = new Timer(15, e -> step());

    private List<Drawn> drawn = new ArrayList<>();
    private Chrome chrome;
    private List<Control> controls = new ArrayList<>();
    private final List<Widget> widgets = new CopyOnWriteArrayList<>();
    /** Kinds whose destination lines are hidden. */
    private Set<PoiType> linesOff = EnumSet.noneOf(PoiType.class);
    private Consumer<String> linesChoice = value -> { };
    private Control pressedControl;
    private Control hoveredControl;
    private Point pressed;
    private Point lastDrag;
    private boolean dragging;
    /** Animating or dragged: tiles scaled the fast way. */
    private boolean moving;
    private final List<Overlay> overlays = new ArrayList<>();
    private final ScaledTiles scaled = new ScaledTiles();
    private BadgeCover badgeCover;
    private WindowControls windowControls;
    private final TextSprites text = new TextSprites();
    private final List<MenuContributor> menuContributors = new ArrayList<>();
    private final List<HitLayer> hitLayers = new ArrayList<>();
    private final Projection projection = new Projection()
    {
        @Override
        public double screenX(double worldX)
        {
            return MapView.this.screenX(worldX);
        }

        @Override
        public double screenY(double worldY)
        {
            return MapView.this.screenY(worldY);
        }

        @Override
        public double zoom()
        {
            return zoom;
        }

        @Override
        public BaseMap map()
        {
            return map;
        }

        @Override
        public int plane()
        {
            return plane;
        }

        @Override
        public int width()
        {
            return getWidth();
        }

        @Override
        public int height()
        {
            return getHeight();
        }

        @Override
        public boolean shows(WorldPoint point)
        {
            // One's house: on the surface map, at its edge.
            return point != null && maps != null && map != null && (HousePlan.contains(point.getX(), point.getY())
                ? map.id == BaseMap.SURFACE || map.id == BaseMap.FULL : map.id == BaseMap.FULL
                ? maps.find(point.getX(), point.getY()) != null || WorldMapMoves.drawn(point) != null
                : WorldMapMoves.toDrawn(map.id, point) != null || maps.drawsGame(map, point));
        }

        @Override
        public WorldPoint shown(WorldPoint point)
        {
            return MapView.this.shown(point);
        }
    };

    WorldPoint gamePoint(WorldPoint drawn)
    {
        if (map == null)
        {
            return drawn;
        }
        if (HousePlan.contains(drawn.getX() - HousePlan.DRAWN_X + HousePlan.X, drawn.getY() - HousePlan.DRAWN_Y + HousePlan.Y))
        {
            return drawn.dx(HousePlan.X - HousePlan.DRAWN_X).dy(HousePlan.Y - HousePlan.DRAWN_Y);
        }
        return map.id == BaseMap.FULL ? WorldMapMoves.fromDrawnAnywhere(drawn) : WorldMapMoves.toWorld(map.id, drawn);
    }

    WorldPoint shown(WorldPoint game)
    {
        return shownOn(map, game);
    }

    static WorldPoint shownOn(BaseMap on, WorldPoint game)
    {
        if (game == null || on == null)
        {
            return game;
        }
        if (HousePlan.contains(game.getX(), game.getY()))
        {
            return game.dx(HousePlan.DRAWN_X - HousePlan.X).dy(HousePlan.DRAWN_Y - HousePlan.Y);
        }
        if (on.id == BaseMap.FULL)
        {
            WorldMapMoves.Drawn drawn = WorldMapMoves.drawn(game);
            return drawn != null ? drawn.point : game;
        }
        WorldPoint drawn = WorldMapMoves.toDrawn(on.id, game);
        return drawn != null ? drawn : game;
    }

    /** The map showing a game point (one drawing it elsewhere first), or null. */
    BaseMap mapOf(WorldPoint game)
    {
        return maps == null || game == null ? null : maps.find(game);
    }

    MapView(TileCache tiles, HdMapReforgedConfig config)
    {
        this.tiles = tiles;
        this.config = config;
        setLinesOff(config.linesOff());
        following = config.followPlayer();
        setOpaque(true);
        // Never takes focus, so typing goes to the game.
        setFocusable(false);
        setPreferredSize(new Dimension(225, 300));
        setMinimumSize(new Dimension(120, 120));
        MouseAdapter mouse = new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                pressedControl = control(e.getPoint());
                if (pressedControl != null)
                {
                    pressed = null;
                    return;
                }
                pressed = e.getPoint();
                lastDrag = e.getPoint();
                dragging = false;
                if (e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e)
            {
                if (pressed == null)
                {
                    return;
                }
                if (!dragging && pressed.distance(e.getPoint()) > 3)
                {
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                if (dragging)
                {
                    double scale = scale();
                    centerX -= (e.getX() - lastDrag.x) / scale;
                    centerY += (e.getY() - lastDrag.y) / scale;
                    clampCenter();
                    targetX = centerX;
                    targetY = centerY;
                    targetZoom = zoom;
                    anchorScreen = null;
                    lastDrag = e.getPoint();
                    setFollowing(false);
                    moving = true;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (pressedControl != null)
                {
                    Control target = pressedControl;
                    pressedControl = null;
                    // Checked against the pressed shape: repaints while the mouse is down make new controls.
                    if (target.shape.contains(e.getPoint()) && e.getButton() == MouseEvent.BUTTON1)
                    {
                        target.action.run();
                    }
                    return;
                }
                if (e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
                else if (e.getButton() == 4)
                {
                    // The mouse's own back button.
                    goBack();
                }
                else if (!dragging && pressed != null && e.getButton() == MouseEvent.BUTTON1)
                {
                    if (caught(e.getPoint()))
                    {
                        repaint();
                    }
                    else if (iconAt(e.getPoint()) != null || e.getClickCount() < 2)
                    {
                        Poi hit = iconAt(e.getPoint());
                        select(hit);
                        Poi.Link inside = mapBelow(hit);
                        if (inside != null)
                        {
                            // A map link: go in; Back returns.
                            goIn(inside);
                        }
                    }
                    else
                    {
                        zoomAt(1, e.getPoint());
                    }
                }
                pressed = null;
                if (dragging)
                {
                    moving = animator.isRunning();
                    repaint();
                }
                dragging = false;
                updateHover(e.getPoint());
            }

            @Override
            public void mouseMoved(MouseEvent e)
            {
                updateHover(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                if (hovered != null || hoveredControl != null)
                {
                    hovered = null;
                    hoveredControl = null;
                    // Else a control's tooltip would show anywhere over the map afterwards.
                    setToolTipText(null);
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e)
            {
                zoomAt(-e.getPreciseWheelRotation() * 0.5, e.getPoint());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                double z = clampZoom(targetZoom);
                if (z != targetZoom)
                {
                    zoom = targetZoom = z;
                }
                clampCenter();
                if (!animator.isRunning())
                {
                    targetX = centerX;
                    targetY = centerY;
                }
                repaint();
            }
        });
    }

    void addOverlay(Overlay overlay)
    {
        overlays.add(overlay);
        repaint();
    }

    void removeOverlay(Overlay overlay)
    {
        overlays.remove(overlay);
        repaint();
    }

    boolean linesShown(PoiType type)
    {
        return !linesOff.contains(type);
    }

    void setLinesShown(PoiType type, boolean shown)
    {
        Set<PoiType> now = EnumSet.copyOf(linesOff);
        if (shown)
        {
            now.remove(type);
        }
        else
        {
            now.add(type);
        }
        linesOff = now;
        repaint();
        linesChoice.accept(now.stream().map(Enum::name).collect(Collectors.joining(",")));
    }

    /** Setting format: "FAIRY_RING,SPIRIT_TREE". */
    void setLinesOff(String value)
    {
        Set<PoiType> off = EnumSet.noneOf(PoiType.class);
        for (String name : value == null ? new String[0] : value.split(","))
        {
            for (PoiType t : PoiType.values())
            {
                if (t.name().equals(name.trim()))
                {
                    off.add(t);
                }
            }
        }
        linesOff = off;
        repaint();
    }

    void setLinesChoice(Consumer<String> choice)
    {
        linesChoice = choice;
    }

    private final List<ClickCatcher> clickCatchers = new CopyOnWriteArrayList<>();

    void addClickCatcher(ClickCatcher catcher)
    {
        clickCatchers.add(catcher);
    }

    void removeClickCatcher(ClickCatcher catcher)
    {
        clickCatchers.remove(catcher);
    }

    Projection projection()
    {
        return projection;
    }

    private boolean caught(Point at)
    {
        return clickCatchers.stream().anyMatch(catcher -> catcher.clicked(at, projection));
    }

    void addWidget(Widget widget)
    {
        widgets.add(widget);
        repaint();
    }

    void removeWidget(Widget widget)
    {
        widgets.remove(widget);
        repaint();
    }

    void addMenuContributor(MenuContributor contributor)
    {
        menuContributors.add(contributor);
    }

    void removeMenuContributor(MenuContributor contributor)
    {
        menuContributors.remove(contributor);
    }

    void addHitLayer(HitLayer layer)
    {
        hitLayers.add(layer);
    }

    Poi hovered()
    {
        return hovered;
    }

    void setWindowControls(WindowControls controls)
    {
        windowControls = controls;
        repaint();
    }

    void setBadgeCover(BadgeCover cover)
    {
        badgeCover = cover;
        repaint();
    }

    void setListener(Listener listener)
    {
        this.listener = listener;
    }

    void setMaps(BaseMaps maps)
    {
        this.maps = maps;
        BaseMap current = map == null ? null : maps.byId(map.id);
        map = current != null ? current : maps.surface();
        shownCache.clear();
        repaint();
    }

    /** Ordered so the most useful icons win when they overlap. */
    void setPois(List<Poi> pois)
    {
        setPois(pois, Collections.emptySet());
    }

    /** {@code hidden} icons are not drawn but still serve routes and search. */
    void setPois(List<Poi> pois, Set<Poi> hidden)
    {
        List<Poi> ordered = new ArrayList<>(pois);
        ordered.sort(Comparator.comparingInt((Poi p) -> p.type.layer.ordinal()));
        this.pois = ordered;
        this.hidden = hidden;
        shownCache.clear();
        repaint();
    }

    boolean drawn(Poi poi)
    {
        return !hidden.contains(poi);
    }

    List<Poi> pois()
    {
        return pois;
    }

    void setSearchExtras(List<Poi> extras)
    {
        searchExtras = extras;
    }

    List<Poi> searchExtras()
    {
        return searchExtras;
    }

    List<PoiLoader.Place> labels()
    {
        return labels;
    }

    void setLabels(List<PoiLoader.Place> labels)
    {
        this.labels = labels;
        repaint();
    }

    void setUnlocks(Unlocks unlocks)
    {
        this.unlocks = unlocks;
        repaint();
    }

    Unlocks unlocks()
    {
        return unlocks;
    }

    private static boolean visible(Poi poi, Filter filter)
    {
        if (filter.shows(poi))
        {
            return true;
        }
        // An icon also stands for what is at the same place (a teleport landing there).
        for (Poi other : poi.nearby())
        {
            if (filter.shows(other))
            {
                return true;
            }
        }
        return false;
    }

    boolean usable(Poi poi)
    {
        return usable(poi, config.onlyUsable());
    }

    private boolean usable(Poi poi, boolean onlyUsable)
    {
        for (Poi member : poi.members())
        {
            if (!onlyUsable || unlocks == null || unlocks.usable(member.needs))
            {
                return true;
            }
        }
        return false;
    }

    boolean usable(Needs needs)
    {
        return !config.onlyUsable() || unlocks == null || unlocks.usable(needs);
    }

    BaseMaps maps()
    {
        return maps;
    }

    BaseMap map()
    {
        return map;
    }

    int plane()
    {
        return plane;
    }

    double targetZoom()
    {
        return targetZoom;
    }

    Poi selected()
    {
        return selected;
    }

    boolean isFollowing()
    {
        return following;
    }

    void setFollowing(boolean following)
    {
        if (this.following == following)
        {
            return;
        }
        this.following = following;
        if (following && player != null)
        {
            follow();
        }
        fireViewChanged();
    }

    void setPlane(int plane)
    {
        this.plane = Math.max(0, Math.min(3, plane));
        repaint();
        fireViewChanged();
    }

    void showMap(BaseMap map, WorldPoint focus, double zoomLevel)
    {
        boolean changed = map != this.map;
        this.map = map;
        if (focus != null)
        {
            plane = focus.getPlane();
        }
        jumpOrAnimate(changed, focus != null ? focus.getX() + 0.5 : map.centerX, focus != null ? focus.getY() + 0.5
            : map.centerY, zoomLevel);
        fireViewChanged();
    }

    void focus(Poi poi)
    {
        setFollowing(false);
        lookAt(poi.map != null && !poi.isOn(map) ? poi.map : null, poi.location);
        select(poi);
    }

    void focus(WorldPoint point)
    {
        setFollowing(false);
        BaseMap target = mapOf(point);
        if (target == null && maps != null && (map == null || map.id != BaseMap.FULL))
        {
            // On no map of its own (an instance): the wiki's map of everything draws it.
            target = maps.byId(BaseMap.FULL);
        }
        lookAt(target != null && target != map && (map == null || map.id != BaseMap.FULL) ? target : null, point);
    }

    private void lookAt(BaseMap other, WorldPoint game)
    {
        if (other != null)
        {
            rememberForBack(other);
            showMap(other, shownOn(other, game), Math.max(targetZoom, 1));
            return;
        }
        WorldPoint at = shown(game);
        plane = at.getPlane();
        animateTo(at.getX() + 0.5, at.getY() + 0.5, Math.max(targetZoom, 1));
        fireViewChanged();
    }

    void select(Poi poi)
    {
        if (poi == selected)
        {
            return;
        }
        selected = poi;
        repaint();
        if (listener != null)
        {
            listener.selectionChanged(poi);
        }
    }

    void zoomBy(double delta)
    {
        zoomAt(delta, new Point(getWidth() / 2, getHeight() / 2));
    }

    void setPlayer(WorldPoint player)
    {
        boolean moved = !Objects.equals(player, this.player);
        this.player = player;
        if (moved && following && player != null)
        {
            follow();
        }
        else if (moved)
        {
            repaint();
        }
    }

    WorldPoint player()
    {
        return player;
    }

    /** Where "nearest" counts from: the player, in one's house its portal outside. */
    WorldPoint near()
    {
        return player != null && HousePlan.contains(player.getX(), player.getY()) ? outside : player;
    }

    void setOutside(WorldPoint outside)
    {
        this.outside = outside;
    }

    void setHome(boolean home)
    {
        this.home = home;
        repaint();
    }

    static final double PLAYER_ZOOM = 3;

    void centerOnPlayer()
    {
        back.clear();
        if (player != null)
        {
            following = false;
            targetZoom = clampZoom(PLAYER_ZOOM);
        }
        setFollowing(true);
    }

    void jumpToPlayer()
    {
        if (player == null)
        {
            setFollowing(true);
            return;
        }
        BaseMap containing = mapOf(player);
        if (containing != null)
        {
            map = containing;
        }
        WorldPoint at = shown(player);
        plane = at.getPlane();
        jumpOrAnimate(true, at.getX() + 0.5, at.getY() + 0.5, PLAYER_ZOOM);
        following = true;
        fireViewChanged();
    }

    private void follow()
    {
        BaseMap containing = mapOf(player);
        if (containing != null && containing != map)
        {
            int[] area = areaOf(containing, player);
            showMap(containing, shownOn(containing, player), area != null ? fitZoom(area) : targetZoom);
            return;
        }
        WorldPoint at = shown(player);
        if (plane != at.getPlane())
        {
            plane = at.getPlane();
            fireViewChanged();
        }
        animateTo(at.getX() + 0.5, at.getY() + 0.5, targetZoom);
    }

    private double scale()
    {
        return Math.pow(2, zoom);
    }

    private double screenX(double worldX)
    {
        return getWidth() / 2.0 + (worldX - centerX) * scale();
    }

    private double screenY(double worldY)
    {
        return getHeight() / 2.0 - (worldY - centerY) * scale();
    }

    private Point2D toWorld(Point p)
    {
        double scale = scale();
        return new Point2D.Double(centerX + (p.x - getWidth() / 2.0) / scale, centerY - (p.y - getHeight() / 2.0) / scale);
    }

    private void jumpOrAnimate(boolean jump, double x, double y, double z)
    {
        if (jump)
        {
            animator.stop();
            moving = false;
            anchorScreen = null;
            centerX = x;
            centerY = y;
            zoom = targetZoom = clampZoom(z);
            clampCenter();
            targetX = centerX;
            targetY = centerY;
            repaint();
        }
        else
        {
            animateTo(x, y, z);
        }
    }

    private void animateTo(double x, double y, double z)
    {
        anchorScreen = null;
        targetZoom = clampZoom(z);
        double scale = Math.pow(2, targetZoom);
        boolean sized = map != null && getWidth() > 0 && getHeight() > 0;
        targetX = sized ? clampAxis(x, map.minX, map.maxX, getWidth() / 2.0 / scale) : x;
        targetY = sized ? clampAxis(y, map.minY, map.maxY, getHeight() / 2.0 / scale) : y;
        double pixels = Math.hypot(targetX - centerX, targetY - centerY) * scale;
        if (getWidth() > 0 && pixels > 3 * Math.max(getWidth(), getHeight()))
        {
            // Far away: flying would load every tile on the way; jump.
            jumpOrAnimate(true, x, y, targetZoom);
            return;
        }
        moving = true;
        animator.start();
    }

    private void zoomAt(double delta, Point at)
    {
        double next = clampZoom(targetZoom + delta);
        if (next == targetZoom)
        {
            return;
        }
        anchorScreen = at;
        anchorWorld = toWorld(at);
        targetZoom = next;
        moving = true;
        animator.start();
        fireViewChanged();
    }

    private double clampZoom(double z)
    {
        if (!Double.isFinite(z))
        {
            // A NaN zoom (broken map bounds) would blank the view for good.
            return Double.isFinite(targetZoom) ? targetZoom : 1;
        }
        return Math.max(minZoom(), Math.min(MAX_ZOOM, z));
    }

    /** The whole map just fits the view, so it never shrinks to a small picture in a black field. */
    double minZoom()
    {
        if (map == null || getWidth() <= 0 || getHeight() <= 0)
        {
            return MIN_ZOOM;
        }
        double fit = Math.min(getWidth() / (double) Math.max(1, map.maxX - map.minX),
            getHeight() / (double) Math.max(1, map.maxY - map.minY));
        return Math.max(MIN_ZOOM, Math.min(1, Math.log(fit) / Math.log(2)));
    }

    /** The view may pass a map edge by a quarter; a map smaller than the view stays centered. */
    private void clampCenter()
    {
        if (map == null || getWidth() <= 0 || getHeight() <= 0)
        {
            return;
        }
        double scale = scale();
        centerX = clampAxis(centerX, map.minX, map.maxX, getWidth() / 2.0 / scale);
        centerY = clampAxis(centerY, map.minY, map.maxY, getHeight() / 2.0 / scale);
    }

    static double clampAxis(double center, double min, double max, double half)
    {
        double lo = min + half - half * 0.5;
        double hi = max - half + half * 0.5;
        return lo > hi ? (min + max) / 2.0 : Math.max(lo, Math.min(hi, center));
    }

    private void step()
    {
        double dz = targetZoom - zoom;
        boolean done = !(Math.abs(dz) > 0.002);
        zoom = done ? targetZoom : zoom + dz * EASE;
        if (anchorScreen != null)
        {
            double scale = scale();
            centerX = targetX = anchorWorld.getX() - (anchorScreen.x - getWidth() / 2.0) / scale;
            centerY = targetY = anchorWorld.getY() + (anchorScreen.y - getHeight() / 2.0) / scale;
            if (done)
            {
                anchorScreen = null;
            }
        }
        else
        {
            double dx = targetX - centerX;
            double dy = targetY - centerY;
            if (Math.hypot(dx, dy) * scale() > 0.3)
            {
                centerX += dx * EASE;
                centerY += dy * EASE;
                done = false;
            }
            else
            {
                centerX = targetX;
                centerY = targetY;
            }
        }
        clampCenter();
        if (anchorScreen != null || done)
        {
            targetX = centerX;
            targetY = centerY;
        }
        if (done)
        {
            animator.stop();
            moving = false;
        }
        repaint();
    }

    boolean isAnimating()
    {
        return animator.isRunning();
    }

    void stop()
    {
        animator.stop();
        toastTimer.stop();
        scaled.clear();
        shownCache.clear();
    }

    private void fireViewChanged()
    {
        if (listener != null)
        {
            listener.viewChanged();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (map == null)
            {
                return;
            }
            paintTiles(g);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (config.showWilderness())
            {
                paintWilderness(g);
            }
            if (config.showLabels())
            {
                paintPlaceNames(g);
            }
            paintLinks(g);
            paintIcons(g);
            for (Overlay overlay : new ArrayList<>(overlays))
            {
                Graphics2D layer = (Graphics2D) g.create();
                try
                {
                    overlay.paint(layer, projection);
                }
                finally
                {
                    layer.dispose();
                }
            }
            paintPlayer(g);
            if (hovered != null && hovered != selected)
            {
                paintLabel(g, hovered);
            }
            if (selected != null && selected.isOn(map))
            {
                paintLabel(g, selected);
            }
            paintControls(g);
        }
        finally
        {
            g.dispose();
        }
    }

    private int tileLevel()
    {
        return MapIconLayer.tileLevel(zoom);
    }

    private void paintTiles(Graphics2D g)
    {
        if (tiles.version() == null)
        {
            String notice = "Loading map…";
            g.setColor(new Color(200, 200, 200));
            g.setFont(LABEL_FONT);
            g.drawString(notice, (getWidth() - g.getFontMetrics().stringWidth(notice)) / 2, getHeight() / 2);
            return;
        }
        int level = tileLevel();
        double span = TileCache.worldPerTile(level);
        double scale = scale();
        Point2D topLeft = toWorld(new Point(0, 0));
        Point2D bottomRight = toWorld(new Point(getWidth(), getHeight()));
        int x0 = Math.max(TileCache.tileIndex(topLeft.getX(), level), TileCache.tileIndex(map.minX, level));
        int x1 = Math.min(TileCache.tileIndex(bottomRight.getX(), level), TileCache.tileIndex(map.maxX - 1, level));
        int y0 = Math.max(TileCache.tileIndex(bottomRight.getY(), level), TileCache.tileIndex(map.minY, level));
        int y1 = Math.min(TileCache.tileIndex(topLeft.getY(), level), TileCache.tileIndex(map.maxY - 1, level));
        int visible = Math.max(0, x1 - x0 + 1) * Math.max(0, y1 - y0 + 1);
        tiles.beginFrame(visible);
        // A frame after one with no tile at all (the map just opened or switched) may read some tiles from disk.
        int diskNow = drawnTiles == 0 ? FIRST_FRAME_DISK_TILES : 0;
        long diskUntil = System.nanoTime() + FIRST_FRAME_DISK_NANOS;
        drawnTiles = 0;
        scaled.setLimit(visible * 2 + 8);
        boolean settled = zoom == targetZoom;
        boolean smooth = !(scale * span > TileCache.TILE_SIZE * 1.01 && config.crispPixels());
        // Smooth scaling is costly on large windows and invisible while moving.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, moving || !smooth
            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Nearest the middle queued last, so loaded first.
        List<int[]> order = new ArrayList<>();
        for (int ty = y0; ty <= y1; ty++)
        {
            for (int tx = x0; tx <= x1; tx++)
            {
                order.add(new int[]{tx, ty});
            }
        }
        double midX = (x0 + x1) / 2.0;
        double midY = (y0 + y1) / 2.0;
        order.sort(Comparator.comparingDouble((int[] t) -> -Math.hypot(t[0] - midX, t[1] - midY)));
        for (int[] t : order)
        {
            int tx = t[0];
            int ty = t[1];
            int left = (int) Math.floor(screenX(tx * span));
            int right = (int) Math.floor(screenX((tx + 1) * span));
            int top = (int) Math.floor(screenY((ty + 1) * span));
            int bottom = (int) Math.floor(screenY(ty * span));
            TileCache.Key key = new TileCache.Key(map.id, level, plane, tx, ty);
            BufferedImage image = tiles.get(key);
            if (image == null && diskNow > 0 && System.nanoTime() < diskUntil)
            {
                diskNow--;
                image = tiles.fromDiskNow(key);
            }
            if (image != null)
            {
                drawnTiles++;
                scaled.draw(g, key, image, left, top, right - left, bottom - top, settled, smooth);
            }
            else
            {
                paintFallback(g, level, tx, ty, left, top, right, bottom);
            }
        }
        // Where nothing is loaded yet, a tile two levels coarser (one covers 16) shows the whole view blurry at once.
        for (TileCache.Key coarse : standIns)
        {
            tiles.get(coarse);
        }
        standIns.clear();
        tiles.endFrame();
    }

    /** Other floors' icons show (faded) above ground when zoomed in; underground floors on one spot are different places. */
    private boolean showsOtherFloor(WorldPoint at)
    {
        // Further out they crowd the floor in view.
        return targetZoom >= 2 && at.getY() < UNDERGROUND_Y;
    }

    static final int UNDERGROUND_Y = 4800;

    private void paintFallback(Graphics2D g, int level, int tx, int ty, int left, int top, int right, int bottom)
    {
        TileCache.Key standIn = new TileCache.Key(map.id, level - 2, plane, Math.floorDiv(tx, 4), Math.floorDiv(ty, 4));
        if (level - 2 >= TileCache.MIN_ZOOM)
        {
            standIns.add(standIn);
        }
        for (int coarser = level - 1; coarser >= TileCache.MIN_ZOOM; coarser--)
        {
            int factor = 1 << (level - coarser);
            int px = Math.floorDiv(tx, factor);
            int py = Math.floorDiv(ty, factor);
            BufferedImage parent = tiles.peek(new TileCache.Key(map.id, coarser, plane, px, py));
            if (parent != null)
            {
                int part = TileCache.TILE_SIZE / factor;
                int sx = (tx - px * factor) * part;
                int sy = (factor - 1 - (ty - py * factor)) * part;
                g.drawImage(parent, left, top, right, bottom, sx, sy, sx + part, sy + part, null);
                if (coarser >= level - 2)
                {
                    standIns.remove(standIn);
                }
                break;
            }
        }
        // Zooming out, the finer tiles of the level before are usually still loaded: drawn over, so nothing flashes.
        if (level < TileCache.MAX_ZOOM)
        {
            int midX = (left + right) / 2;
            int midY = (top + bottom) / 2;
            for (int dy = 0; dy < 2; dy++)
            {
                for (int dx = 0; dx < 2; dx++)
                {
                    BufferedImage child = tiles.peek(new TileCache.Key(map.id, level + 1, plane, tx * 2 + dx, ty * 2 + dy));
                    if (child != null)
                    {
                        int x0 = dx == 0 ? left : midX;
                        int x1 = dx == 0 ? midX : right;
                        // Tile rows grow northwards, screen rows southwards.
                        int y0 = dy == 0 ? midY : top;
                        int y1 = dy == 0 ? bottom : midY;
                        g.drawImage(child, x0, y0, x1 - x0, y1 - y0, null);
                    }
                }
            }
        }
    }

    private final LinkedHashSet<TileCache.Key> standIns = new LinkedHashSet<>();
    private int drawnTiles;
    private static final int FIRST_FRAME_DISK_TILES = 24;
    private static final long FIRST_FRAME_DISK_NANOS = 60_000_000L;

    private static final Layer[] LAYERS = Layer.values();

    /** Which layers show, read once per frame rather than per icon. */
    private final class Filter
    {
        private final boolean[] layers = new boolean[LAYERS.length];
        private final boolean shortcuts;
        final boolean onlyUsable = config.onlyUsable();

        Filter()
        {
            for (Layer layer : LAYERS)
            {
                layers[layer.ordinal()] = shown(layer);
            }
            shortcuts = config.showSkilling() && targetZoom >= 2;
        }

        /** Hidden below a zoom level each, so the zoomed-out map stays readable. */
        private boolean shown(Layer layer)
        {
            switch (layer)
            {
                case TELEPORTS:
                    return config.showTeleports() && targetZoom >= -2.5;
                case TRANSPORTS:
                    return config.showTransports();
                case DUNGEONS:
                    return config.showDungeons() && targetZoom >= -0.5;
                case SERVICES:
                    return config.showServices() && targetZoom >= 1;
                case SAILING:
                    return config.showSailing() && targetZoom >= -1.5;
                case SKILLING:
                    return config.showSkilling() && targetZoom >= 1.5;
                case ACTIVITIES:
                    return config.showActivities() && targetZoom >= -1;
                default:
                    return true;
            }
        }

        /** Shown and usable. */
        boolean shows(Poi poi)
        {
            return (poi.type == PoiType.AGILITY_SHORTCUT && poi.type.layer == Layer.SKILLING ? shortcuts
                : layers[poi.type.layer.ordinal()]) && usable(poi, onlyUsable);
        }
    }

    private boolean inGroup(Poi poi)
    {
        return selected != null && poi != selected && selected.group != null && poi.hasGroup(selected.group)
            && selected.type == PoiType.TELEPORT;
    }

    /** Lines from the selected icon to where it leads: one path per style, stroked together (fairy rings have 50+). */
    private void paintLinks(Graphics2D g)
    {
        if (selected == null || !selected.isOn(map) || !linesShown(selected.type))
        {
            return;
        }
        WorldPoint from = shown(selected.location);
        Point2D icon = fromLayers(layer -> layer.iconCenter(selected, projection));
        double sx = icon != null ? icon.getX() : screenX(from.getX() + 0.5);
        double sy = icon != null ? icon.getY() : screenY(from.getY() + 0.5);
        Color color = selected.type.color;
        List<Poi.Link> links = selected.links();
        float width = links.size() > 12 ? 1.6f : 2.4f;
        Path2D usableLines = new Path2D.Double();
        Path2D lockedLines = new Path2D.Double();
        List<double[]> ends = new ArrayList<>();
        for (Poi.Link link : links)
        {
            boolean usable = unlocks == null || unlocks.usable(link.needs);
            if (!onThisMap(link.map) || !usable && config.onlyUsable())
            {
                continue;
            }
            WorldPoint to = shown(link.point);
            double dx = screenX(to.getX() + 0.5);
            double dy = screenY(to.getY() + 0.5);
            if (Math.hypot(dx - sx, dy - sy) < 2)
            {
                continue;
            }
            double mx = (sx + dx) / 2 - (dy - sy) * 0.18;
            double my = (sy + dy) / 2 + (dx - sx) * 0.18;
            if (Math.max(sx, Math.max(dx, mx)) < -8 || Math.min(sx, Math.min(dx, mx)) > getWidth() + 8
                || Math.max(sy, Math.max(dy, my)) < -8 || Math.min(sy, Math.min(dy, my)) > getHeight() + 8)
            {
                continue;
            }
            Path2D into = usable ? usableLines : lockedLines;
            into.moveTo(sx, sy);
            into.quadTo(mx, my, dx, dy);
            if (dx > -8 && dy > -8 && dx < getWidth() + 8 && dy < getHeight() + 8)
            {
                ends.add(new double[]{dx, dy, usable ? 1 : 0});
            }
        }
        // Dashed: a random destination, or a locked one (grey).
        stroke(g, lockedLines, LOCKED_LINK, width, LINK_DASH);
        stroke(g, usableLines, color, width, selected.type.isRandomDestination() ? LINK_DASH : null);
        for (double[] end : ends)
        {
            PoiIcons.paintDot(g, end[0], end[1], (end[2] > 0 ? color : LOCKED_LINK).darker());
        }
    }

    /** The first hit layer's answer, or null. */
    private <T> T fromLayers(Function<HitLayer, T> ask)
    {
        for (HitLayer layer : hitLayers)
        {
            T at = ask.apply(layer);
            if (at != null)
            {
                return at;
            }
        }
        return null;
    }

    private static final Color LOCKED_LINK = new Color(150, 150, 150);
    private static final float[] LINK_DASH = {6f, 5f};
    private static final Color LINK_SHADOW = new Color(0, 0, 0, 120);

    private static void stroke(Graphics2D g, Path2D lines, Color color, float width, float[] dash)
    {
        if (lines.getCurrentPoint() == null)
        {
            return;
        }
        // Wide antialiased curves are costly: one dark stroke under the colored one, butt caps.
        g.setStroke(linkStroke(width + 2.2f, dash));
        g.setColor(LINK_SHADOW);
        g.draw(lines);
        g.setStroke(linkStroke(width, dash));
        g.setColor(LINK_COLORS.computeIfAbsent(color, c -> new Color(c.getRed(), c.getGreen(), c.getBlue(), 220)));
        g.draw(lines);
    }

    private static final Map<String, BasicStroke> LINK_STROKES = new ConcurrentHashMap<>();
    private static final Map<Color, Color> LINK_COLORS = new ConcurrentHashMap<>();

    private static BasicStroke linkStroke(float width, float[] dash)
    {
        return LINK_STROKES.computeIfAbsent(width + (dash == null ? "" : Arrays.toString(dash)),
            key -> new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
    }

    private boolean onThisMap(BaseMap other)
    {
        return other != null && (map.id == BaseMap.FULL || other == map);
    }

    private void paintIcons(Graphics2D g)
    {
        double size = iconSize(zoom);
        // Zoomed out, icons need more room. Which make way is decided on a world-fixed grid, in half-zoom steps of the
        // target zoom, so dragging or zoom animation never makes icons blink.
        double step = Math.floor(targetZoom * 2) / 2;
        double cellWorld = iconSize(step) * (step < -1 ? 1.5 : step < 1 ? 1.15 : 0.9) / Math.pow(2, step);
        occupied.clear();
        Filter filter = new Filter();
        List<Drawn> frame = new ArrayList<>();
        List<Poi> last = new ArrayList<>();
        Composite normal = g.getComposite();
        int w = getWidth();
        int h = getHeight();
        for (Poi poi : pois)
        {
            boolean special = poi == selected || poi == hovered || inGroup(poi);
            if (hidden.contains(poi) && poi != selected || !poi.isOn(map) || !special && !visible(poi, filter))
            {
                continue;
            }
            WorldPoint at = shownCached(poi);
            if (!special && at.getPlane() != plane && !showsOtherFloor(at))
            {
                continue;
            }
            double x = screenX(at.getX() + 0.5);
            double y = screenY(at.getY() + 0.5);
            // Every icon claims its cell, even hovered or just out of view, so no neighbour pops up in its place.
            boolean free = occupied.add(cellKey(at.getX() + 0.5, at.getY() + 0.5, cellWorld));
            // Where the tile shows the game's icon, that acts as this one: no second icon on top, also when selected.
            if (badgeCover != null && badgeCover.covers(poi, projection) && tileShown(at)
                || x < -size || y < -size || x > w + size || y > h + size)
            {
                continue;
            }
            if (special)
            {
                last.add(poi);
            }
            else if (free)
            {
                g.setComposite(at.getPlane() == plane ? normal : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                PoiIcons.paintCached(g, poi.type, x, y, size, false);
                frame.add(new Drawn(poi, x, y, size / 2 + 2));
            }
        }
        g.setComposite(normal);
        for (Poi poi : last)
        {
            WorldPoint at = shownCached(poi);
            double x = screenX(at.getX() + 0.5);
            double y = screenY(at.getY() + 0.5);
            double s = poi == selected ? size * 1.25 : size * 1.1;
            PoiIcons.paintCached(g, poi.type, x, y, s, poi == selected || inGroup(poi));
            frame.add(new Drawn(poi, x, y, s / 2 + 2));
        }
        drawn = frame;
    }

    private final LongSet occupied = new LongSet();
    private final Map<Poi, WorldPoint> shownCache = new IdentityHashMap<>();
    private BaseMap shownCacheMap;

    private WorldPoint shownCached(Poi poi)
    {
        if (shownCacheMap != map)
        {
            shownCache.clear();
            shownCacheMap = map;
        }
        return shownCache.computeIfAbsent(poi, p -> shown(p.location));
    }

    /** A set of longs without boxing (open addressing), emptied every frame. */
    static final class LongSet
    {
        private long[] keys = new long[256];
        private boolean[] used = new boolean[256];
        private int size;

        /** False when it was there already. */
        boolean add(long key)
        {
            if (size * 2 >= keys.length)
            {
                grow();
            }
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            while (used[i])
            {
                if (keys[i] == key)
                {
                    return false;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            size++;
            return true;
        }

        void clear()
        {
            if (size > 0)
            {
                Arrays.fill(used, false);
                size = 0;
            }
        }

        private void grow()
        {
            long[] oldKeys = keys;
            boolean[] oldUsed = used;
            keys = new long[oldKeys.length * 2];
            used = new boolean[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++)
            {
                if (oldUsed[i])
                {
                    add(oldKeys[i]);
                }
            }
        }

        private static long mix(long key)
        {
            long h = key * 0x9E3779B97F4A7C15L;
            return h ^ (h >>> 29);
        }
    }

    /** Whether the tile at a point is loaded at the level in view: only then does it show the game's icon. */
    private boolean tileShown(WorldPoint point)
    {
        int level = tileLevel();
        return tiles.peek(new TileCache.Key(map.id, level, plane, TileCache.tileIndex(point.getX(), level),
            TileCache.tileIndex(point.getY(), level))) != null;
    }

    static long cellKey(double worldX, double worldY, double cellWorld)
    {
        return ((long) (int) Math.floor(worldX / cellWorld) << 32) | ((int) Math.floor(worldY / cellWorld) & 0xffffffffL);
    }

    private double iconSize(double z)
    {
        return config.iconSize() * iconScale(z);
    }

    /** Icon scale at a zoom, smooth so icons never jump: smaller far out, full from zoom 0, growing past the finest tiles. */
    static double iconScale(double z)
    {
        return z <= -3 ? 0.75 : z < 0 ? 0.75 + (z + 3) / 3 * 0.25 : z > 3 ? Math.min(3, Math.pow(2, z - 3)) : 1.0;
    }

    private void paintPlaceNames(Graphics2D g)
    {
        if (map.id != BaseMap.SURFACE && map.id != BaseMap.FULL)
        {
            return;
        }
        List<Rectangle2D> taken = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++)
        {
            String kind = pass == 0 ? "region" : pass == 1 ? "island" : "settlement";
            double z = targetZoom;
            if (!(pass == 0 ? z < -0.5 : pass == 1 ? z >= -2 && z < 2.5 : z >= -1.2 && z < 3.5))
            {
                continue;
            }
            Font font = pass == 0 ? REGION_FONT : PLACE_FONT;
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            places:
            for (PoiLoader.Place place : labels)
            {
                if (!kind.equals(place.kind))
                {
                    continue;
                }
                double x = screenX(place.point.getX() + 0.5);
                double y = screenY(place.point.getY() + 0.5);
                int width = metrics.stringWidth(place.name);
                Rectangle2D box = new Rectangle2D.Double(x - width / 2.0 - 3, y - metrics.getAscent(), width + 6, metrics.getHeight());
                if (box.getMaxX() < 0 || box.getMaxY() < 0 || box.getX() > getWidth() || box.getY() > getHeight())
                {
                    continue;
                }
                for (Rectangle2D other : taken)
                {
                    if (other.intersects(box))
                    {
                        continue places;
                    }
                }
                taken.add(box);
                text.draw(g, place.name, font, (float) (x - width / 2.0), (float) y, pass == 0 ? REGION_COLOR : Color.WHITE);
            }
        }
    }

    /** Wilderness level lines every five levels, with the level 20 and 30 teleport limits; level n starts 8(n-1) tiles north. */
    private void paintWilderness(Graphics2D g)
    {
        if (zoom < -1.5 || maps == null)
        {
            return;
        }
        double left = screenX(2944);
        double right = screenX(3392);
        g.setFont(SMALL_FONT);
        for (int start : WILDERNESS_STARTS)
        {
            if (plane != 0 || !onThisMap(maps.find(3100, start + 100)))
            {
                continue;
            }
            for (int level = 1; level <= 56; level++)
            {
                boolean limit = level == 21 || level == 31;
                if (level != 1 && level % 5 != 0 && !limit)
                {
                    continue;
                }
                double y = screenY(start + (level - 1) * 8);
                if (y < -2 || y > getHeight() + 2)
                {
                    continue;
                }
                boolean strong = level == 1 || limit;
                g.setStroke(strong ? WILDERNESS_LINE : WILDERNESS_DASHED);
                g.setColor(strong ? WILDERNESS_STRONG : WILDERNESS_FAINT);
                line(g, left, y, right, y);
                if (zoom >= -0.5 || strong)
                {
                    text.draw(g, "Level " + level + (limit ? ": no teleports above " + (level - 1) : ""), SMALL_FONT, (float) Math.max(4, left + 4), (float) y - 3, WILDERNESS_TEXT);
                }
            }
        }
    }

    private void paintPlayer(Graphics2D g)
    {
        if (player == null)
        {
            return;
        }
        if (!onThisMap(mapOf(player)))
        {
            return;
        }
        WorldPoint at = shown(player);
        double x = screenX(at.getX() + 0.5);
        double y = screenY(at.getY() + 0.5);
        if (!(x < -4 || y < -4 || x > getWidth() + 4 || y > getHeight() + 4))
        {
            PlayerMarker.paint(g, x, y, zoom, at.getPlane() != plane, home);
            return;
        }
        // Out of view: a pointer at the edge.
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        double dx = x - cx;
        double dy = y - cy;
        double t = Math.min(dx == 0 ? Double.MAX_VALUE : (cx - 18) / Math.abs(dx),
            dy == 0 ? Double.MAX_VALUE : (cy - 18) / Math.abs(dy));
        PlayerMarker.pointer(g, cx + dx * t, cy + dy * t, Math.atan2(dy, dx));
    }

    private void paintLabel(Graphics2D g, Poi poi)
    {
        if (!poi.isOn(map))
        {
            return;
        }
        WorldPoint at = shown(poi.location);
        Point2D anchor = fromLayers(layer -> layer.labelAnchor(poi, projection));
        double x = anchor != null ? anchor.getX() : screenX(at.getX() + 0.5);
        double y = anchor != null ? anchor.getY() : screenY(at.getY() + 0.5) + iconSize(zoom) * 0.62 + 4;
        g.setFont(LABEL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(poi.name);
        double left = Math.max(2, Math.min(getWidth() - width - 12, x - width / 2.0 - 5));
        RoundRectangle2D box = new RoundRectangle2D.Double(left, y, width + 10, metrics.getHeight() + 4, 8, 8);
        g.setColor(LABEL_BACKGROUND);
        g.fill(box);
        g.setColor(poi.type.color);
        g.setStroke(THIN);
        g.draw(box);
        g.setColor(Color.WHITE);
        g.drawString(poi.name, (float) (left + 5), (float) (y + 2 + metrics.getAscent()));
    }

    /** The icon a click here would take (tests use it as a click). */
    Poi iconAt(Point p)
    {
        List<Drawn> frame = drawn;
        for (int i = frame.size() - 1; i >= 0; i--)
        {
            Drawn d = frame.get(i);
            if (Math.hypot(d.x - p.x, d.y - p.y) <= d.radius)
            {
                if (mapBelow(d.poi) == null)
                {
                    // One of ours on the game's map link: the click goes where the link does.
                    for (HitLayer layer : hitLayers)
                    {
                        Poi under = layer.hit(p, projection);
                        if (under != null && mapBelow(under) != null)
                        {
                            return under;
                        }
                    }
                }
                return d.poi;
            }
        }
        return fromLayers(layer -> layer.hit(p, projection));
    }

    void setChrome(Chrome chrome)
    {
        this.chrome = chrome;
        repaint();
    }

    /** Painted after the other controls, so those win a click where they overlap. */
    private void paintWidgets(Graphics2D g, List<Control> painted, int bottom)
    {
        Clicks clicks = new Clicks()
        {
            @Override
            public void add(RoundRectangle2D shape, Runnable action, String tooltip)
            {
                painted.add(new Control(shape, action, tooltip));
            }

            @Override
            public boolean hovered(RoundRectangle2D shape)
            {
                return isHovered(shape);
            }
        };
        for (Widget widget : widgets)
        {
            widget.paint(g, getWidth(), getHeight(), bottom, clicks);
        }
    }

    private Control control(Point p)
    {
        return controls.stream().filter(control -> control.shape.contains(p)).findFirst().orElse(null);
    }

    private void paintControls(Graphics2D g)
    {
        List<Control> painted = new ArrayList<>();
        if (chrome == null)
        {
            backOffset = 0;
            paintBack(g, painted);
            paintWidgets(g, painted, getHeight() - MARGIN - 18);
            controls = painted;
            return;
        }
        int w = getWidth();
        g.setFont(CONTROL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        String mapName = (map == null ? "Map" : map.name) + "  ▾";
        int mapWidth = metrics.stringWidth(mapName) + 28;
        text(g, mapName, control(g, painted, MARGIN, MARGIN, mapWidth,
            () -> chrome.pickMap(new Point(MARGIN, MARGIN + CONTROL + 4)), "Choose a map"), metrics);
        backOffset = mapWidth + 6;
        paintBack(g, painted);
        g.setFont(CONTROL_FONT);
        WindowControls window = windowControls;
        if (window != null)
        {
            Control close = control(g, painted, w - MARGIN - CONTROL, MARGIN, CONTROL, window::close, "Close (Esc)");
            Control max = control(g, painted, w - MARGIN - 2 * CONTROL - 4, MARGIN, CONTROL, window::toggleMaximised,
                window.isMaximised() ? "Make smaller" : "Fill the game view");
            g.setColor(Color.WHITE);
            g.setStroke(ROUND_LINE);
            double cx = close.shape.getCenterX();
            double cy = close.shape.getCenterY();
            line(g, cx - 6, cy - 6, cx + 6, cy + 6);
            line(g, cx + 6, cy - 6, cx - 6, cy + 6);
            double mx = max.shape.getCenterX();
            double my = max.shape.getCenterY();
            g.setStroke(ICON_LINE);
            if (window.isMaximised())
            {
                g.draw(new Rectangle2D.Double(mx - 6, my - 3, 9, 9));
                line(g, mx - 3, my - 6, mx + 6, my - 6);
                line(g, mx + 6, my - 6, mx + 6, my + 3);
            }
            else
            {
                g.draw(new Rectangle2D.Double(mx - 6, my - 6, 12, 12));
            }
        }
        int right = w - MARGIN - CONTROL;
        int bottom = getHeight() - MARGIN - 18 - CONTROL;
        Control out = control(g, painted, right, bottom, CONTROL, () -> zoomBy(-1), "Zoom out");
        Control in = control(g, painted, right, bottom - CONTROL - 2, CONTROL, () -> zoomBy(1), "Zoom in");
        Control follow = control(g, painted, right, bottom - 2 * CONTROL - 12, CONTROL,
            () -> {
                if (following)
                {
                    setFollowing(false);
                }
                else
                {
                    centerOnPlayer();
                }
            }, following ? "Stop following" : "Follow my character");
        g.setColor(Color.WHITE);
        g.setStroke(ROUND_LINE);
        double x = in.shape.getCenterX();
        double y = in.shape.getCenterY();
        line(g, x - 7, y, x + 7, y);
        line(g, x, y - 7, x, y + 7);
        y = out.shape.getCenterY();
        line(g, x - 7, y, x + 7, y);
        y = follow.shape.getCenterY();
        g.setColor(following ? PLAYER : Color.WHITE);
        g.setStroke(ICON_LINE);
        g.draw(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
        for (int s = -1; s <= 1; s += 2)
        {
            line(g, x, y + s * 7, x, y + s * 11);
            line(g, x + s * 7, y, x + s * 11, y);
        }
        g.fill(new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5));
        Control up = control(g, painted, MARGIN, bottom, CONTROL, () -> setPlane(plane + 1), "Floor up");
        String floor = "Floor " + plane;
        int floorWidth = metrics.stringWidth(floor) + 24;
        Control label = new Control(new RoundRectangle2D.Double(MARGIN + CONTROL + 2, bottom, floorWidth, CONTROL, 10, 10),
            () -> { }, "Floor");
        paintControl(g, label);
        Control down = control(g, painted, MARGIN + CONTROL + 4 + floorWidth, bottom, CONTROL,
            () -> setPlane(plane - 1), "Floor down");
        text(g, floor, label, metrics);
        g.setColor(plane < 3 ? Color.WHITE : Color.GRAY);
        g.fill(triangle(up.shape.getCenterX(), up.shape.getCenterY(), true));
        g.setColor(plane > 0 ? Color.WHITE : Color.GRAY);
        g.fill(triangle(down.shape.getCenterX(), down.shape.getCenterY(), false));
        paintWidgets(g, painted, widgetBottom(getHeight()));
        controls = painted;
    }

    /** A short message on the map ("Added to My route"), or null. */
    private String toast;
    private long toastUntil;
    private final Timer toastTimer = new Timer(3000, e -> repaint());

    void showToast(String text)
    {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2800;
        toastTimer.setRepeats(false);
        toastTimer.restart();
        repaint();
    }

    private void paintToast(Graphics2D g)
    {
        String text = toast;
        if (text == null || System.currentTimeMillis() > toastUntil)
        {
            return;
        }
        g.setFont(CONTROL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 24;
        double x = Math.max(2, (getWidth() - width) / 2.0);
        double y = getHeight() - MARGIN - 18 - CONTROL - 40;
        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, width, 26, 10, 10);
        g.setColor(new Color(20, 20, 24, 225));
        g.fill(box);
        g.setColor(CONTROL_EDGE);
        g.draw(box);
        g.setColor(Color.WHITE);
        g.drawString(text, (float) (x + 12), (float) (y + 13 + (metrics.getAscent() - metrics.getDescent()) / 2.0));
    }

    private int backOffset;

    private void paintBack(Graphics2D g, List<Control> painted)
    {
        paintToast(g);
        g.setFont(CONTROL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        double x = MARGIN + backOffset;
        // Off the world map: a button to it first, then Back (unless Back goes there).
        BaseMap to = backMap();
        BaseMap world = maps == null || map == null || map.id == BaseMap.SURFACE || map.id == BaseMap.FULL ? null
            : maps.byId(BaseMap.SURFACE);
        if (world != null && world != to)
        {
            int width = metrics.stringWidth("World map") + 24;
            double wy = control(g, painted, x, MARGIN, width, () -> toWorld(world), "Open the world map").shape.getCenterY();
            g.setColor(Color.WHITE);
            g.drawString("World map", (float) (x + 12), (float) (wy + metrics.getAscent() / 2.0 - 2));
            x += width + 6;
        }
        if (to == null)
        {
            return;
        }
        double cy = control(g, painted, x, MARGIN, metrics.stringWidth(to.name) + 40, this::goBack, "Back to " + to.name
            + " (or the mouse's back button)").shape.getCenterY();
        double ax = x + 15;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        line(g, ax - 5, cy, ax + 5, cy);
        line(g, ax - 5, cy, ax - 1, cy - 4);
        line(g, ax - 5, cy, ax - 1, cy + 4);
        g.drawString(to.name, (float) (x + 27), (float) (cy + metrics.getAscent() / 2.0 - 2));
    }

    /** A painted button of the control height. */
    private Control control(Graphics2D g, List<Control> into, double x, double y, double width, Runnable action, String tip)
    {
        Control control = new Control(new RoundRectangle2D.Double(x, y, width, CONTROL, 10, 10), action, tip);
        into.add(control);
        paintControl(g, control);
        return control;
    }

    private boolean isHovered(RoundRectangle2D shape)
    {
        return hoveredControl != null && hoveredControl.shape.equals(shape);
    }

    private void paintControl(Graphics2D g, Control control)
    {
        RoundRectangle2D shape = control.shape;
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(shape.getX() + 1, shape.getY() + 2, shape.getWidth(), shape.getHeight(), 10, 10));
        g.setColor(isHovered(shape) ? CONTROL_HOVER : CONTROL_FILL);
        g.fill(shape);
        g.setColor(CONTROL_EDGE);
        g.setStroke(THIN);
        g.draw(shape);
    }

    private static void text(Graphics2D g, String text, Control control, FontMetrics metrics)
    {
        g.setColor(Color.WHITE);
        g.drawString(text, (float) (control.shape.getCenterX() - metrics.stringWidth(text) / 2.0),
            (float) (control.shape.getCenterY() + metrics.getAscent() / 2.0 - 2));
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2)
    {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static Path2D triangle(double x, double y, boolean up)
    {
        double d = up ? -1 : 1;
        Path2D path = new Path2D.Double();
        path.moveTo(x, y + d * 6);
        path.lineTo(x + 7, y - d * 4);
        path.lineTo(x - 7, y - d * 4);
        path.closePath();
        return path;
    }

    /** Repaints only when what is highlighted changes: a full repaint per mouse move is expensive. */
    private void updateHover(Point p)
    {
        Control overControl = control(p);
        // Controls are made anew every paint: the same button has the same shape.
        boolean full = overControl == null ? hoveredControl != null : !isHovered(overControl.shape);
        if (overControl != hoveredControl)
        {
            hoveredControl = overControl;
            setToolTipText(overControl == null ? null : overControl.tooltip);
        }
        Poi hit = overControl == null ? iconAt(p) : null;
        if (hit != hovered)
        {
            hovered = hit;
            full = true;
        }
        if (overControl != null || !dragging)
        {
            setCursor(Cursor.getPredefinedCursor(overControl != null || hit != null ? Cursor.HAND_CURSOR
                : Cursor.DEFAULT_CURSOR));
        }
        if (full)
        {
            repaint();
        }
    }

    static String goInText(Poi.Link inside)
    {
        return inside.map.id == BaseMap.SURFACE ? "Go out" : "Go in";
    }

    @RequiredArgsConstructor
    private static final class Back
    {
        final BaseMap map;
        final double x;
        final double y;
        final double zoom;
        final int plane;
    }

    private static final int MAX_BACK = 12;
    private final ArrayDeque<Back> back = new ArrayDeque<>();

    /** Before the player opens another map: Back returns here. Automatic changes call {@link #showMap} alone. */
    void rememberForBack(BaseMap next)
    {
        if (map == null || next == map)
        {
            return;
        }
        back.push(new Back(map, targetX, targetY, targetZoom, plane));
        while (back.size() > MAX_BACK)
        {
            back.removeLast();
        }
    }

    BaseMap backMap()
    {
        Back top = back.peek();
        return top == null || top.map == map ? null : top.map;
    }

    private void toWorld(BaseMap world)
    {
        setFollowing(false);
        rememberForBack(world);
        WorldPoint me = player != null && world == mapOf(player) ? shownOn(world, player) : null;
        showMap(world, me, me != null ? PLAYER_ZOOM : Math.min(targetZoom, 1));
    }

    void goBack()
    {
        Back top = back.poll();
        if (top == null)
        {
            return;
        }
        setFollowing(false);
        showMap(top.map, new WorldPoint((int) Math.floor(top.x), (int) Math.floor(top.y), top.plane), top.zoom);
    }

    void goIn(Poi.Link inside)
    {
        setFollowing(false);
        rememberForBack(inside.map);
        int[] area = areaOf(inside.map, inside.point);
        if (area != null)
        {
            showMap(inside.map, new WorldPoint((area[0] + area[2]) / 2, (area[1] + area[3]) / 2,
                shownOn(inside.map, inside.point).getPlane()), fitZoom(area));
            return;
        }
        showMap(inside.map, shownOn(inside.map, inside.point), Math.max(targetZoom, PLAYER_ZOOM));
    }

    /** Walkable area around a point as {minX, minY, maxX, maxY}; null when unknown or too large. Set by the route feature. */
    private Function<WorldPoint, int[]> areaBounds = p -> null;

    void setAreaBounds(Function<WorldPoint, int[]> areaBounds)
    {
        this.areaBounds = areaBounds;
    }

    private int[] areaOf(BaseMap on, WorldPoint point)
    {
        if (on == null || on.id == BaseMap.SURFACE || on.id == BaseMap.FULL)
        {
            return null;
        }
        int[] area = areaBounds.apply(point);
        WorldPoint drawn = shownOn(on, point);
        if (area == null || drawn == point)
        {
            return area;
        }
        int dx = drawn.getX() - point.getX();
        int dy = drawn.getY() - point.getY();
        return new int[]{area[0] + dx, area[1] + dy, area[2] + dx, area[3] + dy};
    }

    private double fitZoom(int[] area)
    {
        double w = Math.max(100, getWidth() - 80);
        double h = Math.max(100, getHeight() - 80);
        double fit = Math.min(w / (area[2] - area[0] + 8), h / (area[3] - area[1] + 8));
        return clampZoom(Math.min(PLAYER_ZOOM, Math.log(fit) / Math.log(2)));
    }

    /** Where an icon leads when that is another map or another dungeon floor; null otherwise. */
    static Poi.Link mapBelow(Poi poi)
    {
        if (poi == null || poi.target == null && poi.type != PoiType.DUNGEON_ENTRANCE && poi.type != PoiType.MAP_EXIT
            && poi.type != PoiType.MAP_LINK)
        {
            return null;
        }
        for (Poi.Link link : poi.links())
        {
            boolean elsewhere = link.map != poi.map || poi.location.getY() >= UNDERGROUND_Y
                && link.point.getPlane() != poi.location.getPlane()
                // The game's map links jump within one map too (Keldagrim's tunnel and city).
                || poi.type == PoiType.MAP_LINK && (link.point.getPlane() != poi.location.getPlane()
                    || Math.max(Math.abs(link.point.getX() - poi.location.getX()),
                        Math.abs(link.point.getY() - poi.location.getY())) > 12);
            if (link.map != null && elsewhere && link.map.id != BaseMap.FULL
                && (poi.target == null || link.map == poi.target))
            {
                return link;
            }
        }
        return null;
    }

    private void showContextMenu(MouseEvent e)
    {
        Point2D world = toWorld(e.getPoint());
        JPopupMenu menu = new JPopupMenu();
        Poi clicked = iconAt(e.getPoint());
        if (clicked != null)
        {
            select(clicked);
        }
        Poi.Link inside = mapBelow(clicked);
        if (inside != null)
        {
            JMenuItem open = menu.add("Open " + inside.map.name);
            open.setFont(open.getFont().deriveFont(Font.BOLD));
            open.addActionListener(a -> goIn(inside));
            menu.addSeparator();
        }
        menu.add("Center here").addActionListener(a -> {
            setFollowing(false);
            animateTo(world.getX(), world.getY(), targetZoom);
        });
        if (player != null)
        {
            menu.add("Show my location").addActionListener(a -> centerOnPlayer());
        }
        // A route or stop goes to the game's own spot, which the map may draw elsewhere (Dagannoth Kings' lair).
        WorldPoint point = gamePoint(new WorldPoint((int) Math.floor(world.getX()), (int) Math.floor(world.getY()), plane));
        menu.add("Nearest teleports to here").addActionListener(a -> {
            if (listener != null)
            {
                listener.nearestRequested(point);
            }
        });
        for (MenuContributor contributor : menuContributors)
        {
            contributor.contribute(menu, point);
        }
        menu.show(this, e.getX(), e.getY());
    }
}
