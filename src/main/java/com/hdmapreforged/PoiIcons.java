package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Vector map icons, so they stay sharp at every zoom level and interface scale. */
final class PoiIcons
{
    private static final Color OUTLINE = new Color(20, 18, 16);
    private static final Color GLYPH = new Color(255, 255, 255, 240);

    private PoiIcons()
    {
    }

    /** Rendered badges by type, size and screen scale; icons are drawn from these instead of shape by shape. */
    private static final Map<Long, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    private static final int MAX_SPRITES = 4000;

    /**
     * Same as {@link #paint}, from a cached image: a map with hundreds of icons draws them each frame, and painting
     * each badge's shapes, strokes and glyph every time was the largest cost of a frame.
     */
    static void paintCached(Graphics2D g, PoiType type, double cx, double cy, double size, boolean emphasised)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
        // Quarter pixels of badge size, so zooming changes the image in small steps.
        int quarter = (int) Math.round(size * 4);
        int deviceTenths = (int) Math.round(device * 10);
        long key = ((long) type.ordinal() << 40) | ((long) quarter << 16) | ((long) deviceTenths << 1) | (emphasised ? 1 : 0);
        BufferedImage sprite = SPRITES.get(key);
        double drawnSize = quarter / 4.0;
        // Room around the badge for the shadow and the emphasis ring.
        double pad = Math.ceil(drawnSize * 0.35) + 2;
        double extent = drawnSize + pad * 2;
        if (sprite == null)
        {
            if (SPRITES.size() > MAX_SPRITES)
            {
                SPRITES.clear();
            }
            int pixels = (int) Math.ceil(extent * device);
            sprite = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = sprite.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            sg.scale(device, device);
            paint(sg, type, extent / 2, extent / 2, drawnSize, emphasised);
            sg.dispose();
            SPRITES.put(key, sprite);
        }
        blit(g, sprite, cx - extent / 2, cy - extent / 2);
    }

    /**
     * Draws a sprite made at the screen's own resolution with its top left at {@code (x, y)}, on whole screen
     * pixels: a plain copy. Placed on fractions of a pixel, Java2D resamples the image for every draw, which made
     * the map slow with hundreds of icons and names; half a pixel off is not seen.
     */
    static void blit(Graphics2D g, BufferedImage sprite, double x, double y)
    {
        AffineTransform transform = g.getTransform();
        if ((transform.getType() & (AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION
            | AffineTransform.TYPE_GENERAL_TRANSFORM | AffineTransform.TYPE_FLIP)) != 0)
        {
            double device = Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
            AffineTransform place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(1 / device, 1 / device);
            g.drawImage(sprite, place, null);
            return;
        }
        long sx = Math.round(transform.getScaleX() * x + transform.getTranslateX());
        long sy = Math.round(transform.getScaleY() * y + transform.getTranslateY());
        g.setTransform(AffineTransform.getTranslateInstance(sx, sy));
        g.drawImage(sprite, 0, 0, null);
        g.setTransform(transform);
    }

    /** A white dot with a coloured rim, marking where a line ends; from a cached image. */
    static void paintDot(Graphics2D g, double cx, double cy, Color rim)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
        long key = (1L << 62) | ((long) (rim.getRGB() & 0xffffffffL) << 8) | (int) Math.round(device * 10);
        double extent = 13;
        BufferedImage sprite = SPRITES.get(key);
        if (sprite == null)
        {
            int pixels = (int) Math.ceil(extent * device);
            sprite = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = sprite.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.scale(device, device);
            double r = 4.5;
            double c = extent / 2;
            sg.setColor(Color.WHITE);
            sg.fill(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
            sg.setColor(rim);
            sg.setStroke(new BasicStroke(2f));
            sg.draw(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
            sg.dispose();
            SPRITES.put(key, sprite);
        }
        blit(g, sprite, cx - extent / 2, cy - extent / 2);
    }

    /** Forgets drawn icons, such as when the game's own icons became available. */
    static void clearCache()
    {
        SPRITES.clear();
    }

    /**
     * The icon for a type centered on {@code (cx, cy)}: the game's own map icon where it has one for this kind of
     * place (drawn at {@code size}, like the icons in the map tiles), else a round badge in the type's color with a
     * white symbol.
     */
    static void paint(Graphics2D g, PoiType type, double cx, double cy, double size, boolean emphasised)
    {
        BufferedImage game = GameIconSprites.get(type);
        if (game != null)
        {
            paintGameIcon(g, game, cx, cy, size, emphasised);
            return;
        }
        double r = size / 2;
        Ellipse2D badge = new Ellipse2D.Double(cx - r, cy - r, size, size);
        if (emphasised)
        {
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke((float) (size * 0.16)));
            g.draw(new Ellipse2D.Double(cx - r - size * 0.14, cy - r - size * 0.14, size * 1.28, size * 1.28));
        }
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(cx - r + size * 0.06, cy - r + size * 0.1, size, size));
        g.setColor(type.color);
        g.fill(badge);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((float) Math.max(1, size * 0.08)));
        g.draw(badge);
        g.setColor(GLYPH);
        g.setStroke(new BasicStroke((float) Math.max(1, size * 0.09), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        glyph(g, type, cx, cy, r * 0.62);
    }

    private static void paintGameIcon(Graphics2D g, BufferedImage icon, double cx, double cy, double size, boolean emphasised)
    {
        double scale = size / Math.max(icon.getWidth(), icon.getHeight());
        double w = icon.getWidth() * scale;
        double h = icon.getHeight() * scale;
        if (emphasised)
        {
            double r = Math.max(w, h) / 2 + size * 0.18;
            g.setColor(new Color(255, 255, 255, 210));
            g.setStroke(new BasicStroke((float) Math.max(1.5, size * 0.12)));
            g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        }
        Graphics2D copy = (Graphics2D) g.create();
        // Pixel art: crisp when enlarged, smooth when made smaller.
        copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1
            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        copy.drawImage(icon, new java.awt.geom.AffineTransform(scale, 0, 0, scale, cx - w / 2, cy - h / 2), null);
        copy.dispose();
    }

    private static void glyph(Graphics2D g, PoiType type, double x, double y, double s)
    {
        switch (type)
        {
            case TELEPORT:
                g.fill(star(x, y, s, s * 0.32, 4));
                break;
            case FAIRY_RING:
                for (int i = 0; i < 6; i++)
                {
                    double a = Math.PI * 2 * i / 6;
                    double d = s * 0.28;
                    g.fill(new Ellipse2D.Double(x + Math.cos(a) * s * 0.75 - d, y + Math.sin(a) * s * 0.75 - d, d * 2, d * 2));
                }
                break;
            case SPIRIT_TREE:
            {
                Path2D tree = new Path2D.Double();
                tree.moveTo(x, y - s);
                tree.lineTo(x + s * 0.8, y + s * 0.35);
                tree.lineTo(x - s * 0.8, y + s * 0.35);
                tree.closePath();
                g.fill(tree);
                g.fill(new Rectangle2D.Double(x - s * 0.15, y + s * 0.3, s * 0.3, s * 0.7));
                break;
            }
            case GNOME_GLIDER:
            {
                Path2D glider = new Path2D.Double();
                glider.moveTo(x, y - s * 0.7);
                glider.lineTo(x + s, y + s * 0.5);
                glider.lineTo(x, y + s * 0.15);
                glider.lineTo(x - s, y + s * 0.5);
                glider.closePath();
                g.fill(glider);
                break;
            }
            case BALLOON:
                g.fill(new Ellipse2D.Double(x - s * 0.7, y - s, s * 1.4, s * 1.4));
                g.draw(new Line2D.Double(x - s * 0.4, y + s * 0.2, x - s * 0.25, y + s * 0.65));
                g.draw(new Line2D.Double(x + s * 0.4, y + s * 0.2, x + s * 0.25, y + s * 0.65));
                g.fill(new Rectangle2D.Double(x - s * 0.3, y + s * 0.6, s * 0.6, s * 0.4));
                break;
            case QUETZAL:
            {
                Path2D bird = new Path2D.Double();
                bird.moveTo(x - s, y - s * 0.2);
                bird.quadTo(x - s * 0.4, y - s * 0.5, x, y + s * 0.3);
                bird.quadTo(x + s * 0.4, y - s * 0.5, x + s, y - s * 0.2);
                g.draw(bird);
                break;
            }
            case MUSHTREE:
                g.fill(new Arc2D.Double(x - s, y - s * 0.9, s * 2, s * 1.6, 0, 180, Arc2D.CHORD));
                g.fill(new Rectangle2D.Double(x - s * 0.22, y - s * 0.1, s * 0.44, s * 1.05));
                break;
            case OBELISK:
            {
                Path2D obelisk = new Path2D.Double();
                obelisk.moveTo(x, y - s);
                obelisk.lineTo(x + s * 0.35, y - s * 0.6);
                obelisk.lineTo(x + s * 0.3, y + s);
                obelisk.lineTo(x - s * 0.3, y + s);
                obelisk.lineTo(x - s * 0.35, y - s * 0.6);
                obelisk.closePath();
                g.fill(obelisk);
                break;
            }
            case BOAT:
            case CHARTER:
            {
                Path2D hull = new Path2D.Double();
                hull.moveTo(x - s, y + s * 0.25);
                hull.lineTo(x + s, y + s * 0.25);
                hull.lineTo(x + s * 0.55, y + s * 0.8);
                hull.lineTo(x - s * 0.55, y + s * 0.8);
                hull.closePath();
                g.fill(hull);
                Path2D sail = new Path2D.Double();
                sail.moveTo(x - s * 0.05, y - s);
                sail.lineTo(x - s * 0.05, y + s * 0.1);
                sail.lineTo(x - s * 0.75, y + s * 0.1);
                sail.closePath();
                g.fill(sail);
                if (type == PoiType.CHARTER)
                {
                    Path2D second = new Path2D.Double();
                    second.moveTo(x + s * 0.1, y - s * 0.75);
                    second.lineTo(x + s * 0.1, y + s * 0.1);
                    second.lineTo(x + s * 0.7, y + s * 0.1);
                    second.closePath();
                    g.fill(second);
                }
                break;
            }
            case CANOE:
                g.fill(new Arc2D.Double(x - s, y - s * 0.7, s * 2, s * 1.4, 180, 180, Arc2D.CHORD));
                g.draw(new Line2D.Double(x + s * 0.2, y - s, x - s * 0.3, y + s * 0.4));
                break;
            case CARPET:
            {
                Path2D carpet = new Path2D.Double();
                carpet.moveTo(x - s, y - s * 0.3);
                carpet.quadTo(x - s * 0.5, y - s * 0.7, x, y - s * 0.3);
                carpet.quadTo(x + s * 0.5, y + s * 0.1, x + s, y - s * 0.3);
                carpet.lineTo(x + s, y + s * 0.35);
                carpet.quadTo(x + s * 0.5, y + s * 0.75, x, y + s * 0.35);
                carpet.quadTo(x - s * 0.5, y - s * 0.05, x - s, y + s * 0.35);
                carpet.closePath();
                g.fill(carpet);
                break;
            }
            case MINECART:
            {
                Path2D cart = new Path2D.Double();
                cart.moveTo(x - s, y - s * 0.55);
                cart.lineTo(x + s, y - s * 0.55);
                cart.lineTo(x + s * 0.7, y + s * 0.35);
                cart.lineTo(x - s * 0.7, y + s * 0.35);
                cart.closePath();
                g.fill(cart);
                double w = s * 0.26;
                g.fill(new Ellipse2D.Double(x - s * 0.5 - w, y + s * 0.5 - w, w * 2, w * 2));
                g.fill(new Ellipse2D.Double(x + s * 0.5 - w, y + s * 0.5 - w, w * 2, w * 2));
                break;
            }
            case PORTAL:
                g.draw(new Ellipse2D.Double(x - s * 0.6, y - s, s * 1.2, s * 2));
                g.draw(new Ellipse2D.Double(x - s * 0.25, y - s * 0.5, s * 0.5, s));
                break;
            case LEVER:
                g.fill(new RoundRectangle2D.Double(x - s * 0.7, y + s * 0.45, s * 1.4, s * 0.45, s * 0.2, s * 0.2));
                g.draw(new Line2D.Double(x, y + s * 0.5, x + s * 0.45, y - s * 0.6));
                g.fill(new Ellipse2D.Double(x + s * 0.25, y - s, s * 0.5, s * 0.5));
                break;
            case DUNGEON_ENTRANCE:
            {
                Path2D arch = new Path2D.Double();
                arch.moveTo(x - s * 0.85, y + s);
                arch.lineTo(x - s * 0.85, y - s * 0.1);
                arch.quadTo(x - s * 0.85, y - s, x, y - s);
                arch.quadTo(x + s * 0.85, y - s, x + s * 0.85, y - s * 0.1);
                arch.lineTo(x + s * 0.85, y + s);
                arch.closePath();
                g.fill(arch);
                g.setColor(OUTLINE);
                g.fill(arrow(x, y + s * 0.05, s * 0.55, true));
                break;
            }
            case MAP_EXIT:
            case MAP_LINK:
                g.fill(arrow(x, y, s, false));
                break;
            case BANK:
            {
                g.fill(new Rectangle2D.Double(x - s * 0.9, y - s * 0.35, s * 1.8, s * 1.2));
                Path2D lid = new Path2D.Double();
                lid.moveTo(x - s * 0.9, y - s * 0.45);
                lid.quadTo(x, y - s * 1.2, x + s * 0.9, y - s * 0.45);
                lid.closePath();
                g.fill(lid);
                g.setColor(OUTLINE);
                g.fill(new Rectangle2D.Double(x - s * 0.15, y - s * 0.2, s * 0.3, s * 0.45));
                break;
            }
            case ALTAR:
                g.setColor(OUTLINE);
                g.fill(new Rectangle2D.Double(x - s * 0.17, y - s, s * 0.34, s * 2));
                g.fill(new Rectangle2D.Double(x - s * 0.7, y - s * 0.45, s * 1.4, s * 0.34));
                break;
            case ANVIL:
            {
                Path2D anvil = new Path2D.Double();
                anvil.moveTo(x - s, y - s * 0.5);
                anvil.lineTo(x + s * 0.75, y - s * 0.5);
                anvil.quadTo(x + s * 0.75, y, x + s * 0.3, y);
                anvil.lineTo(x + s * 0.3, y + s * 0.3);
                anvil.lineTo(x + s * 0.65, y + s * 0.75);
                anvil.lineTo(x - s * 0.65, y + s * 0.75);
                anvil.lineTo(x - s * 0.3, y + s * 0.3);
                anvil.lineTo(x - s * 0.3, y);
                anvil.quadTo(x - s * 0.8, y - s * 0.05, x - s, y - s * 0.5);
                anvil.closePath();
                g.fill(anvil);
                break;
            }
            case TRANSPORT:
                g.draw(new Line2D.Double(x - s * 0.9, y - s * 0.3, x + s * 0.8, y - s * 0.3));
                g.fill(arrowHead(x + s, y - s * 0.3, s * 0.45, 1));
                g.draw(new Line2D.Double(x + s * 0.9, y + s * 0.4, x - s * 0.8, y + s * 0.4));
                g.fill(arrowHead(x - s, y + s * 0.4, s * 0.45, -1));
                break;
            case MOORING:
            {
                // An anchor.
                g.draw(new Line2D.Double(x, y - s * 0.6, x, y + s * 0.85));
                g.draw(new Ellipse2D.Double(x - s * 0.22, y - s, s * 0.44, s * 0.44));
                g.draw(new Line2D.Double(x - s * 0.45, y - s * 0.25, x + s * 0.45, y - s * 0.25));
                g.draw(new Arc2D.Double(x - s * 0.8, y - s * 0.3, s * 1.6, s * 1.15, 200, 140, Arc2D.OPEN));
                break;
            }
            case SALVAGE:
            {
                // A broken hull.
                Path2D hull = new Path2D.Double();
                hull.moveTo(x - s, y);
                hull.lineTo(x + s * 0.3, y);
                hull.lineTo(x + s * 0.05, y + s * 0.3);
                hull.lineTo(x + s * 0.4, y + s * 0.8);
                hull.lineTo(x - s * 0.6, y + s * 0.8);
                hull.closePath();
                g.fill(hull);
                g.draw(new Line2D.Double(x - s * 0.35, y, x - s * 0.1, y - s * 0.95));
                g.draw(new Line2D.Double(x + s * 0.55, y + s * 0.1, x + s * 0.9, y + s * 0.6));
                break;
            }
            case RUNECRAFT_ALTAR:
            {
                // A rune stone.
                Path2D rune = new Path2D.Double();
                rune.moveTo(x, y - s);
                rune.lineTo(x + s * 0.8, y - s * 0.2);
                rune.lineTo(x + s * 0.5, y + s * 0.9);
                rune.lineTo(x - s * 0.5, y + s * 0.9);
                rune.lineTo(x - s * 0.8, y - s * 0.2);
                rune.closePath();
                g.fill(rune);
                g.setColor(OUTLINE);
                g.fill(new Ellipse2D.Double(x - s * 0.25, y - s * 0.15, s * 0.5, s * 0.5));
                break;
            }
            case AGILITY_COURSE:
            case AGILITY_SHORTCUT:
            {
                // A running figure's stride.
                g.fill(new Ellipse2D.Double(x + s * 0.1, y - s, s * 0.45, s * 0.45));
                Path2D body = new Path2D.Double();
                body.moveTo(x + s * 0.2, y - s * 0.45);
                body.lineTo(x - s * 0.1, y + s * 0.2);
                body.lineTo(x + s * 0.5, y + s * 0.95);
                body.moveTo(x - s * 0.1, y + s * 0.2);
                body.lineTo(x - s * 0.7, y + s * 0.8);
                body.moveTo(x - s * 0.7, y - s * 0.3);
                body.lineTo(x + s * 0.1, y - s * 0.3);
                body.lineTo(x + s * 0.7, y - s * 0.05);
                g.draw(body);
                if (type == PoiType.AGILITY_SHORTCUT)
                {
                    g.setColor(OUTLINE);
                    g.fill(arrowHead(x + s * 0.95, y + s * 0.1, s * 0.35, 1));
                }
                break;
            }
            case FARMING_PATCH:
            {
                // A sprout.
                g.draw(new Line2D.Double(x, y + s * 0.9, x, y - s * 0.1));
                Path2D leaves = new Path2D.Double();
                leaves.moveTo(x, y - s * 0.1);
                leaves.quadTo(x - s * 0.9, y - s * 0.2, x - s * 0.8, y - s * 0.9);
                leaves.quadTo(x - s * 0.1, y - s * 0.8, x, y - s * 0.1);
                leaves.moveTo(x, y + s * 0.2);
                leaves.quadTo(x + s * 0.9, y + s * 0.1, x + s * 0.8, y - s * 0.6);
                leaves.quadTo(x + s * 0.1, y - s * 0.5, x, y + s * 0.2);
                g.fill(leaves);
                break;
            }
            case MINIGAME:
            {
                // Crossed swords.
                g.draw(new Line2D.Double(x - s * 0.8, y + s * 0.8, x + s * 0.8, y - s * 0.8));
                g.draw(new Line2D.Double(x + s * 0.8, y + s * 0.8, x - s * 0.8, y - s * 0.8));
                g.draw(new Line2D.Double(x - s * 0.75, y + s * 0.35, x - s * 0.35, y + s * 0.75));
                g.draw(new Line2D.Double(x + s * 0.75, y + s * 0.35, x + s * 0.35, y + s * 0.75));
                break;
            }
            case QUEST_START:
                g.fill(new Rectangle2D.Double(x - s * 0.17, y - s * 0.9, s * 0.34, s * 1.15));
                g.fill(new Ellipse2D.Double(x - s * 0.2, y + s * 0.45, s * 0.4, s * 0.4));
                break;
            case SHOP:
                g.fill(new Ellipse2D.Double(x - s * 0.7, y - s * 0.3, s * 1.4, s * 1.1));
                g.fill(new Rectangle2D.Double(x - s * 0.3, y - s * 0.8, s * 0.6, s * 0.6));
                break;
            default:
                g.fill(new Ellipse2D.Double(x - s * 0.4, y - s * 0.4, s * 0.8, s * 0.8));
        }
    }

    private static Path2D star(double x, double y, double outer, double inner, int points)
    {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < points * 2; i++)
        {
            double a = Math.PI * i / points - Math.PI / 2;
            double r = i % 2 == 0 ? outer : inner;
            double px = x + Math.cos(a) * r;
            double py = y + Math.sin(a) * r;
            if (i == 0)
            {
                path.moveTo(px, py);
            }
            else
            {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    /** A small triangle pointing right ({@code dir} 1) or left (-1). */
    private static Path2D arrowHead(double x, double y, double s, int dir)
    {
        Path2D head = new Path2D.Double();
        head.moveTo(x, y);
        head.lineTo(x - dir * s, y - s * 0.6);
        head.lineTo(x - dir * s, y + s * 0.6);
        head.closePath();
        return head;
    }

    private static GeneralPath arrow(double x, double y, double s, boolean down)
    {
        double d = down ? 1 : -1;
        GeneralPath path = new GeneralPath();
        path.moveTo(x, y + d * s);
        path.lineTo(x + s * 0.8, y);
        path.lineTo(x + s * 0.3, y);
        path.lineTo(x + s * 0.3, y - d * s);
        path.lineTo(x - s * 0.3, y - d * s);
        path.lineTo(x - s * 0.3, y);
        path.lineTo(x - s * 0.8, y);
        path.closePath();
        return path;
    }

    /** A small image of an icon, for buttons and lists. */
    static BufferedImage image(PoiType type, int size)
    {
        BufferedImage image = new BufferedImage(size + 2, size + 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        paint(g, type, (size + 2) / 2.0 - 0.5, (size + 2) / 2.0 - 1, size - 1, false);
        g.dispose();
        return image;
    }

    /** A map pin, for searching places. */
    static BufferedImage pinIcon()
    {
        BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        GeneralPath pin = new GeneralPath();
        pin.moveTo(7, 13.5);
        pin.curveTo(3, 8.5, 2.2, 7, 2.2, 5.2);
        pin.curveTo(2.2, 2.5, 4.4, 0.8, 7, 0.8);
        pin.curveTo(9.6, 0.8, 11.8, 2.5, 11.8, 5.2);
        pin.curveTo(11.8, 7, 11, 8.5, 7, 13.5);
        pin.closePath();
        g.setColor(new Color(220, 220, 220));
        g.fill(pin);
        g.setColor(new Color(40, 40, 40));
        g.fill(new Ellipse2D.Double(5.2, 3.4, 3.6, 3.6));
        g.dispose();
        return image;
    }

    /** Crosshairs, for following the player. */
    static BufferedImage followIcon()
    {
        BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Ellipse2D.Double(2.5, 2.5, 9, 9));
        g.draw(new Line2D.Double(7, 0.5, 7, 4));
        g.draw(new Line2D.Double(7, 10, 7, 13.5));
        g.draw(new Line2D.Double(0.5, 7, 4, 7));
        g.draw(new Line2D.Double(10, 7, 13.5, 7));
        g.setColor(new Color(255, 214, 64));
        g.fill(new Ellipse2D.Double(5.5, 5.5, 3, 3));
        g.dispose();
        return image;
    }

    /** A window with an arrow leaving it. */
    static BufferedImage popOutIcon()
    {
        BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D frame = new Path2D.Double();
        frame.moveTo(6, 2.5);
        frame.lineTo(2.5, 2.5);
        frame.lineTo(2.5, 11.5);
        frame.lineTo(11.5, 11.5);
        frame.lineTo(11.5, 8);
        g.draw(frame);
        g.draw(new Line2D.Double(7, 7, 12, 2));
        g.draw(new Line2D.Double(8.5, 2, 12, 2));
        g.draw(new Line2D.Double(12, 2, 12, 5.5));
        g.dispose();
        return image;
    }

    /** The sidebar button: a folded map. */
    static BufferedImage navigationIcon()
    {
        int size = 16;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color[] panels = {new Color(0x6FA85A), new Color(0x4F8C44), new Color(0x6FA85A)};
        for (int i = 0; i < 3; i++)
        {
            Path2D panel = new Path2D.Double();
            double x0 = 1 + i * 14 / 3.0;
            double x1 = 1 + (i + 1) * 14 / 3.0;
            double shift = i % 2 == 0 ? 0 : 1.5;
            panel.moveTo(x0, 2.5 + shift);
            panel.lineTo(x1, 2.5 + (1.5 - shift));
            panel.lineTo(x1, 13.5 + (1.5 - shift) - 1.5);
            panel.lineTo(x0, 13.5 + shift - 1.5);
            panel.closePath();
            g.setColor(panels[i]);
            g.fill(panel);
        }
        g.setColor(new Color(0x3F6FD8));
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(3, 10, 7, 7));
        g.draw(new Line2D.Double(7, 7, 10, 9));
        g.setColor(new Color(0xE04040));
        g.fill(new Ellipse2D.Double(10, 4, 4, 4));
        g.fill(new Rectangle2D.Double(11.4, 7, 1.2, 3));
        g.dispose();
        return image;
    }
}
