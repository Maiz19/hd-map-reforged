package com.hdmapreforged;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;

/** The "Custom routes" panel, where the search results show: pick, name, order and run the player's routes. Swing thread. */
final class TourPanel
{
    interface Actions
    {
        List<Tour> tours();

        String editing();

        void save(List<Tour> tours, String editing);

        void run(Tour tour);

        /** Saves the fastest order if the stops are unchanged, then runs {@code done} even on failure or cancel. */
        void fastestOrder(Tour tour, Runnable done);

        default void cancelOrder()
        {
        }

        String running();

        void stop();
    }

    private static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;

    private final Actions actions;
    private final Consumer<IntFunction<JComponent>> show;
    private final Consumer<WorldPoint> focus;
    private boolean open;
    /** While the fastest order is worked out; {@link #orderings} counts runs, so a cancelled one ends nothing. */
    private boolean ordering;
    private int orderings;

    private final String back;

    TourPanel(Actions actions, Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus)
    {
        this(actions, show, focus, null);
    }

    TourPanel(Actions actions, Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus, String back)
    {
        this.actions = actions;
        this.show = show;
        this.focus = focus;
        this.back = back;
    }

    boolean isOpen()
    {
        return open;
    }

    void toggle()
    {
        if (open)
        {
            close();
        }
        else
        {
            open = true;
            refresh();
        }
    }

    void close()
    {
        open = false;
        cancelOrder();
        show.accept(null);
    }

    void replaced()
    {
        open = false;
        cancelOrder();
    }

    private void cancelOrder()
    {
        if (ordering)
        {
            ordering = false;
            orderings++;
            actions.cancelOrder();
        }
    }

    void refresh()
    {
        if (open)
        {
            show.accept(this::section);
        }
    }

    private JComponent section(int width)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        List<Tour> tours = new ArrayList<>(actions.tours());
        String editing = actions.editing();
        Tour tour = null;
        for (Tour t : tours)
        {
            if (t.name.equals(editing))
            {
                tour = t;
            }
        }
        if (tour == null)
        {
            tour = new Tour(editing, new ArrayList<>());
            tours.add(tour);
        }
        Tour current = tour;

        if (back != null)
        {
            JLabel backLink = link(back, this::close, width);
            backLink.setToolTipText("Back to the list");
            panel.add(backLink);
            panel.add(Box.createVerticalStrut(4));
        }

        JPanel pick = row();
        JComboBox<String> which = new JComboBox<>();
        for (Tour t : tours)
        {
            which.addItem(t.name);
        }
        which.setSelectedItem(current.name);
        which.addActionListener(e -> {
            Object chosen = which.getSelectedItem();
            if (chosen != null && !chosen.equals(current.name))
            {
                cancelOrder();
                actions.save(tours, chosen.toString());
                refresh();
            }
        });
        which.setMaximumSize(new Dimension(Math.max(100, width - 60), which.getPreferredSize().height));
        pick.add(which);
        pick.add(Box.createHorizontalStrut(4));
        pick.add(button("New", () -> {
            if (tours.size() < Tour.MAX_TOURS)
            {
                String name = Tour.unique(tours, "Route " + (tours.size() + 1));
                tours.add(new Tour(name, new ArrayList<>()));
                actions.save(tours, name);
                refresh();
            }
        }));
        panel.add(pick);
        panel.add(Box.createVerticalStrut(4));
        JPanel naming = row();
        JTextField name = new JTextField(current.name, 14);
        name.setToolTipText("The route's name: type and press Enter");
        name.addActionListener(e -> {
            String typed = name.getText().trim();
            if (!typed.isEmpty() && !typed.equals(current.name))
            {
                List<Tour> others = new ArrayList<>(tours);
                others.remove(current);
                String unique = Tour.unique(others, typed);
                tours.set(tours.indexOf(current), current.renamed(unique));
                actions.save(tours, unique);
                refresh();
            }
        });
        name.setMaximumSize(new Dimension(Math.max(100, width - 60), name.getPreferredSize().height));
        naming.add(name);
        naming.add(Box.createHorizontalStrut(4));
        JButton delete = button("Delete", () -> {
            tours.remove(current);
            actions.save(tours, tours.isEmpty() ? "My route" : tours.get(0).name);
            refresh();
        });
        // Not while the fastest order is worked out; moving or removing stops neither.
        delete.setEnabled(!ordering);
        naming.add(delete);
        panel.add(naming);
        panel.add(Box.createVerticalStrut(8));

