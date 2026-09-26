package com.hdmapreforged;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A valuable drop a party member just got (setting "Share valuable drops"), sent once to the party so the others see
 * it rise above the member on their map. Values from other clients are checked before use.
 */
public class HdMapPartyDrop extends PartyMemberMessage
{
    /** Item ids above this are not believed. */
    static final int MAX_ITEM = 100_000;

    /** Item id. */
    private final int i;
    /** Quantity. */
    private final int q;
    /** Worth, in coins, as the sender's client priced it. */
    private final long v;

    HdMapPartyDrop(int item, int quantity, long value)
    {
        i = item;
        q = quantity;
        v = value;
    }

    /** The item, or -1 when not believable. */
    int item()
    {
        return i >= 0 && i < MAX_ITEM ? i : -1;
    }

    int quantity()
    {
        return Math.max(1, q);
    }

    long value()
    {
        return Math.max(0, Math.min(Integer.MAX_VALUE * 100L, v));
    }
}
