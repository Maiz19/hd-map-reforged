package com.hdmapreforged;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(HdMapReforgedConfig.GROUP)
public interface HdMapReforgedConfig extends Config
{
    String GROUP = "hdmapreforged";

    @ConfigSection(name = "Icons", description = "Which icons the map shows", position = 0)
    String icons = "icons";

    @ConfigSection(name = "Map", description = "Map behaviour and appearance", position = 1)
    String map = "map";

    @ConfigSection(name = "Data and downloads", description = "What is downloaded, and how it is kept", position = 2)
    String data = "data";

    @ConfigSection(name = "Route", description = "Right-click the map, Path to here. The settings below the planner "
        + "choice are for this plugin's own planner; Shortest Path has its own settings. It only shows the way; nothing "
        + "is walked or clicked for you", position = 3)
    String route = "route";

    @ConfigSection(name = "Friends", description = "Party members on the map, through RuneLite's party service",
        position = 4)
    String friends = "friends";

    @ConfigSection(name = "Your house", description = "What your player-owned house has, for routes. Filled in when "
        + "you are in your own house; change it here when that is not seen yet", position = 5, closedByDefault = true)
    String house = "house";

    enum JewelleryBox
    {
        NONE("None"),
        BASIC("Basic"),
        FANCY("Fancy"),
        ORNATE("Ornate");

        private final String name;

        JewelleryBox(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "houseJewelleryBox", name = "Jewellery box", position = 0, section = house,
        description = "The jewellery box in your house: basic (duelling, games), fancy (also combat, skills) or ornate "
            + "(also glory)")
    default JewelleryBox houseJewelleryBox()
    {
        return JewelleryBox.NONE;
    }

    @ConfigItem(keyName = "houseGlory", name = "Mounted amulet of glory", position = 1, section = house,
        description = "An amulet of glory mounted in your house")
    default boolean houseGlory()
    {
        return false;
    }

    @ConfigItem(keyName = "houseFairyRing", name = "Fairy ring", position = 2, section = house,
        description = "A fairy ring in your house's garden")
    default boolean houseFairyRing()
    {
        return false;
    }

    @ConfigItem(keyName = "houseSpiritTree", name = "Spirit tree", position = 3, section = house,
        description = "A spirit tree in your house's garden")
    default boolean houseSpiritTree()
    {
        return false;
    }

    @ConfigItem(keyName = "housePortals", name = "Portals", position = 4, section = house,
        description = "Where the portals in your portal chamber or nexus lead, separated by commas: the places of the "
            + "spellbooks' teleports, such as \"Varrock, Falador, Ardougne, Kharyrdaq\"")
    default String housePortals()
    {
        return "";
    }

    @ConfigItem(keyName = "showTeleports", name = "Teleports", position = 0, section = icons,
        description = "Destinations of spells, jewellery, other teleport items and minigame teleports")
    default boolean showTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "showTransports", name = "Transport networks", position = 1, section = icons,
        description = "Fairy rings, spirit trees, gliders, balloons, quetzals, boats, carts, carpets, portals and levers")
    default boolean showTransports()
    {
        return true;
    }

    @ConfigItem(keyName = "showDungeons", name = "Dungeon entrances", position = 2, section = icons,
        description = "Ladders, stairs and holes that lead to another map. Click one to look inside")
    default boolean showDungeons()
    {
        return true;
    }

    @ConfigItem(keyName = "showSailing", name = "Sailing", position = 3, section = icons,
        description = "Moorings and salvage spots, with the Sailing level they need")
    default boolean showSailing()
    {
        return true;
    }

    @ConfigItem(keyName = "showSkilling", name = "Skilling", position = 4, section = icons,
        description = "Runecrafting altars, agility courses and shortcuts, and farming patches")
    default boolean showSkilling()
    {
        return true;
    }

    @ConfigItem(keyName = "showActivities", name = "Minigames", position = 5, section = icons,
        description = "Minigame locations")
    default boolean showActivities()
    {
        return true;
    }

