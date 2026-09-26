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
 * Tiles pre-scaled to their drawn size: smooth scaling every frame was the most expensive part of painting. Swing
 * thread only.
 */
final class ScaledTiles
{
    /** Larger tiles would take a lot of memory and scale cheaply anyway. */
    private static final int MAX_PIXELS = 512 * 512;
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final class Entry
    {
        /** Weak, so a tile the cache dropped is not kept alive here. */
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

    /** A new copy is only made when {@code settled} (the zoom is not animating). */
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
