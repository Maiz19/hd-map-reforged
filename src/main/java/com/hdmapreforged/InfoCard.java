package com.hdmapreforged;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Details of the selected icon: requirements (ticked off when logged in), where it leads and what each trip needs,
 * a wiki summary and links. Also lists the teleports nearest a chosen point.
 */
final class InfoCard extends JPanel implements Scrollable
{
    private static final int MAX_LINKS = 60;
    private static final Color MET = new Color(110, 200, 110);
    private static final Color MISSING = new Color(230, 110, 100);
    private static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;

    private final MapView map;
    private final WikiClient wiki;
    private final ItemNames itemNames;
    private final HdMapReforgedConfig config;
    private Poi poi;
    private WorldPoint nearestTo;
    private List<Poi> nearest = new ArrayList<>();
    private int textWidth = 160;
    /** The route's steps, shown while no icon is selected; built for the card's text width. */
    private java.util.function.IntFunction<javax.swing.JComponent> route;
    /** Called after the card's content changed, so a floating card can resize to it. */
    private Runnable onRebuilt = () -> { };

    InfoCard(MapView map, WikiClient wiki, ItemNames itemNames, HdMapReforgedConfig config)
    {
        this.map = map;
        this.wiki = wiki;
        this.itemNames = itemNames;
        this.config = config;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        rebuild();
    }

    void setOnRebuilt(Runnable onRebuilt)
    {
        this.onRebuilt = onRebuilt;
    }

    void setTextWidth(int width)
    {
        textWidth = width;
        rebuild();
    }

    /** A shop's wares, from its wiki page, once known; null while not (or not a shop). */
    private List<ItemSources.Ware> wares;
    private boolean waresLoading;
    /** Looks an item up in the item search. */
    private java.util.function.Consumer<String> itemSearch;

    void setItemSearch(java.util.function.Consumer<String> itemSearch)
    {
        this.itemSearch = itemSearch;
    }

    /** Adds a stop to the custom route being made, or null. */
    private java.util.function.Consumer<Tour.Stop> stopAdder;

    void setStopAdder(java.util.function.Consumer<Tour.Stop> stopAdder)
    {
        this.stopAdder = stopAdder;
        rebuild();
    }

    /** Asks for a route to a point, or null while there is no route feature. */
    private java.util.function.Consumer<WorldPoint> router;

    void setRouter(java.util.function.Consumer<WorldPoint> router)
    {
        this.router = router;
        rebuild();
    }

    /** Rebuilds with current unlock and item data. */
    void refresh()
    {
        rebuild();
    }

    void show(Poi poi)
    {
        this.poi = poi;
        nearestTo = null;
        wares = null;
        waresLoading = false;
        if (poi != null && poi.type == PoiType.SHOP && poi.wikiQuery != null)
        {
            // What it sells: its wiki page found by name (a search when the name is not the page's), then its stock.
            waresLoading = true;
            wiki.page(poi.wikiQuery, page -> {
                if (page == null)
                {
                    SwingUtilities.invokeLater(() -> {
                        if (this.poi == poi)
                        {
                            waresLoading = false;
                            rebuild();
                        }
                    });
                    return;
                }
                wiki.store(page, found -> SwingUtilities.invokeLater(() -> {
                    if (this.poi == poi)
                    {
                        wares = found;
                        waresLoading = false;
                        rebuild();
                        loadWarePictures(poi, found);
                    }
                }));
            });
        }
        if (poi != null)
        {
            List<Integer> ids = new ArrayList<>();
            for (Poi member : poi.members())
            {
                ids.addAll(Requirements.itemIds(member.needs.items));
            }
            for (Poi.Link link : poi.links())
            {
                ids.addAll(Requirements.itemIds(link.needs.items));
            }
            if (!ids.isEmpty())
            {
                itemNames.resolve(ids, () -> {
                    if (this.poi == poi)
                    {
                        rebuild();
                    }
                });
            }
        }
        rebuild();
    }

    /** Sets or clears the route section; {@code show} also brings it up in place of other content. */
    void setRoute(java.util.function.IntFunction<javax.swing.JComponent> route, boolean show)
    {
        this.route = route;
        if (show && route != null)
        {
            poi = null;
            nearestTo = null;
        }
        rebuild();
    }

    boolean hasRoute()
    {
        return route != null;
    }

    /** Another section shown while no icon is selected, such as where a monster is found; null removes it. */
    void setExtra(java.util.function.IntFunction<javax.swing.JComponent> extra)
    {
        this.extra = extra;
        if (extra != null)
        {
            poi = null;
            nearestTo = null;
        }
        rebuild();
    }

