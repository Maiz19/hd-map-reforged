package com.hdmapreforged;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outlined map text, rendered once and then copied: each outlined label is nine antialiased strings, and a map
 * full of place names drew thousands of them per frame. Swing thread only.
 */
final class TextSprites
{
    private static final int LIMIT = 1500;
    private static final Color OUTLINE = new Color(0, 0, 0, 200);

    private static final class Key
    {
        final String text;
        final Font font;
        final int rgb;
        final int deviceTenths;

        Key(String text, Font font, Color color, int deviceTenths)
        {
            this.text = text;
            this.font = font;
            this.rgb = color.getRGB();
            this.deviceTenths = deviceTenths;
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Key))
            {
                return false;
            }
            Key k = (Key) o;
            return rgb == k.rgb && deviceTenths == k.deviceTenths && text.equals(k.text) && font.equals(k.font);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(text, font, rgb, deviceTenths);
        }
    }

    private final Map<Key, BufferedImage> cache = new LinkedHashMap<Key, BufferedImage>(256, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> eldest)
        {
            return size() > LIMIT;
        }
    };

    /**
     * Draws text with a dark outline, its baseline starting at {@code (x, y)}, as {@code g.drawString} would with
     * the given font.
     */
    void draw(Graphics2D g, String text, Font font, float x, float y, Color color)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
        Key key = new Key(text, font, color, (int) Math.round(device * 10));
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

    /** Text with a dark outline, drawn directly. */
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
