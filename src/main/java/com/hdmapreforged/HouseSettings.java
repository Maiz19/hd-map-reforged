package com.hdmapreforged;

import java.util.*;
import java.util.stream.*;

/** The house panel's choices to and from the house scan's words ("box:ornate", "glory", "portal:Varrock"). */
final class HouseSettings
{
    static final String PORTAL = "portal:";
    static final String NEXUS = "nexus:";
    /** The jewellery box tiers, as in "box:ornate". */
    static final String[] BOXES = {"none", "basic", "fancy", "ornate"};
    /** The superior garden's one centrepiece of these: none, fairy ring, spirit tree, spiritual fairy tree. */
    static final String[] GARDENS = {"", "fairy ring", "spirit tree", "spirit tree+fairy ring"};

    private HouseSettings()
    {
    }

    /** {@code box}: a tier (any case), else none. */
    static Set<String> features(String box, boolean glory, int garden, String portals, String nexus)
    {
        Set<String> features = new LinkedHashSet<>();
        String tier = box == null ? "none" : box.toLowerCase(Locale.ROOT);
        if (!tier.equals("none") && Set.of(BOXES).contains(tier))
        {
            features.add("box:" + tier);
        }
        if (glory)
        {
            features.add("glory");
        }
        if (garden > 0 && garden < GARDENS.length)
        {
            features.add(GARDENS[garden]);
        }
        add(features, PORTAL, portals);
        add(features, NEXUS, nexus);
        return features;
    }

    static String box(Set<String> features)
    {
        for (int i = BOXES.length - 1; i > 0; i--)
        {
            if (features.contains("box:" + BOXES[i]))
            {
                return BOXES[i];
            }
        }
        return BOXES[0];
    }

    private static void add(Set<String> features, String prefix, String places)
    {
        for (String place : places == null ? new String[0] : places.split("[,;\\n]"))
        {
            if (!place.trim().isEmpty())
            {
                features.add(prefix + place.trim());
            }
        }
    }

    /** Which of {@link #GARDENS}. */
    static int garden(Set<String> features)
    {
        for (int i = GARDENS.length - 1; i > 0; i--)
        {
            if (features.contains(GARDENS[i]))
            {
                return i;
            }
        }
        return 0;
    }

    /** As the panel shows them: "Varrock, Falador"; {@code prefix} {@link #PORTAL} or {@link #NEXUS}. */
    static String places(Set<String> features, String prefix)
    {
        return features.stream().filter(f -> f.startsWith(prefix)).map(f -> f.substring(prefix.length()))
            .collect(Collectors.joining(", "));
    }
}