    boolean hasExtra()
    {
        return extra != null;
    }

    private java.util.function.IntFunction<javax.swing.JComponent> extra;

    void showNearest(WorldPoint point, List<Poi> found)
    {
        map.select(null);
        poi = null;
        nearestTo = point;
        nearest = new ArrayList<>(found);
        rebuild();
    }

    /**
     * Builds the card again, but not while the map animates: building a card with many rows takes long enough to
     * make the map stutter, as when clicking another teleport of the same item flies the map there.
     */
    private void rebuild()
    {
        if (!map.isAnimating())
        {
            if (waiting != null)
            {
                waiting.stop();
                waiting = null;
            }
            rebuildNow();
            return;
        }
        if (waiting == null)
        {
            // Until then only the name, cheap to show, so the buttons of what was selected before cannot be used.
            removeAll();
            if (poi != null)
            {
                JLabel title = new JLabel(poi.name);
                title.setForeground(Color.WHITE);
                title.setFont(title.getFont().deriveFont(Font.BOLD));
                title.setAlignmentX(Component.LEFT_ALIGNMENT);
                add(title);
            }
            revalidate();
            repaint();
            waiting = new javax.swing.Timer(40, e -> {
                if (!map.isAnimating())
                {
                    waiting.stop();
                    waiting = null;
                    rebuildNow();
                }
            });
            waiting.start();
        }
    }

    private javax.swing.Timer waiting;

    private void rebuildNow()
    {
        removeAll();
        wareIcons.clear();
        if (nearestTo != null)
        {
            buildNearest();
        }
        else if (poi == null && (extra != null || route != null))
        {
            // A monster search and a route both stay in view: the search first, then the route.
            if (extra != null)
            {
                add(extra.apply(textWidth));
            }
            if (extra != null && route != null)
            {
                add(Box.createVerticalStrut(8));
                javax.swing.JSeparator line = new javax.swing.JSeparator();
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                add(line);
                add(Box.createVerticalStrut(4));
            }
            if (route != null)
            {
                add(route.apply(textWidth));
            }
        }
        else if (poi == null)
        {
            add(text("Click an icon for details. Scroll to zoom, drag to move, double-click to zoom in. "
                + "Right-click for the nearest teleports to a spot.", ColorScheme.LIGHT_GRAY_COLOR, false));
        }
        else
        {
            buildDetails();
        }
        revalidate();
        repaint();
        onRebuilt.run();
    }

    private void buildNearest()
    {
        add(text("Nearest teleports to " + nearestTo.getX() + ", " + nearestTo.getY(), Color.WHITE, true));
        add(text("As the crow flies, from where each one lands. Walking routes may differ.",
            ColorScheme.LIGHT_GRAY_COLOR, false));
        if (nearest.isEmpty())
        {
            add(text("None on this map.", ColorScheme.LIGHT_GRAY_COLOR, false));
        }
        for (Poi found : nearest)
        {
            JLabel entry = link(found.name + "  (" + found.location.distanceTo2D(nearestTo) + " tiles)", () -> map.focus(found));
            entry.setIcon(new ImageIcon(PoiIcons.image(found.type, 12)));
            add(entry);
        }
    }

