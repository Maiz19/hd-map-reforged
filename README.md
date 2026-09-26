# HD Map Reforged

![HD Map Reforged](icon.png)

A world map that zooms much further than the in-game map, both out (the whole world in view) and in (single game tiles as crisp squares). It uses the current map from the [OSRS Wiki](https://oldschool.runescape.wiki/w/RuneScape:Map), with its own icons on top:

- **Teleports**: spell, jewellery, item and minigame teleports, with their requirements. Click one to see the other destinations of the same item or spellbook.
- **Transport networks**: fairy rings (with codes), spirit trees, gnome gliders, balloons, quetzals, mushtrees, obelisks, boats, charter ships, canoes, carpets, minecarts, portals and levers. Click one to draw lines to every place it can take you, with what each trip needs.
- **Dungeon entrances**: click one to open that dungeon's map at the spot you arrive; *◂ Back* returns to where you were.
- **Sailing**: moorings and salvage spots with their Sailing levels.
- **Skilling** (off by default): runecrafting altars, agility courses and shortcuts, farming patches. **Minigames**, **banks, altars and anvils**.
- **The game's own map icons** that the wiki's map shows (shops, quest starts, map links, furnaces and the rest): hover one for its name, click it for details. Shops are named after their town with the wiki's page for that shop, quest starts after their quest, and dungeon map links take you to the map they lead to. Turn off with *Game map icons*.
- **Place names**, and **Wilderness level lines** with the level 20 and 30 teleport limits.

Every icon has a detail card with buttons for the wiki page and the wiki's own map. When you are logged in, requirements are ticked off, and **Only what I can use** hides what you have not unlocked (skills, quests and unlocks; items are not checked). Right-click the map for **Nearest teleports to here**.

**Path to here**: right-click any spot on the map. The plugin searches a route with its own pathfinder on its own map of where one can walk (read from the game's data): walking and running, doors, stairs, ladders and dungeon entrances, the teleports whose runes or item you carry and whose level, quest and unlock you have, transport networks, boats and charter ships, and **Sailing**: boarding your own boat at a dock, sailing over open sea within your Sailing level (hazardous seas such as icy or fetid waters need their level) and docking where you have the level. On your boat the route starts at sea; in your house it starts from its exit portal, and from the portals and jewellery box it has once you have been in it (or as set under *Your house* in the settings). The route is drawn on the map (walking in yellow, jumps dashed, sailing in blue) with a step list in the card; click a step to see it. A spot that cannot be stood on (wall, water, object) or reached is marked, and the route ends as close as it gets. Optionally the route's tiles are marked on the ground and the minimap. It only shows the way: nothing is walked or clicked for you. *Clear path* removes it; settings under *Route*. **Custom routes** (right-click, *Custom routes...*): your own routes with several stops (spots, icons, or the nearest of a kind such as yew trees), in your order or the fastest one the planner finds, saved and run stop after stop. The search bar finds places, teleports and maps, every place of a kind (all herb patches, shark fishing spots, iron rocks, banks), **monsters and NPCs** (their spawns show on the map as on the wiki's location maps) and **items**: their spawns, the shops that have them in stock, and the monsters dropping them, one list at a time.


**Over the game**: set a key for it, or turn on *World map orb opens this map* (off by default) so a left click on the orb opens it; the game's own map stays under right-click → *World Map*. It shows over the game view (Escape or ✕ closes it): move it by the strip along its top, resize it by its edges, or maximise it to cover the game view; it opens where you left it. By default it is part of RuneLite's own window (*Map inside the game window*), otherwise a window of its own. It stays sharp in Stretched Mode and only takes the keyboard while you type in its search field. It is also in the sidebar (folded map icon), where ⤢ opens it over the game.

**Friends on the map**: the members of your RuneLite party (join one in RuneLite's Party panel) show as coloured markers; point at one for their name and world, click for what they carry, wear and can do (unless they turned off *Share inventory, equipment and skills*), with a *Hop* button when they are on another world (not to PvP, high-risk, Deadman, seasonal or beta worlds, nor members worlds for free players). The plugin remembers your last party: *Rejoin party* is on the map and in the sidebar, and with *Remind me to rejoin my party* (off by default) a small *Rejoin party* button shows over the game after you log in outside a party. You always join yourself; the plugin never joins a party on its own. Members who only use RuneLite's Party plugin show too, less precisely. **Favourite friends** always get their name and a star.

Scroll to zoom, drag to move, double-click to zoom in. The crosshair button follows your character, also into dungeons and on your boat.

**Shortest Path**: if you use the Shortest Path plugin, *Route planner* can hand "Path to here" to it, and directions other plugins send to Shortest Path (Quest Helper and others) are also planned on this map. Only RuneLite's plugin messages are used.

## Stays up to date by itself

- **Download the whole map** (a setting under *Data and downloads*) keeps the surface, or also every dungeon, on disk, so nothing has to load any more. It runs in the background and continues after a restart.
- The map, its list of maps and which map shows which area follow the wiki's current map version; nothing waits for a plugin update. The version is checked once a week (configurable); tiles stay on disk in between, and after a new version the old tiles show until the new ones have downloaded.
- The list of the game's map icons is bundled (read from the game cache for this plugin version); icons Jagex adds later are clickable after a plugin update.
- Teleports and transports are the plugin's own tables, built from the OSRS Wiki and the game cache for each release; new teleports appear after a plugin update. Nothing about them is downloaded while you play.

## Data and network use

- `maps.runescape.wiki` (tiles, map list) and `oldschool.runescape.wiki` (map version, and what a search, a shop or a drop list looks up). Only read requests; nothing about your account is sent. Your IP address is visible to these sites, as when you visit them.
- **Party** (only while you are in a RuneLite party, which you join yourself): your location, world and character name go to the other members through RuneLite's party server, at most every five game ticks (3 seconds) while you move and every 30 seconds while you stand still; turn off *Share my location* to send nothing. Unless you turn them off: your inventory, equipment and skills (at most every 6 seconds, when they change) and your valuable drops (at most 4 per kill). A party this plugin rejoined for you is left after 30 minutes logged out and when you turn the plugin off.
- Everything is stored in `.runelite/hd-map-reforged`, with a size limit for tiles. With *Write routes.log* (off by default) the last 30 planned routes are kept there too, for bug reports.

The map only changes what you see. It never walks, clicks, or sends anything to the game.

Map tiles, shop stock, drops and spawn locations: © the [OSRS Wiki](https://oldschool.runescape.wiki) / Weird Gloop, [CC BY-NC-SA 3.0](https://weirdgloop.org/licensing/).

[Credits](THIRD_PARTY_NOTICES.md) · [License](LICENSE)
