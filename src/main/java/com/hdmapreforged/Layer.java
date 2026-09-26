package com.hdmapreforged;

/** Groups of icons that can be switched on and off together, in order of precedence when icons overlap. */
enum Layer
{
    DUNGEONS,
    TRANSPORTS,
    TELEPORTS,
    SAILING,
    SKILLING,
    ACTIVITIES,
    SERVICES,
    /** The game's own map icons baked into the wiki tiles; see {@link MapIconLayer}. */
    GAME_ICONS
}
