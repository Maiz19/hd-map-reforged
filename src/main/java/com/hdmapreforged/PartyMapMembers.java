package com.hdmapreforged;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * The last known location of each party member. Updated from party events (network threads) and read when
 * painting (Swing thread), so every method is synchronized.
 */
final class PartyMapMembers
{
    /** Members send a heartbeat every 30 seconds; after this without news the marker starts to fade. */
    static final long FRESH_MILLIS = 75_000;
    /** Over this time the marker fades to {@link #MIN_ALPHA}. */
    static final long FADE_MILLIS = 5 * 60_000;
    /** After this without news the member is left off the map (they probably crashed or lost connection). */
    static final long GONE_MILLIS = 30 * 60_000;
    static final float MIN_ALPHA = 0.4f;
    /** The core Party plugin's location messages are coarser; ours win while they keep coming. */
    static final long OWN_MESSAGE_PRIORITY_MILLIS = 60_000;

    /** What the map draws for one member. */
    static final class Marker
    {
        final long id;
        final String name;
        final WorldPoint point;
        final int world;
        final BufferedImage avatar;
        final long ageMillis;
        final float alpha;

        Marker(long id, String name, WorldPoint point, int world, BufferedImage avatar, long ageMillis, float alpha)
        {
            this.id = id;
            this.name = name;
            this.point = point;
            this.world = world;
            this.avatar = avatar;
            this.ageMillis = ageMillis;
            this.alpha = alpha;
        }

        boolean stale()
        {
            return ageMillis > FRESH_MILLIS;
        }
    }

    private static final class Member
    {
        String name;
        WorldPoint point;
        int world;
        BufferedImage avatar;
        long updated;
        long ownUpdated = Long.MIN_VALUE / 2;
    }

    private final Map<Long, Member> members = new HashMap<>();
    /** What each member last shared of their inventory, equipment and skills. */
    private final Map<Long, Gear> gear = new HashMap<>();

    /** A member's inventory, equipment and skill levels, as last shared. */
    static final class Gear
    {
        final int[] inventory;
        final int[] quantities;
        final int[] equipment;
        final int[] levels;
        final int[] boosted;
        /** Experience per skill, or null when not shared (an older version). */
        final int[] experience;
        /** Run and special attack energy, 0 to 100, or -1 when not shared. */
        final int run;
        final int special;
        final long at;

        Gear(HdMapPartyGear message, long at)
        {
            inventory = message.inventory();
            quantities = message.quantities();
            equipment = message.equipment();
            levels = message.levels();
            boosted = message.boosted();
            experience = message.experience();
            run = message.runEnergy();
            special = message.specialAttack();
            this.at = at;
        }
    }

    /** Experience gained in one skill, shown rising in the member's details. */
    static final class Drop
    {
        final int skill;
        final int amount;
        final long at;

        Drop(int skill, int amount, long at)
        {
            this.skill = skill;
            this.amount = amount;
            this.at = at;
        }
    }

    /** How long an experience drop shows. */
    static final long DROP_MS = 2200;
    /** Experience drops kept per member at most. */
    static final int MAX_DROPS = 16;
    private final Map<Long, List<Drop>> drops = new HashMap<>();

    synchronized void gear(long id, Gear shared)
    {
        Gear before = gear.put(id, shared);
        if (before != null && before.experience != null && shared.experience != null)
        {
            // What went up since the last share: experience drops.
            List<Drop> list = drops.computeIfAbsent(id, k -> new java.util.ArrayList<>());
            for (int k = 0; k < shared.experience.length; k++)
            {
                int gained = shared.experience[k] - before.experience[k];
                if (gained > 0 && gained < 5_000_000)
                {
                    list.add(new Drop(k, gained, shared.at));
                }
            }
            // Only read while the member's details are open: keep the list short either way.
            list.removeIf(d -> shared.at - d.at > DROP_MS);
            if (list.size() > MAX_DROPS)
            {
                list.subList(0, list.size() - MAX_DROPS).clear();
            }
        }
    }

    /** A valuable drop a member got, shown rising above them on the map. */
    static final class Loot
    {
        final int item;
        final int quantity;
        final long value;
        final long at;

        Loot(int item, int quantity, long value, long at)
        {
            this.item = item;
            this.quantity = quantity;
            this.value = value;
            this.at = at;
        }
    }

