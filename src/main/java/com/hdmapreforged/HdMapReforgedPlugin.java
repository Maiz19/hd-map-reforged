package com.hdmapreforged;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
    name = "HD Map Reforged",
    description = "A zoomable world map from the OSRS Wiki with teleport, transport, dungeon and Sailing icons, wiki info and links",
    tags = {"map", "world", "worldmap", "wiki", "teleport", "dungeon", "fairy", "spirit", "tree", "sailing"}
)
public class HdMapReforgedPlugin extends Plugin
{
    /** Version of the bundled basemaps.json and region_maps.tsv. */
    static final String BUNDLED_VERSION = "2026-08-12_a";
    private static final String DATA = "data/";
    /** Unlocks are read at most this often while things change; quest states this often at all. */
    private static final int UNLOCK_TICKS = 5;
    private static final int QUEST_TICKS = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private ItemManager itemManager;

    @Inject
    private HdMapReforgedConfig config;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private PartyMap party;

    @Inject
    private RouteFeature route;

    private ExecutorService io;
    /** One task at a time so results arrive in order. */
    private ExecutorService dataIo;
    private final AtomicInteger dataGeneration = new AtomicInteger();
    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private File home;
    private TileCache tiles;
    private WikiClient wiki;
    private RegionResolver regionResolver;
    private MapScreen screen;
    private MapScreen fullScreen;
    private FullMapWindow fullMap;
    private MapIconLayer[] gameIcons = new MapIconLayer[0];
    private MapDownloader downloader;
    private MapDownloadControl downloadControl;
    private volatile String downloadingVersion;
    private final HotkeyListener mapKey = new HotkeyListener(() -> config.openMapKey())
    {
        @Override
        public void hotkeyPressed()
        {
            toggleFullMap();
        }
    };
    private final KeyListener escape = new KeyListener()
    {
        @Override
        public void keyTyped(KeyEvent e)
        {
        }

        @Override
        public void keyPressed(KeyEvent e)
        {
            FullMapWindow open = fullMap;
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && open != null && open.isOpen())
            {
                e.consume();
                SwingUtilities.invokeLater(open::close);
            }
        }

