package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Makes the game's map icons that the wiki tiles show baked in hoverable and clickable, without drawing them a
 * second time: an invisible hit area sits on each baked icon, and only the hovered or selected one gets a ring.
 *
 * <p>Measured on the wiki's tiles (map version 2026-08-12_a, zoom levels -3 to 3): icons are 15 × 15 pixels at the
 * tile's own scale, centred 1.5 pixels left of and above the south-west corner of their game tile. Tiles of zoom
 * level 0 and up show them; coarser tiles have none. Ground floor tiles show the icons of every floor, upper floors
 * only their own.
 */
final class MapIconLayer implements MapView.Overlay, MapView.HitLayer
{
    static final double ICON_PIXELS = 15;
    static final double OFFSET_PIXELS = -1.5;
    /** The first tile zoom level with icons baked in. */
    static final int FIRST_LEVEL = 0;
    private static final Color RING = new Color(255, 255, 255, 230);
    private static final Color RING_SHADOW = new Color(0, 0, 0, 150);

    private final MapView view;
    private final BooleanSupplier enabled;
    private List<MapIconLoader.Icon> icons = new ArrayList<>();
    /** The icons by 64×64 region, so a frame or a mouse move only looks at those near what is in view. */
    private Map<Integer, List<MapIconLoader.Icon>> byRegion = new java.util.HashMap<>();
    /** One icon per spot with a sprite of the game (a spot can carry ours and the game's), by region. */
    private Map<Integer, List<MapIconLoader.Icon>> drawnByRegion = new java.util.HashMap<>();
    /** The icons standing for each icon's Poi, for its ring. */
    private Map<Poi, List<MapIconLoader.Icon>> byPoi = new IdentityHashMap<>();
    /** The game's sprites at the size last drawn, by element: scaled once, not in every frame. */
    private final Map<Integer, java.awt.image.BufferedImage> scaled = new java.util.HashMap<>();
    private int scaledSize = -1;
    private static final BasicStroke RING_SHADOW_STROKE = new BasicStroke(4f);
    private static final BasicStroke RING_SELECTED = new BasicStroke(2.2f);
    private static final BasicStroke RING_HOVERED = new BasicStroke(1.6f);
    private static final Color RING_SELECTED_COLOR = new Color(255, 214, 64);
    /** Our icons standing on a baked icon of the same thing. */
    private Map<Poi, MapIconLoader.Icon> covering = new IdentityHashMap<>();
    /** Icons made for baked icons (not our own), for their labels. */
    private Map<Poi, MapIconLoader.Icon> created = new IdentityHashMap<>();

    /** The chosen icon size; above {@link #ICON_PIXELS} the tiles' icons are drawn over at that size. */
    private final java.util.function.IntSupplier size;

    MapIconLayer(MapView view, BooleanSupplier enabled)
    {
        this(view, enabled, () -> (int) ICON_PIXELS);
    }

    MapIconLayer(MapView view, BooleanSupplier enabled, java.util.function.IntSupplier size)
    {
        this.view = view;
        this.enabled = enabled;
        this.size = size;
    }

    /**
     * How large the tiles' icons are drawn at a zoom: as large as ours ({@link MapView#iconScale}, smooth while
     * zooming), never smaller than the tiles show them (they would peek out). Without the game's sprites (not loaded
     * yet), as the tiles show them.
     */
    double drawnSize(double zoom)
    {
        double baked = ICON_PIXELS * scale(zoom);
        return GameIconSprites.hasElements() ? Math.max(baked, size.getAsInt() * MapView.iconScale(zoom)) : baked;
    }

    /** An icon's radius on screen as drawn. */
    double drawnRadius(double zoom)
    {
        return drawnSize(zoom) / 2;
    }

    private static int region(int x, int y)
    {
        return (x >> 6) << 16 | (y >> 6) & 0xffff;
    }

    /** The icons of the regions a screen rectangle (grown by {@code pad} pixels) covers, in no order. */
    private List<List<MapIconLoader.Icon>> near(Map<Integer, List<MapIconLoader.Icon>> grid, MapView.Projection p,
        double left, double top, double right, double bottom)
    {
        double scale = p.screenX(1) - p.screenX(0);
        double x0 = p.screenX(0);
        double y0 = p.screenY(0);
        int minX = (int) Math.floor((left - x0) / scale) - 1;
        int maxX = (int) Math.ceil((right - x0) / scale) + 1;
        // Screen y grows southwards.
        int maxY = (int) Math.ceil((y0 - top) / scale) + 1;
        int minY = (int) Math.floor((y0 - bottom) / scale) - 1;
        List<List<MapIconLoader.Icon>> found = new ArrayList<>();
        if ((long) (maxX - minX) * (maxY - minY) > 4_000_000_000L)
        {
            // The whole world in view: every icon.
            found.addAll(grid.values());
            return found;
        }
        for (int rx = minX >> 6; rx <= maxX >> 6; rx++)
        {
            for (int ry = minY >> 6; ry <= maxY >> 6; ry++)
            {
                List<MapIconLoader.Icon> list = grid.get(rx << 16 | ry & 0xffff);
                if (list != null)
                {
                    found.add(list);
                }
            }
        }
        return found;
    }

