package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Makes the game's icons baked into the wiki tiles hoverable and clickable without drawing them twice; only the
 * hovered or selected one gets a ring. Measured (map 2026-08-12_a): icons are 15 px at tile scale, centred 1.5 px
 * left of and above their tile's south-west corner; only levels 0 and up have them; ground floor tiles show every
 * floor's icons, upper floors only their own.
 */
final class MapIconLayer implements MapView.Overlay, MapView.HitLayer
{
    static final double ICON_PIXELS = 15;
    static final double OFFSET_PIXELS = -1.5;
    static final int FIRST_LEVEL = 0;
    private static final Color RING = new Color(255, 255, 255, 230);
    private static final Color RING_SHADOW = new Color(0, 0, 0, 150);

    private final MapView view;
    private final BooleanSupplier enabled;
    private List<MapIconLoader.Icon> icons = new ArrayList<>();
    /** By 64×64 region, so a frame or mouse move only looks near the view. */
    private Map<Integer, List<MapIconLoader.Icon>> byRegion = new HashMap<>();
    /** One icon per spot with a game sprite, by region. */
    private Map<Integer, List<MapIconLoader.Icon>> drawnByRegion = new HashMap<>();
    private Map<Poi, List<MapIconLoader.Icon>> byPoi = new IdentityHashMap<>();
    /** Game sprites scaled once to the size last drawn, by element. */
    private final Map<Integer, BufferedImage> scaled = new HashMap<>();
    private int scaledSize = -1;
    private static final BasicStroke RING_SHADOW_STROKE = new BasicStroke(4f);
    private static final BasicStroke RING_SELECTED = new BasicStroke(2.2f);
    private static final BasicStroke RING_HOVERED = new BasicStroke(1.6f);
    private static final Color RING_SELECTED_COLOR = new Color(255, 214, 64);
    /** Our icons standing on a baked icon of the same thing. */
    private Map<Poi, MapIconLoader.Icon> covering = new IdentityHashMap<>();
    /** Icons made for baked icons (not ours), for their labels. */
    private Map<Poi, MapIconLoader.Icon> created = new IdentityHashMap<>();

    /** Above {@link #ICON_PIXELS} the tiles' icons are drawn over at this size. */
    private final IntSupplier size;

    MapIconLayer(MapView view, BooleanSupplier enabled)
    {
        this(view, enabled, () -> (int) ICON_PIXELS);
    }

    MapIconLayer(MapView view, BooleanSupplier enabled, IntSupplier size)
    {
        this.view = view;
        this.enabled = enabled;
        this.size = size;
    }

    /** As large as ours at a zoom, never smaller than the tiles show them; as the tiles show them without sprites. */
    double drawnSize(double zoom)
    {
        double baked = ICON_PIXELS * scale(zoom);
        return GameIconSprites.hasElements() ? Math.max(baked, size.getAsInt() * MapView.iconScale(zoom)) : baked;
    }

    double drawnRadius(double zoom)
    {
        return drawnSize(zoom) / 2;
    }

    private static int region(int x, int y)
    {
        return (x >> 6) << 16 | (y >> 6) & 0xffff;
    }

