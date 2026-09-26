package com.hdmapreforged;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.ui.FontManager;

/**
 * The game's minimap orbs (hitpoints, prayer, run, special attack), drawn from its own sprites as the game draws them:
 * the frame, the orb filled to how much is left, its icon, and the number in the frame's box, green when full and red
 * when low.
 */
final class Orbs
{
    /** The frame: 57 × 34, the box for the number on the left and the orb on the right. */
    static final int WIDTH = 57;
    static final int HEIGHT = 34;
    private static final int ORB_X = 27;
    private static final int ORB_Y = 4;
    private static final int ORB = 26;

    /** Which orb: its filling and its icon. */
    enum Kind
    {
        HITPOINTS(SpriteID.OrbFiller.HITPOINTS, SpriteID.OrbIcon.HITPOINTS, new Color(170, 40, 40)),
        PRAYER(SpriteID.OrbFiller.PRAYER, SpriteID.OrbIcon.PRAYER, new Color(40, 140, 170)),
        RUN(SpriteID.OrbFiller.RUN, SpriteID.OrbIcon.RUN, new Color(180, 150, 40)),
        SPECIAL(SpriteID.OrbFiller.SPECIAL, SpriteID.OrbIcon.SPECIAL, new Color(60, 150, 70));

        final int filler;
        final int icon;
        /** Drawn instead of the filling before the game's sprites are there. */
        final Color plain;

        Kind(int filler, int icon, Color plain)
        {
            this.filler = filler;
            this.icon = icon;
            this.plain = plain;
        }
    }

    /** Every sprite an orb needs, to load up front. */
    static final int[] SPRITES = {SpriteID.OrbFrame.FRAME, SpriteID.OrbFiller.EMPTY, SpriteID.OrbFiller.HITPOINTS,
        SpriteID.OrbFiller.PRAYER, SpriteID.OrbFiller.RUN, SpriteID.OrbFiller.SPECIAL, SpriteID.OrbIcon.HITPOINTS,
        SpriteID.OrbIcon.PRAYER, SpriteID.OrbIcon.RUN, SpriteID.OrbIcon.SPECIAL};

    private Orbs()
    {
    }

    /**
     * One orb at {@code (x, y)}: {@code value} of {@code max} (a negative value: not known, drawn empty with a dash);
     * {@code sprites} gives the game's sprites by id, null while they load.
     */
    static void paint(Graphics2D g, int x, int y, Kind kind, int value, int max, IntFunction<BufferedImage> sprites)
    {
        double left = value < 0 || max <= 0 ? 0 : Math.max(0, Math.min(1, value / (double) max));
        BufferedImage frame = sprites.apply(SpriteID.OrbFrame.FRAME);
        BufferedImage filler = sprites.apply(kind.filler);
        BufferedImage empty = sprites.apply(SpriteID.OrbFiller.EMPTY);
        BufferedImage icon = sprites.apply(kind.icon);
        if (frame != null)
        {
            g.drawImage(frame, x, y, null);
        }
        else
        {
            g.setColor(new Color(40, 36, 30));
            g.fillRoundRect(x, y + 6, WIDTH - 4, HEIGHT - 12, 10, 10);
            g.fillOval(x + ORB_X - 3, y + ORB_Y - 3, ORB + 6, ORB + 6);
        }
        int ox = x + ORB_X;
        int oy = y + ORB_Y;
        // The empty orb, then the filling from the bottom up to what is left.
        if (empty != null)
        {
            g.drawImage(empty, ox, oy, ORB, ORB, null);
        }
        else
        {
            g.setColor(new Color(20, 20, 20));
            g.fillOval(ox, oy, ORB, ORB);
        }
        int filled = (int) Math.round(ORB * left);
        Shape clip = g.getClip();
        g.clipRect(ox, oy + ORB - filled, ORB, filled);
        if (filler != null)
        {
            g.drawImage(filler, ox, oy, ORB, ORB, null);
        }
        else
        {
            g.setColor(kind.plain);
            g.fillOval(ox, oy, ORB, ORB);
        }
        g.setClip(clip);
        if (icon != null)
        {
            g.drawImage(icon, ox + (ORB - icon.getWidth()) / 2, oy + (ORB - icon.getHeight()) / 2, null);
        }
        // The number in the frame's box, as the game colours it.
        String text = value < 0 ? "–" : String.valueOf(value);
        Font font = FontManager.getRunescapeSmallFont();
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int tx = x + 15 - metrics.stringWidth(text) / 2;
        int ty = y + 26;
        g.setColor(Color.BLACK);
        g.drawString(text, tx + 1, ty + 1);
        g.setColor(value < 0 ? Color.LIGHT_GRAY : color(left));
        g.drawString(text, tx, ty);
    }

    /** The game's colour for how much is left: green when full, through yellow, to red when empty. */
    static Color color(double left)
    {
        double f = Math.max(0, Math.min(1, left));
        int red = f > 0.5 ? (int) Math.round(255 * (1 - f) * 2) : 255;
        int green = f > 0.5 ? 255 : (int) Math.round(255 * f * 2);
        return new Color(red, green, 0);
    }
}
