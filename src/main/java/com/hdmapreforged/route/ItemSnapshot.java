package com.hdmapreforged.route;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The items a player has, taken on the client thread: carried (inventory, worn, rune pouch), runes a worn staff or
 * tome gives without limit, and the bank as last seen (null when not seen this session). Immutable.
 */
public final class ItemSnapshot
{
    /** Item names used in the transport data, for the ones that matter here. */
    private static final Map<String, Integer> NAMES = new HashMap<>();
    /** Combination runes count as each of their two runes. */
    private static final Map<Integer, int[]> COMBINATIONS = new HashMap<>();
    public static final int AIR = 556;
    public static final int WATER = 555;
    public static final int EARTH = 557;
    public static final int FIRE = 554;

    static
    {
        NAMES.put("AIR_RUNE", AIR);
        NAMES.put("WATER_RUNE", WATER);
        NAMES.put("EARTH_RUNE", EARTH);
        NAMES.put("FIRE_RUNE", FIRE);
        NAMES.put("MIND_RUNE", 558);
        NAMES.put("BODY_RUNE", 559);
        NAMES.put("DEATH_RUNE", 560);
        NAMES.put("NATURE_RUNE", 561);
        NAMES.put("CHAOS_RUNE", 562);
        NAMES.put("LAW_RUNE", 563);
        NAMES.put("COSMIC_RUNE", 564);
        NAMES.put("BLOOD_RUNE", 565);
        NAMES.put("SOUL_RUNE", 566);
        NAMES.put("ASTRAL_RUNE", 9075);
        NAMES.put("WRATH_RUNE", 21880);
        NAMES.put("COINS", 995);
        NAMES.put("BANANA", 1963);
        COMBINATIONS.put(4694, new int[]{WATER, FIRE});
        COMBINATIONS.put(4695, new int[]{AIR, WATER});
        COMBINATIONS.put(4696, new int[]{AIR, EARTH});
        COMBINATIONS.put(4697, new int[]{AIR, FIRE});
        COMBINATIONS.put(4698, new int[]{WATER, EARTH});
        COMBINATIONS.put(4699, new int[]{EARTH, FIRE});
    }

    public static final ItemSnapshot NONE = new ItemSnapshot(Collections.emptyMap(), Collections.emptySet(), null);
    /** Counts every item as there: for routes that ignore items. */
    public static final ItemSnapshot EVERYTHING = new ItemSnapshot(Collections.emptyMap(), Collections.emptySet(), null);

    private final Map<Integer, Long> carried;
    private final Set<Integer> unlimited;
    private final Map<Integer, Long> bank;

    private ItemSnapshot(Map<Integer, Long> carried, Set<Integer> unlimited, Map<Integer, Long> bank)
    {
        this.carried = carried;
        this.unlimited = unlimited;
        this.bank = bank;
    }

    /** Quantities by item id; {@code bank} null when unknown. */
    public static ItemSnapshot of(Map<Integer, Long> carried, Set<Integer> unlimited, Map<Integer, Long> bank)
    {
        Map<Integer, Long> all = new HashMap<>();
        carried.forEach((id, n) -> add(all, id, n));
        Map<Integer, Long> banked = null;
        if (bank != null)
        {
            banked = new HashMap<>();
            for (Map.Entry<Integer, Long> e : bank.entrySet())
            {
                add(banked, e.getKey(), e.getValue());
            }
        }
        return new ItemSnapshot(Collections.unmodifiableMap(all), Collections.unmodifiableSet(new HashSet<>(unlimited)),
            banked == null ? null : Collections.unmodifiableMap(banked));
    }

    /** Like {@link #of}, with the bank of {@code banked} as it is (copied once there, not again for each snapshot). */
    public static ItemSnapshot withBankOf(Map<Integer, Long> carried, Set<Integer> unlimited, ItemSnapshot banked)
    {
        ItemSnapshot own = of(carried, unlimited, null);
        return new ItemSnapshot(own.carried, own.unlimited, banked == null ? null : banked.bank);
    }

    private static void add(Map<Integer, Long> into, int id, long quantity)
    {
        into.merge(id, quantity, Long::sum);
        int[] parts = COMBINATIONS.get(id);
        if (parts != null)
        {
            for (int part : parts)
            {
                into.merge(part, quantity, Long::sum);
            }
        }
    }

    /** The runes a worn weapon gives without limit, judged by its name (staves, battlestaves, tomes). */
    public static Set<Integer> runesFromWeapon(String name)
    {
        Set<Integer> runes = new HashSet<>();
        if (name == null)
        {
            return runes;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.contains("staff") && !n.contains("tome of") && !n.contains("wand"))
        {
            return runes;
        }
        if (n.contains("air") || n.contains("smoke") || n.contains("mist") || n.contains("dust"))
        {
            runes.add(AIR);
        }
        if (n.contains("water") || n.contains("mud") || n.contains("steam") || n.contains("mist") || n.contains("kodai"))
        {
            runes.add(WATER);
        }
        if (n.contains("earth") || n.contains("lava") || n.contains("mud") || n.contains("dust"))
        {
            runes.add(EARTH);
        }
        if (n.contains("fire") || n.contains("lava") || n.contains("steam") || n.contains("smoke"))
        {
            runes.add(FIRE);
        }
        return runes;
    }

    /**
     * Whether a requirement column such as {@code "AIR_RUNE=3&&LAW_RUNE=1"} or {@code "1706=1||1708=1"} is met.
     * Carried items always count; with {@code bankToo} also banked ones, and anything while the bank has not been
     * seen. Names this class does not know (tools, keys) count as met, so only a certainly missing item rules
     * something out.
     */
    public boolean has(String column, boolean bankToo)
    {
        if (this == EVERYTHING)
        {
            return true;
        }
        if (column == null || column.trim().isEmpty())
        {
            return true;
        }
        for (String all : column.split("&&"))
        {
            boolean any = false;
            boolean judged = false;
            for (String alternative : all.split("\\|\\|"))
            {
                String[] parts = alternative.trim().split("=");
                String token = parts[0].trim();
                if (token.isEmpty())
                {
                    continue;
                }
                long quantity = 1;
                if (parts.length > 1)
                {
                    try
                    {
                        quantity = Long.parseLong(parts[1].trim());
                    }
                    catch (NumberFormatException e)
                    {
                        quantity = 1;
                    }
                }
                // An id too long to be one (a broken line) is not known either.
                Integer id = !token.chars().allMatch(Character::isDigit) ? NAMES.get(token)
                    : token.length() <= 9 ? Integer.valueOf(token) : null;
                if (id == null)
                {
                    // Unknown to us: cannot be judged.
                    any = true;
                    continue;
                }
                judged = true;
                if (unlimited.contains(id) || carried.getOrDefault(id, 0L) >= quantity
                    || bankToo && (bank == null || bank.getOrDefault(id, 0L) + carried.getOrDefault(id, 0L) >= quantity))
                {
                    any = true;
                }
            }
            if (judged && !any)
            {
                return false;
            }
        }
        return true;
    }
}
