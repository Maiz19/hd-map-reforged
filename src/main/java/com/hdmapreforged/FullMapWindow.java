package com.hdmapreforged;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import net.runelite.api.Client;

/**
 * The map over the game view: a panel in the game window's layered pane, or a window of its own. Not drawn in the
 * game because Stretched Mode would blur it. Takes the keyboard only while its search is typed in; never moves,
 * resizes or focuses the client window. Swing thread only.
 */
final class FullMapWindow
{
    static final int GRIP_HEIGHT = 10;
    private static final Color GRIP_DOTS = new Color(255, 255, 255, 90);
    private static final int EDGE = 8;
    /** Thicker than the sides: easy to take hold of. */
    private static final int BOTTOM = 12;
    private static final Color OUTLINE = new Color(220, 190, 110);
    private static final int OUTLINE_WIDTH = 2;
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;
    private static final Color FRAME = new Color(30, 32, 37);
    private static final Color FRAME_EDGE = new Color(255, 255, 255, 40);

    private final Client client;
    private final MapScreen screen;
    private final Consumer<String> saveBounds;
    private final Predicate<KeyEvent> isMapKey;
    private JWindow window;
    private JPanel panel;
    /** While dragging over the game: the outline's edges, and where the map goes once let go. */
    private JComponent[] outline;
    private Rectangle pending;
    private final java.util.function.BooleanSupplier inGameWindow;
    private Canvas watchedCanvas;
    private Window watchedFrame;
    /** Fractions of the game view, or null for the default. */
    private Rectangle2D relative;
    private boolean maximised;
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
        Predicate<KeyEvent> isMapKey, java.util.function.BooleanSupplier inGameWindow)
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

    private void setHostBounds(Rectangle screenBounds)
    {
        if (panel != null)
        {
            Container parent = panel.getParent();
            Point at = screenBounds.getLocation();
            SwingUtilities.convertPointFromScreen(at, parent);
            // The game is a native surface with a hole cut where the map is; moving does not refill the old hole but
            // hiding does, so hide, move, show.
            boolean shown = panel.isVisible();
            Component typing = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
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

    private boolean inside(Component c)
    {
        return panel != null ? SwingUtilities.isDescendingFrom(c, panel)
            : window != null && SwingUtilities.getWindowAncestor(c) == window;
    }

    private boolean hasKeys()
    {
        if (panel != null)
        {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return owner != null && SwingUtilities.isDescendingFrom(owner, panel);
        }
        return window != null && window.isFocused();
    }

    /** A panel in the game window's layered pane, so the desktop sees one window. */
    private void openInGameWindow(Canvas canvas)
    {
        if (window != null)
        {
            // Hidden first, which hands the keyboard back to the game.
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
            if (panel.getParent() != null)
            {
                panel.getParent().remove(panel);
            }
            panel = null;
        }
        if (window == null)
        {
            window = new JWindow(SwingUtilities.getWindowAncestor(canvas));
            // A utility window: left out of the taskbar and alt-tab, raised and lowered with the game.
            window.setType(Window.Type.UTILITY);
            // Some window managers still focus it on click; then Escape and the map key must work here too.
            window.setAutoRequestFocus(false);
            // Never focusable, so the desktop (KWin) keeps the game active; only while typing (see typeHere).
            window.setFocusableWindowState(false);
            window.addWindowFocusListener(new java.awt.event.WindowAdapter()
            {
                @Override
                public void windowLostFocus(java.awt.event.WindowEvent e)
                {
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
            // Some window managers place a new window themselves.
            SwingUtilities.invokeLater(this::place);
            screen.opened();
        }
    }

    void close()
    {
        boolean hadKeys;
        if (panel != null && panel.isVisible())
        {
            hadKeys = hasKeys();
            panel.setVisible(false);
            Container parent = panel.getParent();
            if (parent != null)
            {
                parent.repaint();
            }
        }
        else if (window != null && window.isVisible())
        {
            hadKeys = window.isFocused();
            window.setVisible(false);
            window.setFocusableWindowState(false);
        }
        else
        {
            return;
        }
        Canvas canvas = watchedCanvas;
        if (hadKeys && canvas != null && canvas.isShowing())
        {
            canvas.requestFocus();
        }
    }

    void toggleMaximised()
    {
        maximised = !maximised;
        save();
        place();
        screen.refresh();
    }

    /** Escape and the map key close the map while it has the keyboard. Added once (the focus manager keeps every copy). */
    private void closeKeys()
    {
        if (keysWatched)
        {
            return;
        }
        keysWatched = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clicks, java.awt.AWTEvent.MOUSE_EVENT_MASK);
    }

    private final java.awt.KeyEventDispatcher keys = e -> {
        if (!isOpen() || !hasKeys() || e.getID() != KeyEvent.KEY_PRESSED)
        {
            return false;
        }
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean typing = owner instanceof JTextComponent;
        boolean menu = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
        // While typing or in a menu, keys are theirs: Escape closes the menu, letters type.
        if (menu || typing && (e.getKeyCode() != KeyEvent.VK_ESCAPE
            || !((JTextComponent) owner).getText().isEmpty()))
        {
            return false;
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE || FullMapWindow.this.isMapKey.test(e))
        {
            close();
            return true;
        }
        return false;
    };

    /** After a click on the map outside a text field or menu, the keyboard goes back to the game. */
    private final java.awt.event.AWTEventListener clicks = event -> {
        if (event instanceof MouseEvent && event.getID() == MouseEvent.MOUSE_PRESSED && isOpen()
            && event.getSource() instanceof JTextComponent
            && inside((Component) event.getSource()))
        {
            typeHere((JTextComponent) event.getSource());
            return;
        }
        if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_RELEASED || !isOpen() || !hasKeys())
        {
            return;
        }
        Object source = event.getSource();
        if (!(source instanceof Component) || !inside((Component) source)
            || source instanceof JTextComponent)
        {
            return;
        }
        SwingUtilities.invokeLater(this::giveKeysBack);
    };

    /** A click in a text field: the window takes the keyboard for typing there. */
    private void typeHere(JTextComponent field)
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

    private JPanel frame()
    {
        JPanel root = new JPanel(new BorderLayout())
        {
            @Override
            protected void paintComponent(Graphics graphics)
            {
                super.paintComponent(graphics);
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

    /** Screen bounds for fractions of the game view (null: the default), inside the view, at least the minimum size. */
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
     * Over the game only an outline follows the mouse and the map moves once let go: moving it every step leaves black
     * where it was, as the game does not redraw that quickly enough.
     */
    private void dragTo(Rectangle bounds)
    {
        if (panel == null || panel.getParent() == null)
        {
            setFromScreen(bounds);
            return;
        }
        pending = bounds;
        Container parent = panel.getParent();
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
                // An edge only shows where showing it cuts a hole (see setHostBounds).
                outline[i].setVisible(false);
                outline[i].setBounds(edges[i]);
                outline[i].setVisible(true);
            }
        }
    }

    private void dragDone()
    {
        if (outline != null)
        {
            for (JComponent edge : outline)
            {
                Container parent = edge.getParent();
                if (parent != null)
                {
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
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(clicks);
        }
        close();
        unwatch();
        if (window != null)
        {
            window.dispose();
            window = null;
        }
        if (panel != null && panel.getParent() != null)
        {
            Container parent = panel.getParent();
            parent.remove(panel);
            parent.repaint();
        }
        panel = null;
    }

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
                        // Dragging a maximised window restores it under the mouse.
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

        /** Bits: 1 left, 2 right, 4 top, 8 bottom. */
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
