package com.hdmapreforged;

/**
 * A travel table row's requirement columns, kept raw (see {@code plugin/AGENTS.md}). Compared by identity:
 * {@link Unlocks} keeps a result per instance, so make them once per row.
 */

final class Needs
{
    static final Needs NONE = new Needs("", "", "", "");

    final String skills;
    final String items;
    final String quests;
    final String varbits;

    Needs(String skills, String items, String quests, String varbits)
    {
        this.skills = skills;
        this.items = items;
        this.quests = quests;
        this.varbits = varbits;
    }

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
