package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** An icon on the map. */
final class Poi
{
    /** Where an icon leads, and what that particular trip needs. */
    static final class Link
    {
        final String label;
        final WorldPoint point;
        /** The map containing {@link #point}, or null when the wiki has no map for it. */
        final BaseMap map;
        final Needs needs;

        Link(String label, WorldPoint point, BaseMap map, Needs needs)
        {
            this.label = label;
            this.point = point;
            this.map = map;
            this.needs = needs;
        }
    }

    final PoiType type;
    final String name;
    final WorldPoint location;
    /** The map containing {@link #location}, or null. */
    final BaseMap map;
    /** Icons sharing a group (such as all Ardougne cloak teleports) are highlighted together; may be null. */
    final String group;
    final Needs needs;
    final String wikiQuery;
    /** The map an entrance or passage leads to, or null. */
    final BaseMap target;
    /** An extra line for the detail card, or null. */
    final String note;
    private final List<Link> links = new ArrayList<>();
    /** Teleports landing on the same spot, shown as this one icon; empty for a single teleport. */
    private final List<Poi> members = new ArrayList<>();
    /** Other things at the same place that this icon stands for (a teleport landing at a portal, say). */
    private final List<Poi> nearby = new ArrayList<>();

    Poi(PoiType type, String name, WorldPoint location, BaseMap map, String group, Needs needs, String wikiQuery,
        BaseMap target, String note)
    {
        this.type = type;
        this.name = name;
        this.location = location;
        this.map = map;
        this.group = group;
        this.needs = needs;
        this.wikiQuery = wikiQuery;
        this.target = target;
        this.note = note;
    }

    void addLink(Link link)
    {
        links.add(link);
    }

    List<Link> links()
    {
        return Collections.unmodifiableList(links);
    }

    void addMember(Poi member)
    {
        members.add(member);
    }

    /** The teleports this icon stands for: those stacked on it, or just itself. */
    List<Poi> members()
    {
        return members.isEmpty() ? Collections.singletonList(this) : Collections.unmodifiableList(members);
    }

    void addNearby(Poi poi)
    {
        nearby.add(poi);
    }

    /** Other things this icon stands for, at the same place. */
    List<Poi> nearby()
    {
        return Collections.unmodifiableList(nearby);
    }

    /** Icons with the things they stand for at the same place, for code that needs every teleport and transport. */
    static List<Poi> flatten(List<Poi> pois)
    {
        List<Poi> all = new ArrayList<>(pois.size() + 16);
        for (Poi poi : pois)
        {
            all.add(poi);
            all.addAll(poi.nearby);
        }
        return all;
    }

    /** Whether this icon, or a teleport stacked on it, belongs to a group. */
    boolean hasGroup(String group)
    {
        return memberOf(group) != null;
    }

    /** The teleport of a group this icon stands for, or null. */
    Poi memberOf(String group)
    {
        if (group == null)
        {
            return null;
        }
        for (Poi member : members())
        {
            if (group.equals(member.group))
            {
                return member;
            }
        }
        for (Poi other : nearby)
        {
            Poi member = other.memberOf(group);
            if (member != null)
            {
                return member;
            }
        }
        return null;
    }

    boolean isOn(BaseMap view)
    {
        return view != null && map != null && (view.id == BaseMap.FULL || map == view);
    }

    @Override
    public String toString()
    {
        return name;
    }
}
