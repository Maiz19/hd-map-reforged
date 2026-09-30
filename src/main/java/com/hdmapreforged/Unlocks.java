package com.hdmapreforged;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.runelite.api.*;

/**
 * The player's levels, finished quests and varbits, taken on the client thread, read from any. What it cannot judge
 * counts as met, so icons are only hidden when a requirement is certainly missing.
 */
final class Unlocks
{
    private static final Pattern VAR = Pattern.compile("(\\d+)([=<>&@])(\\d+)");

    private final Map<String, Integer> levels = new HashMap<>();
    private final Map<String, Boolean> quests = new HashMap<>();
    private final Map<Integer, Integer> varbits = new HashMap<>();
    /** By identity ({@link Needs} has no equals). */
    private final Map<Needs, Boolean> usable = new ConcurrentHashMap<>();
    static final int CACHED = 20_000;

    private Unlocks()
    {
    }

    /** Client thread only. Quests run a client script each, so unless {@code checkQuests} they come from {@code previous}. */
    static Unlocks capture(Client client, Collection<Needs> needs, Unlocks previous, boolean checkQuests)
    {
        Unlocks unlocks = new Unlocks();
        for (Skill skill : Skill.values())
        {
            if (!"Overall".equals(skill.getName()))
            {
                unlocks.levels.put(skill.getName().toLowerCase(Locale.ROOT), client.getRealSkillLevel(skill));
            }
        }
        unlocks.levels.put("total", client.getTotalLevel());
        Player player = client.getLocalPlayer();
        if (player != null)
        {
            unlocks.levels.put("combat", player.getCombatLevel());
        }
        Set<String> questNames = new HashSet<>();
        Set<Integer> varbitIds = new HashSet<>();
        for (Needs need : needs)
        {
            for (String quest : need.quests.split(";"))
            {
                if (!quest.trim().isEmpty())
                {
                    questNames.add(quest.trim().toLowerCase(Locale.ROOT));
                }
            }
            ids(need.varbits, varbitIds);
        }
        if (checkQuests || previous == null)
        {
            for (Quest quest : Quest.values())
            {
                String name = quest.getName().toLowerCase(Locale.ROOT);
                if (questNames.contains(name))
                {
                    unlocks.quests.put(name, quest.getState(client) == QuestState.FINISHED);
                }
            }
        }
        else
        {
            unlocks.quests.putAll(previous.quests);
        }
        for (int id : varbitIds)
        {
            try
            {
                unlocks.varbits.put(id, client.getVarbitValue(id));
            }
            catch (RuntimeException e)
            {
                // Gone after a game update: unknown, which counts as met.
            }
        }
        return unlocks;
    }

    static Unlocks of(Map<String, Integer> levels, Map<String, Boolean> quests, Map<Integer, Integer> varbits)
    {
        Unlocks unlocks = new Unlocks();
        levels.forEach((skill, level) -> unlocks.levels.put(skill.toLowerCase(Locale.ROOT), level));
        quests.forEach((quest, done) -> unlocks.quests.put(quest.toLowerCase(Locale.ROOT), done));
        unlocks.varbits.putAll(varbits);
        return unlocks;
    }

    private static void ids(String column, Set<Integer> into)
    {
        Matcher matcher = VAR.matcher(column);
        while (matcher.find())
        {
            int id = number(matcher.group(1));
            if (id >= 0)
            {
                into.add(id);
            }
        }
    }

    /** -1 when too long to be one (a broken line). */
    private static int number(String digits)
    {
        return digits.length() <= 9 ? Integer.parseInt(digits) : -1;
    }

    boolean sameAs(Unlocks other)
    {
        return other != null && levels.equals(other.levels) && quests.equals(other.quests) && varbits.equals(other.varbits);
    }

    /** True, false, or null when this line cannot be checked. */
    Boolean met(Requirements.Line line)
    {
        if (line.skill != null)
        {
            Integer level = levels.get(line.skill.toLowerCase(Locale.ROOT));
            return level == null ? null : level >= line.level;
        }
        if (line.quest != null)
        {
            return quests.get(line.quest.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    /** False only when a requirement is certainly not met. */
    boolean usable(Needs needs)
    {
        if (needs.isEmpty())
        {
            return true;
        }
        Boolean known = usable.get(needs);
        if (known != null)
        {
            return known;
        }
        boolean result = check(needs);
        if (usable.size() < CACHED)
        {
            usable.put(needs, result);
        }
        return result;
    }

    private boolean check(Needs needs)
    {
        for (Requirements.Line line : Requirements.describe(new Needs(needs.skills, "", needs.quests, ""), id -> null))
        {
            if (Boolean.FALSE.equals(met(line)))
            {
                return false;
            }
        }
        return vars(needs.varbits, varbits);
    }

    /** Conditions like {@code "4070=0;6069<3"}; cooldowns ({@code @}) do not lock anything away. */
    private static boolean vars(String column, Map<Integer, Integer> values)
    {
        Matcher matcher = VAR.matcher(column);
        while (matcher.find())
        {
            int id = number(matcher.group(1));
            int wanted = number(matcher.group(3));
            Integer value = id < 0 ? null : values.get(id);
            if (value == null || wanted < 0)
            {
                continue;
            }
            String op = matcher.group(2);
            boolean ok = op.equals("=") ? value == wanted : op.equals(">") ? value > wanted
                : op.equals("<") ? value < wanted : !op.equals("&") || (value & wanted) != 0;
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }
}
