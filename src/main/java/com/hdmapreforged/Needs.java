package com.hdmapreforged;

import lombok.RequiredArgsConstructor;

/**
 * A travel table row's requirement columns, kept raw (see {@code plugin/AGENTS.md}). Compared by identity:
 * {@link Unlocks} keeps a result per instance, so make them once per row.
 */

@RequiredArgsConstructor
final class Needs
{
    static final Needs NONE = new Needs("", "", "", "");

    final String skills;
    final String items;
    final String quests;
    final String varbits;

    static Needs of(Tsv.Row row)
    {
        return new Needs(row.get("Skills"), row.get("Items"), row.get("Quests"), row.get("Varbits"));
    }

    static Needs skill(int level, String skill)
    {
        return level > 1 ? new Needs(level + " " + skill, "", "", "") : NONE;
    }

    boolean isEmpty()
    {
        return skills.isEmpty() && items.isEmpty() && quests.isEmpty() && varbits.isEmpty();
    }
}
