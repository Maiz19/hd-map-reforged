package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.PartyMemberAvatar;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.party.messages.LocationUpdate;
import net.runelite.client.util.Text;

/**
 * Friends on the map, through RuneLite's party service (no server of our own). While the user is in a party,
 * each member running this plugin sends where they are; everyone's map shows the others. Members who only run
 * RuneLite's Party plugin show from its coarser location messages.
 *
 * <p>Nothing is sent outside a party. The plugin never joins a party by itself: it remembers the last party the user
 * was in, and "Rejoin party" (the map's menu and button, the sidebar, a small button over the game after login) joins it
 * again when the user clicks it (see local-development/PARTY-RESEARCH.md).
 */
@Slf4j
public final class PartyMap
{
    /** The last party the user was in (its passphrase), kept for "Rejoin party". */
    static final String LAST_KEY = "partyLast";

    private final Client client;
    private final PartyService party;
    private final WSClient wsClient;
    private final EventBus eventBus;
    private final ConfigManager configManager;
    private final HdMapReforgedConfig config;
    private final PartyMapMembers members = new PartyMapMembers();
    private final PartyMapRules.Throttle throttle = new PartyMapRules.Throttle();
    private final PartyMapOverlay overlay;
    /** Swing thread only: the maps drawn on, with the menu entries added to each. */
    private final Map<MapView, MapView.MenuContributor> views = new LinkedHashMap<>();
    /** Swing thread only: the friends tab on each map. */
    private final Map<MapView, FriendsWidget> widgets = new LinkedHashMap<>();
    private volatile int myWorld;
    private volatile Set<String> favourites = Collections.emptySet();
    /** The party this plugin joined for the user, or null. Set on the Swing thread, read on party threads too. */
    private volatile String joinedCode;
    private volatile long lastChange = Long.MIN_VALUE / 2;
    /** The user left the party themselves: no reminder to rejoin it until the client restarts. */
    private volatile boolean leftByUser;
    private Timer idleCheck;
    /** Whether others currently see us, so they get an "offline" message when that ends. */
    private volatile boolean sentOnline;
    private volatile Long loggedOutSince;

    private final net.runelite.client.game.ItemManager itemManager;
    private final net.runelite.client.game.SkillIconManager skillIcons;
    /** Our inventory, equipment or levels changed since they were last shared. Set on party threads too. */
    private volatile boolean gearChanged = true;
    /** Client thread. */
    private int gearSentTick = Integer.MIN_VALUE / 2;
    /** The party's members as last read without a clash with the websocket thread. */
    private volatile List<FriendsWidget.Member> lastGroup = Collections.emptyList();
    /** Item pictures by id and quantity; they load on the client thread and repaint when they arrive. */
    private final Map<Long, BufferedImage> itemImages = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    PartyMap(Client client, PartyService party, WSClient wsClient, EventBus eventBus, ConfigManager configManager,
        HdMapReforgedConfig config, net.runelite.client.game.ItemManager itemManager,
        net.runelite.client.game.SkillIconManager skillIcons, net.runelite.client.game.WorldService worldService,
        net.runelite.client.callback.ClientThread clientThread, net.runelite.client.game.SpriteManager spriteManager,
        net.runelite.client.ui.overlay.OverlayManager overlays, net.runelite.client.input.MouseManager mouse)
    {
        this.overlays = overlays;
        this.mouse = mouse;
        this.spriteManager = spriteManager;
        this.worldService = worldService;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.skillIcons = skillIcons;
        this.client = client;
        this.party = party;
        this.wsClient = wsClient;
        this.eventBus = eventBus;
        this.configManager = configManager;
        this.config = config;
        overlay = new PartyMapOverlay(this::markers, () -> favourites, config::partyOnlyFavourites, () -> myWorld);
        overlay.setFocus(id -> widgets.values().stream().anyMatch(w -> w.selected() == id), id -> id == hoveredMember);
        overlay.setLoot(id -> members.loot(id, System.currentTimeMillis()), this::itemImage, this::repaint);
    }