    /** How long a drop shows. */
    static final long LOOT_MS = 3500;
    private final Map<Long, List<Loot>> loot = new HashMap<>();

    synchronized void loot(long id, Loot drop)
    {
        List<Loot> list = loot.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(drop);
        // A big pile at once shows its most valuable few.
        if (list.size() > 4)
        {
            list.sort(java.util.Comparator.comparingLong((Loot l) -> -l.value));
            list.subList(4, list.size()).clear();
        }
    }

    /** A member's drops still showing at {@code now}. */
    synchronized List<Loot> loot(long id, long now)
    {
        List<Loot> list = loot.get(id);
        if (list == null)
        {
            return Collections.emptyList();
        }
        list.removeIf(l -> now - l.at > LOOT_MS);
        return new ArrayList<>(list);
    }

    /** Whether any member has a drop showing: the map keeps drawing while they rise. */
    synchronized boolean anyLoot(long now)
    {
        for (List<Loot> list : loot.values())
        {
            list.removeIf(l -> now - l.at > LOOT_MS);
            if (!list.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /** A member's experience drops still showing at {@code now}, oldest first. */
    synchronized List<Drop> drops(long id, long now)
    {
        List<Drop> list = drops.get(id);
        if (list == null)
        {
            return java.util.Collections.emptyList();
        }
        list.removeIf(d -> now - d.at > DROP_MS);
        return new java.util.ArrayList<>(list);
    }

    /** What a member last shared, or null. */
    synchronized Gear gear(long id)
    {
        return gear.get(id);
    }

    /** A location from this plugin on the member's side; a null point means they left the game. */
    synchronized void update(long id, WorldPoint point, int world, String name, long now)
    {
        Member member = members.computeIfAbsent(id, k -> new Member());
        member.point = point;
        member.world = world;
        if (name != null)
        {
            member.name = name;
        }
        member.updated = now;
        member.ownUpdated = now;
    }

    /** A location from RuneLite's Party plugin, used only while no recent message of ours came from the member. */
    synchronized boolean updateFromCore(long id, WorldPoint point, long now)
    {
        Member member = members.computeIfAbsent(id, k -> new Member());
        if (now - member.ownUpdated < OWN_MESSAGE_PRIORITY_MILLIS)
        {
            return false;
        }
        member.point = point;
        member.updated = now;
        return true;
    }

    /** A name from RuneLite's party data, used when the member's own messages carry none. */
    synchronized void name(long id, String name)
    {
        Member member = members.get(id);
        if (member != null && member.name == null && name != null && !name.isEmpty())
        {
            member.name = name;
        }
    }

    synchronized void avatar(long id, BufferedImage avatar)
    {
        Member member = members.get(id);
        if (member != null)
        {
            member.avatar = avatar;
        }
    }

    synchronized void remove(long id)
    {
        members.remove(id);
        gear.remove(id);
        drops.remove(id);
        loot.remove(id);
    }

    synchronized void clear()
    {
        members.clear();
        gear.clear();
        drops.clear();
        loot.clear();
    }

    synchronized boolean isEmpty()
    {
        return members.isEmpty();
    }

    /** Members to draw now, oldest news first so the freshest end up on top. */
    synchronized List<Marker> markers(long now)
    {
        List<Marker> markers = new ArrayList<>();
        for (Map.Entry<Long, Member> entry : members.entrySet())
        {
            Member member = entry.getValue();
            long age = Math.max(0, now - member.updated);
            if (member.point == null || age > GONE_MILLIS)
            {
                continue;
            }
            markers.add(new Marker(entry.getKey(), member.name, member.point, member.world, member.avatar, age, alpha(age)));
        }
        markers.sort((a, b) -> Long.compare(b.ageMillis, a.ageMillis));
        return Collections.unmodifiableList(markers);
    }

    /** Full until {@link #FRESH_MILLIS}, then fading linearly to {@link #MIN_ALPHA} over {@link #FADE_MILLIS}. */
    static float alpha(long ageMillis)
    {
        if (ageMillis <= FRESH_MILLIS)
        {
            return 1f;
        }
        double t = Math.min(1, (ageMillis - FRESH_MILLIS) / (double) FADE_MILLIS);
        return (float) (1 - t * (1 - MIN_ALPHA));
    }
}
