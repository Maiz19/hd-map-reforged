package com.hdmapreforged;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.IntFunction;
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
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;

/** The map with its controls and detail card: sidebar, large window and game-view map. */
final class MapScreen extends JPanel implements MapView.Listener
{
    enum Layout
    {
        SIDEBAR,
        WINDOW,
        /** Over the game: floating controls and card; the search field takes keys only once clicked. */
        FULL
    }

    private static final int MAX_RESULTS = 14;
    private static final int NEAREST = 10;
    private static final int FLOATING_WIDTH = 290;
    private static final int FLOATING_TEXT = 195;

    private final MapView view;
    private final InfoCard card;
    private final JScrollPane cardScroll;
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
        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner())
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(new Color(150, 150, 150));
                Insets in = getInsets();
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
                tours.replaced();
            }
            showSection(section);
        });
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
        search.addActionListener(e -> chooseFirst());
        // Build the search indexes in the background while the user starts typing.
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
            public void insertUpdate(DocumentEvent e)
            {
                typing.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                typing.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
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
            showResults();
            // Never take focus from the game.
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

    /** Null until the route feature is set up. */
    private TourPanel tours;

    void setTours(TourPanel.Actions actions)
    {
        boolean first = tours == null;
        tours = actions == null ? null : new TourPanel(actions, this::showTours, view::focus);
        if (first && tours != null)
        {
            // Not in the sidebar: too small for it.
            view.addWidget(new RoutesButton(this::toggleTours, () -> tours != null && tours.isOpen(),
                () -> layout != Layout.SIDEBAR));
        }
    }

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

    void refreshTours()
    {
        if (tours != null)
        {
            tours.refresh();
        }
    }

    private final JPanel toursPanel = new JPanel(new BorderLayout());
    private JScrollPane toursScroll;

    /** Over the game in their own panel beside the Routes button; elsewhere in the card. */
    private void showTours(IntFunction<JComponent> section)
    {
        if (layout != Layout.FULL)
        {
            showSection(section);
            return;
        }
        // Above the card and search results, which could otherwise steal its clicks on a narrow map.
        toursScroll = showFloating(toursScroll, toursPanel, toursFloat, section, JLayeredPane.PALETTE_LAYER + 1);
    }

    private void showSection(IntFunction<JComponent> section)
    {
        if (layout == Layout.FULL)
        {
            npcScroll = showFloating(npcScroll, npcPanel, npcFloat, section, JLayeredPane.PALETTE_LAYER);
            return;
        }
        card.setExtra(section);
        showCard(section != null || view.selected() != null || card.hasRoute());
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    private final JPanel npcPanel = new JPanel(new BorderLayout());
    private JScrollPane npcScroll;

    /** A panel of its own over the game; returns its scroll pane, made on first use. */
    private JScrollPane showFloating(JScrollPane scroll, JPanel panel, Floating floating, IntFunction<JComponent> section,
        Integer layer)
    {
        if (scroll == null)
        {
            panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            scroll = new JScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            floating.setContent(scroll);
        }
        panel.removeAll();
        if (section != null)
        {
            panel.add(section.apply(FLOATING_TEXT), BorderLayout.NORTH);
        }
        if (floating.getParent() != layers)
        {
            layers.add(floating, layer);
        }
        floating.setVisible(section != null);
        placeFloatingCard();
        panel.revalidate();
        layers.repaint();
        return scroll;
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
        int top = 58;
        // Clear of the zoom and follow buttons bottom right.
        int height = cardFloat.height(Math.max(120, Math.min(card.getPreferredSize().height + 4, h - top - 170)));
        cardFloat.setBounds(w - cardWidth - 12, top, cardWidth, height);
        int searchWidth = Math.min(260, Math.max(120, w / 4));
        search.setBounds(12, 52, searchWidth, 28);
        searchMode.setBounds(12 + searchWidth + 4, 52, 28, 28);
        if (toursFloat.isVisible())
        {
            int buttonWidth = MapView.floorGroupWidth(view.getFontMetrics(MapView.CONTROL_FONT));
            int bottomY = MapView.widgetBottom(h);
            int toursHeight = toursFloat.height(Math.max(90, Math.min(toursPanel.getPreferredSize().height + 20,
                bottomY - 60 - Floating.BAR)));
            toursFloat.setBounds(12 + buttonWidth + 8, bottomY - toursHeight, FLOATING_WIDTH, toursHeight);
            toursFloat.revalidate();
        }
        if (npcFloat.isVisible())
        {
            int npcHeight = npcFloat.height(Math.max(90, Math.min(npcPanel.getPreferredSize().height + 20,
                h - 90 - 70 - Floating.BAR)));
            npcFloat.setBounds(12, 90, FLOATING_WIDTH, npcHeight);
            npcFloat.revalidate();
        }
        cardFloat.revalidate();
    }

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

    private void showCard(boolean show)
    {
        if (layout == Layout.FULL)
        {
            cardFloat.setVisible(show);
            placeFloatingCard();
            layers.repaint();
        }
    }

    void opened()
    {
        view.setFollowing(false);
        if (view.player() != null && config.followPlayer())
        {
            view.jumpToPlayer();
        }
    }

    void setData(BaseMaps baseMaps, List<Poi> pois, List<PoiLoader.Place> labels)
    {
        setData(baseMaps, pois, java.util.Collections.emptySet(), labels);
    }

    /** {@code hidden}: icons kept for the planner but not drawn. */
    void setData(BaseMaps baseMaps, List<Poi> pois, Set<Poi> hidden, List<PoiLoader.Place> labels)
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

    void focus(Poi poi)
    {
        view.focus(poi);
    }

    void setRegionCheck(java.util.function.BiConsumer<List<WorldPoint>, Consumer<java.util.Map<WorldPoint, BaseMap>>> regionCheck)
    {
        npcs.setRegionCheck(regionCheck);
    }

    void setRouter(Consumer<WorldPoint> router)
    {
        card.setRouter(router);
    }

    void setStopAdder(Consumer<Tour.Stop> adder)
    {
        card.setStopAdder(adder);
        npcs.setStopAdder(adder);
    }

    void findNpc(String name)
    {
        npcs.findMonster(name);
    }

    void findItem(String name)
    {
        npcs.findItem(name);
    }

    void showToast(String text)
    {
        view.showToast(text);
    }

    KindIndex.Kind kind(String label)
    {
        for (KindIndex.Kind kind : indexes().kinds.all())
        {
            if (kind.label.equalsIgnoreCase(label))
            {
                return kind;
            }
        }
        return null;
    }

    /** Development previews. */
    void lookAtResult(int index)
    {
        npcs.lookAt(index);
    }

    void searchTab(String tab)
    {
        npcs.showTab(tab);
    }

    boolean findKind(String query)
    {
        List<KindIndex.Kind> found = indexes().kinds.find(query, 1);
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

    MapView view()
    {
        return view;
    }

    /** {@code bringUp}: show in front of a selected icon, for a route the user just asked for. */
    void showRoute(IntFunction<JComponent> route, boolean bringUp)
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

    private double fitZoom(BaseMap map)
    {
        double width = Math.max(100, view.getWidth());
        double height = Math.max(100, view.getHeight());
        // Broken bounds must not make the zoom infinite or NaN.
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

    /** Debounce so fast typing does not flood the wiki. */
    private final Timer typing = new Timer(250, e -> showResults());
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
        menu.setFocusable(false);
        results = menu;
        boolean typing = search.isFocusOwner();
        menu.show(search, 0, search.getHeight());
        // Never take focus from the game.
        if (typing)
        {
            search.requestFocusInWindow();
        }
    }

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

    private static final int MAX_GAME_ICONS = 4;

    private JPopupMenu placeResults(String query)
    {
        JPopupMenu menu = new JPopupMenu();
        List<SearchIndex.Hit> hits = indexes().search.find(query, MAX_RESULTS * 2, hit -> {
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
                npcs.showKind((KindIndex.Kind) hit.target, this::placeName);
                npcs.lookAtPoint(hit.point);
                break;
            default:
                view.focus((Poi) hit.target);
                break;
        }
    }

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

    /** Builds search indexes off the Swing thread (slow on the first key otherwise). */
    private static final ThreadPoolExecutor INDEXER = new ThreadPoolExecutor(
        1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "HD Map search index");
            thread.setDaemon(true);
            return thread;
        });

    static
    {
        INDEXER.allowCoreThreadTimeOut(true);
    }

    /** Swing thread only. */
    private Indexes indexes;
    private List<Object> indexesFrom;
    private Future<Indexes> building;
    private List<Object> buildingFrom;

    private List<Object> indexSources()
    {
        return java.util.Arrays.asList(view.pois(), view.searchExtras(), MapIconLoader.skillSpots(), view.labels(),
            view.maps());
    }

    private void prepareSearch()
    {
        List<Object> from = indexSources();
        if (indexes != null && sameLists(from, indexesFrom) || building != null && sameLists(from, buildingFrom))
        {
            return;
        }
        // Read on the Swing thread; the lists are replaced, never changed.
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

    private Indexes indexes()
    {
        List<Object> from = indexSources();
        if (indexes != null && sameLists(from, indexesFrom))
        {
            return indexes;
        }
        prepareSearch();
        Future<Indexes> pending = building;
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

    private JPopupMenu wikiResults(String typed, boolean items)
    {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem pending = new JMenuItem("Searching the wiki…");
        pending.setEnabled(false);
        menu.add(pending);
        Consumer<List<String>> show = titles -> javax.swing.SwingUtilities.invokeLater(() -> {
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
