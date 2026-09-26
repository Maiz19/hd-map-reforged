package com.hdmapreforged;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;

enum PoiType
{
    TELEPORT("Teleport", Layer.TELEPORTS, 0x9B59FF, null),
    FAIRY_RING("Fairy ring", Layer.TRANSPORTS, 0x3DDC84, "Fairy ring"),
    SPIRIT_TREE("Spirit tree", Layer.TRANSPORTS, 0x2E9D4A, "Spirit tree"),
    GNOME_GLIDER("Gnome glider", Layer.TRANSPORTS, 0xE0B050, "Gnome glider"),
    BALLOON("Hot air balloon", Layer.TRANSPORTS, 0xE05A47, "Balloon transport system"),
    QUETZAL("Quetzal", Layer.TRANSPORTS, 0x3FB6C9, "Quetzal Transport System"),
    MUSHTREE("Magic mushtree", Layer.TRANSPORTS, 0xB070E0, "Magic Mushtree"),
    OBELISK("Wilderness obelisk", Layer.TRANSPORTS, 0x9AA3AD, "Wilderness Obelisk"),
    BOAT("Boat", Layer.TRANSPORTS, 0x4A90E2, "Boat"),
    CHARTER("Charter ship", Layer.TRANSPORTS, 0x2F6FB8, "Charter ship"),
    CANOE("Canoe", Layer.TRANSPORTS, 0xB07A45, "Canoe"),
    CARPET("Magic carpet", Layer.TRANSPORTS, 0xD9534F, "Magic carpet"),
    MINECART("Minecart", Layer.TRANSPORTS, 0x8D99A6, "Minecart"),
    PORTAL("Portal", Layer.TRANSPORTS, 0xC070FF, null),
    LEVER("Lever", Layer.TRANSPORTS, 0xB0B0B0, "Lever"),
    TRANSPORT("Transport", Layer.TRANSPORTS, 0x5DADE2, null),
    DUNGEON_ENTRANCE("Dungeon entrance", Layer.DUNGEONS, 0xE08A2A, null),
    MAP_EXIT("Map passage", Layer.DUNGEONS, 0x6FB7E0, null),
    MOORING("Mooring", Layer.SAILING, 0x2E86C1, "Sailing"),
    SALVAGE("Salvage", Layer.SAILING, 0x7D6608, "Salvaging"),
    RUNECRAFT_ALTAR("Runecrafting altar", Layer.SKILLING, 0xC0392B, null),
    AGILITY_COURSE("Agility course", Layer.SKILLING, 0x27AE60, null),
    AGILITY_SHORTCUT("Agility shortcut", Layer.SKILLING, 0x58D68D, "Shortcuts"),
    FARMING_PATCH("Farming patch", Layer.SKILLING, 0x8E6E3B, null),
    MINIGAME("Minigame", Layer.ACTIVITIES, 0xD35400, null),
    BANK("Bank", Layer.SERVICES, 0xF1C40F, "Bank"),
    ALTAR("Altar", Layer.SERVICES, 0xE8E8F0, "Altar"),
    ANVIL("Anvil", Layer.SERVICES, 0x95A5A6, "Anvil"),
    // Icons baked into the wiki tiles (MapIconLayer): never drawn, only hovered and clicked.
    SHOP("Shop", Layer.GAME_ICONS, 0xC8A060, null),
    QUEST_START("Quest start", Layer.GAME_ICONS, 0x3C8CE6, null),
    MAP_LINK("Map link", Layer.GAME_ICONS, 0x4AA3E8, null),
    GAME_ICON("Map icon", Layer.GAME_ICONS, 0xB8B8B8, null),
    /** A search result without an icon of its own: only selected, never drawn. */
    FOUND("Search result", Layer.GAME_ICONS, 0x46DC5A, null);

    final String displayName;
    final Layer layer;
    final Color color;
    /** Wiki page describing this kind of icon, or null when each icon names its own page. */
    final String wikiPage;

    PoiType(String displayName, Layer layer, int rgb, String wikiPage)
    {
        this.displayName = displayName;
        this.layer = layer;
        this.color = new Color(rgb);
        this.wikiPage = wikiPage;
    }

    /** Obelisks send you to a random other obelisk unless you can choose. */
    boolean isRandomDestination()
    {
        return this == OBELISK;
    }

    /** Every stop reaches every other. */

    boolean isNetwork()
    {
        return NETWORKS.contains(this);
    }

    /** Fairy rings to obelisks, in the order above. */
    private static final Set<PoiType> NETWORKS = EnumSet.range(FAIRY_RING, OBELISK);
}
