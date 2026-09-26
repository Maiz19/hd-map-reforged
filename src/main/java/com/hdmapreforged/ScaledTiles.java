package com.hdmapreforged;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiles already scaled to the size they are drawn at. Between the wiki's zoom levels every tile is drawn smaller than
 * it is, and scaling each tile smoothly every frame was the most expensive part of painting; while the zoom level
 * stays the same (dragging, hovering, following the player) each tile is scaled once and then only copied. Swing
 * thread only.
 */
final class ScaledTiles
{
    /** Tiles drawn larger than this are not kept: they would take a lot of memory and scale cheaply anyway. */
    private static final int MAX_PIXELS = 512 * 512;
    /** At most this many bytes of scaled copies (4 bytes a pixel), whatever the tile count allows. */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final class Entry
    {
        /**
         * The tile it was scaled from, only to tell whether the tile is still the same: weakly, so a tile the tile
         * cache has let go of is not kept alive here.
         */
        final WeakReference<BufferedImage> source;
        final BufferedImage scaled;
        final boolean smooth;

        Entry(BufferedImage source, BufferedImage scaled, boolean smooth)
        {
            this.source = new WeakReference<>(source);
            this.scaled = scaled;
            this.smooth = smooth;
        }

        long bytes()
        {
            return 4L * scaled.getWidth() * scaled.getHeight();
        }
    }

    private final LinkedHashMap<TileCache.Key, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private int limit = 64;
    private long bytes;

    /** Keeps at least a frame's worth of tiles (within {@link #MAX_BYTES}). */
    void setLimit(int tiles)
    {
        limit = Math.max(16, tiles);
        trim();
    }

    void clear()
    {
        cache.clear();
        bytes = 0;
    }

    /**
     * Draws {@code source} over the rectangle, from the scaled copy when there is one of the right size. A new copy
     * is only made when {@code settled} (the zoom is not animating); otherwise the tile is scaled as it is drawn,
     * using the interpolation already set on {@code g}.
     */
    void draw(Graphics2D g, TileCache.Key key, BufferedImage source, int x, int y, int width, int height,
        boolean settled, boolean smooth)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.abs(transform.getScaleX());
        int dw = (int) Math.round(width * device);
        int dh = (int) Math.round(height * device);
        if (dw <= 0 || dh <= 0)
        {
            return;
        }
        // At the tile's own size (on a scaled screen too: that only enlarges it, which is cheap) it is drawn as it is.
        if (dw == source.getWidth() && dh == source.getHeight() || width == source.getWidth()
            && height == source.getHeight() || (long) dw * dh > MAX_PIXELS
            || transform.getShearX() != 0 || transform.getShearY() != 0)
        {
            g.drawImage(source, x, y, width, height, null);
            return;
        }
        Entry entry = cache.get(key);
        if (entry == null || entry.source.get() != source || entry.smooth != smooth || entry.scaled.getWidth() != dw
            || entry.scaled.getHeight() != dh)
        {
            if (!settled)
            {
                g.drawImage(source, x, y, width, height, null);
                return;
            }
            entry = new Entry(source, scale(source, dw, dh, smooth), smooth);
            Entry old = cache.put(key, entry);
            bytes += entry.bytes() - (old == null ? 0 : old.bytes());
            trim();
        }
        if (device == 1)
        {
            g.drawImage(entry.scaled, x, y, null);
        }
        else
        {
            AffineTransform place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(width / (double) dw, height / (double) dh);
            Graphics2D copy = (Graphics2D) g.create();
            try
            {
                copy.setTransform(place);
                copy.drawImage(entry.scaled, 0, 0, null);
            }
            finally
            {
                copy.dispose();
            }
        }
    }

    static BufferedImage scale(BufferedImage source, int width, int height, boolean smooth)
    {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
            : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private void trim()
    {
        Iterator<Map.Entry<TileCache.Key, Entry>> it = cache.entrySet().iterator();
        while ((cache.size() > limit || bytes > MAX_BYTES) && it.hasNext())
        {
            bytes -= it.next().getValue().bytes();
            it.remove();
        }
    }
}
