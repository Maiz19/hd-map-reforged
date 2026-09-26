package com.hdmapreforged;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts "Your house" settings to and from the house scan's words ("box:ornate", "glory", "portal:Varrock"). */
final class HouseSettings
{
    private static final String PORTAL = "portal:";

    private HouseSettings()
    {
    }

    static Set<String> features(HdMapReforgedConfig.JewelleryBox box, boolean glory, boolean fairyRing,
        boolean spiritTree, String portals)
    {
        Set<String> features = new LinkedHashSet<>();
        if (box != null && box != HdMapReforgedConfig.JewelleryBox.NONE)
        {
            features.add("box:" + box.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (glory)
        {
            features.add("glory");
        }
        if (fairyRing || spiritTree)
        {
            features.add(!fairyRing ? "spirit tree" : spiritTree ? "spirit tree+fairy ring" : "fairy ring");
        }
        for (String portal : portals == null ? new String[0] : portals.split("[,;\\n]"))
        {
            String place = portal.trim();
            if (!place.isEmpty())
            {
                features.add(PORTAL + place);
            }
        }
        return features;
    }

    static HdMapReforgedConfig.JewelleryBox box(Set<String> features)
    {
        return features.contains("box:ornate") ? HdMapReforgedConfig.JewelleryBox.ORNATE
            : features.contains("box:fancy") ? HdMapReforgedConfig.JewelleryBox.FANCY
            : features.contains("box:basic") ? HdMapReforgedConfig.JewelleryBox.BASIC : HdMapReforgedConfig.JewelleryBox.NONE;
    }

    static boolean fairyRing(Set<String> features)
    {
        return features.contains("fairy ring") || features.contains("spirit tree+fairy ring");
    }

    static boolean spiritTree(Set<String> features)
    {
        return features.contains("spirit tree") || features.contains("spirit tree+fairy ring");
    }

    /** As the setting shows them: "Varrock, Falador". */
    static String portals(Set<String> features)
    {
        return features.stream().filter(f -> f.startsWith(PORTAL)).map(f -> f.substring(PORTAL.length()))
            .collect(Collectors.joining(", "));
    }
}
