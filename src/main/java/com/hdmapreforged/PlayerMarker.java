package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/** The local player on the map as a yellow target, or an arrow at the view's edge when out of sight. */
final class PlayerMarker
{
    static final Color YELLOW = new Color(255, 214, 64);
    private static final Color SHADOW = new Color(0, 0, 0, 170);
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    private PlayerMarker()
    {
    }

    /** {@code otherFloor}: drawn fainter and dashed. */
    static void paint(Graphics2D g, double x, double y, double zoom, boolean otherFloor)
    {
        double ring = 12 + Math.max(0, Math.min(3, zoom)) * 1.5;
        float[] dash = otherFloor ? new float[]{4f, 3f} : null;
        // A glow, so it reads on bright and dark areas alike.
        g.setColor(new Color(255, 214, 64, otherFloor ? 30 : 60));
        g.fill(circle(x, y, ring + 5));
        for (int pass = 0; pass < 2; pass++)
        {
            g.setColor(pass == 0 ? SHADOW : YELLOW);
            g.setStroke(new BasicStroke(pass == 0 ? 5f : 2.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
            g.draw(circle(x, y, ring));
        }
        double in = ring - 5;
        double out = ring + 5;
        for (int pass = 0; pass < 2; pass++)
        {
            g.setStroke(new BasicStroke(pass == 0 ? 2.4f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(pass == 0 ? SHADOW : YELLOW);
            for (int i = 0; i < 4; i++)
            {
                double a = Math.PI / 2 * i;
                g.draw(new Line2D.Double(x + Math.cos(a) * in, y + Math.sin(a) * in, x + Math.cos(a) * out,
                    y + Math.sin(a) * out));
            }
        }
        g.setColor(otherFloor ? new Color(255, 214, 64, 150) : YELLOW);
        g.fill(circle(x, y, 5.5));
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2f));
        g.draw(circle(x, y, 5.5));
        label(g, otherFloor ? "You (other floor)" : "You", x, y + ring + 8);
    }

    private static Ellipse2D circle(double x, double y, double r)
    {
        return new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
    }

    /** An arrow at {@code (x, y)} pointing at {@code angle} (radians, screen coordinates). */
    static void pointer(Graphics2D g, double x, double y, double angle)
    {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(12, 0);
        arrow.lineTo(-8, -9);
        arrow.lineTo(-4, 0);
        arrow.lineTo(-8, 9);
        arrow.closePath();
        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        at.rotate(angle);
        java.awt.Shape shape = at.createTransformedShape(arrow);
        g.setColor(SHADOW);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.setColor(YELLOW);
        g.fill(shape);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(shape);
        label(g, "You", x - Math.cos(angle) * 22, y - Math.sin(angle) * 22 + 4);
    }

    private static void label(Graphics2D g, String text, double cx, double top)
    {
        g.setFont(FONT);
        FontMetrics metrics = g.getFontMetrics();
        float x = (float) (cx - metrics.stringWidth(text) / 2.0);
        float y = (float) (top + metrics.getAscent() / 2.0);
        g.setColor(SHADOW);
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
        g.setColor(YELLOW);
        g.drawString(text, x, y);
    }
}
