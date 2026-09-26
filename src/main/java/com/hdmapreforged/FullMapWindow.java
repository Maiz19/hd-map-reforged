package com.hdmapreforged;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;

/**
 * The map over the game view, opened instead of the game's world map: a panel in the game window's layered pane
 * (setting <i>Map inside the game window</i>) or else a window of its own. It is moved by the slim grip strip along
 * its top (a double-click there fills the game view), resized by its edges and corners, and its place is remembered
 * relative to the game view. Not drawn in the game, because Stretched Mode enlarges everything drawn there and blurs
 * it; the map draws at the screen's own resolution. It takes the keyboard only while one of its text fields (the
 * search) is typed in, so keys otherwise keep going to the game; Escape and the map key close it while it has the
 * keyboard. It stays within the game view and follows it; the client window itself is never moved, resized or
 * focused. Swing thread only.
 */
final class FullMapWindow
{
    static final int GRIP_HEIGHT = 10;
    private static final Color GRIP_DOTS = new Color(255, 255, 255, 90);
    private static final int EDGE = 8;
    /** The strip along the bottom, thicker than the sides: easy to take hold of, with the corner grip. */
    private static final int BOTTOM = 12;
    private static final Color OUTLINE = new Color(220, 190, 110);
    private static final int OUTLINE_WIDTH = 2;
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;
    private static final Color FRAME = new Color(30, 32, 37);
    private static final Color FRAME_EDGE = new Color(255, 255, 255, 40);

    private final Client client;
    private final MapScreen screen;
    /** Saves the window's place, as written by {@link #encode}. */
    private final Consumer<String> saveBounds;
    private final java.util.function.Predicate<java.awt.event.KeyEvent> isMapKey;
    private JWindow window;
    /** The map inside the game's own window instead (above the game view), when that is chosen; or null. */
    private JPanel panel;
    /** While dragging over the game: the outline's four edges, and where the map goes once let go. */
    private JComponent[] outline;
    private Rectangle pending;
    /** Whether the map goes inside the game's window (true) or in a window of its own. */
    private final java.util.function.BooleanSupplier inGameWindow;
    private Canvas watchedCanvas;
    private Window watchedFrame;
    /** Place relative to the game view as fractions of its size, or null for the default. */
    private Rectangle2D relative;
    private boolean maximised;
    /** Whether the key and click watchers are added (see {@link #closeKeys}). */
    private boolean keysWatched;
    private final ComponentListener follower = new ComponentAdapter()
    {
        @Override
        public void componentMoved(ComponentEvent e)
        {
            place();
        }

        @Override
        public void componentResized(ComponentEvent e)
        {
            place();
        }

        @Override
        public void componentHidden(ComponentEvent e)
        {
            close();
        }
    };

    FullMapWindow(Client client, MapScreen screen, String savedBounds, Consumer<String> saveBounds,
        java.util.function.Predicate<java.awt.event.KeyEvent> isMapKey, java.util.function.BooleanSupplier inGameWindow)
    {
        this.inGameWindow = inGameWindow;
        this.client = client;
        this.screen = screen;
        this.saveBounds = saveBounds;
        this.isMapKey = isMapKey;
        screen.setWindowControls(new MapView.WindowControls()
        {
            @Override
            public void close()
            {
                FullMapWindow.this.close();
            }

            @Override
            public void toggleMaximised()
            {
                FullMapWindow.this.toggleMaximised();
            }

            @Override
            public boolean isMaximised()
            {
                return maximised;
            }
        });
        Object[] decoded = decode(savedBounds);
        if (decoded != null)
        {
            relative = (Rectangle2D) decoded[0];
            maximised = (Boolean) decoded[1];
        }
        screen.setScreenLayout(MapScreen.Layout.FULL);
    }

    boolean isOpen()
    {
        return panel != null ? panel.isVisible() : window != null && window.isVisible();
    }

    /** The map's place on screen. */
    private Rectangle hostBounds()
    {
        if (panel != null)
        {
            if (!panel.isShowing())
            {
                return panel.getBounds();
            }
            return new Rectangle(panel.getLocationOnScreen(), panel.getSize());
        }
        return window.getBounds();
    }