    @ConfigItem(keyName = "showServices", name = "Banks, altars and anvils", position = 6, section = icons,
        description = "Shown when zoomed in")
    default boolean showServices()
    {
        return true;
    }

    @ConfigItem(keyName = "showGameIcons", name = "Game map icons", position = 7, section = icons,
        description = "The game's own icons on the map (shops, quest starts, map links and more): hover for the name, "
            + "click for details. Map links lead to the map they show")
    default boolean showGameIcons()
    {
        return true;
    }

    @ConfigItem(keyName = "onlyUsable", name = "Only what I can use", position = 8, section = icons,
        description = "Hide icons and routes whose skill, quest or unlock requirements you do not meet. "
            + "Items are not checked. Needs you to be logged in")
    default boolean onlyUsable()
    {
        return false;
    }

    @Range(min = 10, max = 40)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "iconSize", name = "Icon size", position = 9, section = icons,
        description = "Size of map icons. 15 is the size of the game's own icons in the map tiles; larger draws "
            + "those larger too")
    default int iconSize()
    {
        return 18;
    }

    @ConfigItem(keyName = "mapInGameWindow", name = "Map inside the game window", position = -2,
        description = "The map over the game view is part of RuneLite's own window (on) or a window of its own (off). "
            + "Inside it, the desktop sees one window: the taskbar and alt-tab keep working as usual. Takes effect the "
            + "next time the map opens")
    default boolean mapInGameWindow()
    {
        return true;
    }

    @ConfigItem(keyName = "orbOpensMap", name = "World map orb opens this map", position = -3,
        description = "Left-clicking the world map orb opens this map over the game view (move it by the strip along "
            + "its top, resize it by its edges). The game's own map stays under right-click \"World Map\". Off: the "
            + "orb opens the game's map; open this one from the sidebar or the hotkey")
    default boolean orbOpensMap()
    {
        return false;
    }

    @ConfigItem(keyName = "openMapKey", name = "Open map hotkey", position = -1,
        description = "A key that opens and closes the map over the game view. Escape also closes it")
    default Keybind openMapKey()
    {
        return Keybind.NOT_SET;
    }

    /** Icon kinds whose destination lines are hidden. */
    @ConfigItem(keyName = "linesOff", name = "", description = "", hidden = true)
    default String linesOff()
    {
        return "FAIRY_RING";
    }

    @ConfigItem(keyName = "fullMapBounds", name = "", description = "", hidden = true)
    default String fullMapBounds()
    {
        return "";
    }

    @ConfigItem(keyName = "followPlayer", name = "Follow player", position = 0, section = map,
        description = "Keep your character in view, and switch to the dungeon map you are in. Dragging the map pauses this")
    default boolean followPlayer()
    {
        return true;
    }

    @ConfigItem(keyName = "showLabels", name = "Place names", position = 1, section = map,
        description = "Names of kingdoms, islands and settlements")
    default boolean showLabels()
    {
        return true;
    }

    @ConfigItem(keyName = "showWilderness", name = "Wilderness levels", position = 2, section = map,
        description = "Lines every five Wilderness levels, with the level 20 and 30 teleport limits marked")
    default boolean showWilderness()
    {
        return true;
    }

    @ConfigItem(keyName = "crispPixels", name = "Sharp pixels when zoomed in", position = 3, section = map,
        description = "Show game tiles as crisp squares beyond the wiki's most detailed zoom level, instead of smoothing them")
    default boolean crispPixels()
    {
        return true;
    }

    @Range(min = 64, max = 1024)
    @ConfigItem(keyName = "memoryTiles", name = "Tiles kept in memory", position = 4, section = map,
        description = "Each tile uses about 256 KB. More are kept when a large window needs them")
    default int memoryTiles()
    {
        return 256;
    }

    enum WholeMap
    {
        OFF("Off", null),
        SURFACE("Surface (about 200 MB)", MapDownloader.Scope.SURFACE),
        ALL("Surface and dungeons (about 500 MB)", MapDownloader.Scope.ALL);

        private final String name;
        final MapDownloader.Scope scope;

        WholeMap(String name, MapDownloader.Scope scope)
        {
            this.name = name;
            this.scope = scope;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "wholeMap", name = "Download the whole map", position = 0, section = data,
        description = "Keep the whole map on disk so every part shows at once, also offline. Downloads in the "
            + "background, two tiles at a time, and continues after a restart. Downloaded once: after a wiki map update "
            + "only the parts you look at are fetched again, the rest stays as it was. Turns on the disk cache and "
            + "raises its size limit when needed")
    default WholeMap wholeMap()
    {
        return WholeMap.OFF;
    }

    @ConfigItem(keyName = "diskCache", name = "Cache tiles on disk", position = 1, section = data,
        description = "Keep downloaded map tiles in .runelite/hd-map-reforged so they load instantly next time")
    default boolean diskCache()
    {
        return true;
    }

    @Range(min = 100, max = 10000)
    @Units(" MB")
    @ConfigItem(keyName = "diskCacheMb", name = "Disk cache size", position = 2, section = data,
        description = "The least recently downloaded tiles are removed above this size")
    default int diskCacheMb()
    {
        return 1000;
    }

    enum UpdateCheck
    {
        EVERY_START("Every start", 0),
        DAILY("Daily", 1),
        WEEKLY("Weekly", 7),
        NEVER("Never", -1);

        private final String name;
        final int days;

        UpdateCheck(String name, int days)
        {
            this.name = name;
            this.days = days;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "mapUpdateCheck", name = "Check for a new map", position = 3, section = data,
        description = "How often to ask the wiki whether its map changed. The map is kept on disk in between; after a "
            + "change, the old map shows until the new tiles have downloaded")
    default UpdateCheck mapUpdateCheck()
    {
        return UpdateCheck.WEEKLY;
    }

    @ConfigItem(keyName = "mapVersion", name = "Map version", position = 4, section = data,
        description = "Leave empty to use the version the wiki currently shows, such as 2026-08-12_a")
    default String mapVersion()
    {
        return "";
    }

    @ConfigItem(keyName = "partyShowMembers", name = "Show party members", position = 0, section = friends,
        description = "Show where the members of your RuneLite party are (join one in RuneLite's Party panel)")
    default boolean partyShowMembers()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareGear", name = "Share inventory, equipment and skills", position = 2,
        section = friends, description = "While you are in a party, its members can see what you carry, wear and your "
            + "skill levels in their friends tab (click your name there). Sent only when it changes, at most every "
            + "6 seconds; nothing is sent outside a party")
    default boolean partyShareGear()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareDrops", name = "Share valuable drops", position = 3, section = friends,
        description = "While you are in a party, a drop worth at least the value below rises above you on your friends' "
            + "maps, as experience drops do (the 4 most valuable of one drop). Nothing is sent outside a party")
    default boolean partyShareDrops()
    {
        return true;
    }

    @Range(min = 1000)
    @ConfigItem(keyName = "partyDropValue", name = "Valuable drop from", position = 4, section = friends,
        description = "The least a drop (one item stack) is worth to be shared, in coins; 100,000 is Ground Items' "
            + "medium value")
    default int partyDropValue()
    {
        return 100_000;
    }

    @ConfigItem(keyName = "partyShareLocation", name = "Share my location", position = 1, section = friends,
        description = "While you are in a party, send your location to its members so their map shows you. Nothing "
            + "is sent when you are not in a party")
    default boolean partyShareLocation()
    {
        return true;
    }

    @ConfigItem(keyName = "partyAskOnLogin", name = "Remind me to rejoin my party", position = 5,
        section = friends, description = "Out of any party after you log in, but in one before: a small Rejoin party "
            + "button over the game. Nothing is joined without that click")
    default boolean partyAskOnLogin()
    {
        return false;
    }

    @ConfigItem(keyName = "partyFavourites", name = "Favourite friends", position = 6, section = friends,
        description = "Character names, separated by commas. Always shown with their name and a star, on top of others")
    default String partyFavourites()
    {
        return "";
    }

    @ConfigItem(keyName = "partyFriendsTab", name = "Friends button on the map", position = 8, section = friends,
        description = "A button at the bottom left of the map, while you are in a party: the members with their "
            + "worlds. Click one to see where they are and what they carry")
    default boolean partyFriendsTab()
    {
        return true;
    }

    @ConfigItem(keyName = "partyOnlyFavourites", name = "Only show favourites", position = 7, section = friends,
        description = "Leave other party members off the map, for example in a big raid party")
    default boolean partyOnlyFavourites()
    {
        return false;
    }

    enum RoutePlanner
    {
        OWN("HD Map Reforged"),
        SHORTEST_PATH("Shortest Path plugin");

        private final String name;

        RoutePlanner(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "routePlanner", name = "Route planner", position = -2, section = route,
        description = "Who plans \"Path to here\": this plugin (shown on this map and in the game), or the Shortest Path "
            + "plugin, if you have it (it shows the route in the game)")
    default RoutePlanner routePlanner()
    {
        return RoutePlanner.OWN;
    }

    @ConfigItem(keyName = "routeFollowPlugins", name = "Follow other plugins' directions", position = -1, section = route,
        description = "Plugins such as Quest Helper send directions to Shortest Path; with this on, this map plans a "
            + "route to them too (when this plugin is the route planner)")
    default boolean routeFollowPlugins()
    {
        return true;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTeleport", name = "Own: Teleport if it saves", position = 13, section = route,
        description = "A teleport is only taken when it saves at least this many tiles of running (a teleport takes about 5 ticks and costs runes or a charge)")
    default int routeSaveTeleport()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShortcut", name = "Own: Shortcut if it saves", position = 14, section = route,
        description = "An agility shortcut is only taken when it saves at least this many tiles")
    default int routeSaveShortcut()
    {
        return 0;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTransport", name = "Own: Transport if it saves", position = 15, section = route,
        description = "Fairy rings, spirit trees, gliders, balloons, minecarts, quetzals, obelisks and the like are only taken when they save at least this many tiles")
    default int routeSaveTransport()
    {
        return 5;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCanoe", name = "Own: Canoe if it saves", position = 16, section = route,
        description = "A canoe (about 30 ticks to shape and paddle) is only taken when it saves at least this many tiles")
    default int routeSaveCanoe()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCarpet", name = "Own: Magic carpet if it saves", position = 17, section = route,
        description = "A magic carpet (a long ride, about 50 ticks) is only taken when it saves at least this many tiles")
    default int routeSaveCarpet()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShip", name = "Own: Boat if it saves", position = 18, section = route,
        description = "Boats and charter ships are only taken when they save at least this many tiles")
    default int routeSaveShip()
    {
        return 10;
    }

    @ConfigItem(keyName = "routeNeverUse", name = "Own: Never use", position = 12, section = route,
        description = "Teleports and transports the route never takes, one per line; a whole kind as \"type:Hot air "
            + "balloon\" or \"type:Minigame teleports\". Add them with the ⋯ button of a route step")
    default String routeNeverUse()
    {
        return "";
    }

    @ConfigItem(keyName = "routeIgnoreLevels", name = "Own: Ignore levels", position = 10, section = route,
        description = "Plan as if every level, quest and unlock were met, to see the fastest way there is. "
            + "Only for this plugin's planner")
    default boolean routeIgnoreLevels()
    {
        return false;
    }

    @ConfigItem(keyName = "routeIgnoreItems", name = "Own: Ignore items", position = 11, section = route,
        description = "Plan as if you had every teleport item, rune and fare with you, to see the fastest way there is. "
            + "Only for this plugin's planner")
    default boolean routeIgnoreItems()
    {
        return false;
    }

    @ConfigItem(keyName = "routeTeleports", name = "Own: Use teleports", position = 0, section = route,
        description = "Let routes start with a teleport whose runes or item you carry (spells, jewellery, tablets) and "
            + "whose level, quest and unlock you have")
    default boolean routeTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "routeSailing", name = "Own: Sail my boat", position = 1, section = route,
        description = "Let routes board your own boat at a dock, sail and dock elsewhere, within your Sailing level. "
            + "Needs Pandemonium done")
    default boolean routeSailing()
    {
        return true;
    }

    enum BoatFocus
    {
        NONE("None"),
        FOCUS("Teleport focus"),
        GREATER("Greater teleport focus");

        private final String name;

        BoatFocus(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "routeBoatFocus", name = "Own: Boat teleport facility", position = 2, section = route,
        description = "The teleport focus on your boat. With one, routes may summon the boat to any dock (Summon Boat); "
            + "with the greater one also teleport to it (Teleport to Boat, Sailing cape). Without, the boat is boarded "
            + "where it lies, or a shipwright brings it to their port")
    default BoatFocus routeBoatFocus()
    {
        return BoatFocus.NONE;
    }

    @ConfigItem(keyName = "routeRunning", name = "Own: Running", position = 3, section = route,
        description = "Count on running (two tiles a tick) for how long a route takes")
    default boolean routeRunning()
    {
        return true;
    }

    @ConfigItem(keyName = "routeClearOnArrival", name = "Own: Done when arrived", position = 6, section = route,
        description = "Remove the route once you are within a few tiles of where it leads")
    default boolean routeClearOnArrival()
    {
        return true;
    }

    @ConfigItem(keyName = "routeHdTiles", name = "Own: Tiles by HD Tile Markers", position = 5, section = route,
        description = "When the HD Tile Markers plugin is on, it draws the route's tiles on the ground (sharper, also in "
            + "stretched mode); this map keeps the minimap")
    default boolean routeHdTiles()
    {
        return true;
    }

    @ConfigItem(keyName = "routeInGame", name = "Own: Show in the game", position = 4, section = route,
        description = "Also mark the route's tiles on the ground and on the minimap")
    default boolean routeInGame()
    {
        return true;
    }

    @ConfigItem(keyName = "routeColor", name = "Route colour", position = 20, section = route,
        description = "The colour of the route's tiles and walks: on the map, on the ground and on the minimap")
    default Color routeColor()
    {
        return RouteFeature.WALK;
    }

    @ConfigItem(keyName = "routeJumpColor", name = "Teleport colour", position = 21, section = route,
        description = "The colour of teleports, transports and passages on the route")
    default Color routeJumpColor()
    {
        return RouteFeature.JUMP;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileFill", name = "Tile fill opacity", position = 22, section = route,
        description = "How much the route's ground tiles are filled: 0 is only the border, 255 solid")
    default int routeTileFill()
    {
        return 75;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileBorder", name = "Tile border opacity", position = 23, section = route,
        description = "How strong the border of the route's ground tiles is: 0 is no border")
    default int routeTileBorder()
    {
        return 100;
    }

    @ConfigItem(keyName = "routeTileWidth", name = "Tile border width", position = 24, section = route,
        description = "The width of the border of the route's ground tiles")
    default double routeTileWidth()
    {
        return 1.5;
    }

    @ConfigItem(keyName = "routeMinimap", name = "Route on the minimap", position = 25, section = route,
        description = "Dots on the minimap for the route's tiles")
    default boolean routeMinimap()
    {
        return true;
    }

    @ConfigItem(keyName = "routeLog", name = "Write routes.log", position = 26, section = route,
        description = "Keeps the last 30 planned routes in routes.log in the plugin folder (.runelite/hd-map-reforged), "
            + "for bug reports. Nothing is sent anywhere")
    default boolean routeLog()
    {
        return false;
    }
}
