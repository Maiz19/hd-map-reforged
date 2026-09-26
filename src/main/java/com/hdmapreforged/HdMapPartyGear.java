package com.hdmapreforged;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * What a party member carries, wears and can do, sent to their party by this plugin when it changes (setting
 * "Share inventory, equipment and skills"). Short field names keep the message small; values from other clients are
 * checked before use.
 */
public class HdMapPartyGear extends PartyMemberMessage
{
    static final int INVENTORY = 28;
    static final int EQUIPMENT = 14;
    static final int SKILLS = 24;
    /** Item ids above this are not believed. */
    static final int MAX_ITEM = 100_000;

    /** Inventory item ids, -1 for an empty slot. */
    private final int[] i;
    /** Inventory quantities. */
    private final int[] q;
    /** Worn item ids by equipment slot, -1 for none. */
    private final int[] e;
    /** Real skill levels, in the order of RuneLite's Skill list. */
    private final int[] s;
    /** Current (boosted or drained) skill levels, same order. */
    private final int[] b;
    /** Experience per skill, same order; null from older versions. */
    private final int[] x;
    /** Run energy, 0 to 100; null from older versions. */
    private final Integer r;
    /** Special attack energy, 0 to 100; null from older versions. */
    private final Integer p;

    HdMapPartyGear(int[] inventory, int[] quantities, int[] equipment, int[] levels, int[] boosted)
    {
        this(inventory, quantities, equipment, levels, boosted, null, -1, -1);
    }

    HdMapPartyGear(int[] inventory, int[] quantities, int[] equipment, int[] levels, int[] boosted, int[] experience,
        int run, int special)
    {
        i = inventory;
        q = quantities;
        e = equipment;
        s = levels;
        b = boosted;
        x = experience;
        r = run < 0 ? null : run;
        p = special < 0 ? null : special;
    }

    /** Experience per skill, or null when not shared. */
    int[] experience()
    {
        if (x == null)
        {
            return null;
        }
        int[] out = new int[SKILLS];
        for (int k = 0; k < Math.min(x.length, SKILLS); k++)
        {
            out[k] = Math.max(0, Math.min(200_000_000, x[k]));
        }
        return out;
    }

    /** Run energy 0 to 100, or -1 when not shared. */
    int runEnergy()
    {
        return r == null ? -1 : Math.max(0, Math.min(100, r));
    }

    /** Special attack energy 0 to 100, or -1 when not shared. */
    int specialAttack()
    {
        return p == null ? -1 : Math.max(0, Math.min(100, p));
    }

    int[] inventory()
    {
        return items(i, INVENTORY);
    }

    int[] quantities()
    {
        int[] out = new int[INVENTORY];
        for (int k = 0; q != null && k < Math.min(q.length, INVENTORY); k++)
        {
            out[k] = Math.max(0, q[k]);
        }
        return out;
    }

    int[] equipment()
    {
        return items(e, EQUIPMENT);
    }

    int[] levels()
    {
        return levels(s);
    }

    int[] boosted()
    {
        return levels(b);
    }

    private static int[] items(int[] ids, int size)
    {
        int[] out = new int[size];
        java.util.Arrays.fill(out, -1);
        for (int k = 0; ids != null && k < Math.min(ids.length, size); k++)
        {
            out[k] = ids[k] >= 0 && ids[k] < MAX_ITEM ? ids[k] : -1;
        }
        return out;
    }

    private static int[] levels(int[] values)
    {
        int[] out = new int[SKILLS];
        for (int k = 0; values != null && k < Math.min(values.length, SKILLS); k++)
        {
            out[k] = Math.max(0, Math.min(255, values[k]));
        }
        return out;
    }
}