    /** Puts the map at a place on screen. */
    private void setHostBounds(Rectangle screenBounds)
    {
        if (panel != null)
        {
            java.awt.Container parent = panel.getParent();
            Point at = screenBounds.getLocation();
            SwingUtilities.convertPointFromScreen(at, parent);
            // The game's picture is a native window under the map, with a hole cut where the map is. Moving the map
            // cuts a new hole but does not fill the old one again; hiding the map does, so it is hidden, moved and
            // shown again, and everything under it laid out anew.
            boolean shown = panel.isVisible();
            java.awt.Component typing = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            panel.setVisible(false);
            panel.setBounds(at.x, at.y, screenBounds.width, screenBounds.height);
            panel.setVisible(shown);
            if (shown && typing != null && inside(typing))
            {
                typing.requestFocusInWindow();
            }
            parent.invalidate();
            parent.validate();
            parent.repaint();
            return;
        }
        window.setBounds(screenBounds);
        window.validate();
    }

    /** Whether a component is part of the map. */
    private boolean inside(java.awt.Component c)
    {
        return panel != null ? SwingUtilities.isDescendingFrom(c, panel)
            : window != null && SwingUtilities.getWindowAncestor(c) == window;
    }

    /** Whether the map has the keyboard (typing in its search field). */
    private boolean hasKeys()
    {
        if (panel != null)
        {
            java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return owner != null && SwingUtilities.isDescendingFrom(owner, panel);
        }
        return window != null && window.isFocused();
    }

    /**
     * Inside the game's window: a panel in its layered pane over the game view, so the desktop sees one window (no
     * taskbar or focus trouble). Java cuts the game view's native surface where the panel lies over it.
     */
    private void openInGameWindow(Canvas canvas)
    {
        if (window != null)
        {
            // Hidden first, which hands the keyboard back to the game if it was typing here.
            close();
            window.dispose();
            window = null;
        }
        javax.swing.JRootPane root = SwingUtilities.getRootPane(canvas);
        if (root == null)
        {
            return;
        }
        if (panel == null)
        {
            panel = frame();
            panel.setVisible(false);
            closeKeys();
        }
        if (panel.getParent() != root.getLayeredPane())
        {
            root.getLayeredPane().add(panel, javax.swing.JLayeredPane.PALETTE_LAYER);
        }
        watch(canvas);
        place();
        if (!panel.isVisible())
        {
            panel.setVisible(true);
            panel.revalidate();
            screen.opened();
        }
    }

    void toggle()
    {
        if (isOpen())
        {
            close();
        }
        else
        {
            open();
        }
    }

    void open()
    {
        Canvas canvas = client.getCanvas();
        if (canvas == null || !canvas.isShowing())
        {
            return;
        }
        if (inGameWindow.getAsBoolean())
        {
            openInGameWindow(canvas);
            return;
        }
        if (panel != null)
        {
            // Changed to a window of its own.
            if (panel.getParent() != null)
            {
                panel.getParent().remove(panel);
            }
            panel = null;
        }
        if (window == null)
        {
            window = new JWindow(SwingUtilities.getWindowAncestor(canvas));
            // A helper window of the game's, not a program of its own: the taskbar and alt-tab leave it out, and it
            // goes to the front and back with the game.
            window.setType(Window.Type.UTILITY);
            // It does not ask for the keyboard, so keys keep going to the game. Some window managers still hand it
            // the keyboard when it is clicked; then Escape and the map key must work here too.
            window.setAutoRequestFocus(false);
            // Never the keyboard's, so the desktop (KWin and others) keeps the game as the active window and its
            // taskbar button brings it back; only while a text field here is typed in (see typeHere).
            window.setFocusableWindowState(false);
            window.addWindowFocusListener(new java.awt.event.WindowAdapter()
            {
                @Override
                public void windowLostFocus(java.awt.event.WindowEvent e)
                {
                    // Done typing: not focusable again.
                    window.setFocusableWindowState(false);
                }
            });
            window.setContentPane(frame());
            closeKeys();
        }
        watch(canvas);
        place();
        if (!window.isVisible())
        {
            window.setVisible(true);
            // Some window managers place a new window themselves; put it back where it belongs.
            SwingUtilities.invokeLater(this::place);
            screen.opened();
        }
    }

