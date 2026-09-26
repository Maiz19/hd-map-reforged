package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** "Rejoin party" over the game after login, with a cross to dismiss; clicks on it are consumed, others go to the game. */
final class RejoinButton extends Overlay
{
    private static final Color FILL = new Color(20, 21, 25, 215);
    private static final Color EDGE = new Color(255, 255, 255, 45);
    private static final Color HOVER = new Color(255, 255, 255, 30);
    private static final Color JOIN_HOVER = new Color(90, 160, 90, 110);
    private static final Color ACCENT = new Color(120, 210, 130);
    private static final Color TEXT = new Color(225, 225, 225);
    private static final Font FONT = FontManager.getRunescapeSmallFont();

    private final Runnable rejoin;
    private final Runnable dismiss;
    private volatile boolean showing;
    /** Relative to the overlay; empty before the first frame. */
    private volatile Rectangle joinArea = new Rectangle();
    private volatile Rectangle closeArea = new Rectangle();
    private volatile Point mouse;

    final MouseAdapter clicks = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            Point at = at(e);
            if (at != null && (joinArea.contains(at) || closeArea.contains(at)))
            {
                e.consume();
                (joinArea.contains(at) ? rejoin : dismiss).run();
            }
            return e;
        }

        @Override
        public MouseEvent mouseReleased(MouseEvent e)
        {
            return onButton(e);
        }

        @Override
        public MouseEvent mouseClicked(MouseEvent e)
        {
            return onButton(e);
        }

        /** The rest of a left click on the button is ours too, so the game never sees half of it. */
        private MouseEvent onButton(MouseEvent e)
        {
            Point at = at(e);
            if (at != null && (joinArea.contains(at) || closeArea.contains(at)))
            {
                e.consume();
            }
            return e;
        }

        /** A left click's point on the overlay, or null. */
        private Point at(MouseEvent e)
        {
            return showing && SwingUtilities.isLeftMouseButton(e) ? local(e.getPoint()) : null;
        }

        @Override
        public MouseEvent mouseMoved(MouseEvent e)
        {
            mouse = e.getPoint();
            return e;
        }
    };

    RejoinButton(Runnable rejoin, Runnable dismiss)
    {
        this.rejoin = rejoin;
        this.dismiss = dismiss;
        setPosition(OverlayPosition.BOTTOM_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    void setShowing(boolean show)
    {
        showing = show;
    }

    /** Null before the button was drawn. */
    private Point local(Point canvas)
    {
        Rectangle bounds = getBounds();
        if (bounds == null || bounds.isEmpty())
        {
            return null;
        }
        return new Point(canvas.x - bounds.x, canvas.y - bounds.y);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!showing)
        {
            return null;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(FONT);
        FontMetrics metrics = g.getFontMetrics();
        String text = "Rejoin party";
        int height = 18;
        int markWidth = 14;
        int joinWidth = 6 + markWidth + metrics.stringWidth(text) + 6;
        int closeWidth = 15;
        int width = joinWidth + 1 + closeWidth;
        Point at = mouse == null ? null : local(mouse);
        Rectangle join = new Rectangle(0, 0, joinWidth, height);
        Rectangle close = new Rectangle(joinWidth + 1, 0, closeWidth, height);
        boolean onJoin = at != null && join.contains(at);
        boolean onClose = at != null && close.contains(at);
        RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, width, height, height, height);
        g.setColor(FILL);
        g.fill(shape);
        if (onJoin || onClose)
        {
            Shape clip = g.getClip();
            g.clip(shape);
            g.setColor(onJoin ? JOIN_HOVER : HOVER);
            g.fill(onJoin ? join : close);
            g.setClip(clip);
        }
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, width - 1, height - 1, height - 1, height - 1));
        g.drawLine(joinWidth, 4, joinWidth, height - 4);
        // Two heads and shoulders.
        int mx = 7;
        int my = height / 2;
        g.setColor(ACCENT);
        g.fillOval(mx + 5, my - 5, 5, 5);
        g.fillArc(mx + 3, my + 1, 9, 8, 0, 180);
        g.setColor(ACCENT.darker());
        g.fillOval(mx, my - 4, 4, 4);
        g.fillArc(mx - 2, my + 1, 8, 7, 0, 180);
        int baseline = (height + metrics.getAscent()) / 2 - 1;
        g.setColor(onJoin ? Color.WHITE : TEXT);
        g.drawString(text, 6 + markWidth, baseline);
        g.setColor(onClose ? Color.WHITE : new Color(160, 160, 165));
        g.drawString("\u00D7", close.x + (closeWidth - metrics.stringWidth("\u00D7")) / 2 - 1, baseline);
        joinArea = join;
        closeArea = close;
        return new Dimension(width + 1, height + 1);
    }
}
