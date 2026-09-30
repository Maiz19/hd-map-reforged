package com.hdmapreforged;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

/** Outlined map text, rendered once then copied (each label is nine antialiased strings). Swing thread only. */
final class TextSprites
{
    private static final int LIMIT = 1500;
    private static final Color OUTLINE = new Color(0, 0, 0, 200);

    /** By text, font, colour and device scale (in tenths). */
    private final Map<List<Object>, BufferedImage> cache = new LinkedHashMap<List<Object>, BufferedImage>(256, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, BufferedImage> eldest)
        {
            return size() > LIMIT;
        }
    };

    /** Like {@code g.drawString} with the given font, outlined. */
    void draw(Graphics2D g, String text, Font font, float x, float y, Color color)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
        List<Object> key = List.of(text, font, color.getRGB(), (int) Math.round(device * 10));
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int ascent = metrics.getAscent();
        BufferedImage sprite = cache.get(key);
        if (sprite == null)
        {
            int width = metrics.stringWidth(text) + 4;
            int height = metrics.getHeight() + 4;
            sprite = new BufferedImage((int) Math.ceil(width * device), (int) Math.ceil(height * device), BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = sprite.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.scale(device, device);
            sg.setFont(font);
            outlined(sg, text, 2, 2 + ascent, color);
            sg.dispose();
            cache.put(key, sprite);
        }
        PoiIcons.blit(g, sprite, x - 2, y - ascent - 2);
    }

    /** Drawn directly, not cached. */

    static void outlined(Graphics2D g, String text, float x, float y, Color color)
    {
        g.setColor(OUTLINE);
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                if (dx != 0 || dy != 0)
                {
                    g.drawString(text, x + dx, y + dy);
                }
            }
        }
        g.setColor(color);
        g.drawString(text, x, y);
    }
}