        @Override
        public void keyReleased(KeyEvent e)
        {
        }
    };
    private MapPanel panel;
    private MapWindow window;
    private NavigationButton button;
    private WorldPoint lastPlayer;
    private BaseMaps bundledMaps;
    private volatile BaseMaps currentMaps;
    private volatile String currentVersion = BUNDLED_VERSION;
    private volatile List<Needs> allNeeds = Collections.emptyList();
    /** Regions being checked (value Long.MAX_VALUE) or failed, with when to retry. Cleared per map version. */
    private final Map<Integer, Long> regionsPending = new ConcurrentHashMap<>();
    private static final long REGION_RETRY_MS = 60_000;
    /** Counts starts and stops, so late callbacks of an earlier run do nothing. */
    private final AtomicInteger life = new AtomicInteger();
    private volatile String wantedVersion;
    private Unlocks unlocks;
    private boolean unlocksDirty = true;
    private int ticks;
    private int lastUnlockTick = -UNLOCK_TICKS;
    private int lastQuestTick = -QUEST_TICKS;

    @Provides
    HdMapReforgedConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(HdMapReforgedConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        int started = life.incrementAndGet();
        io = Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "HD Map Reforged tiles"));
        dataIo = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "HD Map Reforged data"));
        home = new File(RuneLite.RUNELITE_DIR, "hd-map-reforged");
        tiles = new TileCache(okHttpClient, io, new File(home, "tiles"), this::tileLoaded);
        configureTiles();
        wiki = new WikiClient(okHttpClient, gson);
        regionResolver = new RegionResolver(tiles);
        try (Reader reader = resource("basemaps.json"))
        {
            bundledMaps = BaseMaps.parse(gson, reader).withRegions(regionTable(BUNDLED_VERSION));
        }
        currentMaps = bundledMaps;
        currentVersion = BUNDLED_VERSION;

        ItemNames itemNames = new ItemNames(clientThread, itemManager);
        screen = new MapScreen(tiles, config, wiki, itemNames, this::togglePopOut);
        fullScreen = new MapScreen(tiles, config, wiki, itemNames, () -> fullMap.close());
        fullMap = new FullMapWindow(client, fullScreen, config.fullMapBounds(),
            bounds -> configManager.setConfiguration(HdMapReforgedConfig.GROUP, "fullMapBounds", bounds),
            key -> config.openMapKey().matches(key), config::mapInGameWindow);
        keyManager.registerKeyListener(escape);
        keyManager.registerKeyListener(mapKey);
        panel = new MapPanel(() -> fullMap.open(), this::togglePopOut, this::showOnMap, party.sidebar(),
            fullScreen::placeName);
        button = NavigationButton.builder()
            .tooltip("HD Map Reforged")
            .icon(PoiIcons.navigationIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(button);
        party.start(screen.view(), fullScreen.view());
        route.start(screen, fullScreen);
        route.setRouteLog(new RouteLog(new File(home, "routes.log"), io));
        route.setSidebar(panel::showRoute, this::showOnMap);
        panel.setTours(route.sidebarTours(panel::refreshTours));
        gameIcons = new MapIconLayer[]{gameIconLayer(screen), gameIconLayer(fullScreen)};
        downloader = new MapDownloader(tiles, this::tileLoaded);
        downloadControl = new MapDownloadControl(downloader, this::refresh);
        for (MapScreen on : new MapScreen[]{screen, fullScreen})
        {
            on.view().setLinesChoice(value -> configManager.setConfiguration(HdMapReforgedConfig.GROUP, "linesOff", value));
            on.setRegionCheck(this::checkRegions);
            on.view().addOverlay(downloadControl);
        }

        refreshData(bundledMaps, BUNDLED_VERSION);
        resolveMapVersion();
        whenGameLoaded(started, () -> GameIconSprites.load(client), PoiIcons::clearCache);
    }

    private void whenGameLoaded(int started, BooleanSupplier load, Runnable after)
    {
        clientThread.invokeLater(() -> {
            if (started != life.get())
            {
                return true;
            }
            if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState() || !load.getAsBoolean())
            {
                return false;
            }
            after.run();
            refreshLater();
            return true;
        });
    }

    private MapIconLayer gameIconLayer(MapScreen on)
    {
        MapIconLayer layer = new MapIconLayer(on.view(), config::showGameIcons, config::iconSize);
        on.view().addOverlay(layer);
        on.view().addHitLayer(layer);
        on.view().setBadgeCover(layer::covers);
        return layer;
    }

    private static Thread daemon(Runnable runnable, String name)
    {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    protected void shutDown()
    {
        life.incrementAndGet();
        wantedVersion = null;
        GameIconSprites.clear();
        PoiIcons.clearCache();
        PointMaps.clear();
        downloader.stop();
        if (downloadControl != null)
        {
            downloadControl.dispose();
        }
        party.stop();
        route.stop();
        clientToolbar.removeNavigation(button);
        dock();
        keyManager.unregisterKeyListener(escape);
        keyManager.unregisterKeyListener(mapKey);
        fullMap.dispose();
        dataGeneration.incrementAndGet();
        screen.stop();
        fullScreen.stop();
        io.shutdownNow();
        dataIo.shutdownNow();
        tiles.clear();
        regionsPending.clear();
        lastPlayer = null;
        unlocks = null;
        unlocksDirty = true;
        screen = null;
        gameIcons = new MapIconLayer[0];
        fullScreen = null;
        fullMap = null;
        if (panel != null)
        {
            panel.stop();
        }
        panel = null;
        button = null;
    }

    private void screens(Consumer<MapScreen> change)
    {
        SwingUtilities.invokeLater(() -> {
            if (screen != null)
            {
                change.accept(screen);
                change.accept(fullScreen);
            }
        });
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        ticks++;
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return;
        }
        WorldPoint location = playerLocation(player);
        party.tick(location, ticks);
        route.tick(location);
        if (location != null && !location.equals(lastPlayer))
        {
            lastPlayer = location;
            screens(s -> s.setPlayer(location));
            checkRegion(location);
        }
        updateUnlocks();
    }

    /** As the wiki maps show it: in instances the copied real location, on a boat the boat's main-world place. */
    private WorldPoint playerLocation(Player player)
    {
        LocalPoint local = player.getLocalLocation();
        WorldView view = player.getWorldView();
        if (local != null && view != null && !view.isTopLevel())
        {
            WorldEntity boat = client.getTopLevelWorldView().worldEntities().byIndex(view.getId());
            local = boat != null ? boat.transformToMainWorld(local) : null;
        }
        return local == null ? null : WorldPoint.fromLocalInstance(client, local);
    }

    /** Which map shows each point where maps overlap, checked on the tiles; answers on the Swing thread. */
    private void checkRegions(List<WorldPoint> points, Consumer<Map<WorldPoint, BaseMap>> done)
    {
        BaseMaps maps = currentMaps;
        String version = tiles.version();
        runData(() -> {
            Map<WorldPoint, BaseMap> found = version == null ? Collections.emptyMap()
                : PointMaps.resolve(tiles, version, maps, points);
            SwingUtilities.invokeLater(() -> done.accept(found));
        });
    }

    private void checkRegion(WorldPoint location)
    {
        BaseMaps maps = currentMaps;
        int region = RegionTable.regionId(location.getX(), location.getY());
        if (maps.regions().isResolved(region) || !RegionResolver.needsCheck(maps, location.getX(), location.getY()))
        {
            return;
        }
        long now = System.currentTimeMillis();
        Long retryAt = regionsPending.get(region);
        if (retryAt != null && now < retryAt
            || !(retryAt == null ? regionsPending.putIfAbsent(region, Long.MAX_VALUE) == null
                : regionsPending.replace(region, retryAt, Long.MAX_VALUE)))
        {
            return;
        }
        String version = currentVersion;
        boolean queued = runData(() -> {
            boolean found = false;
            try
            {
                found = regionResolver.resolve(version, maps, maps.regions(), Collections.singletonList(location),
                    () -> false) > 0;
            }
            finally
            {
                if (found)
                {
                    regionsPending.remove(region);
                }
                else
                {
                    regionsPending.replace(region, Long.MAX_VALUE, System.currentTimeMillis() + REGION_RETRY_MS);
                }
            }
            if (found)
            {
                saveRegions(version, maps.regions());
                screens(s -> {
                    s.setPlayer(null);
                    s.setPlayer(location);
                });
            }
        });
        if (!queued)
        {
            regionsPending.remove(region);
        }
    }

    private void updateUnlocks()
    {
        boolean due = unlocksDirty && ticks - lastUnlockTick >= UNLOCK_TICKS || ticks - lastUnlockTick >= QUEST_TICKS;
        if (!due || allNeeds.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        boolean quests = ticks - lastQuestTick >= QUEST_TICKS;
        Unlocks previous = unlocks;
        unlocks = Unlocks.capture(client, allNeeds, unlocks, quests);
        unlocksDirty = false;
        lastUnlockTick = ticks;
        if (quests)
        {
            lastQuestTick = ticks;
        }
        if (unlocks.sameAs(previous))
        {
            // Varbits change all the time; rebuilding the detail card for nothing made the map stutter.
            unlocks = previous;
            return;
        }
        Unlocks current = unlocks;
        screens(s -> s.setUnlocks(current));
    }

    /** A RuneLite-only orb entry; the game's "World Map" stays in the menu. */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        int widget = event.getActionParam1();
        if (!config.orbOpensMap() || !isOrb(widget) || !isWorldMapOption(event.getOption()))
        {
            return;
        }
        // The orb can list several map options; one entry of ours is enough.
        if (ownEntryIndex(client.getMenu().getMenuEntries()) >= 0)
        {
            return;
        }
        client.getMenu().createMenuEntry(-1)
            .setOption(MENU_OPTION)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .setParam1(widget)
            .onClick(entry -> toggleFullMap());
    }

    private void toggleFullMap()
    {
        SwingUtilities.invokeLater(() -> {
            if (fullMap != null)
            {
                fullMap.toggle();
            }
        });
    }

    /** Keeps "HD Map" as the orb's left-click option, as later entries would take the top; only reorders. */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event)
    {
        if (!config.orbOpensMap() || client.isMenuOpen())
        {
            return;
        }
        MenuEntry[] entries = client.getMenu().getMenuEntries();
        int ours = ownEntryIndex(entries);
        if (ours >= 0 && ours != entries.length - 1)
        {
            client.getMenu().setMenuEntries(moveToTop(entries, ours));
        }
    }

    static final String MENU_OPTION = "HD Map";

    static boolean isOrb(int widget)
    {
        return widget == InterfaceID.Orbs.ORB_WORLDMAP || widget == InterfaceID.Orbs.WORLDMAP;
    }

    static boolean isWorldMapOption(String option)
    {
        return option != null && Text.removeTags(option).toLowerCase(Locale.ROOT).contains("world map");
    }

    private static boolean isOurEntry(MenuEntry entry)
    {
        return entry.getType() == MenuAction.RUNELITE && MENU_OPTION.equals(entry.getOption()) && isOrb(entry.getParam1());
    }

    static int ownEntryIndex(MenuEntry[] entries)
    {
        for (int i = 0; i < entries.length; i++)
        {
            if (isOurEntry(entries[i]))
            {
                return i;
            }
        }
        return -1;
    }

    /** The last entry is the top of the menu, which is what a left click does. */
    static MenuEntry[] moveToTop(MenuEntry[] entries, int index)
    {
        List<MenuEntry> reordered = new ArrayList<>(Arrays.asList(entries));
        reordered.add(reordered.remove(index));
        return reordered.toArray(new MenuEntry[0]);
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        unlocksDirty = true;
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        unlocksDirty = true;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            unlocksDirty = true;
            lastQuestTick = ticks - QUEST_TICKS;
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            lastPlayer = null;
            unlocks = null;
            screens(s -> {
                s.setPlayer(null);
                s.setUnlocks(null);
            });
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()) || "fullMapBounds".equals(event.getKey()))
        {
            return;
        }
        configureTiles();
        switch (event.getKey())
        {
            case "wholeMap":
            case "diskCache":
                applyWholeMap();
                break;
            case "mapVersion":
                resolveMapVersion();
                break;
            case "followPlayer":
                screens(s -> s.setFollowing(config.followPlayer()));
                break;
            case "linesOff":
                screens(s -> s.view().setLinesOff(config.linesOff()));
                return;

            default:
                break;
        }
        refreshLater();
    }

    private void configureTiles()
    {
        MapDownloader.Scope scope = config.wholeMap().scope;
        // The chosen whole-map download always fits, so trimming never deletes it.
        tiles.setDiskFloor(scope == null ? 0 : downloadRoom(scope));
        tiles.configure(config.diskCache(), config.memoryTiles(), config.diskCacheMb());
    }

    private static int downloadRoom(MapDownloader.Scope scope)
    {
        return scope.megabytes + 500;
    }

    private void applyWholeMap()
    {
        MapDownloader.Scope scope = config.wholeMap().scope;
        if (scope == null)
        {
            downloader.stop();
            downloader.clearMessage();
            refreshLater();
            return;
        }
        ensureDiskLimit(downloadRoom(scope));
        if (!config.diskCache())
        {
            // Comes back here through onConfigChanged.
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, "diskCache", true);
            return;
        }
        String version = tiles.version();
        if (version != null && (!downloader.isRunning() || downloader.scope() != scope || !version.equals(downloadingVersion)))
        {
            download(scope, version, currentMaps);
        }
    }

    private void resumeDownload(String version, BaseMaps maps)
    {
        MapDownloader.Scope scope = config.wholeMap().scope;
        if (scope != null && config.diskCache() && downloader != null
            && (!downloader.isRunning() || !version.equals(downloadingVersion) || maps != downloadingMaps))
        {
            ensureDiskLimit(downloadRoom(scope));
            download(scope, version, maps);
        }
    }

    private void download(MapDownloader.Scope scope, String version, BaseMaps maps)
    {
        downloadingVersion = version;
        downloadingMaps = maps;
        downloader.start(scope, version, maps);
    }

    private volatile BaseMaps downloadingMaps;

    private void ensureDiskLimit(int megabytes)
    {
        if (config.diskCacheMb() < megabytes)
        {
            configManager.setConfiguration(HdMapReforgedConfig.GROUP, "diskCacheMb", Math.min(10000, megabytes));
            configureTiles();
        }
    }

    private void tileLoaded()
    {
        if (repaintPending.compareAndSet(false, true))
        {
            SwingUtilities.invokeLater(() -> {
                repaintPending.set(false);
                refresh();
            });
        }
    }

    private void refreshLater()
    {
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh()
    {
        if (screen != null)
        {
            screen.refresh();
            fullScreen.refresh();
        }
    }

    /** The configured map version, else the wiki's current one (remembered), else the bundled one. */
    private void resolveMapVersion()
    {
        String configured = config.mapVersion().trim();
        if (!configured.isEmpty())
        {
            if (WikiClient.isSafeVersion(configured))
            {
                useVersion(configured, false);
                return;
            }
            log.warn("Ignoring map version setting \"{}\"", configured);
        }
        KnownVersion known = KnownVersion.read(new File(home, KnownVersion.FILE));
        if (known != null)
        {
            useVersion(known.version, true);
        }
        if (known != null && !known.due(config.mapUpdateCheck(), System.currentTimeMillis()))
        {
            return;
        }
        int started = life.get();
        wiki.mapVersion(version -> {
            // A manual version set meanwhile wins.
            if (started != life.get() || !config.mapVersion().trim().isEmpty())
            {
                return;
            }
            if (version != null)
            {
                KnownVersion.write(new File(home, KnownVersion.FILE), version, System.currentTimeMillis());
                useVersion(version, true);
            }
            else if (known == null)
            {
                useVersion(BUNDLED_VERSION, false);
            }
        });
    }

    /** Tiles switch together with the version's map list. */
    private void useVersion(String version, boolean official)
    {
        int started = life.get();
        wantedVersion = version;
        if (version.equals(currentVersion))
        {
            tiles.setVersion(version, official);
            resumeDownload(version, currentMaps);
            refreshLater();
            return;
        }
        BaseMaps kept = BUNDLED_VERSION.equals(version) ? bundledMaps : readBaseMaps(version);
        if (kept != null)
        {
            switchVersion(version, official, kept, started);
            return;
        }
        if (tiles.version() == null)
        {
            tiles.setVersion(currentVersion, false);
            refreshLater();
        }
        wiki.baseMaps(version, (maps, json) -> {
            if (started != life.get() || !version.equals(wantedVersion))
            {
                return;
            }
            if (maps == null)
            {
                log.warn("Could not get the map list of map version {}; showing version {} meanwhile", version,
                    currentVersion);
                return;
            }
            saveBaseMaps(version, json);
            switchVersion(version, official, maps, started);
        });
    }

    private void switchVersion(String version, boolean official, BaseMaps maps, int started)
    {
        if (started != life.get())
        {
            return;
        }
        BaseMaps withRegions = maps;
        if (maps != bundledMaps)
        {
            try
            {
                withRegions = maps.withRegions(regionTable(version));
            }
            catch (IOException e)
            {
                log.warn("Could not read the region table for map version {}", version, e);
                return;
            }
        }
        regionsPending.clear();
        tiles.setVersion(version, official);
        refreshData(withRegions, version);
        refreshLater();
    }

    private File baseMapsFile(String version)
    {
        return WikiClient.isSafeVersion(version) ? new File(new File(home, "basemaps"), version + ".json") : null;
    }

    private BaseMaps readBaseMaps(String version)
    {
        File file = baseMapsFile(version);
        if (file == null || !file.isFile() || Files.isSymbolicLink(file.toPath()))
        {
            return null;
        }
        try (Reader reader = utf8(new FileInputStream(file)))
        {
            BaseMaps maps = BaseMaps.parse(gson, reader);
            return maps.isUsable() ? maps : null;
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Ignoring unreadable {}", file, e);
            return null;
        }
    }

    private void saveBaseMaps(String version, String json)
    {
        File file = baseMapsFile(version);
        if (file == null || json == null)
        {
            return;
        }
        try
        {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs())
            {
                return;
            }
            Path temp = Files.createTempFile(parent.toPath(), file.getName() + ".", ".part");
            try
            {
                Files.writeString(temp, json);
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(temp);
            }
        }
        catch (IOException e)
        {
            log.debug("Could not keep the map list of version {}", version, e);
        }
    }

    private void refreshData(BaseMaps maps, String version)
    {
        int generation = dataGeneration.incrementAndGet();
        int started = life.get();
        currentMaps = maps;
        currentVersion = version;
        if (version.equals(tiles.version()))
        {
            resumeDownload(version, maps);
        }
        runData(() -> {
            MapData data = publish(maps, generation, started);
            if (data == null)
            {
                return;
            }
            if (resolveRegions(maps, version, data, generation))
            {
                publish(maps, generation, started);
            }
        });
    }

    private boolean resolveRegions(BaseMaps maps, String version, MapData data, int generation)
    {
        if (regionResolver.resolve(version, maps, maps.regions(), data.points(), () -> generation != dataGeneration.get())
            == 0)
        {
            return false;
        }
        saveRegions(version, maps.regions());
        return true;
    }

    private MapData publish(BaseMaps maps, int generation, int started)
    {
        if (generation != dataGeneration.get())
        {
            return null;
        }
        try
        {
            MapData data = MapData.load(maps, this::resource);
            if (generation != dataGeneration.get())
            {
                return null;
            }
            allNeeds = Poi.flatten(data.pois).stream().flatMap(poi -> Stream.concat(poi.members().stream().map(member -> member.needs),
                poi.links().stream().map(link -> link.needs))).collect(Collectors.toList());
            route.setIconEntries(data.iconEntries);
            clientThread.invokeLater(() -> unlocksDirty = true);
            Set<Integer> elements = data.icons.stream().filter(icon -> icon.element >= 0).map(icon -> icon.element)
                .collect(Collectors.toSet());
            whenGameLoaded(started, () -> GameIconSprites.loadElements(client, elements), () -> { });
            SwingUtilities.invokeLater(() -> {
                if (screen != null && generation == dataGeneration.get())
                {
                    screen.setData(maps, data.pois, data.hidden, data.labels);
                    fullScreen.setData(maps, data.pois, data.hidden, data.labels);
                    for (MapIconLayer layer : gameIcons)
                    {
                        layer.setIcons(data.icons);
                    }
                }
            });
            return data;
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not load map icons", e);
            return null;
        }
    }

    private RegionTable regionTable(String version) throws IOException
    {
        RegionTable table = new RegionTable();
        if (BUNDLED_VERSION.equals(version))
        {
            try (Reader reader = resource("region_maps.tsv"))
            {
                table.read(reader);
            }
        }
        File cached = regionFile(version);
        if (cached.isFile())
        {
            try (Reader reader = utf8(new FileInputStream(cached)))
            {
                table.read(reader);
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Ignoring unreadable region table {}", cached, e);
            }
        }
        return table;
    }

    private File regionFile(String version)
    {
        return new File(new File(home, "regions"), version + ".tsv");
    }

    private void saveRegions(String version, RegionTable table)
    {
        try
        {
            table.write(regionFile(version), "Wiki map ids showing each overlapping region, map version " + version);
        }
        catch (IOException e)
        {
            log.debug("Could not save region table", e);
        }
    }

    private boolean runData(Runnable task)
    {
        try
        {
            dataIo.execute(task);
            return true;
        }
        catch (RejectedExecutionException e)
        {
            return false;
        }
    }

    private Reader resource(String file) throws IOException
    {
        InputStream in = HdMapReforgedPlugin.class.getResourceAsStream(DATA + file);
        if (in == null)
        {
            throw new IOException("Missing resource " + file);
        }
        return utf8(in);
    }

    private static Reader utf8(InputStream in)
    {
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private void togglePopOut()
    {
        if (window != null)
        {
            dock();
            return;
        }
        window = new MapWindow(screen, this::dock);
        window.setVisible(true);
    }

    private void dock()
    {
        MapWindow closing = window;
        window = null;
        if (closing != null)
        {
            closing.dispose();
        }
    }

    private void showOnMap(WorldPoint point)
    {
        if (fullMap == null)
        {
            return;
        }
        fullMap.open();
        fullScreen.view().focus(point);
    }
}
