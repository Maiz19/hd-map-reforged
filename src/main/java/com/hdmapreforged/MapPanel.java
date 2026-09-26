package com.hdmapreforged;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The sidebar: no map of its own (too small to be of use), but buttons to open the map, the route being followed with
 * its steps, and the party, who is where. Clicking a step or a friend shows that place on the map. Swing thread.
 */
final class MapPanel extends PluginPanel
{
    /** The party, as the sidebar lists it. */
    interface Friends
    {
        boolean inGroup();

        List<FriendsWidget.Row> rows();

        /** The world the player is on, or 0. */
        int world();

        /** What a member last shared, or null. */
        PartyMapMembers.Gear gear(long id);

        FriendsWidget.Images images();

        /** Hops to a world (the game's world switcher does it). */
        void hop(int world);

        /** Whether there is a last party to rejoin. */
        boolean canRejoin();

        /** Rejoins the last party. */
        void join(java.awt.Component from);
    }

    /** Text width inside the sidebar's padding. */
    static final int TEXT_WIDTH = PANEL_WIDTH - 52;
    private static final int FRIENDS_REFRESH_MS = 2000;

    private final Consumer<WorldPoint> showOnMap;
    private final Friends friends;
    private final Function<WorldPoint, String> placeName;
    private final JPanel routeHolder = column();
    /** "Route" above the note that there is none; a route's steps have their own title. */
    private final JLabel routeHeading = heading("Route");
    private final JPanel friendsHolder = column();
    private final JPanel toursHolder = column();
    /** "Custom routes" above the list; the open editor has its own title. */
    private final JLabel toursHeading = heading("Custom routes");
    private TourPanel.Actions tours;
    /** The custom route open for editing (built for a width), or null for the list. */
    private IntFunction<JComponent> editorSection;
    private TourPanel editor;
    /** The member whose details are open, or -1. */
    private long openFriend = -1;
    private int friendPage;
    private final Timer friendsTimer;
    private String friendsShown;

