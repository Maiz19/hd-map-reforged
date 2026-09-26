package com.hdmapreforged;

import java.util.Collection;
import java.util.Objects;
import net.runelite.http.api.worlds.WorldType;
import net.runelite.api.coords.WorldPoint;

/**
 * The decisions behind showing a RuneLite party on the map, kept free of RuneLite services so they can be tested:
 * when rejoining the last party is allowed, when to leave, and when to send our location.
 *
 * <p>Joining is always the user's own action: RuneLite rejects parties that are joined automatically (see
 * local-development/PARTY-RESEARCH.md).
 */
final class PartyMapRules
{
    /** Party Hotkeys waits this long between party changes; so do we. */
    static final long CHANGE_COOLDOWN_MILLIS = 5_000;
    /** RuneLite's Party plugin leaves after 30 minutes logged out; we do the same for a party we rejoined. */
    static final long IDLE_LEAVE_MILLIS = 30 * 60_000;
    /** Location messages at most every this many game ticks while moving (5 ticks = 3 s). */
    static final int MIN_SEND_TICKS = 5;
    /** Inventory, equipment and skill messages at most every this many game ticks (10 ticks = 6 s). */
    static final int GEAR_TICKS = 10;
    /** At most this many drop messages for one loot, its most valuable stacks. */
    static final int MAX_DROPS_PER_LOOT = 4;
    /** And at least this often while standing still (50 ticks = 30 s), so newcomers and fading work. */
    static final int HEARTBEAT_TICKS = 50;

    private PartyMapRules()
    {
    }

    /** What a click on "Rejoin party" may do. */
    enum Join
    {
        /** Join now. */
        JOIN,
        /** Already in this party; nothing to do. */
        ALREADY_IN,
        /** In another party the user joined: only after they confirm leaving it. */
        CONFIRM_LEAVE_OTHER,
        NOT_LOGGED_IN,
        /** No party to rejoin. */
        INVALID_CODE,
        TOO_SOON,
    }

    /**
     * Whether pressing "Rejoin party" may join now.
     *
     * @param currentPassphrase the party the user is in, or null
     * @param lastChange when this plugin last changed party, or a large negative number
     */
    static Join join(String code, boolean loggedIn, String currentPassphrase, long lastChange, long now)
    {
        if (code == null)
        {
            return Join.INVALID_CODE;
        }
        if (code.equals(currentPassphrase))
        {
            return Join.ALREADY_IN;
        }
        if (!loggedIn)
        {
            return Join.NOT_LOGGED_IN;
        }
        if (now - lastChange < CHANGE_COOLDOWN_MILLIS)
        {
            return Join.TOO_SOON;
        }
        return currentPassphrase != null ? Join.CONFIRM_LEAVE_OTHER : Join.JOIN;
    }

    /**
     * Whether to remind, after logging in, that the last party can be rejoined: only with one known, logged in, in no
     * party at all, not after the user left it themselves this session, and not right after a change. Joining itself
     * always waits for the user's click.
     */
    static boolean askToJoin(String code, boolean loggedIn, String currentPassphrase, boolean leftByUser,
        long lastChange, long now)
    {
        return code != null && loggedIn && currentPassphrase == null && !leftByUser
            && now - lastChange >= CHANGE_COOLDOWN_MILLIS;
    }

    /**
     * Whether to leave the party by ourselves: only a party this plugin joined for the user, and only after
     * they have been logged out for a long time. Parties the user joined elsewhere are never left.
     *
     * @param joinedCode the group this plugin joined in this session, or null
     * @param loggedOutSince when the user logged out, or null while logged in
     */
    static boolean leaveIdle(String joinedCode, String currentPassphrase, Long loggedOutSince, long now)
    {
        return joinedCode != null && joinedCode.equals(currentPassphrase) && loggedOutSince != null
            && now - loggedOutSince >= IDLE_LEAVE_MILLIS;
    }

    /** Whether shutting the plugin down should leave the party: only a party it joined itself. */
    static boolean leaveOnShutdown(String joinedCode, String currentPassphrase)
    {
        return joinedCode != null && joinedCode.equals(currentPassphrase);
    }

    /**
     * Why "Hop" refuses a world, or null when it may hop there: worlds where dying costs items or that change the
     * account (PvP, Deadman, seasonal, beta, ...) are left to the game's own world switcher, and so are members worlds
     * for a free player.
     */
    static String hopRefusal(Collection<WorldType> types, boolean member)
    {
        if (types == null)
        {
            return null;
        }
        for (WorldType type : types)
        {
            switch (type)
            {
                case PVP:
                case HIGH_RISK:
                case BOUNTY:
                    return "is a PvP or high-risk world";
                case DEADMAN:
                    return "is a Deadman world";
                case SEASONAL:
                case FRESH_START_WORLD:
                case BETA_WORLD:
                case TOURNAMENT:
                case NOSAVE_MODE:
                    return "is a special world (seasonal, beta or tournament)";
                case PVP_ARENA:
                case LAST_MAN_STANDING:
                case QUEST_SPEEDRUNNING:
                    return "is a minigame world";
                default:
                    break;
            }
        }
        if (!member && types.contains(WorldType.MEMBERS))
        {
            return "is a members world";
        }
        return null;
    }

    /**
     * Decides, tick by tick, when our location goes out. {@link #shouldSend} runs on the client thread; party events
     * reset and force it from other threads.
     */
    static final class Throttle
    {
        private WorldPoint lastSent;
        private int lastTick = Integer.MIN_VALUE / 2;
        private volatile boolean forced;

        /** Send soon even if nothing changed (someone joined, or asked for everyone's state). */
        void force()
        {
            forced = true;
        }

        synchronized void reset()
        {
            lastSent = null;
            lastTick = Integer.MIN_VALUE / 2;
            forced = false;
        }

        /** Whether to send {@code point} on this tick; if so it counts as sent. */
        synchronized boolean shouldSend(WorldPoint point, int tick)
        {
            if (point == null)
            {
                return false;
            }
            int since = tick - lastTick;
            boolean moved = !Objects.equals(point, lastSent);
            boolean due = forced || since >= HEARTBEAT_TICKS || moved;
            if (!due || since < MIN_SEND_TICKS)
            {
                return false;
            }
            lastSent = point;
            lastTick = tick;
            forced = false;
            return true;
        }
    }
}