        if (current.stops.isEmpty())
        {
            panel.add(text("No stops yet. Right-click the map, Add to custom route, or + Stop in a card.",
                ColorScheme.LIGHT_GRAY_COLOR, false, width));
        }
        for (int i = 0; i < current.stops.size(); i++)
        {
            int at = i;
            Tour.Stop stop = current.stops.get(i);
            JPanel line = new JPanel(new BorderLayout(4, 0));
            line.setOpaque(false);
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            boolean repeats = current.repeats(i);
            JLabel label = repeats ? text((i + 1) + ". " + stop.name, ColorScheme.MEDIUM_GRAY_COLOR, false, width - 90)
                : stop.isKind() ? text((i + 1) + ". " + stop.name, Color.WHITE, false, width - 90)
                : link((i + 1) + ". " + stop.name, () -> focus.accept(stop.point), width - 90);
            if (repeats)
            {
                label.setToolTipText("The same as the stop before it: skipped, unless you move it elsewhere");
            }
            line.add(label, BorderLayout.CENTER);
            JPanel moves = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            moves.setOpaque(false);
            JButton earlier = small("↑", () -> move(tours, current, at, -1), "Earlier");
            earlier.setEnabled(i > 0 && !ordering);
            moves.add(earlier);
            JButton later = small("↓", () -> move(tours, current, at, 1), "Later");
            later.setEnabled(i < current.stops.size() - 1 && !ordering);
            moves.add(later);
            JButton remove = small("✕", () -> {
                current.stops.remove(at);
                actions.save(tours, current.name);
                refresh();
            }, "Remove this stop");
            remove.setEnabled(!ordering);
            moves.add(remove);
            line.add(moves, BorderLayout.EAST);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
            panel.add(line);
            panel.add(Box.createVerticalStrut(2));
        }
        panel.add(Box.createVerticalStrut(8));

        JPanel go = row();
        String running = actions.running();
        if (running != null)
        {
            go.add(button("Stop " + running, () -> {
                actions.stop();
                refresh();
            }));
        }
        else if (!current.stops.isEmpty())
        {
            go.add(button("Run", () -> {
                actions.run(current);
                refresh();
            }));
        }
        if (current.stops.size() > 1)
        {
            go.add(Box.createHorizontalStrut(4));
            JButton fastest = button(ordering ? "Working it out…" : "Fastest order", () -> {
                ordering = true;
                int run = ++orderings;
                refresh();
                actions.fastestOrder(current, () -> {
                    if (run == orderings)
                    {
                        ordering = false;
                        refresh();
                    }
                });
            });
            fastest.setEnabled(!ordering);
            fastest.setToolTipText("Let the route planner put the stops in the quickest order from where you are");
            go.add(fastest);
        }
        panel.add(go);
        return panel;
    }

    private void move(List<Tour> tours, Tour tour, int at, int by)
    {
        int to = at + by;
        if (to < 0 || to >= tour.stops.size())
        {
            return;
        }
        tour.stops.add(to, tour.stops.remove(at));
        actions.save(tours, tour.name);
        refresh();
    }

    private static JPanel row()
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static JButton button(String text, Runnable action)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JButton small(String text, Runnable action, String tip)
    {
        JButton button = button(text, action);
        button.setMargin(new java.awt.Insets(0, 4, 0, 4));
        button.setToolTipText(tip);
        return button;
    }

    private static JLabel text(String text, Color color, boolean bold, int width)
    {
        JLabel label = new JLabel("<html><div style='width:" + Math.max(60, width) + "px'>" + InfoCard.escape(text)
            + "</div></html>");
        label.setForeground(color);
        if (bold)
        {
            label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1));
        }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return label;
    }

    private static JLabel link(String text, Runnable action, int width)
    {
        JLabel label = text(text, LINK, false, width);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText("Show on the map");
        label.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                action.run();
            }
        });
        return label;
    }
}
