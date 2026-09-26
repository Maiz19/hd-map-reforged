package com.hdmapreforged;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/** What the game's Hunter, Fishing, Mining and Rare trees icons stand for, from RuneLite's world map lists. */
@RequiredArgsConstructor
final class SkillSpots
{
    static final String FILE = "runelite_skill_spots.tsv";
    private static final int RADIUS = 12;

    @RequiredArgsConstructor
    static final class Spot
    {
        final WorldPoint location;
        /** hunter, fishing, mining or trees. */
        final String kind;
        final String name;
        /** The lowest level any of it needs, or 0. */
        final int level;
        final String details;
    }

    static final SkillSpots NONE = new SkillSpots(Collections.emptyList());

    private final List<Spot> spots;

    List<Spot> all()
    {
        return Collections.unmodifiableList(spots);
    }

    static SkillSpots load(PoiLoader.Source source) throws IOException
    {
        List<Spot> spots = new ArrayList<>();
        try (Reader reader = source.open(FILE))
        {
            for (Tsv.Row row : Tsv.parse(reader))
            {
                WorldPoint at = row.isComment() ? null : row.point("Location");
                if (at == null)
                {
                    continue;
                }
                int level = 0;
                try
                {
                    level = Integer.parseInt(row.get("Level"));
                }
                catch (NumberFormatException e)
                {
                    // None, or unreadable.
                }
                spots.add(new Spot(at, row.get("Kind"), row.get("Name"), level, row.get("Details")));
            }
        }
        return new SkillSpots(spots);
    }

    static String kindOf(String iconName)
    {
        switch (iconName.toLowerCase(Locale.ROOT))
        {
            case "hunter training":
                return "hunter";
            case "fishing spot":
                return "fishing";
            case "mining site":
                return "mining";
            case "rare trees":
                return "trees";
            default:
                return null;
        }
    }

    Spot near(String iconName, WorldPoint at)
    {
        String kind = kindOf(iconName);
        if (kind == null)
        {
            return null;
        }
        Spot best = null;
        int bestDistance = RADIUS + 1;
        for (Spot spot : spots)
        {
            int d = PoiLoader.chebyshev(spot.location, at);
            if (kind.equals(spot.kind) && spot.location.getPlane() == at.getPlane() && d < bestDistance)
            {
                best = spot;
                bestDistance = d;
            }
        }
        return best;
    }

    static String skill(String kind)
    {
        switch (kind)
        {
            case "hunter":
                return "Hunter";
            case "fishing":
                return "Fishing";
            case "mining":
                return "Mining";
            default:
                return "Woodcutting";
        }
    }

    static Poi poi(Spot spot, WorldPoint at, BaseMap map, String place)
    {
        String skill = skill(spot.kind);
        String name = place == null ? spot.name : spot.name + " (" + place + ")";
        String first = spot.details.split(";")[0].replaceAll("^\\d+ × ", "").replaceAll("\\s*\\(.*$", "").trim();
        String wiki = spot.kind.equals("mining") ? first + " rocks" : spot.kind.equals("fishing") ? "Fishing spots" : first;
        String note = skill + ": " + spot.details.replace("; ", ", ");
        return new Poi(PoiType.GAME_ICON, name, at, map, null, spot.level > 0 ? Needs.skill(spot.level, skill) : Needs.NONE,
            wiki, null, note);
    }
}