    /** Swing thread only. */
    void setIcons(List<MapIconLoader.Icon> icons)
    {
        Map<Poi, MapIconLoader.Icon> made = new IdentityHashMap<>();
        Map<Poi, MapIconLoader.Icon> cover = new IdentityHashMap<>();
        for (MapIconLoader.Icon icon : icons)
        {
            if (!icon.own)
            {
                made.put(icon.poi, icon);
            }
            else
            {
                // Any of our icons standing on the game's icon for it: the tile shows that one, ours would double it.
                cover.put(icon.poi, icon);
            }
        }
        covering = cover;
        this.icons = new ArrayList<>(icons);
        this.created = made;
        Map<Integer, List<MapIconLoader.Icon>> grid = new java.util.HashMap<>();
        Map<Integer, List<MapIconLoader.Icon>> drawnGrid = new java.util.HashMap<>();
        Map<Poi, List<MapIconLoader.Icon>> poiIcons = new IdentityHashMap<>();
        java.util.Set<Long> spots = new java.util.HashSet<>();
        for (MapIconLoader.Icon icon : icons)
        {
            int key = region(icon.drawn.getX(), icon.drawn.getY());
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            poiIcons.computeIfAbsent(icon.poi, k -> new ArrayList<>(1)).add(icon);
            long spot = (long) icon.drawn.getX() << 32 | (long) icon.drawn.getY() << 2 | icon.drawn.getPlane();
            if (icon.element >= 0 && spots.add(spot))
            {
                drawnGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            }
        }
        byRegion = grid;
        drawnByRegion = drawnGrid;
        byPoi = poiIcons;
        view.setSearchExtras(new ArrayList<>(made.keySet()));
        view.repaint();
    }

    private void paintLarger(Graphics2D g, MapView.Projection projection)
    {
        // Nothing to draw when the layer is off or the tiles in view have no icons: no need to look at any icon.
        if (!showsAny(projection))
        {
            return;
        }
        double drawn = drawnSize(projection.zoom());
        int px = (int) Math.round(drawn);
        if (px <= 0)
        {
            return;
        }
        if (px != scaledSize)
        {
            scaled.clear();
            scaledSize = px;
        }
        double r = drawn / 2 + 1;
        for (List<MapIconLoader.Icon> list : near(drawnByRegion, projection, -r, -r, projection.width() + r,
            projection.height() + r))
        {
            for (MapIconLoader.Icon icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
                if (sx < -r - 2 || sy < -r - 2 || sx > projection.width() + r + 2 || sy > projection.height() + r + 2
                    || !onView(icon, projection))
                {
                    continue;
                }
                java.awt.image.BufferedImage sprite = sized(icon.element, px);
                if (sprite == null)
                {
                    continue;
                }
                Point2D c = center(icon, projection);
                // Drawn around the baked icon's middle, at least as large: it covers that one.
                g.drawImage(sprite, (int) Math.round(c.getX() - sprite.getWidth() / 2.0),
                    (int) Math.round(c.getY() - sprite.getHeight() / 2.0), null);
            }
        }
    }

    /** A game sprite scaled to fit {@code px} pixels, made once per size (pixel art: kept crisp). */
    private java.awt.image.BufferedImage sized(int element, int px)
    {
        java.awt.image.BufferedImage cached = scaled.get(element);
        if (cached != null)
        {
            return cached;
        }
        java.awt.image.BufferedImage sprite = GameIconSprites.element(element);
        if (sprite == null)
        {
            return null;
        }
        double k = (double) px / Math.max(sprite.getWidth(), sprite.getHeight());
        int w = Math.max(1, (int) Math.round(sprite.getWidth() * k));
        int h = Math.max(1, (int) Math.round(sprite.getHeight() * k));
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, k >= 1
            ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(sprite, 0, 0, w, h, null);
        g.dispose();
        scaled.put(element, out);
        return out;
    }

    List<MapIconLoader.Icon> icons()
    {
        return icons;
    }

    /** The wiki zoom level of the tiles shown at a view zoom, as the map view picks them. */
    static int tileLevel(double zoom)
    {
        return (int) Math.max(TileCache.MIN_ZOOM, Math.min(TileCache.MAX_ZOOM, Math.ceil(zoom - 1e-6)));
    }

    /** How much the tiles, and so the baked icons, are scaled on screen at a view zoom. */
    static double scale(double zoom)
    {
        return Math.pow(2, zoom - tileLevel(zoom));
    }

    /** Whether the view shows a baked icon: the right map and floor, and tiles detailed enough to have icons. */
    boolean shows(MapIconLoader.Icon icon, MapView.Projection projection)
    {
        return showsAny(projection) && onView(icon, projection);
    }

    /** Whether the view can show baked icons at all: the layer on, a map, and tiles detailed enough to have icons. */
    private boolean showsAny(MapView.Projection projection)
    {
        return projection.map() != null && tileLevel(projection.zoom()) >= FIRST_LEVEL && enabled.getAsBoolean();
    }