    private void buildDetails()
    {
        JLabel title = text(poi.name, Color.WHITE, true);
        title.setIcon(new ImageIcon(PoiIcons.image(poi.type, 16)));
        title.setIconTextGap(6);
        add(title);
        String where = poi.type.displayName + (poi.map != null ? " · " + poi.map.name : "") + " · "
            + poi.location.getX() + ", " + poi.location.getY() + (poi.location.getPlane() > 0 ? ", floor " + poi.location.getPlane() : "");
        add(text(where, ColorScheme.LIGHT_GRAY_COLOR, false));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        Poi.Link inside = MapView.mapBelow(poi);
        if (inside != null)
        {
            buttons.add(button(MapView.goInText(inside), () -> map.goIn(inside)));
            buttons.add(Box.createHorizontalStrut(4));
        }
        if (router != null)
        {
            Poi target = poi;
            JButton route = button("Route", () -> router.accept(target.location));
            route.setToolTipText("A route from where you are to here");
            buttons.add(route);
            buttons.add(Box.createHorizontalStrut(4));
        }
        if (stopAdder != null)
        {
            Poi target = poi;
            JButton add = button("+ Stop", () -> stopAdder.accept(Tour.Stop.place(target.name, target.location)));
            add.setToolTipText("Add to your custom route (right-click the map, Custom routes...)");
            buttons.add(add);
            buttons.add(Box.createHorizontalStrut(4));
        }
        buttons.add(button("Wiki", () -> LinkBrowser.browse(
            WikiClient.searchUrl(poi.wikiQuery != null ? poi.wikiQuery : poi.name))));
        add(Box.createVerticalStrut(6));
        add(wrap(buttons, textWidth));
        if (extra != null)
        {
            // Back to what a search found, which this icon was picked from.
            add(Box.createVerticalStrut(4));
            JLabel back = link("← Back to the search results", () -> map.select(null));
            add(back);
        }

        if (poi.note != null)
        {
            add(Box.createVerticalStrut(6));
            add(text(poi.note, new Color(255, 200, 120), false));
        }

        if (!poi.nearby().isEmpty())
        {
            boolean teleports = poi.nearby().stream().allMatch(p -> p.type == PoiType.TELEPORT);
            heading(teleports ? "Teleports that land here" : "Also here");
            for (Poi other : poi.nearby())
            {
                for (Poi member : other.members())
                {
                    boolean usable = map.unlocks() == null || map.unlocks().usable(member.needs);
                    if (!usable && config.onlyUsable())
                    {
                        continue;
                    }
                    String kind = member.type == PoiType.TELEPORT || teleports ? "" : "  (" + member.type.displayName + ")";
                    add(text((usable ? "" : "✗ ") + member.name + kind, usable ? Color.WHITE : MISSING, false));
                    List<Requirements.Line> needs = Requirements.describe(member.needs, itemNames);
                    if (!needs.isEmpty())
                    {
                        addRequirements(needs, "    ");
                    }
                }
            }
        }

        if (poi.type == PoiType.SHOP)
        {
            buildWares();
        }

        List<Poi> members = poi.members();
        if (members.size() > 1)
        {
            // Several teleports land here: each with what it needs.
            heading("Ways to get here (" + members.size() + ")");
            for (Poi member : members)
            {
                boolean usable = map.unlocks() == null || map.unlocks().usable(member.needs);
                if (!usable && config.onlyUsable())
                {
                    continue;
                }
                add(text((usable ? "" : "✗ ") + member.name, usable ? Color.WHITE : MISSING, false));
                List<Requirements.Line> needs = Requirements.describe(member.needs, itemNames);
                if (!needs.isEmpty())
                {
                    addRequirements(needs, "    ");
                }
            }
        }
        else
        {
            List<Requirements.Line> requirements = Requirements.describe(poi.needs, itemNames);
            if (!requirements.isEmpty())
            {
                heading("Requirements");
                addRequirements(requirements, "");
            }
        }

        List<Poi.Link> links = poi.links();
        java.util.Set<String> ownLines = new java.util.HashSet<>();
        for (Requirements.Line line : Requirements.describe(poi.needs, itemNames))
        {
            ownLines.add(line.text);
        }
        if (!links.isEmpty())
        {
            String linkHeading = poi.type.isRandomDestination() ? "Can send you to (" + links.size() + ")"
                : poi.type.isNetwork() ? "Travel to (" + links.size() + ")" : "Leads to";
            heading(linkHeading);
            // Lines on the map to every destination: on by default, off for fairy rings (dozens of them).
            javax.swing.JCheckBox lines = new javax.swing.JCheckBox("Show lines on the map", map.linesShown(poi.type));
            lines.setOpaque(false);
            lines.setFocusable(false);
            lines.setFont(FontManager.getRunescapeSmallFont());
            lines.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            lines.setAlignmentX(Component.LEFT_ALIGNMENT);
            lines.setToolTipText("Draw lines from this " + poi.type.displayName.toLowerCase(java.util.Locale.ROOT)
                + " to where it leads; remembered for every " + poi.type.displayName.toLowerCase(java.util.Locale.ROOT));
            PoiType type = poi.type;
            lines.addActionListener(e -> map.setLinesShown(type, lines.isSelected()));
            add(lines);
            for (int i = 0; i < links.size() && i < MAX_LINKS; i++)
            {
                Poi.Link link = links.get(i);
                boolean usable = map.unlocks() == null || map.unlocks().usable(link.needs);
                if (!usable && config.onlyUsable())
                {
                    continue;
                }
                String label = link.label + (link.map != null && link.map != poi.map && !link.label.contains(link.map.name)
                    ? "  (" + link.map.name + ")" : "");
                JLabel entry = link((usable ? "" : "✗ ") + label, () -> goTo(link));
                if (!usable)
                {
                    entry.setToolTipText("You do not meet a requirement for this trip");
                }
                add(entry);
                // Only what this trip needs on top of the stop's own requirements, which are listed above already.
                List<Requirements.Line> tripNeeds = new ArrayList<>(Requirements.describe(link.needs, itemNames));
                tripNeeds.removeIf(line -> ownLines.contains(line.text));
                if (!tripNeeds.isEmpty() && !sameNeeds(link.needs, poi.needs))
                {
                    addRequirements(tripNeeds, "    ");
                }
            }
        }

        List<Poi> group = groupOf(poi);
        if (!group.isEmpty())
        {
            String kind = poi.group.toLowerCase(Locale.ROOT).endsWith("teleports") ? poi.group : poi.group + " teleports";
            heading("Other " + kind + " (" + group.size() + ")");
            for (int i = 0; i < group.size() && i < MAX_LINKS; i++)
            {
                Poi other = group.get(i);
                add(link(shortName(other), () -> map.focus(other)));
            }
        }
    }

