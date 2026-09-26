package com.hdmapreforged;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.PartyMemberAvatar;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.input.MouseManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.party.messages.WebsocketMessage;
import net.runelite.client.plugins.party.messages.LocationUpdate;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Text;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.WorldResult;

/**
 * Party members on the map through RuneLite's party service. Nothing is sent outside a party, and a party is never
 * joined without a user click: "Rejoin party" rejoins the remembered last one (see PARTY-RESEARCH.md).
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class PartyMap
{
    static final String LAST_KEY = "partyLast";
    private static final List<Class<? extends WebsocketMessage>> MESSAGES =
        List.of(HdMapPartyLocation.class, HdMapPartyGear.class, HdMapPartyDrop.class);

    // The constructor takes these in this order.
    private final Client client;
    private final PartyService party;
    private final WSClient wsClient;
    private final EventBus eventBus;
    private final ConfigManager configManager;
    private final HdMapReforgedConfig config;
    private final ItemManager itemManager;
    private final SkillIconManager skillIcons;
    private final WorldService worldService;
    private final ClientThread clientThread;
    private final SpriteManager spriteManager;
    private final OverlayManager overlays;
    private final MouseManager mouse;

    private final PartyMapMembers members = new PartyMapMembers();
    private final PartyMapRules.Throttle throttle = new PartyMapRules.Throttle();
    // Initializers may read later or constructor-set fields only through "this" or "PartyMap.this".
    private final PartyMapOverlay overlay = new PartyMapOverlay(this::markers, () -> this.favourites,
        () -> PartyMap.this.config.partyOnlyFavourites(), () -> this.myWorld);

    {
        overlay.setFocus(id -> this.widgets.values().stream().anyMatch(w -> w.selected() == id), id -> id == this.hoveredMember);
        overlay.setLoot(id -> members.loot(id, System.currentTimeMillis()), this::itemImage, this::repaint);
    }

    /** Swing thread only; each view's value undoes what {@link #start} added to it. */
    private final Map<MapView, Runnable> views = new LinkedHashMap<>();
    private final Map<MapView, FriendsWidget> widgets = new LinkedHashMap<>();
    private volatile int myWorld;
    private volatile Set<String> favourites = Collections.emptySet();
    /** The party this plugin joined for the user, or null. */
    private volatile String joinedCode;
    private volatile long lastChange = Long.MIN_VALUE / 2;
    /** The user left themselves: no rejoin reminder until the client restarts. */
    private volatile boolean leftByUser;
    private Timer idleCheck;
    private volatile boolean sentOnline;
    private volatile Long loggedOutSince;

    private volatile boolean gearChanged = true;
    private int gearSentTick = Integer.MIN_VALUE / 2;
    /** The members as last read without a clash with the websocket thread. */
    private volatile List<FriendsWidget.Member> lastGroup = Collections.emptyList();
    private final Map<Long, BufferedImage> itemImages = new ConcurrentHashMap<>();

    void start(MapView... maps)
    {
        MESSAGES.forEach(wsClient::registerMessage);
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
                view.addOverlay(overlay);
                view.addMenuContributor(menu);
                FriendsWidget widget = new FriendsWidget(this::markers, this::groupMembers, party::isInParty,
                    () -> myWorld, config::partyFriendsTab, view::focus, view::repaint);
                widget.setDrops(id -> members.drops(id, System.currentTimeMillis()));
                widget.setActions(this::hop, () -> !party.isInParty() && lastParty() != null, () -> join(view));
                MapView.ClickCatcher catcher = (at, projection) -> clickedMember(widget, at, projection);
                view.addClickCatcher(catcher);
                MouseAdapter pointing = new MouseAdapter()
                {
                    @Override
                    public void mouseMoved(MouseEvent e)
                    {
                        long now = memberAt(e.getPoint(), view.projection());
                        if (now != hoveredMember)
                        {
                            hoveredMember = now;
                            view.repaint();
                        }
                    }

                    @Override
                    public void mouseExited(MouseEvent e)
                    {
                        if (hoveredMember >= 0)
                        {
                            hoveredMember = -1;
                            view.repaint();
                        }
                    }
                };
                view.addMouseMotionListener(pointing);
                view.addMouseListener(pointing);
                widget.setDetails(members::gear, sidebarImages);
                widgets.put(view, widget);
                view.addWidget(widget);
                views.put(view, () -> {
                    view.removeOverlay(overlay);
                    view.removeMenuContributor(menu);
                    view.removeClickCatcher(catcher);
                    view.removeMouseMotionListener(pointing);
                    view.removeMouseListener(pointing);
                    view.removeWidget(widget);
                    widget.dispose();
                });
            }
            idleCheck = new Timer(30_000, e -> checkIdle());
            idleCheck.start();
        });
    }

    void stop()
    {
        sendOffline();
        eventBus.unregister(this);
        removeReminder();
        overlays.remove(rejoin);
        mouse.unregisterMouseListener(rejoin.clicks);
        MESSAGES.forEach(wsClient::unregisterMessage);
        members.clear();
        throttle.reset();
        SwingUtilities.invokeLater(() -> {
            if (idleCheck != null)
            {
                idleCheck.stop();
                idleCheck = null;
            }
            views.values().forEach(Runnable::run);
            views.clear();
            widgets.clear();
            overlay.dispose();
            if (PartyMapRules.leaveOnShutdown(joinedCode, party.getPartyPassphrase()))
            {
                party.changeParty(null);
            }
            joinedCode = null;
        });
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        myWorld = client.getWorld();
        continueHop();
    }

    private volatile long hoveredMember = -1;

    private boolean clickedMember(FriendsWidget widget, Point at, MapView.Projection projection)
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

    private long memberAt(Point at, MapView.Projection projection)
    {
        long best = -1;
        double bestDistance = 14;
        for (PartyMapMembers.Marker marker : markers())
        {
            // Where the map draws them, which can differ from the game.
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

    private final Map<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
    private World hopTarget;
    private int hopAttempts;

    /** Only on a user click, as RuneLite's World Hopper: opens the world switcher, then hops on a later tick. */
    void hop(int worldId)
    {
        clientThread.invoke(() -> {
            if (client.getGameState() != GameState.LOGGED_IN || client.getWorld() == worldId)
            {
                return;
            }
            WorldResult result = worldService.getWorlds();
            net.runelite.http.api.worlds.World world = result == null ? null : result.findWorld(worldId);
            if (world == null)
            {
                return;
            }
            boolean member = client.getWorldType().contains(WorldType.MEMBERS)
                || client.getVarpValue(VarPlayerID.ACCOUNT_CREDIT) > 0;
            String refused = PartyMapRules.hopRefusal(world.getTypes(), member);
            if (refused != null)
            {
                // Dangerous or special worlds are left to the game's own world switcher.
                SwingUtilities.invokeLater(() -> tell(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
                    "World " + worldId + " " + refused + ". Hop there with the game's world switcher if you mean to."));
                return;
            }
            World target = client.createWorld();
            target.setActivity(world.getActivity());
            target.setAddress(world.getAddress());
            target.setId(world.getId());
            target.setPlayerCount(world.getPlayers());
            target.setLocation(world.getLocation());
            target.setTypes(WorldUtil.toWorldTypes(world.getTypes()));
            hopTarget = target;
            hopAttempts = 0;
        });
    }

    private void continueHop()
    {
        World target = hopTarget;
        if (target == null)
        {
            return;
        }
        if (client.getWidget(InterfaceID.Worldswitcher.BUTTONS) == null)
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
            Skill[] skills = Skill.values();
            return ordinal < 0 || ordinal >= Math.min(HdMapPartyGear.SKILLS, skills.length) ? null
                : skillIcons.getSkillImage(skills[ordinal], true);
        }

        @Override
        public BufferedImage sprite(int id)
        {
            return sprites.get(id);
        }
    };

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
            public void join(Component from)
            {
                PartyMap.this.join(from);
            }
        };
    }

    List<FriendsWidget.Member> groupMembers()
    {
        List<FriendsWidget.Member> list = new ArrayList<>();
        if (!party.isInParty())
        {
            return list;
        }
        List<PartyMember> all;
        try
        {
            // The websocket thread changes this list while we read it on the Swing thread.
            all = new ArrayList<>(party.getMembers());
        }
        catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException e)
        {
            return new ArrayList<>(lastGroup);
        }
        for (PartyMember member : all)
        {
            if (member != null && !isLocal(member.getMemberId()))
            {
                // RuneLite names a member "<unknown>" until they log in and send their name.
                String name = member.getDisplayName();
                list.add(new FriendsWidget.Member(member.getMemberId(),
                    name == null || name.startsWith("<") ? null : name));
            }
        }
        lastGroup = List.copyOf(list);
        return list;
    }

    List<PartyMapMembers.Marker> markers()
    {
        return config.partyShowMembers() ? members.markers(System.currentTimeMillis()) : Collections.emptyList();
    }

    void tick(WorldPoint location, int tick)
    {
        if (!members.isEmpty())
        {
            // Fading follows the clock.
            repaint();
        }
        // Run and special attack energy alone are no reason to send.
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
            sendOffline();
            throttle.reset();
            myWorld = 0;
        }
    }

    private void sendOffline()
    {
        if (sentOnline && party.isInParty())
        {
            party.send(HdMapPartyLocation.offline(null));
        }
        sentOnline = false;
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
        favourites = Collections.unmodifiableSet(Text.fromCSV(config.partyFavourites()).stream()
            .map(PartyMapOverlay::nameKey).filter(key -> !key.isEmpty()).collect(Collectors.toSet()));
    }

    // Party events arrive on the websocket thread.

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

    /** RuneLite's Party plugin's messages, for members without this plugin. */
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
        throttle.force();
        gearChanged = true;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == InventoryID.INV || id == InventoryID.WORN)
        {
            gearChanged = true;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
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
    public void onNpcLootReceived(NpcLootReceived event)
    {
        shareDrops(event.getItems());
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        shareDrops(event.getItems());
    }

    private void shareDrops(Collection<ItemStack> items)
    {
        if (!party.isInParty() || !config.partyShareDrops())
        {
            return;
        }
        List<HdMapPartyDrop> valuable = new ArrayList<>();
        for (ItemStack stack : items)
        {
            long value = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
            if (value >= config.partyDropValue())
            {
                valuable.add(new HdMapPartyDrop(stack.getId(), stack.getQuantity(), value));
            }
        }
        // Only the most valuable few; the map shows no more.
        valuable.sort(Comparator.comparingLong((HdMapPartyDrop d) -> -d.value()));
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

    private HdMapPartyGear gear()
    {
        int[] quantities = new int[HdMapPartyGear.INVENTORY];
        int[] inventory = items(InventoryID.INV, quantities);
        int[] equipment = items(InventoryID.WORN, new int[HdMapPartyGear.EQUIPMENT]);
        int[] levels = new int[HdMapPartyGear.SKILLS];
        int[] boosted = new int[HdMapPartyGear.SKILLS];
        int[] experience = new int[HdMapPartyGear.SKILLS];
        Skill[] skills = Skill.values();
        for (int k = 0; k < Math.min(skills.length, levels.length); k++)
        {
            levels[k] = client.getRealSkillLevel(skills[k]);
            boosted[k] = client.getBoostedSkillLevel(skills[k]);
            experience[k] = client.getSkillExperience(skills[k]);
        }
        return new HdMapPartyGear(inventory, quantities, equipment, levels, boosted, experience,
            client.getEnergy() / 100, client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);
    }

    /** Item ids (-1 where empty) and quantities of a container, as many as {@code quantities} holds. */
    private int[] items(int container, int[] quantities)
    {
        int[] ids = new int[quantities.length];
        Arrays.fill(ids, -1);
        ItemContainer items = client.getItemContainer(container);
        Item[] all = items == null ? new Item[0] : items.getItems();
        for (int k = 0; k < Math.min(all.length, ids.length); k++)
        {
            ids[k] = all[k].getId();
            quantities[k] = all[k].getQuantity();
        }
        return ids;
    }

    private BufferedImage itemImage(int id, int quantity)
    {
        long key = (long) id << 32 | quantity;
        if (itemImages.size() > 512 && !itemImages.containsKey(key))
        {
            itemImages.clear();
        }
        return itemImages.computeIfAbsent(key, k -> {
            AsyncBufferedImage image = itemManager.getImage(id, quantity, quantity > 1);
            image.onLoaded(this::repaint);
            return image;
        });
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
                // Whoever joined it (this plugin or RuneLite's Party panel).
                configManager.setConfiguration(HdMapReforgedConfig.GROUP, LAST_KEY, event.getPassphrase());
            }
        }
        String ours = joinedCode;
        if (event.getPartyId() == null && ours != null
            && System.currentTimeMillis() - lastChange >= PartyMapRules.CHANGE_COOLDOWN_MILLIS)
        {
            // Left in RuneLite's Party panel: no reminder either.
            leftByUser = true;
        }
        if (ours != null && !ours.equals(event.getPassphrase()))
        {
            // A party joined by hand is the user's own and never left for them.
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
            // The next message fills this in.
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

    private void contribute(JPopupMenu popup, MapView view)
    {
        JMenu friends = new JMenu("Friends");
        String current = party.getPartyPassphrase();
        for (PartyMapMembers.Marker marker : markers())
        {
            String name = marker.name != null ? marker.name : "party member";
            friends.add("Show " + name + (marker.stale() ? " (" + PartyMapOverlay.ago(marker.ageMillis) + ")" : ""))
                .addActionListener(a -> view.focus(marker.point));
        }
        if (friends.getMenuComponentCount() > 0)
        {
            friends.addSeparator();
        }
        if (current == null && lastParty() != null)
        {
            friends.add("Rejoin party").addActionListener(a -> join(view));
        }
        if (current != null)
        {
            friends.add("Leave party").addActionListener(a -> {
                leftByUser = true;
                leave();
            });
        }
        if (friends.getMenuComponentCount() == 0)
        {
            friends.add("Join a party in RuneLite's Party panel").setEnabled(false);
        }
        popup.addSeparator();
        popup.add(friends);
    }

    /** An earlier version's group code (a passphrase too), moved to {@link #LAST_KEY}. */
    static final String LEGACY_KEY = "partyGroupCode";

    String lastParty()
    {
        String last = configManager.getConfiguration(HdMapReforgedConfig.GROUP, LAST_KEY);
        return last == null || last.trim().isEmpty() ? null : last.trim();
    }

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

    void join(Component view)
    {
        String last = lastParty();
        if (last != null)
        {
            joinCode(view, last);
        }
    }

    private boolean joinCode(Component view, String code)
    {
        long now = System.currentTimeMillis();
        String current = party.getPartyPassphrase();
        switch (PartyMapRules.join(code, client.getGameState() == GameState.LOGGED_IN, current, lastChange, now))
        {
            case ALREADY_IN:
                return true;
            case NOT_LOGGED_IN:
                tell(view, "Log in to the game first, then rejoin the party.");
                return false;
            case TOO_SOON:
                tell(view, "You just changed party. Try again in a few seconds.");
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
            default:
                break;
        }
        lastChange = now;
        joinedCode = code;
        party.changeParty(code);
        return true;
    }

    private boolean askedThisLogin;
    private final RejoinButton rejoin = new RejoinButton(() -> {
        String code = lastParty();
        removeReminder();
        if (code != null)
        {
            SwingUtilities.invokeLater(() -> joinCode(null, code));
        }
    }, this::removeReminder);

    /** After login, out of any party: a "Rejoin party" button; nothing is joined without its click. */
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

    private static void tell(Component to, String message)
    {
        JOptionPane.showMessageDialog(to, message, "HD Map Reforged", JOptionPane.INFORMATION_MESSAGE);
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

    /** Leaves a party we rejoined after long logged out, as RuneLite's Party plugin does. */
    private void checkIdle()
    {
        if (PartyMapRules.leaveIdle(joinedCode, party.getPartyPassphrase(), loggedOutSince, System.currentTimeMillis()))
        {
            log.debug("Leaving the party we rejoined after a long time logged out");
            leave();
        }
    }
}
