package com.hdmapreforged;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Timer;

/** Shows the progress of "Download the whole map" on the map. Swing thread only. */
final class MapDownloadControl implements MapView.Overlay
{
    private static final int MESSAGE_MS = 8000;
    private static final Color PANEL = new Color(22, 24, 28, 225);
    private static final Color BAR = new Color(90, 180, 110);
    private static final Color TRACK = new Color(255, 255, 255, 40);
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    private final MapDownloader downloader;
    private String shownMessage;
    private Timer messageTimer;

    private final Runnable repaint;

    MapDownloadControl(MapDownloader downloader, Runnable repaint)
    {
        this.downloader = downloader;
        this.repaint = repaint;
    }

    /** Stops the timer so nothing runs after shutdown. */
    void dispose()
    {
        if (messageTimer != null)
        {
            messageTimer.stop();
            messageTimer = null;
        }
        shownMessage = null;
    }

    @Override
    public void paint(Graphics2D g, MapView.Projection projection)
    {
        String status = downloader.status();
        if (status == null)
        {
            return;
        }
        if (downloader.isRunning())
        {
            shownMessage = null;
        }
        else if (!status.equals(shownMessage))
        {
            // A finished message stays for a few seconds.
            shownMessage = status;
            if (messageTimer != null)
            {
                messageTimer.stop();
            }
            messageTimer = new Timer(MESSAGE_MS, e -> {
                downloader.clearMessage();
                shownMessage = null;
                repaint.run();
            });
            messageTimer.setRepeats(false);
            messageTimer.start();
        }
        g.setFont(FONT);
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(status) + 24 > projection.width() - 16 && downloader.isRunning())
        {
            // Narrow sidebar: just the percentage.
            status = "Downloading map " + downloader.done() * 100 / Math.max(1, downloader.total()) + "%";
        }
        int width = Math.min(projection.width() - 16, metrics.stringWidth(status) + 24);
        g.clip(new java.awt.Rectangle(0, 0, projection.width(), projection.height()));
        int height = downloader.isRunning() ? 34 : 26;
        double x = (projection.width() - width) / 2.0;
        double y = 10;
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 10, 10));
        g.setColor(Color.WHITE);
        java.awt.Shape before = g.getClip();
        g.clip(new java.awt.Rectangle((int) x + 6, (int) y, width - 12, height));
        g.drawString(status, (float) (x + 12), (float) (y + 6 + metrics.getAscent()));
        g.setClip(before);
        if (downloader.isRunning())
        {
            double fraction = downloader.done() / (double) Math.max(1, downloader.total());
            double barWidth = width - 24;
            g.setColor(TRACK);
            g.fill(new RoundRectangle2D.Double(x + 12, y + height - 9, barWidth, 4, 4, 4));
            g.setColor(BAR);
            g.fill(new RoundRectangle2D.Double(x + 12, y + height - 9, barWidth * fraction, 4, 4, 4));
        }
    }
}
