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
import java.util.function.Consumer;

/** Vector map icons, so they stay sharp at every zoom level and interface scale. */
final class PoiIcons
{
    private static final Color OUTLINE = new Color(20, 18, 16);
    private static final Color GLYPH = new Color(255, 255, 255, 240);

    private PoiIcons()
    {
    }

    /** Rendered badges by type, size and screen scale. */
    private static final Map<Long, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    private static final int MAX_SPRITES = 4000;

    /** {@link #paint} from a cached image: painting hundreds of badges shape by shape was most of a frame's cost. */
    static void paintCached(Graphics2D g, PoiType type, double cx, double cy, double size, boolean emphasised)
    {
        double device = device(g.getTransform());
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
            sprite = sprite(extent, device, sg -> {
                sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                paint(sg, type, extent / 2, extent / 2, drawnSize, emphasised);
            });
            SPRITES.put(key, sprite);
        }
        blit(g, sprite, cx - extent / 2, cy - extent / 2);
    }

    /** Draws a screen-resolution sprite on whole screen pixels: at fractions Java2D resamples it each draw (slow). */
    static void blit(Graphics2D g, BufferedImage sprite, double x, double y)
    {
        AffineTransform transform = g.getTransform();
        if ((transform.getType() & (AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION
            | AffineTransform.TYPE_GENERAL_TRANSFORM | AffineTransform.TYPE_FLIP)) != 0)
        {
            double device = device(transform);
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
        double device = device(g.getTransform());
        long key = (1L << 62) | ((long) (rim.getRGB() & 0xffffffffL) << 8) | (int) Math.round(device * 10);
        double extent = 13;
        BufferedImage sprite = SPRITES.get(key);
        if (sprite == null)
        {
            sprite = sprite(extent, device, sg -> {
                Ellipse2D dot = new Ellipse2D.Double(extent / 2 - 4.5, extent / 2 - 4.5, 9, 9);
                sg.setColor(Color.WHITE);
                sg.fill(dot);
                sg.setColor(rim);
                sg.setStroke(new BasicStroke(2f));
                sg.draw(dot);
            });
            SPRITES.put(key, sprite);
        }
        blit(g, sprite, cx - extent / 2, cy - extent / 2);
    }

    private static double device(AffineTransform transform)
    {
        return Math.max(1, Math.min(4, Math.abs(transform.getScaleX())));
    }

    private static BufferedImage sprite(double extent, double device, Consumer<Graphics2D> draw)
    {
        int pixels = (int) Math.ceil(extent * device);
        BufferedImage sprite = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sprite.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.scale(device, device);
        draw.accept(sg);
        sg.dispose();
        return sprite;
    }

    static void clearCache()
    {
        SPRITES.clear();
    }

    /** The game's own map icon for the type if it has one, else a round badge in the type's color with a glyph. */
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
        copy.drawImage(icon, new AffineTransform(scale, 0, 0, scale, cx - w / 2, cy - h / 2), null);
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
                g.fill(poly(x, y, s, 0, -1, 0.8, 0.35, -0.8, 0.35));
                g.fill(rect(x, y, s, -0.15, 0.3, 0.3, 0.7));
                break;
            case GNOME_GLIDER:
                g.fill(poly(x, y, s, 0, -0.7, 1, 0.5, 0, 0.15, -1, 0.5));
                break;
            case BALLOON:
                g.fill(oval(x, y, s, -0.7, -1, 1.4, 1.4));
                line(g, x, y, s, -0.4, 0.2, -0.25, 0.65);
                line(g, x, y, s, 0.4, 0.2, 0.25, 0.65);
                g.fill(rect(x, y, s, -0.3, 0.6, 0.6, 0.4));
                break;
            case QUETZAL:
                g.draw(new Pen(x, y, s).m(-1, -0.2).q(-0.4, -0.5, 0, 0.3).q(0.4, -0.5, 1, -0.2));
                break;
            case MUSHTREE:
                g.fill(new Arc2D.Double(x - s, y - s * 0.9, s * 2, s * 1.6, 0, 180, Arc2D.CHORD));
                g.fill(rect(x, y, s, -0.22, -0.1, 0.44, 1.05));
                break;
            case OBELISK:
                g.fill(poly(x, y, s, 0, -1, 0.35, -0.6, 0.3, 1, -0.3, 1, -0.35, -0.6));
                break;
            case BOAT:
            case CHARTER:
                g.fill(poly(x, y, s, -1, 0.25, 1, 0.25, 0.55, 0.8, -0.55, 0.8));
                g.fill(poly(x, y, s, -0.05, -1, -0.05, 0.1, -0.75, 0.1));
                if (type == PoiType.CHARTER)
                {
                    g.fill(poly(x, y, s, 0.1, -0.75, 0.1, 0.1, 0.7, 0.1));
                }
                break;
            case CANOE:
                g.fill(new Arc2D.Double(x - s, y - s * 0.7, s * 2, s * 1.4, 180, 180, Arc2D.CHORD));
                line(g, x, y, s, 0.2, -1, -0.3, 0.4);
                break;
            case CARPET:
                g.fill(new Pen(x, y, s).m(-1, -0.3).q(-0.5, -0.7, 0, -0.3).q(0.5, 0.1, 1, -0.3).l(1, 0.35)
                    .q(0.5, 0.75, 0, 0.35).q(-0.5, -0.05, -1, 0.35).z());
                break;
            case MINECART:
            {
                g.fill(poly(x, y, s, -1, -0.55, 1, -0.55, 0.7, 0.35, -0.7, 0.35));
                double w = s * 0.26;
                g.fill(new Ellipse2D.Double(x - s * 0.5 - w, y + s * 0.5 - w, w * 2, w * 2));
                g.fill(new Ellipse2D.Double(x + s * 0.5 - w, y + s * 0.5 - w, w * 2, w * 2));
                break;
            }
            case PORTAL:
                g.draw(oval(x, y, s, -0.6, -1, 1.2, 2));
                g.draw(oval(x, y, s, -0.25, -0.5, 0.5, 1));
                break;
            case LEVER:
                g.fill(new RoundRectangle2D.Double(x - s * 0.7, y + s * 0.45, s * 1.4, s * 0.45, s * 0.2, s * 0.2));
                line(g, x, y, s, 0, 0.5, 0.45, -0.6);
                g.fill(oval(x, y, s, 0.25, -1, 0.5, 0.5));
                break;
            case DUNGEON_ENTRANCE:
                g.fill(new Pen(x, y, s).m(-0.85, 1).l(-0.85, -0.1).q(-0.85, -1, 0, -1).q(0.85, -1, 0.85, -0.1).l(0.85, 1)
                    .z());
                g.setColor(OUTLINE);
                g.fill(arrow(x, y + s * 0.05, s * 0.55, true));
                break;
            case MAP_EXIT:
            case MAP_LINK:
                g.fill(arrow(x, y, s, false));
                break;
            case BANK:
                g.fill(rect(x, y, s, -0.9, -0.35, 1.8, 1.2));
                g.fill(new Pen(x, y, s).m(-0.9, -0.45).q(0, -1.2, 0.9, -0.45).z());
                g.setColor(OUTLINE);
                g.fill(rect(x, y, s, -0.15, -0.2, 0.3, 0.45));
                break;
            case ALTAR:
                g.setColor(OUTLINE);
                g.fill(rect(x, y, s, -0.17, -1, 0.34, 2));
                g.fill(rect(x, y, s, -0.7, -0.45, 1.4, 0.34));
                break;
            case ANVIL:
                g.fill(new Pen(x, y, s).m(-1, -0.5).l(0.75, -0.5).q(0.75, 0, 0.3, 0).l(0.3, 0.3).l(0.65, 0.75)
                    .l(-0.65, 0.75).l(-0.3, 0.3).l(-0.3, 0).q(-0.8, -0.05, -1, -0.5).z());
                break;
            case TRANSPORT:
                line(g, x, y, s, -0.9, -0.3, 0.8, -0.3);
                g.fill(arrowHead(x + s, y - s * 0.3, s * 0.45, 1));
                line(g, x, y, s, 0.9, 0.4, -0.8, 0.4);
                g.fill(arrowHead(x - s, y + s * 0.4, s * 0.45, -1));
                break;
            case MOORING:
                // An anchor.
                line(g, x, y, s, 0, -0.6, 0, 0.85);
                g.draw(oval(x, y, s, -0.22, -1, 0.44, 0.44));
                line(g, x, y, s, -0.45, -0.25, 0.45, -0.25);
                g.draw(new Arc2D.Double(x - s * 0.8, y - s * 0.3, s * 1.6, s * 1.15, 200, 140, Arc2D.OPEN));
                break;
            case SALVAGE:
                // A broken hull.
                g.fill(poly(x, y, s, -1, 0, 0.3, 0, 0.05, 0.3, 0.4, 0.8, -0.6, 0.8));
                line(g, x, y, s, -0.35, 0, -0.1, -0.95);
                line(g, x, y, s, 0.55, 0.1, 0.9, 0.6);
                break;
            case RUNECRAFT_ALTAR:
                // A rune stone.
                g.fill(poly(x, y, s, 0, -1, 0.8, -0.2, 0.5, 0.9, -0.5, 0.9, -0.8, -0.2));
                g.setColor(OUTLINE);
                g.fill(oval(x, y, s, -0.25, -0.15, 0.5, 0.5));
                break;
            case AGILITY_COURSE:
            case AGILITY_SHORTCUT:
                // A running figure's stride.
                g.fill(oval(x, y, s, 0.1, -1, 0.45, 0.45));
                g.draw(new Pen(x, y, s).m(0.2, -0.45).l(-0.1, 0.2).l(0.5, 0.95).m(-0.1, 0.2).l(-0.7, 0.8)
                    .m(-0.7, -0.3).l(0.1, -0.3).l(0.7, -0.05));
                if (type == PoiType.AGILITY_SHORTCUT)
                {
                    g.setColor(OUTLINE);
                    g.fill(arrowHead(x + s * 0.95, y + s * 0.1, s * 0.35, 1));
                }
                break;
            case FARMING_PATCH:
                // A sprout.
                line(g, x, y, s, 0, 0.9, 0, -0.1);
                g.fill(new Pen(x, y, s).m(0, -0.1).q(-0.9, -0.2, -0.8, -0.9).q(-0.1, -0.8, 0, -0.1).m(0, 0.2)
                    .q(0.9, 0.1, 0.8, -0.6).q(0.1, -0.5, 0, 0.2));
                break;
            case MINIGAME:
                // Crossed swords.
                line(g, x, y, s, -0.8, 0.8, 0.8, -0.8);
                line(g, x, y, s, 0.8, 0.8, -0.8, -0.8);
                line(g, x, y, s, -0.75, 0.35, -0.35, 0.75);
                line(g, x, y, s, 0.75, 0.35, 0.35, 0.75);
                break;
            case QUEST_START:
                g.fill(rect(x, y, s, -0.17, -0.9, 0.34, 1.15));
                g.fill(oval(x, y, s, -0.2, 0.45, 0.4, 0.4));
                break;
            case SHOP:
                g.fill(oval(x, y, s, -0.7, -0.3, 1.4, 1.1));
                g.fill(rect(x, y, s, -0.3, -0.8, 0.6, 0.6));
                break;
            default:
                g.fill(oval(x, y, s, -0.4, -0.4, 0.8, 0.8));
        }
    }

    /** A closed polygon through {@code (x + s * dx, y + s * dy)} pairs. */
    private static Path2D poly(double x, double y, double s, double... d)
    {
        Pen pen = new Pen(x, y, s).m(d[0], d[1]);
        for (int i = 2; i < d.length; i += 2)
        {
            pen.l(d[i], d[i + 1]);
        }
        return pen.z();
    }

    /** A path in units of {@code s} around {@code (x, y)}. */
    private static final class Pen extends Path2D.Double
    {
        private final double x;
        private final double y;
        private final double s;

        Pen(double x, double y, double s)
        {
            this.x = x;
            this.y = y;
            this.s = s;
        }

        Pen m(double dx, double dy)
        {
            moveTo(x + s * dx, y + s * dy);
            return this;
        }

        Pen l(double dx, double dy)
        {
            lineTo(x + s * dx, y + s * dy);
            return this;
        }

        Pen q(double dx1, double dy1, double dx2, double dy2)
        {
            quadTo(x + s * dx1, y + s * dy1, x + s * dx2, y + s * dy2);
            return this;
        }

        Pen z()
        {
            closePath();
            return this;
        }
    }

    private static void line(Graphics2D g, double x, double y, double s, double x1, double y1, double x2, double y2)
    {
        g.draw(new Line2D.Double(x + s * x1, y + s * y1, x + s * x2, y + s * y2));
    }

    private static Ellipse2D oval(double x, double y, double s, double dx, double dy, double w, double h)
    {
        return new Ellipse2D.Double(x + s * dx, y + s * dy, s * w, s * h);
    }

    private static Rectangle2D rect(double x, double y, double s, double dx, double dy, double w, double h)
    {
        return new Rectangle2D.Double(x + s * dx, y + s * dy, s * w, s * h);
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
        return icon(size + 2, g -> {
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paint(g, type, (size + 2) / 2.0 - 0.5, (size + 2) / 2.0 - 1, size - 1, false);
        });
    }

    private static BufferedImage icon(int size, Consumer<Graphics2D> draw)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.accept(g);
        g.dispose();
        return image;
    }

    /** A map pin, for searching places. */
    static BufferedImage pinIcon()
    {
        return icon(14, g -> {
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
        });
    }

    /** Crosshairs, for following the player. */
    static BufferedImage followIcon()
    {
        return icon(14, g -> {
            g.setColor(new Color(220, 220, 220));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new Ellipse2D.Double(2.5, 2.5, 9, 9));
            g.draw(new Line2D.Double(7, 0.5, 7, 4));
            g.draw(new Line2D.Double(7, 10, 7, 13.5));
            g.draw(new Line2D.Double(0.5, 7, 4, 7));
            g.draw(new Line2D.Double(10, 7, 13.5, 7));
            g.setColor(new Color(255, 214, 64));
            g.fill(new Ellipse2D.Double(5.5, 5.5, 3, 3));
        });
    }

    /** A window with an arrow leaving it. */
    static BufferedImage popOutIcon()
    {
        return icon(14, g -> {
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
        });
    }

    /** The sidebar button: a folded map. */
    static BufferedImage navigationIcon()
    {
        return icon(16, g -> {
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
        });
    }
}