    private List<List<MapIconLoader.Icon>> near(Map<Integer, List<MapIconLoader.Icon>> grid, MapView.Projection p,
        double left, double top, double right, double bottom)
    {
        double scale = p.screenX(1) - p.screenX(0);
        double x0 = p.screenX(0);
        double y0 = p.screenY(0);
        int minX = (int) Math.floor((left - x0) / scale) - 1;
        int maxX = (int) Math.ceil((right - x0) / scale) + 1;
        int maxY = (int) Math.ceil((y0 - top) / scale) + 1;
        int minY = (int) Math.floor((y0 - bottom) / scale) - 1;
        List<List<MapIconLoader.Icon>> found = new ArrayList<>();
        if ((long) (maxX - minX) * (maxY - minY) > 4_000_000_000L)
        {
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
        Map<Integer, List<MapIconLoader.Icon>> grid = new HashMap<>();
        Map<Integer, List<MapIconLoader.Icon>> drawnGrid = new HashMap<>();
        Map<Poi, List<MapIconLoader.Icon>> poiIcons = new IdentityHashMap<>();
        java.util.Set<Long> spots = new java.util.HashSet<>();
        for (MapIconLoader.Icon icon : icons)
        {
            // Ours standing on the game's icon for it: the tile shows that one.
            (icon.own ? cover : made).put(icon.poi, icon);
            int key = region(icon.drawn.getX(), icon.drawn.getY());
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            poiIcons.computeIfAbsent(icon.poi, k -> new ArrayList<>(1)).add(icon);
            long spot = (long) icon.drawn.getX() << 32 | (long) icon.drawn.getY() << 2 | icon.drawn.getPlane();
            if (icon.element >= 0 && spots.add(spot))
            {
                drawnGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            }
        }
        covering = cover;
        this.icons = new ArrayList<>(icons);
        created = made;
        byRegion = grid;
        drawnByRegion = drawnGrid;
        byPoi = poiIcons;
        view.setSearchExtras(new ArrayList<>(made.keySet()));
        view.repaint();
    }

    private void paintLarger(Graphics2D g, MapView.Projection projection)
    {
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
                BufferedImage sprite = sized(icon.element, px);
                if (sprite == null)
                {
                    continue;
                }
                Point2D c = center(icon, projection);
                // Centred on the baked icon, at least as large: it covers that one.
                g.drawImage(sprite, (int) Math.round(c.getX() - sprite.getWidth() / 2.0),
                    (int) Math.round(c.getY() - sprite.getHeight() / 2.0), null);
            }
        }
    }

    /** A game sprite scaled to {@code px}, once per size (pixel art kept crisp). */
    private BufferedImage sized(int element, int px)
    {
        BufferedImage cached = scaled.get(element);
        if (cached != null)
        {
            return cached;
        }
        BufferedImage sprite = GameIconSprites.element(element);
        if (sprite == null)
        {
            return null;
        }
        double k = (double) px / Math.max(sprite.getWidth(), sprite.getHeight());
        int w = Math.max(1, (int) Math.round(sprite.getWidth() * k));
        int h = Math.max(1, (int) Math.round(sprite.getHeight() * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, k >= 1
            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(sprite, 0, 0, w, h, null);
        g.dispose();
        scaled.put(element, out);
        return out;
    }

    List<MapIconLoader.Icon> icons()
    {
        return icons;
    }

    static int tileLevel(double zoom)
    {
        return (int) Math.max(TileCache.MIN_ZOOM, Math.min(TileCache.MAX_ZOOM, Math.ceil(zoom - 1e-6)));
    }

    static double scale(double zoom)
    {
        return Math.pow(2, zoom - tileLevel(zoom));
    }

    boolean shows(MapIconLoader.Icon icon, MapView.Projection projection)
    {
        return showsAny(projection) && onView(icon, projection);
    }

    private boolean showsAny(MapView.Projection projection)
    {
        return projection.map() != null && tileLevel(projection.zoom()) >= FIRST_LEVEL && enabled.getAsBoolean();
    }

    private static boolean onView(MapIconLoader.Icon icon, MapView.Projection projection)
    {
        BaseMap map = projection.map();
        return (map.id == BaseMap.FULL || icon.map == map)
            && (icon.drawn.getPlane() == projection.plane()
                // Ground floor tiles show the floors above; underground, floors on one spot are other places.
                || projection.plane() == 0 && icon.drawn.getY() < MapView.UNDERGROUND_Y);
    }

    boolean covers(Poi poi, MapView.Projection projection)
    {
        MapIconLoader.Icon icon = covering.get(poi);
        return icon != null && shows(icon, projection);
    }

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

    /** The baked icon under a point, nearest middle wins; null if none. */
    MapIconLoader.Icon iconAt(Point point, MapView.Projection projection)
    {
        if (!showsAny(projection))
        {
            return null;
        }
        double reach = drawnRadius(projection.zoom()) + 1;
        MapIconLoader.Icon best = null;
        double bestDistance = reach;
        for (List<MapIconLoader.Icon> list : near(byRegion, projection, point.x - reach - 8, point.y - reach - 8,
            point.x + reach + 8, point.y + reach + 8))
        {
            for (MapIconLoader.Icon icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
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
            icon = covering.get(poi);
        }
        return icon == null ? null : center(icon, projection);
    }

    /** At a larger icon size, draws the game's sprites over the tiles' icons; then rings the hovered and selected. */
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
        List<MapIconLoader.Icon> ringed = new ArrayList<>(byPoi.getOrDefault(hovered, Collections.emptyList()));
        if (selected != hovered)
        {
            ringed.addAll(byPoi.getOrDefault(selected, Collections.emptyList()));
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