    /**
     * The buttons of a row, in as many rows as the width needs: a narrow card (the sidebar, the floating card)
     * cannot show four buttons side by side.
     */
    static JPanel wrap(JPanel row, int width)
    {
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel line = null;
        int used = 0;
        for (Component c : row.getComponents())
        {
            if (!(c instanceof JButton))
            {
                continue;
            }
            int w = c.getPreferredSize().width;
            if (line == null || used + 4 + w > width + 70)
            {
                if (line != null)
                {
                    rows.add(Box.createVerticalStrut(4));
                }
                line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                line.setOpaque(false);
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                rows.add(line);
                used = 0;
            }
            else
            {
                line.add(Box.createHorizontalStrut(4));
                used += 4;
            }
            line.add(c);
            used += w;
        }
        return rows;
    }

    /** The wares' pictures, by item name, as they arrive. */
    private final java.util.Map<String, java.awt.image.BufferedImage> warePictures = new java.util.LinkedHashMap<String,
        java.awt.image.BufferedImage>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, java.awt.image.BufferedImage> eldest)
        {
            // The pictures of the last few shops looked at, not of every ware since the plugin started.
            return size() > MAX_WARE_PICTURES;
        }
    };
    private static final int MAX_WARE_PICTURES = 200;
    /** The picture squares of the wares shown, by item, to fill in when a picture arrives. */
    private final java.util.Map<String, JLabel> wareIcons = new java.util.HashMap<>();

    private void loadWarePictures(Poi shop, List<ItemSources.Ware> list)
    {
        for (int i = 0; i < list.size() && i < MAX_WARES; i++)
        {
            ItemSources.Ware ware = list.get(i);
            if (ware.image.isEmpty() || warePictures.containsKey(ware.item))
            {
                continue;
            }
            wiki.file(ware.image, image -> SwingUtilities.invokeLater(() -> {
                if (image != null)
                {
                    warePictures.put(ware.item, image);
                    // Into the row shown: no rebuild of the whole card for each picture.
                    JLabel shown = this.poi == shop ? wareIcons.get(ware.item) : null;
                    if (shown != null)
                    {
                        shown.setIcon(SearchResults.fitted(image));
                    }
                }
            }));
        }
    }

    /** A shop's stock: each item with its picture, stock and price; an item opens its wiki page. */
    private void buildWares()
    {
        if (waresLoading)
        {
            heading("Stock");
            add(text("Loading…", ColorScheme.LIGHT_GRAY_COLOR, false));
            return;
        }
        if (wares == null || wares.isEmpty())
        {
            return;
        }
        heading("Stock (" + wares.size() + ")");
        for (int i = 0; i < wares.size() && i < MAX_WARES; i++)
        {
            ItemSources.Ware ware = wares.get(i);
            boolean inStock = ItemSources.inStock(ware.stock);
            String facts = (inStock ? ware.stock.equals("∞") ? "Endless stock" : "Stock " + ware.stock
                : "Out of stock") + (ware.price.isEmpty() ? "" : " · " + ware.price);
            add(Box.createVerticalStrut(3));
            add(pictureRow(warePictures.get(ware.item), ware.item, facts, inStock ? ColorScheme.LIGHT_GRAY_COLOR
                : new Color(170, 110, 110), () -> LinkBrowser.browse(WikiClient.pageUrl(ware.item)),
                "Open " + ware.item + " on the wiki", itemSearch == null ? null : () -> itemSearch.accept(ware.item)));
        }
        if (wares.size() > MAX_WARES)
        {
            add(text((wares.size() - MAX_WARES) + " more on the wiki page", ColorScheme.LIGHT_GRAY_COLOR, false));
        }
    }

    /** Room for a picture in a row: the size of an inventory slot. */
    static final int PICTURE = 36;

    /**
     * One row of a list with a picture: the picture (or an empty slot while it loads) on the left, the name as a link
     * and a grey line under it; {@code where}, when given, adds a small "Where" link (an item's other places).
     */
    javax.swing.JComponent pictureRow(java.awt.image.BufferedImage picture, String name, String facts, Color factColor,
        Runnable open, String tip, Runnable where)
    {
        JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(PICTURE, PICTURE));
        icon.setHorizontalAlignment(JLabel.CENTER);
        icon.setOpaque(true);
        icon.setBackground(new Color(255, 255, 255, 12));
        if (picture != null)
        {
            icon.setIcon(SearchResults.fitted(picture));
        }
        wareIcons.put(name, icon);
        row.add(icon, java.awt.BorderLayout.WEST);
        JPanel words = new JPanel();
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        words.setOpaque(false);
        JLabel title = link(name, open);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setToolTipText(tip);
        words.add(title);
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        line.setOpaque(false);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel detail = new JLabel(facts);
        detail.setForeground(factColor);
        line.add(detail);
        if (where != null)
        {
            line.add(Box.createHorizontalStrut(8));
            JLabel find = link("Where else?", where);
            find.setToolTipText("Spawns, other shops and drops of " + name);
            line.add(find);
        }
        words.add(line);
        row.add(words, java.awt.BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(PICTURE, row.getPreferredSize().height)));
        return row;
    }

    /** Wares listed at most. */
    private static final int MAX_WARES = 60;

    private static boolean sameNeeds(Needs a, Needs b)
    {
        return a.skills.equals(b.skills) && a.items.equals(b.items) && a.quests.equals(b.quests);
    }

    /** Requirement lines, green or red when the player's state is known. */
    private void addRequirements(List<Requirements.Line> lines, String indent)
    {
        Unlocks unlocks = map.unlocks();
        for (Requirements.Line line : lines)
        {
            Boolean met = unlocks == null ? null : unlocks.met(line);
            String mark = met == null ? "• " : met ? "✓ " : "✗ ";
            Color color = met == null ? Color.WHITE : met ? MET : MISSING;
            add(text(indent + mark + line.text, color, false));
        }
    }

    /** Other teleports of the same item or spellbook. */
    private List<Poi> groupOf(Poi poi)
    {
        List<Poi> group = new ArrayList<>();
        if (poi.type != PoiType.TELEPORT || poi.group == null)
        {
            return group;
        }
        for (Poi other : map.pois())
        {
            Poi member = other.memberOf(poi.group);
            if (other != poi && other.type == PoiType.TELEPORT && member != null && map.usable(member.needs))
            {
                group.add(other);
            }
        }
        return group;
    }

    /** The name of the group's own teleport on an icon, without the item name in front. */
    private String shortName(Poi poi)
    {
        Poi member = poi.memberOf(this.poi.group);
        String name = member != null ? member.name : poi.name;
        int colon = name.indexOf(':');
        return colon > 0 ? name.substring(colon + 1).trim() : name;
    }

    /** Selects the icon at a destination when there is one, so its own links show. */
    private void goTo(Poi.Link link)
    {
        for (Poi other : map.pois())
        {
            if (other != poi && other.type == poi.type && other.location.getPlane() == link.point.getPlane()
                && other.location.distanceTo2D(link.point) <= 12)
            {
                map.focus(other);
                return;
            }
        }
        map.focus(link.point);
    }

    private void heading(String text)
    {
        add(Box.createVerticalStrut(8));
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.BRAND_ORANGE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(label);
        add(Box.createVerticalStrut(2));
    }

    private JLabel text(String text, Color color, boolean bold)
    {
        JLabel label = new JLabel();
        if (bold)
        {
            label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1));
        }
        // HTML labels wrap, but are slow to build; a card with dozens of destinations stays quick with plain labels
        // wherever the text fits on one line.
        if (text.indexOf('\n') < 0 && label.getFontMetrics(label.getFont()).stringWidth(text) <= textWidth)
        {
            label.setText(text);
        }
        else
        {
            label.setText("<html><div style='width:" + textWidth + "px'>" + escape(text) + "</div></html>");
        }
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel link(String text, Runnable action)
    {
        JLabel label = text(text, LINK, false);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                action.run();
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                label.setForeground(Color.WHITE);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                label.setForeground(LINK);
            }
        });
        return label;
    }

    private static JButton button(String text, Runnable action)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.addActionListener(e -> action.run());
        return button;
    }

    static String escape(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    @Override
    public Dimension getPreferredScrollableViewportSize()
    {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
    {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
    {
        return visible.height - 16;
    }

    /** The card always fits the width of its scroll pane, so text wraps instead of being cut off. */
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
