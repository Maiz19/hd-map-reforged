package com.hdmapreforged;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/** Details of the selected icon (requirements, destinations, links), or the teleports nearest a chosen point. */
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
    private IntFunction<JComponent> route;
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

    private List<ItemSources.Ware> wares;
    private boolean waresLoading;
    private Consumer<String> itemSearch;

    void setItemSearch(Consumer<String> itemSearch)
    {
        this.itemSearch = itemSearch;
    }

    private Consumer<Tour.Stop> stopAdder;

    void setStopAdder(Consumer<Tour.Stop> stopAdder)
    {
        this.stopAdder = stopAdder;
        rebuild();
    }

    private Consumer<WorldPoint> router;

    void setRouter(Consumer<WorldPoint> router)
    {
        this.router = router;
        rebuild();
    }

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
            waresLoading = true;
            wiki.page(poi.wikiQuery, page -> {
                if (page == null)
                {
                    SwingUtilities.invokeLater(() -> showWares(poi, null));
                    return;
                }
                wiki.store(page, found -> SwingUtilities.invokeLater(() -> showWares(poi, found)));
            });
        }
        if (poi != null)
        {
            List<Integer> ids = new ArrayList<>();
            poi.members().forEach(member -> ids.addAll(Requirements.itemIds(member.needs.items)));
            poi.links().forEach(link -> ids.addAll(Requirements.itemIds(link.needs.items)));
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

    /** {@code found}: the shop's stock, or null when its page could not be read. */
    private void showWares(Poi shop, List<ItemSources.Ware> found)
    {
        if (poi == shop)
        {
            wares = found == null ? wares : found;
            waresLoading = false;
            rebuild();
            if (found != null)
            {
                loadWarePictures(shop, found);
            }
        }
    }

    void setRoute(IntFunction<JComponent> route, boolean show)
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

    void setExtra(IntFunction<JComponent> extra)
    {
        this.extra = extra;
        if (extra != null)
        {
            poi = null;
            nearestTo = null;
        }
        rebuild();
    }

    /**
     * A background update of {@code old}: taken only while it is still the card's extra, re-rendered only while shown
     * (no place's details open over it), never closing anything.
     */
    boolean updateExtra(IntFunction<JComponent> old, IntFunction<JComponent> now)
    {
        if (extra == null || extra != old)
        {
            return false;
        }
        extra = now;
        if (poi == null && nearestTo == null)
        {
            rebuild();
        }
        return true;
    }

    boolean hasExtra()
    {
        return extra != null;
    }

    private IntFunction<JComponent> extra;

    void showNearest(WorldPoint point, List<Poi> found)
    {
        map.select(null);
        poi = null;
        nearestTo = point;
        nearest = new ArrayList<>(found);
        rebuild();
    }

    /** Not while the map animates: building a card with many rows made the map's flight stutter. */
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
            // Until then only the name, so the old selection's buttons cannot be used.
            removeAll();
            if (poi != null)
            {
                JLabel title = SearchResults.bold(plain(poi.name));
                title.setForeground(Color.WHITE);
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
            add(grey("Click an icon for details. Scroll to zoom, drag to move, double-click to zoom in. "
                + "Right-click for the nearest teleports to a spot."));
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
        add(grey("As the crow flies, from where each one lands. Walking routes may differ."));
        if (nearest.isEmpty())
        {
            add(grey("None on this map."));
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
        add(grey(where));

        // Laid out by wrap.
        JPanel buttons = new JPanel();
        Poi.Link inside = MapView.mapBelow(poi);
        if (inside != null)
        {
            buttons.add(button(MapView.goInText(inside), () -> map.goIn(inside)));
        }
        Poi target = poi;
        if (router != null)
        {
            buttons.add(SearchResults.tip(button("Route", () -> router.accept(target.location)),
                "A route from where you are to here"));
        }
        if (stopAdder != null)
        {
            buttons.add(SearchResults.tip(button("+ Stop", () -> stopAdder.accept(Tour.Stop.place(target.name,
                target.location))), "Add to your custom route (right-click the map, Custom routes...)"));
        }
        buttons.add(button("Wiki", () -> LinkBrowser.browse(
            WikiClient.searchUrl(poi.wikiQuery != null ? poi.wikiQuery : poi.name))));
        add(Box.createVerticalStrut(6));
        add(wrap(buttons, textWidth));
        if (extra != null)
        {
            add(Box.createVerticalStrut(4));
            add(link("← Back to the search results", () -> map.select(null)));
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
                    addMember(member, member.type == PoiType.TELEPORT || teleports ? ""
                        : "  (" + member.type.displayName + ")");
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
            heading("Ways to get here (" + members.size() + ")");
            for (Poi member : members)
            {
                addMember(member, "");
            }
        }
        List<Requirements.Line> own = Requirements.describe(poi.needs, itemNames);
        if (members.size() <= 1 && !own.isEmpty())
        {
            heading("Requirements");
            addRequirements(own, "");
        }

        List<Poi.Link> links = poi.links();
        java.util.Set<String> ownLines = own.stream().map(line -> line.text).collect(java.util.stream.Collectors.toSet());
        if (!links.isEmpty())
        {
            heading(poi.type.isRandomDestination() ? "Can send you to (" + links.size() + ")"
                : poi.type.isNetwork() ? "Travel to (" + links.size() + ")" : "Leads to");
            javax.swing.JCheckBox lines = new javax.swing.JCheckBox("Show lines on the map", map.linesShown(poi.type));
            lines.setOpaque(false);
            lines.setFocusable(false);
            lines.setFont(FontManager.getRunescapeSmallFont());
            lines.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            lines.setAlignmentX(Component.LEFT_ALIGNMENT);
            String type = poi.type.displayName.toLowerCase(Locale.ROOT);
            lines.setToolTipText("Draw lines from this " + type + " to where it leads; remembered for every " + type);
            lines.addActionListener(e -> map.setLinesShown(target.type, lines.isSelected()));
            add(lines);
            for (int i = 0; i < links.size() && i < MAX_LINKS; i++)
            {
                Poi.Link link = links.get(i);
                boolean usable = usable(link.needs);
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
                // Only what this trip needs beyond the stop's own requirements, listed above.
                List<Requirements.Line> tripNeeds = Requirements.describe(link.needs, itemNames);
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

    private boolean usable(Needs needs)
    {
        return map.unlocks() == null || map.unlocks().usable(needs);
    }

    private void addMember(Poi member, String kind)
    {
        boolean usable = usable(member.needs);
        if (!usable && config.onlyUsable())
        {
            return;
        }
        add(text((usable ? "" : "✗ ") + member.name + kind, usable ? Color.WHITE : MISSING, false));
        List<Requirements.Line> needs = Requirements.describe(member.needs, itemNames);
        if (!needs.isEmpty())
        {
            addRequirements(needs, "    ");
        }
    }

    static JPanel wrap(JPanel row, int width)
    {
        JPanel rows = SearchResults.column();
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
                line = SearchResults.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
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

    private final Map<String, BufferedImage> warePictures = SearchResults.pictureCache(200);
    private final Map<String, JLabel> wareIcons = new java.util.HashMap<>();

    private void loadWarePictures(Poi shop, List<ItemSources.Ware> list)
    {
        for (int i = 0; i < list.size() && i < MAX_WARES; i++)
        {
            ItemSources.Ware ware = list.get(i);
            if (ware.image.isEmpty() || warePictures.containsKey(ware.item))
            {
                continue;
            }
            wiki.file(ware.image, image -> SwingUtilities.invokeLater(() -> SearchResults.pictureLoaded(warePictures,
                this.poi == shop ? wareIcons : Map.of(), ware.item, image)));
        }
    }

    private void buildWares()
    {
        if (waresLoading)
        {
            heading("Stock");
            add(grey("Loading…"));
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
            JLabel icon = SearchResults.pictureIcon(warePictures.get(ware.item));
            wareIcons.put(ware.item, icon);
            JPanel line = SearchResults.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JLabel detail = plain((inStock ? ware.stock.equals("∞") ? "Endless stock" : "Stock " + ware.stock
                : "Out of stock") + (ware.price.isEmpty() ? "" : " · " + ware.price));
            detail.setForeground(inStock ? ColorScheme.LIGHT_GRAY_COLOR : new Color(170, 110, 110));
            line.add(detail);
            if (itemSearch != null)
            {
                line.add(Box.createHorizontalStrut(8));
                line.add(SearchResults.tip(link("Where else?", () -> itemSearch.accept(ware.item)),
                    "Spawns, other shops and drops of " + ware.item));
            }
            add(Box.createVerticalStrut(3));
            add(SearchResults.pictureRow(icon, SearchResults.tip(SearchResults.bold(link(ware.item,
                () -> LinkBrowser.browse(WikiClient.pageUrl(ware.item)))), "Open " + ware.item + " on the wiki"), line));
        }
        if (wares.size() > MAX_WARES)
        {
            add(grey((wares.size() - MAX_WARES) + " more on the wiki page"));
        }
    }

    private static final int MAX_WARES = 60;

    private static boolean sameNeeds(Needs a, Needs b)
    {
        return a.skills.equals(b.skills) && a.items.equals(b.items) && a.quests.equals(b.quests);
    }

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

    private String shortName(Poi poi)
    {
        Poi member = poi.memberOf(this.poi.group);
        String name = member != null ? member.name : poi.name;
        int colon = name.indexOf(':');
        return colon > 0 ? name.substring(colon + 1).trim() : name;
    }

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
        JLabel label = plain(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.BRAND_ORANGE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(label);
        add(Box.createVerticalStrut(2));
    }

    /** Wiki text and party names as they are: a leading {@code <html>} must not render as HTML (remote images). */
    static JLabel plain(String text)
    {
        JLabel label = new JLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setText(text);
        return label;
    }

    private JLabel grey(String text)
    {
        return text(text, ColorScheme.LIGHT_GRAY_COLOR, false);
    }

    private JLabel text(String text, Color color, boolean bold)
    {
        JLabel label = new JLabel();
        if (bold)
        {
            label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1));
        }
        // HTML labels wrap but are slow to build: plain labels wherever the text fits on one line.
        if (text.indexOf('\n') < 0 && label.getFontMetrics(label.getFont()).stringWidth(text) <= textWidth)
        {
            label.putClientProperty("html.disable", Boolean.TRUE);
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
        return SearchResults.clickable(text(text, LINK, false), action, true);
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
