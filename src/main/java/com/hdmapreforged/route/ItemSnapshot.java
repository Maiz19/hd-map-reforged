package com.hdmapreforged.route;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Items carried, runes a worn staff gives without limit, and the bank as last seen (null if unseen). Immutable. */
public final class ItemSnapshot
{
    private static final Map<String, Integer> NAMES = new HashMap<>();
    private static final Map<Integer, int[]> COMBINATIONS = new HashMap<>();
    public static final int AIR = 556;
    public static final int WATER = 555;
    public static final int EARTH = 557;
    public static final int FIRE = 554;

    static
    {
        for (String name : ("AIR_RUNE=556 WATER_RUNE=555 EARTH_RUNE=557 FIRE_RUNE=554 MIND_RUNE=558 BODY_RUNE=559 DEATH_RUNE=560"
            + " NATURE_RUNE=561 CHAOS_RUNE=562 LAW_RUNE=563 COSMIC_RUNE=564 BLOOD_RUNE=565 SOUL_RUNE=566 ASTRAL_RUNE=9075"
            + " WRATH_RUNE=21880 COINS=995 BANANA=1963").split(" "))
        {
            NAMES.put(name.split("=")[0], Integer.valueOf(name.split("=")[1]));
        }
        COMBINATIONS.put(4694, new int[]{WATER, FIRE});
        COMBINATIONS.put(4695, new int[]{AIR, WATER});
        COMBINATIONS.put(4696, new int[]{AIR, EARTH});
        COMBINATIONS.put(4697, new int[]{AIR, FIRE});
        COMBINATIONS.put(4698, new int[]{WATER, EARTH});
        COMBINATIONS.put(4699, new int[]{EARTH, FIRE});
    }

    public static final ItemSnapshot NONE = new ItemSnapshot(Collections.emptyMap(), Collections.emptySet(), null);
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

    public static ItemSnapshot of(Map<Integer, Long> carried, Set<Integer> unlimited, Map<Integer, Long> bank)
    {
        Map<Integer, Long> all = new HashMap<>();
        carried.forEach((id, n) -> add(all, id, n));
        Map<Integer, Long> banked = bank == null ? null : new HashMap<>();
        if (bank != null)
        {
            bank.forEach((id, n) -> add(banked, id, n));
        }
        return new ItemSnapshot(Collections.unmodifiableMap(all), Collections.unmodifiableSet(new HashSet<>(unlimited)),
            banked == null ? null : Collections.unmodifiableMap(banked));
    }

    /** Like {@link #of}, sharing {@code banked}'s bank rather than copying it again. */
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

    public static Set<Integer> runesFromWeapon(String name)
    {
        Set<Integer> runes = new HashSet<>();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!n.contains("staff") && !n.contains("tome of") && !n.contains("wand"))
        {
            return runes;
        }
        // The words that give each rune.
        String[] words = {"air smoke mist dust", "water mud steam mist kodai", "earth lava mud dust", "fire lava steam smoke"};
        int[] ids = {AIR, WATER, EARTH, FIRE};
        for (int i = 0; i < ids.length; i++)
        {
            if (Arrays.stream(words[i].split(" ")).anyMatch(n::contains))
            {
                runes.add(ids[i]);
            }
        }
        return runes;
    }

    /**
     * Whether a column like {@code "AIR_RUNE=3&&LAW_RUNE=1"} is met; with {@code bankToo} banked items count too, and
     * anything while the bank is unseen. Unknown names count as met: only a certainly missing item rules out.
     */
    public boolean has(String column, boolean bankToo)
    {
        if (this == EVERYTHING || column == null || column.trim().isEmpty())
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
                        // One then.
                    }
                }
                Integer id = !token.chars().allMatch(Character::isDigit) ? NAMES.get(token)
                    : token.length() <= 9 ? Integer.valueOf(token) : null;
                if (id == null)
                {
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
