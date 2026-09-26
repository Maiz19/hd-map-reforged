package com.hdmapreforged;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "Your house" in the plugin's settings, and the words the house scan uses for the same ("box:ornate", "glory",
 * "fairy ring", "spirit tree", "spirit tree+fairy ring", "portal:Varrock"), both ways.
 */
final class HouseSettings
{
    private static final String PORTAL = "portal:";

    private HouseSettings()
    {
    }

    /** What the settings say the house has, in the scan's words. */
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
        if (fairyRing && spiritTree)
        {
            features.add("spirit tree+fairy ring");
        }
        else if (fairyRing)
        {
            features.add("fairy ring");
        }
        else if (spiritTree)
        {
            features.add("spirit tree");
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

    /** The portals' places, as the setting shows them: "Varrock, Falador". */
    static String portals(Set<String> features)
    {
        StringBuilder text = new StringBuilder();
        for (String feature : features)
        {
            if (feature.startsWith(PORTAL))
            {
                text.append(text.length() == 0 ? "" : ", ").append(feature.substring(PORTAL.length()));
            }
        }
        return text.toString();
    }
}