    void close()
    {
        if (panel != null && panel.isVisible())
        {
            boolean hadKeys = hasKeys();
            panel.setVisible(false);
            java.awt.Container parent = panel.getParent();
            if (parent != null)
            {
                parent.repaint();
            }
            Canvas canvas = watchedCanvas;
            if (hadKeys && canvas != null && canvas.isShowing())
            {
                canvas.requestFocus();
            }
            return;
        }
        if (window != null && window.isVisible())
        {
            boolean hadKeys = window.isFocused();
            window.setVisible(false);
            window.setFocusableWindowState(false);
            Canvas canvas = watchedCanvas;
            if (hadKeys && canvas != null && canvas.isShowing())
            {
                // The keyboard goes back to the game it came from.
                canvas.requestFocus();
            }
        }
    }

    void toggleMaximised()
    {
        maximised = !maximised;
        save();
        place();
        screen.refresh();
    }

    /**
     * Escape, and the map key, close the map while this window has the keyboard. Added once however often the map's
     * host is made again (the focus manager keeps every copy added), taken away in {@link #dispose}.
     */
    private void closeKeys()
    {
        if (keysWatched)
        {
            return;
        }
        keysWatched = true;
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clicks, java.awt.AWTEvent.MOUSE_EVENT_MASK);
    }

    /** Sees keys only while this window has the keyboard; never takes them from the game. */
    private final java.awt.KeyEventDispatcher keys = e -> {
        if (!isOpen() || !hasKeys() || e.getID() != java.awt.event.KeyEvent.KEY_PRESSED)
        {
            return false;
        }
        java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean typing = owner instanceof javax.swing.text.JTextComponent;
        boolean menu = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
        // While typing in the search field or using a menu, keys are theirs: Escape closes the menu, letters type.
        if (menu || typing && (e.getKeyCode() != java.awt.event.KeyEvent.VK_ESCAPE
            || !((javax.swing.text.JTextComponent) owner).getText().isEmpty()))
        {
            return false;
        }
        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE || FullMapWindow.this.isMapKey.test(e))
        {
            close();
            return true;
        }
        return false;
    };

    /**
     * After a click on the map (not in the search field or a menu), the keyboard goes back to the game: the window
     * can take it only so the search field can be typed in.
     */
    private final java.awt.event.AWTEventListener clicks = event -> {
        if (event instanceof MouseEvent && event.getID() == MouseEvent.MOUSE_PRESSED && isOpen()
            && event.getSource() instanceof javax.swing.text.JTextComponent
            && inside((java.awt.Component) event.getSource()))
        {
            typeHere((javax.swing.text.JTextComponent) event.getSource());
            return;
        }
        if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_RELEASED || !isOpen() || !hasKeys())
        {
            return;
        }
        Object source = event.getSource();
        if (!(source instanceof java.awt.Component) || !inside((java.awt.Component) source)
            || source instanceof javax.swing.text.JTextComponent)
        {
            return;
        }
        SwingUtilities.invokeLater(this::giveKeysBack);
    };

    /** A click in a text field (the search, a route's name): the window takes the keyboard for typing there. */
    private void typeHere(javax.swing.text.JTextComponent field)
    {
        if (window != null && !window.getFocusableWindowState())
        {
            window.setFocusableWindowState(true);
        }
        SwingUtilities.invokeLater(field::requestFocus);
    }

    private void giveKeysBack()
    {
        Canvas canvas = watchedCanvas;
        if (isOpen() && hasKeys() && canvas != null && canvas.isShowing()
            && javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length == 0)
        {
            canvas.requestFocus();
            if (window != null)
            {
                window.setFocusableWindowState(false);
            }
        }
    }

    /** A slim strip to move by, and a thin frame to resize by, around the map. */
    private JPanel frame()
    {
        JPanel root = new JPanel(new BorderLayout())
        {
            @Override
            protected void paintComponent(Graphics graphics)
            {
                super.paintComponent(graphics);
                // A grip in the bottom right corner: drag it to size the map.
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(GRIP_DOTS);
                int right = getWidth() - 3;
                int bottom = getHeight() - 3;
                for (int row = 0; row < 3; row++)
                {
                    for (int col = 0; col <= row; col++)
                    {
                        g.fillOval(right - col * 4 - 2, bottom - (row - col) * 4 - 2, 2, 2);
                    }
                }
                g.dispose();
            }
        };
        root.setBackground(FRAME);
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(FRAME_EDGE),
            BorderFactory.createEmptyBorder(0, EDGE - 1, BOTTOM - 1, EDGE - 1)));
        root.add(new Grip(), BorderLayout.NORTH);
        root.add(screen, BorderLayout.CENTER);
        Resizer resizer = new Resizer(root);
        root.addMouseListener(resizer);
        root.addMouseMotionListener(resizer);
        return root;
    }

    /** Places the window from its remembered place, within the game view. */
    private void place()
    {
        Canvas canvas = watchedCanvas;
        if (window == null && panel == null || canvas == null)
        {
            return;
        }
        if (!canvas.isShowing())
        {
            close();
            return;
        }
        Rectangle view = new Rectangle(canvas.getLocationOnScreen(), canvas.getSize());
        Rectangle bounds = maximised ? view : fit(relative, view);
        if (!hostBounds().equals(bounds))
        {
            setHostBounds(bounds);
        }
    }

    /**
     * Screen bounds for a place given as fractions of the game view; null gives the default (most of the view,
     * centered). Always inside the view and at least the minimum size where the view allows.
     */
    static Rectangle fit(Rectangle2D relative, Rectangle view)
    {
        double rx = relative != null ? relative.getX() : 0.08;
        double ry = relative != null ? relative.getY() : 0.06;
        double rw = relative != null ? relative.getWidth() : 0.84;
        double rh = relative != null ? relative.getHeight() : 0.88;
        int width = (int) Math.round(rw * view.width);
        int height = (int) Math.round(rh * view.height);
        width = Math.min(view.width, Math.max(Math.min(MIN_WIDTH, view.width), width));
        height = Math.min(view.height, Math.max(Math.min(MIN_HEIGHT, view.height), height));
        int x = view.x + (int) Math.round(rx * view.width);
        int y = view.y + (int) Math.round(ry * view.height);
        x = Math.max(view.x, Math.min(view.x + view.width - width, x));
        y = Math.max(view.y, Math.min(view.y + view.height - height, y));
        return new Rectangle(x, y, width, height);
    }

    /**
     * Moves or sizes the map while dragging. Over the game (in its window) only an outline follows the mouse and the
     * map goes there once let go: moving it over the game's picture every step leaves black where it was, as the
     * game does not draw that part again quickly enough.
     */
    private void dragTo(Rectangle bounds)
    {
        if (panel == null || panel.getParent() == null)
        {
            setFromScreen(bounds);
            return;
        }
        pending = bounds;
        java.awt.Container parent = panel.getParent();
        if (outline == null)
        {
            outline = new JComponent[4];
            for (int i = 0; i < outline.length; i++)
            {
                JPanel edge = new JPanel();
                edge.setBackground(OUTLINE);
                outline[i] = edge;
                parent.add(edge, javax.swing.JLayeredPane.DRAG_LAYER);
            }
        }
        Point at = bounds.getLocation();
        SwingUtilities.convertPointFromScreen(at, parent);
        int w = bounds.width;
        int h = bounds.height;
        Rectangle[] edges = {
            new Rectangle(at.x, at.y, w, OUTLINE_WIDTH),
            new Rectangle(at.x, at.y + h - OUTLINE_WIDTH, w, OUTLINE_WIDTH),
            new Rectangle(at.x, at.y, OUTLINE_WIDTH, h),
            new Rectangle(at.x + w - OUTLINE_WIDTH, at.y, OUTLINE_WIDTH, h)};
        for (int i = 0; i < outline.length; i++)
        {
            if (!outline[i].getBounds().equals(edges[i]))
            {
                // Over the game's picture an edge only shows where a hole is cut for it, which showing it does
                // (see setHostBounds); moved while shown, it would stay hidden outside the map.
                outline[i].setVisible(false);
                outline[i].setBounds(edges[i]);
                outline[i].setVisible(true);
            }
        }
    }

    /** Puts the map where it was dragged to, and takes the outline away. */
    private void dragDone()
    {
        if (outline != null)
        {
            for (JComponent edge : outline)
            {
                java.awt.Container parent = edge.getParent();
                if (parent != null)
                {
                    // Hidden first, which fills its hole in the game's picture again.
                    edge.setVisible(false);
                    parent.remove(edge);
                    parent.repaint(edge.getX(), edge.getY(), edge.getWidth(), edge.getHeight());
                }
            }
            outline = null;
        }
        if (pending != null)
        {
            Rectangle to = pending;
            pending = null;
            setFromScreen(to);
        }
    }

    /** Remembers the window's place after the user moved or resized it. */
    private void setFromScreen(Rectangle bounds)
    {
        Canvas canvas = watchedCanvas;
        if (canvas == null || !canvas.isShowing() || canvas.getWidth() <= 0 || canvas.getHeight() <= 0)
        {
            return;
        }
        Rectangle view = new Rectangle(canvas.getLocationOnScreen(), canvas.getSize());
        Rectangle fitted = fit(new Rectangle2D.Double((bounds.x - view.x) / (double) view.width,
            (bounds.y - view.y) / (double) view.height, bounds.width / (double) view.width,
            bounds.height / (double) view.height), view);
        relative = new Rectangle2D.Double((fitted.x - view.x) / (double) view.width, (fitted.y - view.y) / (double) view.height,
            fitted.width / (double) view.width, fitted.height / (double) view.height);
        maximised = false;
        if (!hostBounds().equals(fitted))
        {
            setHostBounds(fitted);
        }
    }

    private void save()
    {
        saveBounds.accept(encode(relative, maximised));
    }

    static String encode(Rectangle2D relative, boolean maximised)
    {
        if (relative == null)
        {
            return maximised ? "max" : "";
        }
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f%s", relative.getX(), relative.getY(), relative.getWidth(),
            relative.getHeight(), maximised ? ",max" : "");
    }

    /** {bounds or null, maximised}, or null when nothing usable was saved. */
    static Object[] decode(String saved)
    {
        if (saved == null || saved.trim().isEmpty())
        {
            return null;
        }
        String[] parts = saved.trim().split(",");
        if (parts.length == 1 && "max".equals(parts[0]))
        {
            return new Object[]{null, true};
        }
        if (parts.length != 4 && parts.length != 5)
        {
            return null;
        }
        try
        {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double w = Double.parseDouble(parts[2]);
            double h = Double.parseDouble(parts[3]);
            if (!(w > 0.05 && w <= 1 && h > 0.05 && h <= 1 && x >= -1 && x <= 1 && y >= -1 && y <= 1))
            {
                return null;
            }
            return new Object[]{new Rectangle2D.Double(x, y, w, h), parts.length == 5 && "max".equals(parts[4])};
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private void watch(Canvas canvas)
    {
        if (canvas == watchedCanvas)
        {
            return;
        }
        unwatch();
        watchedCanvas = canvas;
        canvas.addComponentListener(follower);
        watchedFrame = SwingUtilities.getWindowAncestor(canvas);
        if (watchedFrame != null)
        {
            watchedFrame.addComponentListener(follower);
        }
    }

    private void unwatch()
    {
        if (watchedCanvas != null)
        {
            watchedCanvas.removeComponentListener(follower);
        }
        if (watchedFrame != null)
        {
            watchedFrame.removeComponentListener(follower);
        }
        watchedCanvas = null;
        watchedFrame = null;
    }

    void dispose()
    {
        if (keysWatched)
        {
            keysWatched = false;
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(clicks);
        }
        // Closed first, which hands the keyboard back to the game if it was typing here.
        close();
        unwatch();
        if (window != null)
        {
            window.dispose();
            window = null;
        }
        if (panel != null && panel.getParent() != null)
        {
            java.awt.Container parent = panel.getParent();
            parent.remove(panel);
            parent.repaint();
        }
        panel = null;
    }

    /** A slim strip along the top to move the window by (drag) or fill the game view (double-click). */
    private final class Grip extends JComponent
    {
        private Point grab;
        private Rectangle start;

        Grip()
        {
            setPreferredSize(new Dimension(100, GRIP_HEIGHT));
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            setToolTipText("Drag to move; double-click to fill the game view");
            MouseAdapter mouse = new MouseAdapter()
            {
                @Override
                public void mousePressed(MouseEvent e)
                {
                    if (SwingUtilities.isLeftMouseButton(e))
                    {
                        grab = e.getLocationOnScreen();
                        start = hostBounds();
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e)
                {
                    if (grab == null)
                    {
                        return;
                    }
                    if (maximised)
                    {
                        Canvas canvas = watchedCanvas;
                        if (canvas == null || !canvas.isShowing())
                        {
                            return;
                        }
                        // Dragging a maximised window restores it under the mouse, like other windows do.
                        Rectangle restored = fit(relative, new Rectangle(canvas.getLocationOnScreen(), canvas.getSize()));
                        double along = (grab.x - start.x) / (double) Math.max(1, start.width);
                        start = new Rectangle(grab.x - (int) (restored.width * along), start.y, restored.width, restored.height);
                        maximised = false;
                    }
                    Point now = e.getLocationOnScreen();
                    dragTo(new Rectangle(start.x + now.x - grab.x, start.y + now.y - grab.y, start.width, start.height));
                }

                @Override
                public void mouseReleased(MouseEvent e)
                {
                    if (grab != null)
                    {
                        grab = null;
                        dragDone();
                        save();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 2)
                    {
                        toggleMaximised();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(FRAME);
            g.fillRect(0, 0, getWidth(), getHeight());
            // A few dots in the middle, the usual sign for "grab here".
            g.setColor(GRIP_DOTS);
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            for (int i = -3; i <= 3; i++)
            {
                g.fill(new java.awt.geom.Ellipse2D.Double(cx + i * 7 - 1.5, cy - 1.5, 3, 3));
            }
            g.dispose();
        }
    }

    /** Resizing by the frame around the map: edges and corners. */
    private final class Resizer extends MouseAdapter
    {
        private final JComponent root;
        private int edges;
        private Point grab;
        private Rectangle start;

        Resizer(JComponent root)
        {
            this.root = root;
        }

        /** Bit set of the edges under a point: 1 left, 2 right, 4 top, 8 bottom. */
        private int edgesAt(Point p)
        {
            int corner = EDGE * 4;
            int w = root.getWidth();
            int h = root.getHeight();
            int found = 0;
            if (p.x < EDGE || p.x < corner && p.y >= h - corner)
            {
                found |= 1;
            }
            if (p.x >= w - EDGE || p.x >= w - corner && p.y >= h - corner)
            {
                found |= 2;
            }
            if (p.y < 3)
            {
                found |= 4;
            }
            if (p.y >= h - BOTTOM || p.y >= h - corner && (p.x < corner || p.x >= w - corner))
            {
                found |= 8;
            }
            return found;
        }

        @Override
        public void mouseMoved(MouseEvent e)
        {
            root.setCursor(Cursor.getPredefinedCursor(cursor(edgesAt(e.getPoint()))));
        }

        @Override
        public void mouseExited(MouseEvent e)
        {
            if (grab == null)
            {
                root.setCursor(Cursor.getDefaultCursor());
            }
        }

        @Override
        public void mousePressed(MouseEvent e)
        {
            edges = edgesAt(e.getPoint());
            if (edges != 0 && SwingUtilities.isLeftMouseButton(e))
            {
                grab = e.getLocationOnScreen();
                start = hostBounds();
            }
        }

        @Override
        public void mouseDragged(MouseEvent e)
        {
            if (grab == null)
            {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - grab.x;
            int dy = now.y - grab.y;
            Rectangle r = new Rectangle(start);
            if ((edges & 1) != 0)
            {
                int width = Math.max(MIN_WIDTH, start.width - dx);
                r.x = start.x + start.width - width;
                r.width = width;
            }
            if ((edges & 2) != 0)
            {
                r.width = Math.max(MIN_WIDTH, start.width + dx);
            }
            if ((edges & 4) != 0)
            {
                int height = Math.max(MIN_HEIGHT, start.height - dy);
                r.y = start.y + start.height - height;
                r.height = height;
            }
            if ((edges & 8) != 0)
            {
                r.height = Math.max(MIN_HEIGHT, start.height + dy);
            }
            dragTo(r);
        }

        @Override
        public void mouseReleased(MouseEvent e)
        {
            if (grab != null)
            {
                grab = null;
                dragDone();
                save();
            }
        }

        private int cursor(int edges)
        {
            switch (edges)
            {
                case 1:
                    return Cursor.W_RESIZE_CURSOR;
                case 2:
                    return Cursor.E_RESIZE_CURSOR;
                case 4:
                    return Cursor.N_RESIZE_CURSOR;
                case 8:
                    return Cursor.S_RESIZE_CURSOR;
                case 5:
                    return Cursor.NW_RESIZE_CURSOR;
                case 6:
                    return Cursor.NE_RESIZE_CURSOR;
                case 9:
                    return Cursor.SW_RESIZE_CURSOR;
                case 10:
                    return Cursor.SE_RESIZE_CURSOR;
                default:
                    return Cursor.DEFAULT_CURSOR;
            }
        }
    }
}
