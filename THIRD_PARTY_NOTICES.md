# Third-party notices

## RuneLite

- Source: https://github.com/runelite/runelite, tag `runelite-parent-1.12.39`, `runelite-client/src/main/java/net/runelite/client/plugins/worldmap/` (`DungeonLocation`, `TransportationPointLocation`, `MooringLocation`, `SalvagingSpotLocation`, `RunecraftingAltarLocation`, `MinigameLocation`, `AgilityCourseLocation`, `FarmingPatchLocation`, and for `runelite_skill_spots.tsv` `HunterAreaLocation`, `FishingSpotLocation`, `MiningSiteLocation`, `RareTreeLocation` and `net/runelite/client/game/FishingSpot`).
- Authors named in those files: Morgan Lewis, Magic fTail, Torkel Velure, Bryce Altomare, Dava96, Kyle Sergio, Kyle Stead, Arman S, melky, Adam, coopermor, Sam Szotkowski, and the RuneLite contributors.
- License: BSD 2-Clause. Full text in `src/main/resources/META-INF/LICENSE-runelite`, included in the JAR.

The names, levels and coordinates of those lists are converted to `data/runelite_*.tsv` by `local-development/tools/build-runelite-lists.py` and `build-skill-spots.py` (fish and ore levels added from the game). No RuneLite code is copied.

## Old School RuneScape game data

- `map_icons.tsv` lists the positions of the game's world map icons, the quest names and fairy ring codes they belong to, and where map links lead. It is read from the OSRS game cache (as archived by [OpenRS2](https://archive.openrs2.org), cache 2720 of 2026-09-23) with RuneLite's `net.runelite:cache` library (BSD 2-Clause) by `local-development/tools/map-icons`. No game graphics are included. The game and its data are © Jagex Ltd; the same icons are shown on the wiki's map tiles.
- The travel tables (`teleports.tsv`, `transport_networks.tsv`, `transport_routes.tsv`, `shortcuts.tsv`) use the same cache for the tiles of fairy rings, spirit trees, mushtrees, balloons, minecarts, levers, canoe stations and shortcut objects, and for item ids. Built by `local-development/tools/travel-data` with `local-development/tools/map-icons` (`dumpTravelCache`).
- `route/collision.zip` (which tiles can be walked on, walls and doors, water; ladders, stairs and entrances with where they lead) and `route/ports.tsv` (the Sailing ports) are computed from the map files, object definitions and database of the same OSRS cache (OpenRS2 cache 2720) by `local-development/tools/collision`, also with `net.runelite:cache`. No game code, models or graphics are included; the data is our own summary of the game's map.
- `data/world_map_moves.tsv` (parts of the game the world map draws elsewhere) is read from the same cache's composite maps by `local-development/tools/map-icons` (`WorldMapMoves`). `route/map_link_passages.tsv` pairs the game's map links (`map_icons.tsv`) with the cache's objects (`MapLinkPassages`, a development tool that is not published).
- `route/sea_areas.tsv` (named seas and their Sailing hazards) comes from the OSRS Wiki's sea pages and its *Sailing hazards* page (CC BY-NC-SA 3.0), built by `local-development/tools/build-sea-areas.py`.
- The names of the icon kinds come from the wiki map's icon list (`maps.runescape.wiki/osrs/data/iconLists/MainIcons.json`) and, for kinds missing there, the wiki's *Map icon* page (CC BY-NC-SA 3.0).

## Old School RuneScape Wiki

- Map tiles, `basemaps.json` and page text: © the OSRS Wiki / Weird Gloop, [CC BY-NC-SA 3.0](https://weirdgloop.org/licensing/). Tiles, shop stock, drops and spawn locations are downloaded at runtime; the plugin credits them here and in its README rather than over the map.
- `basemaps.json` is the wiki's map list for map version `2026-08-12_a`, used until the current list has loaded.
- `data/icon_names.tsv`, `data/boss_entrances.tsv`, `route/gates.tsv`, `route/fees.tsv`, `route/one_way.tsv` and `route/links.tsv` are hand-made from wiki pages (named in each file) and the game cache; `data/npc_hidden.tsv` and `data/npc_no_location.tsv` list wiki monster pages, built by the development tools `NpcAudit` and `MonsterRouteAudit` (not published) from the wiki's location lines. All under the same license.
- `region_maps.tsv` is derived from the wiki's map tiles (which map shows each overlapping area); `place_names.tsv` from settlement, island and region pages (kingdom label positions are hand-placed). All under the same license.
- The travel tables (`teleports.tsv`, `transport_networks.tsv`, `transport_routes.tsv`, `shortcuts.tsv`) are our own, built by `local-development/tools/travel-data` from wiki pages read through the wiki's API (page wikitext and Bucket tables `infobox_spell` and `quest`): names, destinations and stops from their `{{TeleportLocationLine}}`, `{{Map}}` and `{{ObjectLocLine}}` coordinates, levels, runes, fares and quest requirements from their infoboxes and tables (spell, jewellery, item and cape pages, *Teleport tablet*, *Master scroll book*, *Minigame Teleport*, *Fairy ring*, *Spirit tree*, *Gnome glider*, *Balloon transport system*, *Quetzal Transport System*, *Magic Mushtree*, *Wilderness Obelisk*, *Charter ship* and *Template:Charter ship fares*, *Canoe station*, *Magic carpet*, *Lovakengj Minecart Network*, *Shortcuts* and each shortcut's page, and the pages of the NPCs who run boats). Under the same license.
