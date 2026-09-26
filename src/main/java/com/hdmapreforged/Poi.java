package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** An icon on the map. */
final class Poi
{
    static final class Link
    {
        final String label;
        final WorldPoint point;
        /** Null when the wiki has no map for it. */
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
    final BaseMap map;
    /** Highlighted together (such as all Ardougne cloak teleports); may be null. */
    final String group;
    final Needs needs;
    final String wikiQuery;
    /** Where an entrance or passage leads, or null. */
    final BaseMap target;
    final String note;
    private final List<Link> links = new ArrayList<>();
    /** Teleports stacked on this icon; empty for a single one. */
    private final List<Poi> members = new ArrayList<>();
    /** Other things at the same place this icon stands for. */
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

    List<Poi> members()
    {
        return members.isEmpty() ? Collections.singletonList(this) : Collections.unmodifiableList(members);
    }

    void addNearby(Poi poi)
    {
        nearby.add(poi);
    }

    List<Poi> nearby()
    {
        return Collections.unmodifiableList(nearby);
    }

    /** Icons with the things they stand for. */
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

    boolean hasGroup(String group)
    {
        return memberOf(group) != null;
    }

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