    /** Whether a baked icon is on the map and floor in view (see {@link #shows}; the rest checked by the caller). */
    private static boolean onView(MapIconLoader.Icon icon, MapView.Projection projection)
    {
        BaseMap map = projection.map();
        return (map.id == BaseMap.FULL || icon.map == map)
            && (icon.drawn.getPlane() == projection.plane()
                // The ground floor's tiles show the icons of the floors above too; underground, floors on the same
                // spot are other places, drawn (if at all) elsewhere.
                || projection.plane() == 0 && icon.drawn.getY() < MapView.UNDERGROUND_Y);
    }

    /** Whether the tiles in view show the game's own icon for one of our dungeon or service icons. */
    boolean covers(Poi poi, MapView.Projection projection)
    {
        MapIconLoader.Icon icon = covering.get(poi);
        return icon != null && shows(icon, projection);
    }

    /** The screen centre of a baked icon. */
    static Point2D center(MapIconLoader.Icon icon, MapView.Projection projection)
    {
        double offset = OFFSET_PIXELS * scale(projection.zoom());
        return new Point2D.Double(projection.screenX(icon.drawn.getX()) + offset,
            projection.screenY(icon.drawn.getY()) + offset);
    }

    static double radius(double zoom)
    {
        return ICON_PIXELS / 2 * scale(zoom);
    }

    @Override
    public Poi hit(Point point, MapView.Projection projection)
    {
        MapIconLoader.Icon icon = iconAt(point, projection);
        return icon == null ? null : icon.poi;
    }

    /** The baked icon under a point, the one whose middle is nearest when they overlap; null if none. */
    MapIconLoader.Icon iconAt(Point point, MapView.Projection projection)
    {
        if (!showsAny(projection))
        {
            return null;
        }
        double reach = drawnRadius(projection.zoom()) + 1;
        MapIconLoader.Icon best = null;
        double bestDistance = reach;
        List<MapIconLoader.Icon> candidates = new ArrayList<>();
        for (List<MapIconLoader.Icon> list : near(byRegion, projection, point.x - reach - 8, point.y - reach - 8,
            point.x + reach + 8, point.y + reach + 8))
        {
            candidates.addAll(list);
        }
        for (MapIconLoader.Icon icon : candidates)
        {
            double sx = projection.screenX(icon.drawn.getX());
            double sy = projection.screenY(icon.drawn.getY());
            // Cheap rejection before the exact test.
            if (Math.abs(sx - point.x) > reach + 4 || Math.abs(sy - point.y) > reach + 4 || !onView(icon, projection))
            {
                continue;
            }
            Point2D c = center(icon, projection);
            double d = c.distance(point);
            if (d <= bestDistance)
            {
                best = icon;
                bestDistance = d;
            }
        }
        return best;
    }

    @Override
    public Point2D labelAnchor(Poi poi, MapView.Projection projection)
    {
        Point2D c = iconCenter(poi, projection);
        return c == null ? null : new Point2D.Double(c.getX(), c.getY() + drawnRadius(projection.zoom()) + 5);
    }

    @Override
    public Point2D iconCenter(Poi poi, MapView.Projection projection)
    {
        MapIconLoader.Icon icon = created.get(poi);
        if (icon == null && covers(poi, projection))
        {
            // One of ours hidden under the game's icon for it: that is the icon one sees.
            icon = covering.get(poi);
        }
        return icon == null ? null : center(icon, projection);
    }

    /**
     * At a larger icon size, the tiles' icons drawn over at that size (the game's own sprites); then a ring around
     * the hovered and the selected one.
     */
    @Override
    public void paint(Graphics2D g, MapView.Projection projection)
    {
        if (GameIconSprites.hasElements() && enabled.getAsBoolean())
        {
            paintLarger(g, projection);
        }
        Poi hovered = view.hovered();
        Poi selected = view.selected();
        if (hovered == null && selected == null)
        {
            return;
        }
        double r = drawnRadius(projection.zoom()) + 2.5;
        List<MapIconLoader.Icon> ringed = new ArrayList<>();
        ringed.addAll(byPoi.getOrDefault(hovered, java.util.Collections.emptyList()));
        if (selected != hovered)
        {
            ringed.addAll(byPoi.getOrDefault(selected, java.util.Collections.emptyList()));
        }
        for (MapIconLoader.Icon icon : ringed)
        {
            if (!shows(icon, projection))
            {
                continue;
            }
            Point2D c = center(icon, projection);
            if (c.getX() < -r || c.getY() < -r || c.getX() > projection.width() + r || c.getY() > projection.height() + r)
            {
                continue;
            }
            Ellipse2D ring = new Ellipse2D.Double(c.getX() - r, c.getY() - r, r * 2, r * 2);
            g.setStroke(RING_SHADOW_STROKE);
            g.setColor(RING_SHADOW);
            g.draw(ring);
            g.setStroke(icon.poi == selected ? RING_SELECTED : RING_HOVERED);
            g.setColor(icon.poi == selected ? RING_SELECTED_COLOR : RING);
            g.draw(ring);
        }
    }
}