    /** Called from the plugin's startUp with the maps to draw on. */
    void start(MapView... maps)
    {
        wsClient.registerMessage(HdMapPartyLocation.class);
        wsClient.registerMessage(HdMapPartyGear.class);
        wsClient.registerMessage(HdMapPartyDrop.class);
        migrateLegacyCode();
        eventBus.register(this);
        overlays.add(rejoin);
        mouse.registerMouseListener(rejoin.clicks);
        for (int id : Orbs.SPRITES)
        {
            spriteManager.getSpriteAsync(id, 0, image -> {
                sprites.put(id, image);
                repaint();
            });
        }
        readFavourites();
        loggedOutSince = client.getGameState() == GameState.LOGGED_IN ? null : System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            for (MapView view : maps)
            {
                MapView.MenuContributor menu = (popup, point) -> contribute(popup, view);
                views.put(view, menu);
                view.addOverlay(overlay);
                view.addMenuContributor(menu);
                FriendsWidget widget = new FriendsWidget(this::markers, this::groupMembers, party::isInParty,
                    () -> myWorld, config::partyFriendsTab, view::focus, view::repaint);
                widget.setDrops(id -> members.drops(id, System.currentTimeMillis()));
                widget.setActions(this::hop, () -> !party.isInParty() && lastParty() != null, () -> join(view));
                MapView.ClickCatcher catcher = (at, projection) -> clickedMember(widget, at, projection);
                catchers.put(view, catcher);
                view.addClickCatcher(catcher);
                java.awt.event.MouseAdapter pointing = new java.awt.event.MouseAdapter()
                {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e)
                    {
                        long now = memberAt(e.getPoint(), view.projection());
                        if (now != hoveredMember)
                        {
                            hoveredMember = now;
                            view.repaint();
                        }
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e)
                    {
                        if (hoveredMember >= 0)
                        {
                            hoveredMember = -1;
                            view.repaint();
                        }
                    }
                };
                pointers.put(view, pointing);
                view.addMouseMotionListener(pointing);
                view.addMouseListener(pointing);
                widget.setDetails(members::gear, sidebarImages);
                widgets.put(view, widget);
                view.addWidget(widget);
            }
            idleCheck = new Timer(30_000, e -> checkIdle());
            idleCheck.start();
        });
    }

    void stop()
    {
        if (sentOnline && party.isInParty())
        {
            party.send(HdMapPartyLocation.offline(null));
        }
        sentOnline = false;
        eventBus.unregister(this);
        removeReminder();
        overlays.remove(rejoin);
        mouse.unregisterMouseListener(rejoin.clicks);
        wsClient.unregisterMessage(HdMapPartyLocation.class);
        wsClient.unregisterMessage(HdMapPartyGear.class);
        wsClient.unregisterMessage(HdMapPartyDrop.class);
        members.clear();
        throttle.reset();
        SwingUtilities.invokeLater(() -> {
            if (idleCheck != null)
            {
                idleCheck.stop();
                idleCheck = null;
            }
            views.forEach((view, menu) -> {
                view.removeOverlay(overlay);
                view.removeMenuContributor(menu);
            });
            views.clear();
            overlay.dispose();
            catchers.forEach(MapView::removeClickCatcher);
            pointers.forEach((view, pointing) -> {
                view.removeMouseMotionListener(pointing);
                view.removeMouseListener(pointing);
            });
            pointers.clear();
            catchers.clear();
            widgets.forEach((view, widget) -> {
                view.removeWidget(widget);
                widget.dispose();
            });
            widgets.clear();
            // A party we rejoined for the user is not left running without the plugin that shows it.
            if (PartyMapRules.leaveOnShutdown(joinedCode, party.getPartyPassphrase()))
            {
                party.changeParty(null);
            }
            joinedCode = null;
        });
    }

    /** The world the player is on, for telling party members on other worlds apart. */
    @Subscribe
    public void onGameTick(net.runelite.api.events.GameTick event)
    {
        myWorld = client.getWorld();
        continueHop();
    }

    /** Clicks on the map that land on a member's marker: their details open in the Friends list. */
    private final Map<MapView, MapView.ClickCatcher> catchers = new LinkedHashMap<>();

    private final Map<MapView, java.awt.event.MouseAdapter> pointers = new LinkedHashMap<>();
    /** The member under the mouse on a map, or -1: their name shows. Swing thread. */
    private volatile long hoveredMember = -1;

    private boolean clickedMember(FriendsWidget widget, java.awt.Point at, MapView.Projection projection)
    {
        long best = memberAt(at, projection);
        if (best < 0)
        {
            return false;
        }
        widget.show(best);
        repaint();
        return true;
    }

    /** The member whose marker is under a point of the map, or -1. */
    private long memberAt(java.awt.Point at, MapView.Projection projection)
    {
        long best = -1;
        double bestDistance = 14;
        for (PartyMapMembers.Marker marker : markers())
        {
            // Where the map draws them (a place the wiki draws elsewhere than it is in the game).
            WorldPoint shown = projection.shown(marker.point);
            if (!projection.shows(marker.point))
            {
                continue;
            }
            double d = at.distance(projection.screenX(shown.getX() + 0.5), projection.screenY(shown.getY() + 0.5));
            if (d < bestDistance)
            {
                bestDistance = d;
                best = marker.id;
            }
        }
        return best;
    }

    private final net.runelite.client.game.WorldService worldService;
    private final net.runelite.client.game.SpriteManager spriteManager;
    /** The game's orb sprites, by id, once loaded. */
    private final Map<Integer, BufferedImage> sprites = new java.util.concurrent.ConcurrentHashMap<>();
    private final net.runelite.client.callback.ClientThread clientThread;
    /** A world to hop to, once the game's world switcher is open; null when none. Client thread. */
    private net.runelite.api.World hopTarget;
    private int hopAttempts;

    /**
     * Hops to a friend's world, as RuneLite's World Hopper does: only when the user clicks for it, never by itself.
     * Opens the game's world switcher, then hops on the next tick.
     */
    void hop(int worldId)
    {
        clientThread.invoke(() -> {
            if (client.getGameState() != GameState.LOGGED_IN || client.getWorld() == worldId)
            {
                return;
            }
            net.runelite.http.api.worlds.WorldResult result = worldService.getWorlds();
            net.runelite.http.api.worlds.World world = result == null ? null : result.findWorld(worldId);
            if (world == null)
            {
                return;
            }
            boolean member = client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS)
                || client.getVarpValue(net.runelite.api.gameval.VarPlayerID.ACCOUNT_CREDIT) > 0;
            String refused = PartyMapRules.hopRefusal(world.getTypes(), member);
            if (refused != null)
            {
                // Dangerous or special worlds are left to the game's own world switcher.
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
                    "World " + worldId + " " + refused + ". Hop there with the game's world switcher if you mean to.",
                    "HD Map Reforged", JOptionPane.INFORMATION_MESSAGE));
                return;
            }
            net.runelite.api.World target = client.createWorld();
            target.setActivity(world.getActivity());
            target.setAddress(world.getAddress());
            target.setId(world.getId());
            target.setPlayerCount(world.getPlayers());
            target.setLocation(world.getLocation());
            target.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(world.getTypes()));
            hopTarget = target;
            hopAttempts = 0;
        });
    }

    /** Moves a hop along: the switcher open first, then the hop. Client thread, each game tick. */
    private void continueHop()
    {
        net.runelite.api.World target = hopTarget;
        if (target == null)
        {
            return;
        }
        if (client.getWidget(net.runelite.api.gameval.InterfaceID.Worldswitcher.BUTTONS) == null)
        {
            client.openWorldHopper();
            if (++hopAttempts >= 5)
            {
                hopTarget = null;
            }
            return;
        }
        client.hopToWorld(target);
        hopTarget = null;
    }

    /** Item and skill pictures, for the maps' details and the sidebar's. */
    private final FriendsWidget.Images sidebarImages = new FriendsWidget.Images()
    {
        @Override
        public BufferedImage item(int id, int quantity)
        {
            return itemImage(id, quantity);
        }

        @Override
        public BufferedImage skill(int ordinal)
        {
            net.runelite.api.Skill[] skills = net.runelite.api.Skill.values();
            if (ordinal < 0 || ordinal >= Math.min(HdMapPartyGear.SKILLS, skills.length))
            {
                return null;
            }
            return skillIcons.getSkillImage(skills[ordinal], true);
        }

        @Override
        public BufferedImage sprite(int id)
        {
            return sprites.get(id);
        }
    };

    /** The party for the sidebar's list. */
    MapPanel.Friends sidebar()
    {
        return new MapPanel.Friends()
        {
            @Override
            public boolean inGroup()
            {
                return party.isInParty();
            }

            @Override
            public List<FriendsWidget.Row> rows()
            {
                return FriendsWidget.rows(markers(), groupMembers());
            }

            @Override
            public int world()
            {
                return myWorld;
            }

            @Override
            public PartyMapMembers.Gear gear(long id)
            {
                return members.gear(id);
            }

            @Override
            public FriendsWidget.Images images()
            {
                return sidebarImages;
            }

            @Override
            public void hop(int world)
            {
                PartyMap.this.hop(world);
            }

            @Override
            public boolean canRejoin()
            {
                return lastParty() != null;
            }

            @Override
            public void join(java.awt.Component from)
            {
                PartyMap.this.join(from);
            }
        };
    }

    /** Everyone in the party but the player, by the party's own list. */
    List<FriendsWidget.Member> groupMembers()
    {
        List<FriendsWidget.Member> list = new java.util.ArrayList<>();
        if (!party.isInParty())
        {
            return list;
        }
        PartyMember local = party.getLocalMember();
        List<PartyMember> all;
        try
        {
            // The websocket thread changes this list while we read it on the Swing thread.
            all = new java.util.ArrayList<>(party.getMembers());
        }
        catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException e)
        {
            return new java.util.ArrayList<>(lastGroup);
        }
        for (PartyMember member : all)
        {
            if (member != null && (local == null || member.getMemberId() != local.getMemberId()))
            {
                // RuneLite names a member "<unknown>" until they log in and send their name.
                String name = member.getDisplayName();
                list.add(new FriendsWidget.Member(member.getMemberId(),
                    name == null || name.startsWith("<") ? null : name));
            }
        }
        lastGroup = Collections.unmodifiableList(new java.util.ArrayList<>(list));
        return list;
    }

    /** What the maps draw now. */
    List<PartyMapMembers.Marker> markers()
    {
        return config.partyShowMembers() ? members.markers(System.currentTimeMillis()) : Collections.emptyList();
    }

    // ---- sending, on the client thread ----

    /** Every game tick, with the player's real location (instances and boats resolved). */
    void tick(WorldPoint location, int tick)
    {
        if (!members.isEmpty())
        {
            // Fading follows the clock, not only new messages.
            repaint();
        }
        // What we carry, wear and can do: when it changed, at most every GEAR_TICKS. Run and special attack
        // energy go along with it but are no reason to send by themselves.
        if (gearChanged && party.isInParty() && config.partyShareGear()
            && tick - gearSentTick >= PartyMapRules.GEAR_TICKS)
        {
            gearChanged = false;
            gearSentTick = tick;
            party.send(gear());
        }
        if (party.isInParty() && config.partyShareLocation() && throttle.shouldSend(location, tick))
        {
            party.send(new HdMapPartyLocation(location, client.getWorld(), localName()));
            sentOnline = true;
        }
    }

    private String localName()
    {
        Player player = client.getLocalPlayer();
        return player == null || player.getName() == null ? null : Text.removeTags(player.getName());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN)
        {
            loggedOutSince = null;
            askToJoin();
        }
        else if (state == GameState.LOGIN_SCREEN)
        {
            askedThisLogin = false;
            removeReminder();
            if (loggedOutSince == null)
            {
                loggedOutSince = System.currentTimeMillis();
            }
            if (sentOnline && party.isInParty())
            {
                // Take our marker off the others' maps now instead of letting it fade.
                party.send(HdMapPartyLocation.offline(null));
            }
            sentOnline = false;
            throttle.reset();
            myWorld = 0;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()) || !event.getKey().startsWith("party"))
        {
            return;
        }
        readFavourites();
        if ("partyShareLocation".equals(event.getKey()) && !config.partyShareLocation() && party.isInParty()
            && client.getGameState() == GameState.LOGGED_IN)
        {
            party.send(HdMapPartyLocation.offline(null));
            sentOnline = false;
        }
        throttle.force();
        repaint();
    }

    private void readFavourites()
    {
        Set<String> names = new HashSet<>();
        for (String name : Text.fromCSV(config.partyFavourites()))
        {
            String key = PartyMapOverlay.nameKey(name);
            if (!key.isEmpty())
            {
                names.add(key);
            }
        }
        favourites = Collections.unmodifiableSet(names);
    }

    // ---- receiving; party events arrive on the websocket thread ----

    @Subscribe
    public void onHdMapPartyLocation(HdMapPartyLocation message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        members.update(message.getMemberId(), message.point(), message.world(), message.name(),
            System.currentTimeMillis());
        memberDetails(message.getMemberId());
        repaint();
    }

    /** RuneLite's Party plugin, for members without this plugin (only arrives while that plugin is on). */
    @Subscribe
    public void onLocationUpdate(LocationUpdate message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        WorldPoint point = message.getWorldPoint();
        if (!HdMapPartyLocation.plausible(point))
        {
            // Messages come from other clients.
            return;
        }
        if (members.updateFromCore(message.getMemberId(), point, System.currentTimeMillis()))
        {
            memberDetails(message.getMemberId());
            repaint();
        }
    }

    @Subscribe
    public void onUserJoin(UserJoin event)
    {
        // Let the newcomer see us without waiting for the next heartbeat.
        throttle.force();
        gearChanged = true;
    }

    @Subscribe
    public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == net.runelite.api.gameval.InventoryID.INV || id == net.runelite.api.gameval.InventoryID.WORN)
        {
            gearChanged = true;
        }
    }

    /** Levels and experience; sent no more often than {@link PartyMapRules#GEAR_TICKS} however fast they rise. */
    @Subscribe
    public void onStatChanged(net.runelite.api.events.StatChanged event)
    {
        gearChanged = true;
    }

    @Subscribe
    public void onHdMapPartyGear(HdMapPartyGear message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        members.gear(message.getMemberId(), new PartyMapMembers.Gear(message, System.currentTimeMillis()));
        repaint();
    }

    @Subscribe
    public void onNpcLootReceived(net.runelite.client.events.NpcLootReceived event)
    {
        shareDrops(event.getItems());
    }

    @Subscribe
    public void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
    {
        shareDrops(event.getItems());
    }

    /** Sends the drop's valuable stacks to the party, when sharing them is on. Client thread. */
    private void shareDrops(java.util.Collection<net.runelite.client.game.ItemStack> items)
    {
        if (!party.isInParty() || !config.partyShareDrops())
        {
            return;
        }
        List<HdMapPartyDrop> valuable = new java.util.ArrayList<>();
        for (net.runelite.client.game.ItemStack stack : items)
        {
            long value = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
            if (value >= config.partyDropValue())
            {
                valuable.add(new HdMapPartyDrop(stack.getId(), stack.getQuantity(), value));
            }
        }
        // A big pile sends only its most valuable few; the map shows no more than that anyway.
        valuable.sort(java.util.Comparator.comparingLong((HdMapPartyDrop d) -> -d.value()));
        for (HdMapPartyDrop drop : valuable.subList(0, Math.min(valuable.size(), PartyMapRules.MAX_DROPS_PER_LOOT)))
        {
            party.send(drop);
        }
    }

    @Subscribe
    public void onHdMapPartyDrop(HdMapPartyDrop message)
    {
        if (isLocal(message.getMemberId()) || message.item() < 0)
        {
            return;
        }
        members.loot(message.getMemberId(), new PartyMapMembers.Loot(message.item(), message.quantity(), message.value(),
            System.currentTimeMillis()));
        repaint();
    }

    /** What we carry, wear and can do, for the party. Client thread. */
    private HdMapPartyGear gear()
    {
        int[] inventory = new int[HdMapPartyGear.INVENTORY];
        int[] quantities = new int[HdMapPartyGear.INVENTORY];
        java.util.Arrays.fill(inventory, -1);
        net.runelite.api.ItemContainer inv = client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
        if (inv != null)
        {
            net.runelite.api.Item[] items = inv.getItems();
            for (int k = 0; k < Math.min(items.length, inventory.length); k++)
            {
                inventory[k] = items[k].getId();
                quantities[k] = items[k].getQuantity();
            }
        }
        int[] equipment = new int[HdMapPartyGear.EQUIPMENT];
        java.util.Arrays.fill(equipment, -1);
        net.runelite.api.ItemContainer worn = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
        if (worn != null)
        {
            net.runelite.api.Item[] items = worn.getItems();
            for (int k = 0; k < Math.min(items.length, equipment.length); k++)
            {
                equipment[k] = items[k].getId();
            }
        }
        int[] levels = new int[HdMapPartyGear.SKILLS];
        int[] boosted = new int[HdMapPartyGear.SKILLS];
        int[] experience = new int[HdMapPartyGear.SKILLS];
        net.runelite.api.Skill[] skills = net.runelite.api.Skill.values();
        for (int k = 0; k < Math.min(skills.length, levels.length); k++)
        {
            levels[k] = client.getRealSkillLevel(skills[k]);
            boosted[k] = client.getBoostedSkillLevel(skills[k]);
            experience[k] = client.getSkillExperience(skills[k]);
        }
        return new HdMapPartyGear(inventory, quantities, equipment, levels, boosted, experience,
            client.getEnergy() / 100, client.getVarpValue(net.runelite.api.gameval.VarPlayerID.SA_ENERGY) / 10);
    }

    /** An item's picture, cached; repaints the maps when it has loaded. */
    private BufferedImage itemImage(int id, int quantity)
    {
        long key = (long) id << 32 | quantity;
        BufferedImage cached = itemImages.get(key);
        if (cached != null)
        {
            return cached;
        }
        if (itemImages.size() > 512)
        {
            itemImages.clear();
        }
        net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(id, quantity, quantity > 1);
        image.onLoaded(this::repaint);
        itemImages.put(key, image);
        return image;
    }

    @Subscribe
    public void onUserSync(UserSync event)
    {
        throttle.force();
    }

    @Subscribe
    public void onUserPart(UserPart event)
    {
        members.remove(event.getMemberId());
        repaint();
    }

    @Subscribe
    public void onPartyChanged(PartyChanged event)
    {
        if (event.getPartyId() != null)
        {
            removeReminder();
            if (event.getPassphrase() != null && !event.getPassphrase().isEmpty())
            {
                // Remembered for "Rejoin party", whoever joined it (this plugin or RuneLite's Party panel).
                configManager.setConfiguration(HdMapReforgedConfig.GROUP, LAST_KEY, event.getPassphrase());
            }
        }
        String ours = joinedCode;
        if (event.getPartyId() == null && ours != null
            && System.currentTimeMillis() - lastChange >= PartyMapRules.CHANGE_COOLDOWN_MILLIS)
        {
            // Left elsewhere (RuneLite's Party panel): no reminder to rejoin it either.
            leftByUser = true;
        }
        if (ours != null && !ours.equals(event.getPassphrase()))
        {
            // Left, or joined another party by hand: that one is the user's own and never left for them.
            joinedCode = null;
        }
        members.clear();
        throttle.reset();
        throttle.force();
        repaint();
    }

    @Subscribe
    public void onPartyMemberAvatar(PartyMemberAvatar event)
    {
        members.avatar(event.getMemberId(), event.getImage());
        repaint();
    }

    private boolean isLocal(long memberId)
    {
        PartyMember local = party.getLocalMember();
        return local != null && local.getMemberId() == memberId;
    }

    /** Name and avatar as RuneLite's party knows them, when the Party plugin has set them. */
    private void memberDetails(long memberId)
    {
        try
        {
            PartyMember member = party.getMemberById(memberId);
            if (member != null)
            {
                members.name(memberId, member.getDisplayName());
                BufferedImage avatar = member.getAvatar();
                if (avatar != null)
                {
                    members.avatar(memberId, avatar);
                }
            }
        }
        catch (ConcurrentModificationException e)
        {
            // The member list changed meanwhile; the next message fills this in.
        }
    }

    private void repaint()
    {
        SwingUtilities.invokeLater(() -> {
            for (MapView view : views.keySet())
            {
                view.repaint();
            }
        });
    }

    // ---- the map's right-click menu, on the Swing thread ----

    private void contribute(JPopupMenu popup, MapView view)
    {
        JMenu friends = new JMenu("Friends");
        String current = party.getPartyPassphrase();
        if (config.partyShowMembers())
        {
            for (PartyMapMembers.Marker marker : markers())
            {
                String name = marker.name != null ? marker.name : "party member";
                JMenuItem show = new JMenuItem("Show " + name + (marker.stale()
                    ? " (" + PartyMapOverlay.ago(marker.ageMillis) + ")" : ""));
                show.addActionListener(a -> view.focus(marker.point));
                friends.add(show);
            }
            if (friends.getMenuComponentCount() > 0)
            {
                friends.addSeparator();
            }
        }
        if (current == null && lastParty() != null)
        {
            JMenuItem rejoin = new JMenuItem("Rejoin party");
            rejoin.addActionListener(a -> join(view));
            friends.add(rejoin);
        }
        if (current != null)
        {
            JMenuItem leave = new JMenuItem("Leave party");
            leave.addActionListener(a -> {
                leftByUser = true;
                leave();
            });
            friends.add(leave);
        }
        if (friends.getMenuComponentCount() == 0)
        {
            JMenuItem how = new JMenuItem("Join a party in RuneLite's Party panel");
            how.setEnabled(false);
            friends.add(how);
        }
        popup.addSeparator();
        popup.add(friends);
    }

    /** An earlier version's "map group" code; it was a party passphrase too, and moves to {@link #LAST_KEY}. */
    static final String LEGACY_KEY = "partyGroupCode";

    /** The last party the user was in, or null. */
    String lastParty()
    {
        String last = configManager.getConfiguration(HdMapReforgedConfig.GROUP, LAST_KEY);
        return last == null || last.trim().isEmpty() ? null : last.trim();
    }

    /** Moves an earlier version's group code to {@link #LAST_KEY} once, then forgets the old setting. */
    void migrateLegacyCode()
    {
        String legacy = configManager.getConfiguration(HdMapReforgedConfig.GROUP, LEGACY_KEY);
        if (legacy == null)
        {
            return;
        }
        if (!legacy.trim().isEmpty() && lastParty() == null)
        {
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, LAST_KEY, legacy.trim());
        }
        configManager.unsetConfiguration(HdMapReforgedConfig.GROUP, LEGACY_KEY);
    }

    /** Rejoins the last party, from a button or menu the user clicked. */
    void join(java.awt.Component view)
    {
        String last = lastParty();
        if (last != null)
        {
            joinCode(view, last);
        }
    }

    /** Joins the group after the user asked to; true if joined. */
    private boolean joinCode(java.awt.Component view, String code)
    {
        long now = System.currentTimeMillis();
        String current = party.getPartyPassphrase();
        switch (PartyMapRules.join(code, client.getGameState() == GameState.LOGGED_IN, current, lastChange, now))
        {
            case ALREADY_IN:
                return true;
            case NOT_LOGGED_IN:
                JOptionPane.showMessageDialog(view, "Log in to the game first, then rejoin the party.",
                    "HD Map Reforged", JOptionPane.INFORMATION_MESSAGE);
                return false;
            case TOO_SOON:
                JOptionPane.showMessageDialog(view, "You just changed party. Try again in a few seconds.",
                    "HD Map Reforged", JOptionPane.INFORMATION_MESSAGE);
                return false;
            case INVALID_CODE:
                return false;
            case CONFIRM_LEAVE_OTHER:
                if (JOptionPane.showConfirmDialog(view, "You are in another party. Leave it and rejoin your last party?",
                    "HD Map Reforged", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                {
                    return false;
                }
                break;
            case JOIN:
            default:
                break;
        }
        lastChange = now;
        joinedCode = code;
        party.changeParty(code);
        return true;
    }

    /** Reminded already after this login: not again until the next login. */
    private boolean askedThisLogin;
    private final net.runelite.client.ui.overlay.OverlayManager overlays;
    private final net.runelite.client.input.MouseManager mouse;
    /** "Rejoin party" over the game after login, out of the party joined before. */
    private final RejoinButton rejoin = new RejoinButton(() -> {
        String code = lastParty();
        removeReminder();
        if (code != null)
        {
            SwingUtilities.invokeLater(() -> joinCode(null, code));
        }
    }, this::removeReminder);

    /**
     * After logging in, out of any party but with a party before: a small "Rejoin party" button over the game. One
     * click rejoins; nothing is joined without it. Client thread.
     */
    private void askToJoin()
    {
        String code = lastParty();
        if (askedThisLogin || !config.partyAskOnLogin() || !PartyMapRules.askToJoin(code,
            client.getGameState() == GameState.LOGGED_IN, party.getPartyPassphrase(), leftByUser, lastChange,
            System.currentTimeMillis()))
        {
            return;
        }
        askedThisLogin = true;
        rejoin.setShowing(true);
    }

    private void removeReminder()
    {
        rejoin.setShowing(false);
    }

    private void leave()
    {
        lastChange = System.currentTimeMillis();
        joinedCode = null;
        party.changeParty(null);
    }

    /** Leaves a party we rejoined after a long time logged out, as RuneLite's Party plugin does. */
    private void checkIdle()
    {
        if (PartyMapRules.leaveIdle(joinedCode, party.getPartyPassphrase(), loggedOutSince, System.currentTimeMillis()))
        {
            log.debug("Leaving the party we rejoined after a long time logged out");
            leave();
        }
    }
}