    MapPanel(Runnable openMap, Runnable openWindow, Consumer<WorldPoint> showOnMap, Friends friends,
        Function<WorldPoint, String> placeName)
    {
        super(false);
        this.showOnMap = showOnMap;
        this.friends = friends;
        this.placeName = placeName;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel content = column();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(button("Open map", openMap, "The map over the game (also with the map key)"));
        buttons.add(button("In a window", openWindow, "The map in a window of its own"));
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        content.add(buttons);
        content.add(Box.createVerticalStrut(10));
        content.add(card(routeHeading, routeHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(toursHeading, toursHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Party"), friendsHolder));

        // As wide as the sidebar, never wider: what does not fit wraps instead of running off to the right.
        JPanel top = new Fitted();
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        showRoute(null);
        friendsTimer = new Timer(FRIENDS_REFRESH_MS, e -> refreshFriends());
        friendsTimer.start();
        refreshFriends();
    }

    /** The route's steps ({@code section} builds them for a width), or the note that there is none. */
    void showRoute(IntFunction<JComponent> section)
    {
        routeHolder.removeAll();
        routeHeading.setVisible(section == null);
        if (section == null)
        {
            routeHolder.add(text("No route. Right-click a place on the map, Path to here.", ColorScheme.LIGHT_GRAY_COLOR));
        }
        else
        {
            JComponent steps = section.apply(TEXT_WIDTH);
            steps.setAlignmentX(Component.LEFT_ALIGNMENT);
            routeHolder.add(steps);
        }
        routeHolder.revalidate();
        routeHolder.repaint();
    }

    /** The saved custom routes, each to run (or stop) with a click; {@code actions} keeps and runs them. */
    void setTours(TourPanel.Actions actions)
    {
        tours = actions;
        editor = new TourPanel(actions, section -> {
            editorSection = section;
            showTours();
        }, showOnMap, "\u2039 All routes");
        refreshTours();
    }

    /** The custom routes changed (saved, run, done). */
    void refreshTours()
    {
        if (editor != null && editor.isOpen())
        {
            editor.refresh();
            return;
        }
        showTours();
    }

    /** Opens a member's details, as clicking them does. */
    void openFriend(long id, int page)
    {
        openFriend = id;
        friendPage = page;
        friendsShown = null;
        refreshFriends();
    }

    /** Opens a route for editing, in place of the list. */
    private void edit(Tour tour)
    {
        tours.save(tours.tours(), tour.name);
        if (!editor.isOpen())
        {
            editor.toggle();
        }
        else
        {
            editor.refresh();
        }
    }

    private void showTours()
    {
        toursHolder.removeAll();

        if (editorSection != null)
        {
            // HTML widths come out wider than asked in RuneLite's font: some room to spare.
            JComponent section = editorSection.apply(TEXT_WIDTH - 40);
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            toursHolder.add(section);
            toursHolder.revalidate();
            toursHolder.repaint();
            return;
        }
        List<Tour> saved = tours == null ? java.util.Collections.emptyList() : tours.tours();
        String running = tours == null ? null : tours.running();
        if (saved.isEmpty())
        {
            toursHolder.add(text("None yet. Right-click the map, Add to custom route.", ColorScheme.LIGHT_GRAY_COLOR));
            JButton make = button("New route", () -> {
                edit(new Tour(Tour.unique(saved, "My route"), java.util.Collections.emptyList()));
            }, "Make a route; add its stops from the map");
            make.setAlignmentX(Component.LEFT_ALIGNMENT);
            toursHolder.add(make);
        }
        for (Tour tour : saved)
        {
            boolean runs = tour.name.equals(running);
            String what = tour.stops.size() + (tour.stops.size() == 1 ? " stop" : " stops");
            toursHolder.add(entry(tour.name, runs ? "Running, " + what : what, runs ? new Color(120, 220, 140) : null,
                "Open this route: its stops, their order, Run", () -> edit(tour)));
        }
        toursHolder.revalidate();
        toursHolder.repaint();
    }

    void stop()
    {
        friendsTimer.stop();
    }

    /** The party as it is now; rebuilt only when something shown changed. */
    private void refreshFriends()
    {
        if (!isShowing() && friendsShown != null)
        {
            return;
        }
        boolean in = friends.inGroup();
        List<FriendsWidget.Row> rows = in ? friends.rows() : java.util.Collections.emptyList();
        boolean rejoin = !in && friends.canRejoin();
        StringBuilder key = new StringBuilder(in ? "in" : rejoin ? "out, rejoin" : "out");
        String[] places = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++)
        {
            FriendsWidget.Row row = rows.get(i);
            places[i] = row.point == null ? null : placeName.apply(row.point);
            key.append('|').append(row.name).append(',').append(row.world).append(',').append(places[i]);
            if (row.id == openFriend)
            {
                PartyMapMembers.Gear shared = friends.gear(row.id);
                key.append(",open,").append(friendPage).append(',').append(shared == null ? 0 : shared.at);
            }
        }
        if (key.toString().equals(friendsShown))
        {
            return;
        }
        friendsShown = key.toString();
        friendsHolder.removeAll();
        if (!in)
        {
            if (rejoin)
            {
                JButton join = button("Rejoin party", () -> friends.join(this), "Join your last party again");
                join.setAlignmentX(Component.LEFT_ALIGNMENT);
                join.setMaximumSize(new Dimension(Integer.MAX_VALUE, join.getPreferredSize().height));
                friendsHolder.add(join);
                friendsHolder.add(Box.createVerticalStrut(4));
            }
            friendsHolder.add(text("Not in a party. In one (RuneLite's Party panel), your friends show here: where "
                + "they are and what they carry.", ColorScheme.LIGHT_GRAY_COLOR));
        }
        else if (rows.isEmpty())
        {
            friendsHolder.add(text("No one else in your party yet.", ColorScheme.LIGHT_GRAY_COLOR));
        }
        int me = friends.world();
        for (int i = 0; i < rows.size(); i++)
        {
            FriendsWidget.Row row = rows.get(i);
            String where = row.point == null ? "Not sharing where they are"
                : (row.world > 0 && row.world != me ? "World " + row.world + ", " : "")
                    + (places[i] != null ? places[i] : "on the map");
            friendsHolder.add(friend(row, where));
            if (row.id == openFriend)
            {
                friendsHolder.add(details(row, me));
            }
        }
        friendsHolder.revalidate();
        friendsHolder.repaint();
    }

    private JComponent friend(FriendsWidget.Row row, String where)
    {
        return entry(row.name, where, row.color != null ? row.color : ColorScheme.MEDIUM_GRAY_COLOR,
            row.id == openFriend ? "Close" : "What " + row.name + " carries, wears and can do", () -> {
                openFriend = openFriend == row.id ? -1 : row.id;
                friendsShown = null;
                refreshFriends();
            });
    }

    private static final String[] PAGES = {"Items", "Worn", "Skills"};

    /** A member's details: buttons to see them on the map or hop to them, their status and one page of what they share. */
    private JComponent details(FriendsWidget.Row row, int me)
    {
        JPanel box = column();
        box.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 0));
        JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton show = button("On map", () -> showOnMap.accept(row.point), "Open the map where they are");
        show.setEnabled(row.point != null);
        actions.add(show);
        if (row.world > 0 && me > 0 && row.world != me)
        {
            actions.add(button("Hop W" + row.world, () -> friends.hop(row.world), "Hop to their world, " + row.world));
        }
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, actions.getPreferredSize().height));
        box.add(actions);
        box.add(Box.createVerticalStrut(6));
        PartyMapMembers.Gear shared = friends.gear(row.id);
        if (shared == null)
        {
            box.add(text("Nothing shared yet. They share it with this plugin's setting.", ColorScheme.LIGHT_GRAY_COLOR));
            return box;
        }
        int hp = net.runelite.api.Skill.HITPOINTS.ordinal();
        int prayer = net.runelite.api.Skill.PRAYER.ordinal();
        FriendsWidget.Images pictures = friends.images();
        JPanel status = new JPanel(new GridLayout(2, 2, 4, 4));
        status.setOpaque(false);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.add(orb(Orbs.Kind.HITPOINTS, shared.boosted[hp], shared.levels[hp], pictures));
        status.add(orb(Orbs.Kind.PRAYER, shared.boosted[prayer], shared.levels[prayer], pictures));
        status.add(orb(Orbs.Kind.RUN, shared.run, 100, pictures));
        status.add(orb(Orbs.Kind.SPECIAL, shared.special, 100, pictures));
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, status.getPreferredSize().height));
        box.add(status);
        box.add(Box.createVerticalStrut(6));
        JPanel pages = new JPanel(new GridLayout(1, 3, 4, 0));
        pages.setOpaque(false);
        pages.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int k = 0; k < PAGES.length; k++)
        {
            int page = k;
            JButton tab = button(PAGES[k], () -> {
                friendPage = page;
                friendsShown = null;
                refreshFriends();
            }, PAGES[k]);
            tab.setEnabled(k != friendPage);
            tab.setMargin(new java.awt.Insets(2, 1, 2, 1));
            pages.add(tab);
        }
        pages.setMaximumSize(new Dimension(Integer.MAX_VALUE, pages.getPreferredSize().height));
        box.add(pages);
        box.add(Box.createVerticalStrut(6));
        FriendsWidget.Images images = friends.images();
        JPanel grid;
        if (friendPage == 0)
        {
            grid = new JPanel(new GridLayout(7, 4, 2, 2));
            for (int k = 0; k < HdMapPartyGear.INVENTORY; k++)
            {
                grid.add(item(images, shared.inventory[k], shared.quantities[k]));
            }
        }
        else if (friendPage == 1)
        {
            grid = new JPanel(new GridLayout(5, 3, 2, 2));
            JComponent[] cells = new JComponent[15];
            for (int k = 0; k < FriendsWidget.WORN.length; k++)
            {
                int[] at = FriendsWidget.WORN[k];
                if (at != null)
                {
                    cells[at[1] * 3 + at[0]] = item(images, shared.equipment[k], 1);
                }
            }
            for (JComponent cell : cells)
            {
                grid.add(cell != null ? cell : new JLabel());
            }
        }
        else
        {
            grid = new JPanel(new GridLayout(8, 3, 2, 2));
            for (int k = 0; k < Math.min(HdMapPartyGear.SKILLS, net.runelite.api.Skill.values().length); k++)
            {
                int now = shared.boosted[k];
                int real = shared.levels[k];
                java.awt.image.BufferedImage icon = images.skill(k);
                JLabel skill = new JLabel(now == real ? String.valueOf(real) : now + "/" + real,
                    icon == null ? null : new javax.swing.ImageIcon(icon), JLabel.LEFT);
                skill.setForeground(now > real ? new Color(120, 220, 120) : now < real ? new Color(230, 120, 110)
                    : Color.WHITE);
                skill.setToolTipText(net.runelite.api.Skill.values()[k].getName());
                grid.add(skill);
            }
        }
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        box.add(grid);
        return box;
    }

    private static JComponent item(FriendsWidget.Images images, int id, int quantity)
    {
        java.awt.image.BufferedImage picture = id < 0 ? null : images.item(id, Math.max(1, quantity));
        JLabel label = new JLabel(picture == null ? null : new javax.swing.ImageIcon(picture));
        label.setHorizontalAlignment(JLabel.CENTER);
        label.setPreferredSize(new Dimension(36, 32));
        return label;
    }

    /** One of the game's orbs, as on the minimap. */
    private static JComponent orb(Orbs.Kind kind, int value, int max, FriendsWidget.Images pictures)
    {
        JComponent orb = new JComponent()
        {
            @Override
            protected void paintComponent(java.awt.Graphics g)
            {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                Orbs.paint(g2, (getWidth() - Orbs.WIDTH) / 2, 0, kind, value, max, pictures::sprite);
                g2.dispose();
            }
        };
        orb.setPreferredSize(new Dimension(Orbs.WIDTH, Orbs.HEIGHT));
        return orb;
    }

    /** A line of a list: a name over a smaller line; with {@code action}, clickable. {@code dot}: a coloured dot. */
    private static JComponent entry(String title, String under, Color dot, String tip, Runnable action)
    {
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setBackground(ColorScheme.DARK_GRAY_COLOR);
        line.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (dot != null)
        {
            JLabel mark = new JLabel("\u25CF");
            mark.setForeground(dot);
            line.add(mark, BorderLayout.WEST);
        }
        JPanel texts = new JPanel(new GridLayout(2, 1));
        texts.setOpaque(false);
        JLabel name = new JLabel(title);
        name.setForeground(action != null ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
        name.setFont(FontManager.getRunescapeBoldFont());
        texts.add(name);
        JLabel small = new JLabel(under);
        small.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        small.setFont(FontManager.getRunescapeSmallFont());
        texts.add(small);
        line.add(texts, BorderLayout.CENTER);
        if (action != null)
        {
            line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            line.setToolTipText(tip);
            line.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    action.run();
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    line.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    line.setBackground(ColorScheme.DARK_GRAY_COLOR);
                }
            });
        }
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        JPanel spaced = column();
        spaced.add(line);
        spaced.add(Box.createVerticalStrut(4));
        return spaced;
    }

    /** A panel that takes the width of the scroll pane it is in, and scrolls only up and down. */
    private static final class Fitted extends JPanel implements javax.swing.Scrollable
    {
        Fitted()
        {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
        {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }

    /** A block of the sidebar: a darker box around a heading and what is under it. */
    private static JComponent card(JComponent heading, JComponent body)
    {
        JPanel card = column();
        card.setOpaque(true);
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        card.add(heading);
        card.add(body);
        return card;
    }

    private static JPanel column()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JLabel heading(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel text(String text, Color color)
    {
        JLabel label = new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>" + InfoCard.escape(text)
            + "</div></html>");
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JButton button(String text, Runnable action, String tip)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setToolTipText(tip);
        button.addActionListener(e -> action.run());
        return button;
    }
}
