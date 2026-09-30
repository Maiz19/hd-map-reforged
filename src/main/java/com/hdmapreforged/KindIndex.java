package com.hdmapreforged;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/** Every place of one kind (herb patches, shark fishing spots, banks) from our icons, game icons and skill spots. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class KindIndex
{
    @RequiredArgsConstructor
    static final class Entry
    {
        final String name;
        final WorldPoint point;
        final String detail;
        /** Null for a listed skilling spot. */
        final Poi poi;
    }

    @RequiredArgsConstructor
    static final class Kind
    {
        final String label;
        final PoiType type;
        final String page;
        final List<Entry> entries = new ArrayList<>();
    }

    /** Every type but these; a new type is counted unless added here. */
    private static final Set<PoiType> COUNTED = EnumSet.complementOf(EnumSet.of(PoiType.TELEPORT, PoiType.BOAT,
        PoiType.PORTAL, PoiType.LEVER, PoiType.TRANSPORT, PoiType.MAP_EXIT, PoiType.SHOP, PoiType.MAP_LINK,
        PoiType.GAME_ICON, PoiType.FOUND));
    /** "3 × Copper (1)", "Willow tree (30 Woodcutting)", "Shark (76)". */
    private static final Pattern SKILL_NOTE = Pattern.compile("^(Fishing|Mining|Hunter|Woodcutting): .*");
    private static final Pattern TRAILING_BRACKETS = Pattern.compile("\\s*\\([^()]*\\)$");
    private static final Pattern SLASH = Pattern.compile("\\s*/\\s*");
    private static final Pattern PLURAL_S = Pattern.compile("s$");
    private static final Pattern NOT_KEY = Pattern.compile("[^a-z0-9]");
    private static final Pattern RESOURCE = Pattern.compile("^(?:\\d+\\s*×\\s*)?(.+?)\\s*(?:\\((\\d+)[^)]*\\))?$");
    static final int MIN_ENTRIES = 2;
    private static final int SAME_PLACE = 1;

    private final List<Kind> kinds;


    List<Kind> all()
    {
        return Collections.unmodifiableList(kinds);
    }

    static KindIndex build(List<Poi> pois, List<Poi> gameIcons, List<SkillSpots.Spot> spots)
    {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Poi poi : Poi.flatten(pois))
        {
            addIcon(kinds, poi);
        }
        for (Poi poi : gameIcons)
        {
            addIcon(kinds, poi);
        }
        for (SkillSpots.Spot spot : spots)
        {
            addSpot(kinds, spot);
        }
        return new KindIndex(kinds.values().stream().filter(kind -> kind.entries.size() >= MIN_ENTRIES)
            .collect(Collectors.toList()));
    }

    private static void addIcon(Map<String, Kind> kinds, Poi poi)
    {
        if (poi.location == null)
        {
            return;
        }
        Entry entry = new Entry(poi.name, poi.location, poi.note, poi);
        if (COUNTED.contains(poi.type))
        {
            add(kinds, poi.type == PoiType.SALVAGE ? "Salvaging spots" : plural(poi.type.displayName), poi.type,
                poi.type.wikiPage, entry);
        }
        if (poi.type == PoiType.FARMING_PATCH)
        {
            int dash = poi.name.indexOf(" – ");
            String grows = dash < 0 ? poi.name : poi.name.substring(0, dash);
            for (String part : grows.split("/"))
            {
                String what = sentence(part.trim());
                if (!what.isEmpty())
                {
                    add(kinds, what + " patches", poi.type, what + " patch", entry);
                }
            }
        }
        else if (poi.type == PoiType.SHOP || poi.type == PoiType.GAME_ICON)
        {
            if (poi.note != null && SKILL_NOTE.matcher(poi.note).matches())
            {
                // Skilling spots come from the list.
                return;
            }
            String base = TRAILING_BRACKETS.matcher(poi.name).replaceAll("").trim();
            if (base.isEmpty() || base.equals("Dungeon") || base.equals("Map link") || base.startsWith("To "))
            {
                return;
            }
            String spotKind = SkillSpots.kindOf(base);
            if (spotKind != null)
            {
                add(kinds, spotLabel(spotKind), null, spotPage(spotKind), entry);
                return;
            }
            add(kinds, plural(base), poi.type, poi.wikiQuery != null && !poi.wikiQuery.contains("(") ? base : null, entry);
        }
    }

    private static void addSpot(Map<String, Kind> kinds, SkillSpots.Spot spot)
    {
        String details = spot.details.replace("; ", ", ");
        Entry entry = new Entry(spot.name, spot.location, details, null);
        add(kinds, spotLabel(spot.kind), null, spotPage(spot.kind), entry);
        for (String part : spot.details.split(";"))
        {
            Matcher m = RESOURCE.matcher(part.trim());
            if (!m.matches() || m.group(1).isEmpty())
            {
                continue;
            }
            for (String what : split(m.group(1)))
            {
                addResource(kinds, spot.kind, what, entry);
            }
        }
    }

    /** "Maple/yew trees" gives "Maple trees" and "Yew trees". */
    static List<String> split(String names)
    {
        String[] parts = SLASH.split(names);
        List<String> out = new ArrayList<>();
        String last = parts[parts.length - 1];
        int space = last.indexOf(' ');
        String suffix = space > 0 ? last.substring(space) : "";
        for (String part : parts)
        {
            if (part.isEmpty())
            {
                continue;
            }
            out.add(sentence(part.contains(" ") ? part : part + suffix));
        }
        return out;
    }

    private static void addResource(Map<String, Kind> kinds, String spotKind, String what, Entry entry)
    {
        switch (spotKind)
        {
            case "fishing":
                add(kinds, what + " fishing spots", null, null, entry);
                break;
            case "mining":
                // "Gem rock" is already a rock.
                String rocks = what.toLowerCase(Locale.ROOT).endsWith("rock") ? plural(what) : what + " rocks";
                add(kinds, rocks, null, rocks, entry);
                break;
            case "hunter":
                add(kinds, what + " hunting areas", null, what, entry);
                break;
            default:
                add(kinds, plural(what), null, PLURAL_S.matcher(what).replaceAll(""), entry);
                break;
        }
    }

    private static String spotLabel(String kind)
    {
        return kind.equals("fishing") ? "Fishing spots" : kind.equals("mining") ? "Mining sites"
            : kind.equals("hunter") ? "Hunter areas" : "Rare trees";
    }

    private static String spotPage(String kind)
    {
        return kind.equals("fishing") ? "Fishing spots" : kind.equals("mining") ? "Mining"
            : kind.equals("hunter") ? "Hunter" : "Woodcutting";
    }

    private static void add(Map<String, Kind> kinds, String label, PoiType type, String page, Entry entry)
    {
        // "Agility short-cuts" and "Agility shortcuts" are one kind.
        String key = NOT_KEY.matcher(label.toLowerCase(Locale.ROOT)).replaceAll("");
        Kind kind = kinds.computeIfAbsent(key, k -> new Kind(label, type, page));
        if (kind.entries.stream().noneMatch(other -> PoiLoader.near(other.point, entry.point, SAME_PLACE)))
        {
            kind.entries.add(entry);
        }
    }

    /** Best match first, then more places first. */
    List<Kind> find(String query, int limit)
    {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Kind> found = new ArrayList<>();
        for (Kind kind : kinds)
        {
            if (q.length() >= 2 && score(kind.label.toLowerCase(Locale.ROOT), q) >= 0)
            {
                found.add(kind);
            }
        }
        found.sort(Comparator.comparingInt((Kind k) -> score(k.label.toLowerCase(Locale.ROOT), q))
            .thenComparing(k -> -k.entries.size()).thenComparing(k -> k.label, String::compareToIgnoreCase));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    /** 0: starts with the query; 1: a word does; 2: contains it; -1: no match. */
    static int score(String label, String query)
    {
        return label.startsWith(query) ? 0 : label.contains(" " + query) || label.contains("/" + query) ? 1
            : label.contains(query) ? 2 : -1;
    }

    /** "Farming patch" gives "Farming patches", "Agility shortcut (one way)" "Agility shortcuts (one way)". */
    static String plural(String name)
    {
        int bracket = name.indexOf(" (");
        if (bracket > 0 && name.endsWith(")"))
        {
            return plural(name.substring(0, bracket)) + name.substring(bracket);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("ss") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh") ? name + "es"
            : lower.endsWith("s") ? name
            : lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0
            ? name.substring(0, name.length() - 1) + "ies" : name + "s";
    }

    static String sentence(String name)
    {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}
