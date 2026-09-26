package com.hdmapreforged;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;

/**
 * The map with its controls and detail card. The same screen serves the sidebar, the large window, and the
 * window that takes the place of the game's world map.
 */
final class MapScreen extends JPanel implements MapView.Listener
{
    enum Layout
    {
        /** Narrow: controls in rows, card below the map. */
        SIDEBAR,
        /** One row of controls, card beside the map. */
        WINDOW,
        /**
         * Over the whole game view: floating controls painted on the map, the search field under the map list, and a
         * floating card while something is selected. The search field takes the keyboard only once clicked; keys
         * otherwise keep going to the game (see {@link FullMapWindow}).
         */
        FULL
    }

    private static final int MAX_RESULTS = 14;
    private static final int NEAREST = 10;
    /** The floating card on the full-screen map; its text leaves room for the icon, padding and a scroll bar. */
    private static final int FLOATING_WIDTH = 290;
    private static final int FLOATING_TEXT = 195;

    private final MapView view;
    private final InfoCard card;
    private final JScrollPane cardScroll;
    /** Over the game: the card, the search results and the custom routes each float in a panel that folds up. */
    private final Floating cardFloat = new Floating("Details", () -> showCard(false));
    private final Floating npcFloat = new Floating("Search", () -> showSection(null));
    private final Floating toursFloat = new Floating("Custom routes", () -> {
        if (this.tours != null)
        {
            this.tours.close();
        }
    });
    private final JTextField search = new JTextField()
    {
        /** A hint while empty, so it is clear what can be searched. */
        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner())
            {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(new Color(150, 150, 150));
                java.awt.Insets in = getInsets();
                g2.drawString(mode.hint, in.left + 2,
                    (getHeight() + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2);
                g2.dispose();
            }
        }
    };
    private final JComboBox<BaseMap> maps = new JComboBox<>();
    private final JComboBox<String> floors = new JComboBox<>(new String[]{"Floor 0", "Floor 1", "Floor 2", "Floor 3"});
    private final JToggleButton follow = new JToggleButton(new ImageIcon(PoiIcons.followIcon()));
    private final JButton popOut = new JButton();
    private final JButton zoomIn;
    private final JButton zoomOut;
    private final JPanel controls = new JPanel(new GridBagLayout());
    private JSplitPane split;
    private boolean syncing;
    private Layout layout = Layout.SIDEBAR;

    private final HdMapReforgedConfig config;
    private final WikiClient wiki;
    /** What the search bar looks for. */
    enum SearchMode
    {
        PLACES("Search places, patches, spots…", "Searching places, teleports, maps and every place of a kind (herb "
            + "patches, shark fishing spots, banks). Click to search monsters and NPCs"),
        MONSTERS("Find a monster or NPC…", "Searching monsters and NPCs (on the wiki). Click to search items"),
        ITEMS("Find an item…", "Searching items (on the wiki): spawns, shops with stock, and drops. Click to search "
            + "places");

        final String hint;
        final String tooltip;

        SearchMode(String hint, String tooltip)
        {
            this.hint = hint;
            this.tooltip = tooltip;
        }

        SearchMode next()
        {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private SearchMode mode = SearchMode.PLACES;
    private final JButton searchMode = new JButton();


    private final SearchResults npcs;

    private final JLayeredPane layers = new JLayeredPane();

    MapScreen(TileCache tiles, HdMapReforgedConfig config, WikiClient wiki, ItemNames itemNames, Runnable togglePopOut)
    {
        super(new BorderLayout());
        this.config = config;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        view = new MapView(tiles, config);
        view.setListener(this);
        card = new InfoCard(view, wiki, itemNames, config);
        this.wiki = wiki;
        npcs = new SearchResults(view, wiki, section -> {
            if (tours != null && layout != Layout.FULL)
            {
                // The search takes the custom routes' place in the card (over the game they have their own panel).
                tours.replaced();
            }
            showSection(section);
        });
        // A shop's wares link to the item search.
        card.setItemSearch(name -> npcs.findItem(name));
        cardScroll = new JScrollPane(card, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        cardScroll.setBorder(BorderFactory.createEmptyBorder());
        cardScroll.getVerticalScrollBar().setUnitIncrement(16);
        for (Floating floating : new Floating[]{cardFloat, npcFloat, toursFloat})
        {
            floating.addPropertyChangeListener("folded", e -> placeFloatingCard());
        }

        search.setToolTipText("Type and press Enter. The button next to it switches between places, monsters and items");
        // Suggestions show while typing; Enter takes the first one.
        search.addActionListener(e -> chooseFirst());
        // About to type: the place search's indexes get ready meanwhile, off the Swing thread.
        search.addFocusListener(new java.awt.event.FocusAdapter()
        {
            @Override
            public void focusGained(java.awt.event.FocusEvent e)
            {
                prepareSearch();
            }
        });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e)
            {
                typing.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e)
            {
                typing.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e)
            {
                typing.restart();
            }
        });
        typing.setRepeats(false);
        searchMode.setFocusable(false);
        searchMode.setMargin(new Insets(1, 3, 1, 3));
        searchMode.addActionListener(e -> {
            boolean typing = search.isFocusOwner();
            mode = mode.next();
            updateSearchMode();
            // What was typed, looked for again in the new mode at once.
            showResults();
            // Only kept in the field while typing there: never taken from the game.
            if (typing)
            {
                search.requestFocusInWindow();
            }
        });
        updateSearchMode();
        maps.setToolTipText("Map: the surface, a dungeon or another area");
        maps.addActionListener(e -> {
            BaseMap chosen = (BaseMap) maps.getSelectedItem();
            if (!syncing && chosen != null && chosen != view.map())
            {
                view.setFollowing(false);
                openMap(chosen);
            }
        });
        floors.setToolTipText("Floor (plane)");
        floors.addActionListener(e -> {
            if (!syncing)
            {
                view.setPlane(floors.getSelectedIndex());
            }
        });
        follow.setToolTipText("Follow: keep your character in view");
        follow.setMargin(new Insets(1, 4, 1, 4));
        follow.setFocusable(false);
        follow.addActionListener(e -> {
            if (follow.isSelected())
            {
                view.centerOnPlayer();
            }
            else
            {
                view.setFollowing(false);
            }
        });
        popOut.setFocusable(false);
        popOut.setMargin(new Insets(1, 4, 1, 4));
        popOut.addActionListener(e -> togglePopOut.run());
        card.setOnRebuilt(this::placeFloatingCard);
        layers.addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                placeFloatingCard();
            }
        });
        zoomIn = small("+", "Zoom in", () -> view.zoomBy(1));
        zoomOut = small("−", "Zoom out", () -> view.zoomBy(-1));

        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setScreenLayout(Layout.SIDEBAR);
        viewChanged();
    }

    private JButton small(String text, String tip, Runnable action)
    {
        JButton button = new JButton(text);
        button.setToolTipText(tip);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 6, 1, 6));
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * Lays the screen out for where it is shown. Over the game ({@link Layout#FULL}) the map fills the view and the
     * search field and card float over it, the card on the right below the close button; elsewhere the controls
     * are in rows above the map and the card below or beside it.
     */
    void setScreenLayout(Layout layout)
    {
        this.layout = layout;
        removeAll();
        layers.removeAll();
        if (split != null)
        {
            split.removeAll();
            split = null;
        }
        if (layout == Layout.FULL)
        {
            view.setChrome(this::showMapPicker);
            cardScroll.setBorder(BorderFactory.createEmptyBorder());
            card.setTextWidth(FLOATING_TEXT);
            layers.add(view, JLayeredPane.DEFAULT_LAYER);
            cardFloat.setContent(cardScroll);
            cardScroll.setVisible(!cardFloat.isFolded());
            layers.add(cardFloat, JLayeredPane.PALETTE_LAYER);
            layers.add(search, JLayeredPane.PALETTE_LAYER);
            layers.add(searchMode, JLayeredPane.PALETTE_LAYER);
            cardFloat.setVisible(view.selected() != null || card.hasRoute() || card.hasExtra());
            add(layers, BorderLayout.CENTER);
            placeFloatingCard();
            revalidate();
            repaint();
            return;
        }
        view.setChrome(null);
        cardScroll.setBorder(BorderFactory.createEmptyBorder());
        cardScroll.setVisible(true);
        boolean wide = layout != Layout.SIDEBAR;
        controls.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;
        if (wide)
        {
            c.gridy = 0;
            c.weightx = 1;
            controls.add(search, c);
            c.weightx = 0;
            controls.add(searchMode, c);
            c.weightx = 0.6;
            controls.add(maps, c);
            c.weightx = 0;
            controls.add(floors, c);
            controls.add(zoomOut, c);
            controls.add(zoomIn, c);
            controls.add(follow, c);
            controls.add(popOut, c);
            popOut.setIcon(null);
            popOut.setText("Dock");
            popOut.setToolTipText("Put the map back in the sidebar");
        }
        else
        {
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 4;
            c.weightx = 1;
            controls.add(search, c);
            c.gridx = 4;
            c.gridwidth = 1;
            c.weightx = 0;
            controls.add(searchMode, c);
            c.gridx = 0;
            c.gridwidth = 5;
            c.weightx = 1;
            c.gridy = 1;
            controls.add(maps, c);
            c.gridy = 2;
            c.gridwidth = 1;
            c.weightx = 1;
            controls.add(floors, c);
            c.weightx = 0;
            c.gridx = 1;
            controls.add(zoomOut, c);
            c.gridx = 2;
            controls.add(zoomIn, c);
            c.gridx = 3;
            controls.add(follow, c);
            c.gridx = 4;
            controls.add(popOut, c);
            popOut.setText(null);
            popOut.setIcon(new ImageIcon(PoiIcons.popOutIcon()));
            popOut.setToolTipText("Open the map in a large window");
        }
        maps.setPrototypeDisplayValue(new BaseMap(0, "Gielinor Surface", 0, 0, 0, 0, 0, 0));
        add(controls, BorderLayout.NORTH);
        split = new JSplitPane(wide ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT, view, cardScroll);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setResizeWeight(wide ? 0.78 : 0.62);
        cardScroll.setPreferredSize(wide ? new Dimension(300, 400) : new Dimension(225, 180));
        card.setTextWidth(wide ? 250 : 160);
        add(split, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** Custom routes, in the place of the search results; null until the route feature is there. */
    private TourPanel tours;

    /** Where the custom routes button leads: the routes' own panel. */
    void setTours(TourPanel.Actions actions)
    {
        boolean first = tours == null;
        tours = actions == null ? null : new TourPanel(actions, this::showTours, view::focus);
        if (first && tours != null)
        {
            // The button at the bottom left, above the floor buttons.
            // Not in the side panel: too small for it.
            view.addWidget(new RoutesButton(this::toggleTours, () -> tours != null && tours.isOpen(),
                () -> layout != Layout.SIDEBAR));
        }
    }

    /** Opens or closes the custom routes; open, shows them as they are now. */
    void toggleTours()
    {
        if (tours != null && layout != Layout.SIDEBAR)
        {
            tours.toggle();
        }
    }

    void openTours()
    {
        if (layout == Layout.SIDEBAR)
        {
            return;
        }
        if (tours != null && !tours.isOpen())
        {
            tours.toggle();
        }
        else if (tours != null)
        {
            tours.refresh();
        }
    }

    /** The custom routes changed (a stop added, a route done): shown again when open. */
    void refreshTours()
    {
        if (tours != null)
        {
            tours.refresh();
        }
    }

    /** The custom routes' own panel over the game, beside the Routes button. */
    private final JPanel toursPanel = new JPanel(new BorderLayout());
    private JScrollPane toursScroll;

    /**
     * The custom routes: over the game in a panel of their own that opens beside the Routes button (bottom left);
     * elsewhere in the card, where search results show.
     */
    private void showTours(java.util.function.IntFunction<JComponent> section)
    {
        if (layout != Layout.FULL)
        {
            showSection(section);
            return;
        }
        if (toursScroll == null)
        {
            toursPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            toursPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            toursScroll = new JScrollPane(toursPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            toursScroll.setBorder(BorderFactory.createEmptyBorder());
            toursScroll.getVerticalScrollBar().setUnitIncrement(16);
            toursFloat.setContent(toursScroll);
        }
        toursPanel.removeAll();
        if (section != null)
        {
            toursPanel.add(section.apply(FLOATING_TEXT), BorderLayout.NORTH);
        }
        if (toursFloat.getParent() != layers)
        {
            // Above the card and the search results, which can reach under it on a narrow map: clicks on its
            // buttons (the arrows on its right) went to them.
            layers.add(toursFloat, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 1));
        }
        toursFloat.setVisible(section != null);
        placeFloatingCard();
        toursPanel.revalidate();
        layers.repaint();
    }

    /** What the search results, or the custom routes, show: in the card, or a panel of its own over the game. */
    private void showSection(java.util.function.IntFunction<JComponent> section)
    {
        npcSection = section;
        if (layout == Layout.FULL)
        {
            // Over the game, it has a panel of its own on the left, beside the route and icon card.
            showNpcPanel();
            return;
        }
        card.setExtra(section);
        showCard(section != null || view.selected() != null || card.hasRoute());
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    /** The monster search's own panel on the map over the game. */
    private final JPanel npcPanel = new JPanel(new BorderLayout());
    private JScrollPane npcScroll;
    private java.util.function.IntFunction<JComponent> npcSection;

    private void showNpcPanel()
    {
        if (npcScroll == null)
        {
            npcPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            npcPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            npcScroll = new JScrollPane(npcPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            npcScroll.setBorder(BorderFactory.createEmptyBorder());
            npcScroll.getVerticalScrollBar().setUnitIncrement(16);
            npcFloat.setContent(npcScroll);
        }
        npcPanel.removeAll();
        if (npcSection != null)
        {
            npcPanel.add(npcSection.apply(FLOATING_TEXT), BorderLayout.NORTH);
        }
        if (npcFloat.getParent() != layers)
        {
            layers.add(npcFloat, JLayeredPane.PALETTE_LAYER);
        }
        npcFloat.setVisible(npcSection != null);
        placeFloatingCard();
        npcPanel.revalidate();
        layers.repaint();
    }

    private void placeFloatingCard()
    {
        if (layout != Layout.FULL)
        {
            return;
        }
        int w = layers.getWidth();
        int h = layers.getHeight();
        view.setBounds(0, 0, w, h);
        int cardWidth = FLOATING_WIDTH;
        // Below the close and maximise buttons.
        int top = 58;
        // Leave the zoom and follow buttons in the bottom right corner free.
        int height = cardFloat.height(Math.max(120, Math.min(card.getPreferredSize().height + 4, h - top - 170)));
        cardFloat.setBounds(w - cardWidth - 12, top, cardWidth, height);
        // The search field sits under the map list, top left.
        int searchWidth = Math.min(260, Math.max(120, w / 4));
        search.setBounds(12, 52, searchWidth, 28);
        searchMode.setBounds(12 + searchWidth + 4, 52, 28, 28);
        if (toursFloat.isVisible())
        {
            // Beside the Routes button, its bottom level with the button's.
            int buttonWidth = MapView.floorGroupWidth(view.getFontMetrics(MapView.CONTROL_FONT));
            int bottomY = MapView.widgetBottom(h);
            int toursHeight = toursFloat.height(Math.max(90, Math.min(toursPanel.getPreferredSize().height + 20,
                bottomY - 60 - Floating.BAR)));
            toursFloat.setBounds(12 + buttonWidth + 8, bottomY - toursHeight, FLOATING_WIDTH, toursHeight);
            toursFloat.revalidate();
        }
        if (npcFloat.isVisible())
        {
            // Under the search field, clear of the floor buttons at the bottom.
            int npcHeight = npcFloat.height(Math.max(90, Math.min(npcPanel.getPreferredSize().height + 20,
                h - 90 - 70 - Floating.BAR)));
            npcFloat.setBounds(12, 90, FLOATING_WIDTH, npcHeight);
            npcFloat.revalidate();
        }
        cardFloat.revalidate();
    }

    /** Maps grouped for a short menu: the surface and full map first, then separate maps by first letter. */
    private void showMapPicker(Point at)
    {
        JPopupMenu menu = new JPopupMenu();
        List<BaseMap> all = allMaps();
        BaseMaps baseMaps = view.maps();
        WorldPoint me = view.player();
        if (me != null && baseMaps != null)
        {
            BaseMap mine = baseMaps.find(me);
            if (mine != null)
            {
                menu.add(mapItem("Where I am: " + mine.name, mine));
            }
        }
        List<BaseMap> rest = new ArrayList<>();
        for (BaseMap map : all)
        {
            if (map.id == BaseMap.SURFACE || map.id == BaseMap.FULL)
            {
                menu.add(mapItem(map.name, map));
            }
            else
            {
                rest.add(map);
            }
        }
        menu.addSeparator();
        String[] ranges = {"A–C", "D–F", "G–K", "L–O", "P–S", "T–Z"};
        for (String range : ranges)
        {
            JMenu sub = new JMenu(range);
            char from = range.charAt(0);
            char to = range.charAt(2);
            for (BaseMap map : rest)
            {
                char first = Character.toUpperCase(map.name.charAt(0));
                if (first >= from && first <= to || range.equals("T–Z") && !Character.isLetter(first))
                {
                    sub.add(mapItem(map.name, map));
                }
            }
            if (sub.getItemCount() > 0)
            {
                menu.add(sub);
            }
        }
        menu.show(view, at.x, at.y);
    }

    private JMenuItem mapItem(String label, BaseMap map)
    {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> {
            view.setFollowing(false);
            openMap(map);
        });
        return item;
    }

    /** On the full-screen map, the card only floats over the map while it has something to show. */
    private void showCard(boolean show)
    {
        if (layout == Layout.FULL)
        {
            cardFloat.setVisible(show);
            placeFloatingCard();
            layers.repaint();
        }
    }

    /** The full-screen map was opened: start from the player's location, or where the map was left. */
    void opened()
    {
        view.setFollowing(false);
        if (view.player() != null && config.followPlayer())
        {
            // The same close-up view as the centre button, there at once: no zooming in while the map opens.
            view.jumpToPlayer();
        }
    }

    /** New maps and icons; a selected icon stays selected when it still exists. */
    void setData(BaseMaps baseMaps, List<Poi> pois, List<PoiLoader.Place> labels)
    {
        setData(baseMaps, pois, java.util.Collections.emptySet(), labels);
    }

    /** {@code hidden}: of the icons, those the map does not draw (the planner's own passages; see {@link MapData}). */
    void setData(BaseMaps baseMaps, List<Poi> pois, java.util.Set<Poi> hidden, List<PoiLoader.Place> labels)
    {
        Poi previous = view.selected();
        syncing = true;
        maps.setModel(new DefaultComboBoxModel<>(baseMaps.all().toArray(new BaseMap[0])));
        syncing = false;
        view.setMaps(baseMaps);
        view.setPois(pois, hidden);
        view.setLabels(labels);
        Poi same = null;
        for (Poi poi : pois)
        {
            if (previous != null && poi.type == previous.type && poi.location.equals(previous.location))
            {
                same = poi;
                break;
            }
        }
        view.select(same);
        viewChanged();
        prepareSearch();
    }

    void setUnlocks(Unlocks unlocks)
    {
        view.setUnlocks(unlocks);
        card.refresh();
    }

    void setFollowing(boolean following)
    {
        view.setFollowing(following);
    }

    /** Teleports and transport stops that land nearest a point on the same map, closest first. */
    @Override
    public void nearestRequested(WorldPoint point)
    {
        BaseMaps baseMaps = view.maps();
        BaseMap target = baseMaps == null ? null : baseMaps.find(point.getX(), point.getY());
        List<Poi> found = new ArrayList<>();
        for (Poi poi : view.pois())
        {
            boolean arrives = poi.type == PoiType.TELEPORT || poi.type.layer == Layer.TRANSPORTS && !poi.links().isEmpty()
                || poi.nearby().stream().anyMatch(p -> p.type == PoiType.TELEPORT);
            if (arrives && poi.map != null && poi.map == target && view.usable(poi)
                && (poi.type == PoiType.TELEPORT ? config.showTeleports() : config.showTransports()))
            {
                found.add(poi);
            }
        }
        found.sort(Comparator.comparingInt(p -> p.location.distanceTo2D(point)));
        card.showNearest(point, found.subList(0, Math.min(NEAREST, found.size())));
        showCard(true);
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    /** Selects an icon and brings it into view. */
    void focus(Poi poi)
    {
        view.focus(poi);
    }

    /** How the monster search finds out which of overlapping maps shows a spot. */
    void setRegionCheck(java.util.function.BiConsumer<List<WorldPoint>, java.util.function.Consumer<java.util.Map<WorldPoint, BaseMap>>> regionCheck)
    {
        npcs.setRegionCheck(regionCheck);
    }

    /** Asks for a route to a point (the Route buttons of the card and the search); set by the route feature. */
    void setRouter(java.util.function.Consumer<WorldPoint> router)
    {
        card.setRouter(router);
    }

    /** Adds a stop to the custom route being made (the card's and the search's "Add to route"). */
    void setStopAdder(java.util.function.Consumer<Tour.Stop> adder)
    {
        card.setStopAdder(adder);
        npcs.setStopAdder(adder);
    }

    /** Looks a monster or NPC up on the wiki and shows where it is found. */
    void findNpc(String name)
    {
        npcs.findMonster(name);
    }

    /** Looks an item up on the wiki and shows its spawns, the shops with it in stock and what drops it. */
    void findItem(String name)
    {
        npcs.findItem(name);
    }

    /** Shows a short message on the map. */
    void showToast(String text)
    {
        view.showToast(text);
    }

    /** The kind of place of that name ("Yew trees"), or null. */
    KindIndex.Kind kind(String label)
    {
        for (KindIndex.Kind kind : kindIndex().all())
        {
            if (kind.label.equalsIgnoreCase(label))
            {
                return kind;
            }
        }
        return null;
    }

    /** Goes to a place the search found, as a click on it in the card does (development previews). */
    void lookAtResult(int index)
    {
        npcs.lookAt(index);
    }

    /** Shows one list of the search result (development previews). */
    void searchTab(String tab)
    {
        npcs.showTab(tab);
    }

    /** Marks every place of the kind best matching {@code query} ("herb patch"); false when none matches. */
    boolean findKind(String query)
    {
        List<KindIndex.Kind> found = kindIndex().find(query, 1);
        if (found.isEmpty())
        {
            return false;
        }
        npcs.showKind(found.get(0), this::placeName);
        return true;
    }

    void setWindowControls(MapView.WindowControls controls)
    {
        view.setWindowControls(controls);
    }

    /** The map itself, for overlays and menu entries added by other parts of the plugin. */
    MapView view()
    {
        return view;
    }

    /**
     * Shows the route's steps in the card while nothing is selected (null removes them). {@code bringUp} puts
     * them in front of a selected icon, for a route the user just asked for.
     */
    void showRoute(java.util.function.IntFunction<JComponent> route, boolean bringUp)
    {
        if (bringUp && route != null && view.selected() != null)
        {
            view.select(null);
        }
        card.setRoute(route, bringUp);
        if (bringUp || route == null)
        {
            showCard(view.selected() != null || route != null);
        }
        view.repaint();
    }

    void setPlayer(WorldPoint player)
    {
        view.setPlayer(player);
    }

    void refresh()
    {
        view.repaint();
    }

    void stop()
    {
        typing.stop();
        view.stop();
    }

    @Override
    public void selectionChanged(Poi poi)
    {
        card.show(poi);
        showCard(poi != null || card.hasRoute() || card.hasExtra());
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    @Override
    public void viewChanged()
    {
        syncing = true;
        if (view.map() != null && maps.getSelectedItem() != view.map())
        {
            maps.setSelectedItem(view.map());
        }
        if (floors.getSelectedIndex() != view.plane())
        {
            floors.setSelectedIndex(view.plane());
        }
        follow.setSelected(view.isFollowing());
        syncing = false;
    }

    /**
     * Shows a chosen map: around the player when they are on it (the surface usually), else the whole map.
     */
    private void openMap(BaseMap map)
    {
        WorldPoint me = view.player();
        BaseMaps baseMaps = view.maps();
        BaseMap mine = me == null || baseMaps == null ? null : baseMaps.find(me);
        if (mine == map)
        {
            view.rememberForBack(map);
            view.showMap(map, MapView.shownOn(map, me), MapView.PLAYER_ZOOM);
            return;
        }
        view.rememberForBack(map);
        view.showMap(map, null, fitZoom(map));
    }

    /** A zoom level that fits the whole map in view. */
    private double fitZoom(BaseMap map)
    {
        double width = Math.max(100, view.getWidth());
        double height = Math.max(100, view.getHeight());
        // A map with broken bounds (no width or height) must not make the zoom infinite or not a number.
        double fit = Math.min(width / Math.max(1, map.maxX - map.minX), height / Math.max(1, map.maxY - map.minY));
        double zoom = Math.log(fit) / Math.log(2);
        return Double.isFinite(zoom) ? Math.max(MapView.MIN_ZOOM, Math.min(2, zoom)) : MapView.PLAYER_ZOOM;
    }

    private void updateSearchMode()
    {
        searchMode.setIcon(new ImageIcon(mode == SearchMode.MONSTERS ? SearchResults.dotIcon()
            : mode == SearchMode.ITEMS ? SearchResults.itemIcon() : PoiIcons.pinIcon()));
        searchMode.setToolTipText(mode.tooltip);
        search.repaint();
    }

    /** A short pause after the last key before suggestions update, so fast typing does not flood the wiki. */
    private final javax.swing.Timer typing = new javax.swing.Timer(250, e -> showResults());
    /** The suggestions shown now, or null. */
    private JPopupMenu results;

    private void showResults()
    {
        String typed = search.getText().trim();
        if (results != null)
        {
            results.setVisible(false);
            results = null;
        }
        if (typed.length() < 2 || !search.isShowing())
        {
            return;
        }
        JPopupMenu menu = mode == SearchMode.MONSTERS ? wikiResults(typed, false)
            : mode == SearchMode.ITEMS ? wikiResults(typed, true) : placeResults(typed.toLowerCase(Locale.ROOT));
        // The list must not take the keyboard: typing goes on in the field.
        menu.setFocusable(false);
        results = menu;
        boolean typing = search.isFocusOwner();
        menu.show(search, 0, search.getHeight());
        // Kept in the field while typing there; never taken from the game when the list shows otherwise.
        if (typing)
        {
            search.requestFocusInWindow();
        }
    }

    /** Enter: the first suggestion, as if clicked. */
    private void chooseFirst()
    {
        typing.stop();
        if (results == null || !results.isVisible())
        {
            showResults();
        }
        JPopupMenu menu = results;
        if (menu == null)
        {
            return;
        }
        for (java.awt.Component item : menu.getComponents())
        {
            if (item instanceof JMenuItem && item.isEnabled())
            {
                menu.setVisible(false);
                ((JMenuItem) item).doClick(0);
                return;
            }
        }
    }

    /** At most this many of the game's own icons (furnaces, water sources…) among the results. */
    private static final int MAX_GAME_ICONS = 4;

    /**
     * Places first (towns, islands, kingdoms), then separate maps, then our icons, then a few of the game's icons:
     * names starting with the text before names only containing it.
     */
    private JPopupMenu placeResults(String query)
    {
        JPopupMenu menu = new JPopupMenu();
        List<SearchIndex.Hit> hits = searchIndex().find(query, MAX_RESULTS * 2, hit -> {
            switch (hit.type)
            {
                case ICON:
                    Poi poi = (Poi) hit.target;
                    return poi.map != null && view.usable(poi);
                case GAME_ICON:
                    return config.showGameIcons();
                default:
                    return true;
            }
        });
        int shown = 0;
        int shownGameIcons = 0;
        for (SearchIndex.Hit hit : hits)
        {
            // A few of the game's own icons (furnaces, water sources…) at most.
            if (shown >= MAX_RESULTS || hit.type == SearchIndex.Type.GAME_ICON && ++shownGameIcons > MAX_GAME_ICONS)
            {
                continue;
            }
            shown++;
            JMenuItem item = new JMenuItem(hit.label);
            if (hit.icon != null)
            {
                item.setIcon(new ImageIcon(PoiIcons.image(hit.icon, 14)));
            }
            else if (hit.type == SearchIndex.Type.KIND || hit.type == SearchIndex.Type.KIND_PLACE)
            {
                item.setIcon(new ImageIcon(SearchResults.dotIcon()));
            }
            item.addActionListener(e -> choose(hit));
            menu.add(item);
        }
        if (menu.getComponentCount() == 0)
        {
            JMenuItem none = new JMenuItem("Nothing found. Looking for a monster or item? Switch the search with the button.");
            none.setEnabled(false);
            menu.add(none);
        }
        return menu;
    }

    /** What picking a place result does. */
    private void choose(SearchIndex.Hit hit)
    {
        switch (hit.type)
        {
            case PLACE:
                view.focus(((PoiLoader.Place) hit.target).point);
                break;
            case MAP:
                view.setFollowing(false);
                openMap((BaseMap) hit.target);
                break;
            case KIND:
                npcs.showKind((KindIndex.Kind) hit.target, this::placeName);
                break;
            case KIND_PLACE:
                // Every one marked, and the one at that place looked at.
                npcs.showKind((KindIndex.Kind) hit.target, this::placeName);
                npcs.lookAtPoint(hit.point);
                break;
            default:
                view.focus((Poi) hit.target);
                break;
        }
    }

    /** What the place search looks in: every place of a kind, and everything with a name. */
    private static final class Indexes
    {
        final KindIndex kinds;
        final SearchIndex search;

        Indexes(KindIndex kinds, SearchIndex search)
        {
            this.kinds = kinds;
            this.search = search;
        }
    }

    /**
     * Builds the search indexes, one after the other, off the Swing thread (building them took a noticeable moment
     * on the first key typed). Its one thread ends when idle.
     */
    private static final java.util.concurrent.ThreadPoolExecutor INDEXER = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "HD Map search index");
            thread.setDaemon(true);
            return thread;
        });

    static
    {
        INDEXER.allowCoreThreadTimeOut(true);
    }

    /** The indexes ready, and the data they were built from; the ones being built. Swing thread only. */
    private Indexes indexes;
    private List<Object> indexesFrom;
    private java.util.concurrent.Future<Indexes> building;
    private List<Object> buildingFrom;

    /** The data the indexes are built from: new lists mean new icons or maps. */
    private List<Object> indexSources()
    {
        return java.util.Arrays.asList(view.pois(), view.searchExtras(), MapIconLoader.skillSpots(), view.labels(),
            view.maps());
    }

    /** Starts building the indexes in the background unless they are ready, or on their way, for the data now. */
    private void prepareSearch()
    {
        List<Object> from = indexSources();
        if (indexes != null && sameLists(from, indexesFrom) || building != null && sameLists(from, buildingFrom))
        {
            return;
        }
        // Everything read here, on the Swing thread; the lists are not changed once set, only replaced.
        List<Poi> pois = view.pois();
        List<Poi> extras = view.searchExtras();
        List<SkillSpots.Spot> spots = MapIconLoader.skillSpots().all();
        List<PoiLoader.Place> labels = view.labels();
        List<BaseMap> all = allMaps();
        BaseMaps baseMaps = view.maps();
        buildingFrom = from;
        building = INDEXER.submit(() -> {
            KindIndex kinds = KindIndex.build(pois, extras, spots);
            return new Indexes(kinds, SearchIndex.build(labels, all, pois, extras, kinds,
                point -> placeName(baseMaps, labels, point)));
        });
    }

    /** The indexes for the data now: ready, or waited for (built now when that fails). */
    private Indexes indexes()
    {
        List<Object> from = indexSources();
        if (indexes != null && sameLists(from, indexesFrom))
        {
            return indexes;
        }
        prepareSearch();
        java.util.concurrent.Future<Indexes> pending = building;
        List<Object> pendingFrom = buildingFrom;
        building = null;
        buildingFrom = null;
        Indexes ready;
        try
        {
            ready = pending.get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            ready = buildNow();
        }
        catch (java.util.concurrent.ExecutionException e)
        {
            ready = buildNow();
        }
        indexes = ready;
        indexesFrom = pendingFrom;
        return ready;
    }

    private Indexes buildNow()
    {
        KindIndex kinds = KindIndex.build(view.pois(), view.searchExtras(), MapIconLoader.skillSpots().all());
        return new Indexes(kinds, SearchIndex.build(view.labels(), allMaps(), view.pois(), view.searchExtras(), kinds,
            this::placeName));
    }

    private SearchIndex searchIndex()
    {
        return indexes().search;
    }

    /** Monsters and NPCs, or items, from the wiki; pick one to see where it is. */
    private JPopupMenu wikiResults(String typed, boolean items)
    {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem pending = new JMenuItem("Searching the wiki…");
        pending.setEnabled(false);
        menu.add(pending);
        java.util.function.Consumer<List<String>> show = titles -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (menu != results)
            {
                return;
            }
            menu.remove(pending);
            if (titles.isEmpty())
            {
                JMenuItem none = new JMenuItem(items ? "No items found" : "No monsters or NPCs with locations found");
                none.setEnabled(false);
                menu.add(none);
            }
            for (String title : titles)
            {
                JMenuItem suggestion = new JMenuItem(title);
                suggestion.setIcon(new ImageIcon(items ? SearchResults.itemIcon() : SearchResults.dotIcon()));
                suggestion.addActionListener(e -> {
                    search.setText(title);
                    typing.stop();
                    if (items)
                    {
                        npcs.findItem(title);
                    }
                    else
                    {
                        npcs.findMonster(title);
                    }
                });
                menu.add(suggestion);
            }
            if (menu.isVisible())
            {
                menu.pack();
                menu.revalidate();
                menu.repaint();
            }
        });
        if (items)
        {
            wiki.suggestItems(typed, 10, show);
        }
        else
        {
            wiki.suggestNpcs(typed, 10, show);
        }
        return menu;
    }


    /** The kinds of places, built again when the icons change. */
    private KindIndex kindIndex()
    {
        return indexes().kinds;
    }

    private static boolean sameLists(List<Object> a, Object b)
    {
        if (!(b instanceof List) || ((List<?>) b).size() != a.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            if (a.get(i) != ((List<?>) b).get(i))
            {
                return false;
            }
        }
        return true;
    }

    /** What a place is near: the town or island on the surface, else the map it is on (a dungeon). */
    String placeName(WorldPoint point)
    {
        return placeName(view.maps(), view.labels(), point);
    }

    private static String placeName(BaseMaps baseMaps, List<PoiLoader.Place> labels, WorldPoint point)
    {
        BaseMap map = baseMaps == null ? null : baseMaps.find(point);
        if (map != null && map.id != BaseMap.SURFACE)
        {
            return map.name;
        }
        return MapIconLoader.settlement(labels, point);
    }

    private List<BaseMap> allMaps()
    {
        List<BaseMap> list = new ArrayList<>();
        for (int i = 0; i < maps.getItemCount(); i++)
        {
            list.add(maps.getItemAt(i));
        }
        return list;
    }
}
