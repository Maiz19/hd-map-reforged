package com.hdmapreforged;

/**
 * What using a teleport or transport needs, as the requirement columns of our tables write it (see
 * {@code plugin/AGENTS.md}): skills {@code "25 Magic;43 Agility"}, items {@code "563=1&&556=3"} with {@code ||} for
 * alternatives, quests {@code "Plague City;Biohazard"}, varbits {@code "4070=0"}. Kept raw; see {@link Requirements}
 * and {@link Unlocks}. Compared by identity: {@link Unlocks} keeps a result per instance, so make them once per row.
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
