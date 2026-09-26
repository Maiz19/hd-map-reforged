package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** Where a monster or NPC is, from the {@code {{LocLine}}} templates on its wiki page. */
final class NpcSpawns
{
    static final class Group
    {
        final String name;
        final String location;
        final String levels;
        final boolean members;
        final int mapId;
        final List<WorldPoint> points;
        final String category;
        final String note;
        final java.awt.Color color;
        Poi poi;

        Group(String name, String location, String levels, boolean members, int mapId, List<WorldPoint> points)
        {
            this(name, location, levels, members, mapId, points, null, null, null);
        }

        Group(String name, String location, String levels, boolean members, int mapId, List<WorldPoint> points,
            String category, String note, java.awt.Color color)
        {
            this.name = name;
            this.location = location;
            this.levels = levels;
            this.members = members;
            this.mapId = mapId;
            this.points = points;
            this.category = category;
            this.note = note;
            this.color = color;
        }

        Group as(String category, String note, java.awt.Color color)
        {
            return new Group(name, location, levels, members, mapId, points, category, note, color);
        }

        WorldPoint center()
        {
            double x = 0;
            double y = 0;
            for (WorldPoint p : points)
            {
                x += p.getX();
                y += p.getY();
            }
            x /= points.size();
            y /= points.size();
            WorldPoint best = points.get(0);
            double bestDistance = Double.MAX_VALUE;
            for (WorldPoint p : points)
            {
                double d = (p.getX() - x) * (p.getX() - x) + (p.getY() - y) * (p.getY() - y);
                if (d < bestDistance)
                {
                    best = p;
                    bestDistance = d;
                }
            }
            return best;
        }
    }

    static final Pattern LOC_LINE = Pattern.compile("\\{\\{\\s*LocLine\\s*\\|", Pattern.CASE_INSENSITIVE);
    static final Pattern ITEM_SPAWN_LINE = Pattern.compile("\\{\\{\\s*ItemSpawnLine\\s*\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^{}]*}}");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern BOLD = Pattern.compile("'''?");
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");
    private static final Pattern COLON = Pattern.compile("\\s*:\\s*");
    private static final Pattern NUMBERED = Pattern.compile(".*\\d{3,}.*|\\d+ \\w+");
    private static final int MAX_POINTS = 5000;

    final String page;
    final List<Group> groups;
    /** Linked pages, most mentioned first: where to look when no spawn is visible (Zulrah's "Zul-Andra"). */
    final List<String> mentioned;

    NpcSpawns(String page, List<Group> groups)
    {
        this(page, groups, Collections.emptyList());
    }

    NpcSpawns(String page, List<Group> groups, List<String> mentioned)
    {
        this.page = page;
        this.groups = Collections.unmodifiableList(groups);
        this.mentioned = Collections.unmodifiableList(mentioned);
    }

    int spawnCount()
    {
        int n = 0;
        for (Group group : groups)
        {
            n += group.points.size();
        }
        return n;
    }

    static NpcSpawns parse(String page, String wikitext)
    {
        return parse(page, wikitext, LOC_LINE);
    }

    static NpcSpawns parse(String page, String wikitext, Pattern template)
    {
        List<Group> groups = new ArrayList<>();
        int total = 0;
        Matcher start = template.matcher(wikitext);
        while (start.find() && total < MAX_POINTS)
        {
            int end = templateEnd(wikitext, start.start());
            if (end < 0)
            {
                break;
            }
            Group group = group(page, wikitext.substring(start.end(), end - 2));
            if (group != null)
            {
                groups.add(group);
                total += group.points.size();
            }
        }
        return new NpcSpawns(page, groups);
    }

    private static int templateEnd(String text, int from)
    {
        int depth = 0;
        for (int i = from; i < text.length() - 1; i++)
        {
            if (text.startsWith("{{", i))
            {
                depth++;
                i++;
            }
            else if (text.startsWith("}}", i))
            {
                depth--;
                i++;
                if (depth == 0)
                {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static Group group(String page, String body)
    {
        String name = page;
        String location = "";
        String levels = "";
        boolean members = false;
        int mapId = -1;
        int plane = 0;
        List<String> anonymous = new ArrayList<>();
        // Links first: their "|" would split a field.
        String text = TEMPLATE.matcher(LINK.matcher(body).replaceAll("$1")).replaceAll("");
        for (String part : text.split("\\|"))
        {
            int eq = part.indexOf('=');
            if (eq < 0)
            {
                anonymous.add(part);
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            switch (key)
            {
                case "name":
                    name = clean(value);
                    break;
                case "location":
                    location = clean(value);
                    break;
                case "levels":
                    levels = clean(value);
                    break;
                case "members":
                    members = value.equalsIgnoreCase("yes");
                    break;
                case "mapid":
                    mapId = number(value, -1);
                    break;
                case "plane":
                    plane = number(value, 0);
                    break;
                default:
                    break;
            }
        }
        List<WorldPoint> points = new ArrayList<>();
        for (String part : anonymous)
        {
            points(part, plane, points);
        }
        if (points.isEmpty())
        {
            return null;
        }
        // Where the wiki's map draws them; the game has some elsewhere (the Kalphite Lair).
        return new Group(name.isEmpty() ? page : name, location, levels, members, mapId,
            WorldMapMoves.toWorld(mapId, points));
    }

    /** The points of an unnamed map argument, as the wiki's map module reads them ("3200,3200", "x:3200", "plane:1"). */
    static void points(String arg, int plane, List<WorldPoint> out)
    {
        List<int[]> xy = new ArrayList<>();
        int[] open = null;
        int z = plane;
        for (String option : COMMA.split(arg.trim()))
        {
            if (option.isEmpty())
            {
                continue;
            }
            String[] kv = COLON.split(option, 2);
            Integer value = null;
            if (kv.length == 1 || kv[0].equalsIgnoreCase("x") || kv[0].equalsIgnoreCase("y"))
            {
                value = coordinate(kv[kv.length - 1]);
                if (value == null)
                {
                    // A word, not coordinates at all.
                    return;
                }
            }
            else if (coordinate(kv[0]) != null && coordinate(kv[1]) != null)
            {
                xy.add(new int[]{coordinate(kv[0]), coordinate(kv[1])});
                open = null;
                continue;
            }
            else if (kv[0].equalsIgnoreCase("plane"))
            {
                z = number(kv[1], plane);
                continue;
            }
            else
            {
                continue;
            }
            if (open == null)
            {
                open = new int[]{value, -1};
            }
            else
            {
                open[1] = value;
                xy.add(open);
                open = null;
            }
        }
        z = Math.max(0, Math.min(3, z));
        for (int[] p : xy)
        {
            if (out.size() < MAX_POINTS)
            {
                out.add(new WorldPoint(p[0], p[1], z));
            }
        }
    }

    private static Integer coordinate(String text)
    {
        try
        {
            double v = Double.parseDouble(text.trim());
            return v >= 0 && v < 20000 ? (int) Math.floor(v) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static final Pattern MAP = Pattern.compile("\\{\\{\\s*Map\\s*\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[\\s*([^\\]|#]+?)\\s*(?:[|#][^\\]]*)?]]");
    private static final Pattern TAIL = Pattern.compile("\\n==\\s*(Drops|Combat Achievements|Changes|Update history|"
        + "Trivia|Gallery|References)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_VARIANT = Pattern.compile("(?i)[/:]|money making|\\b(adept|master|veteran|champion|"
        + "elite|grandmaster|novice|speed-|perfect|strategies|task)\\b");

    static List<Group> maps(String page, String wikitext)
    {
        List<Group> groups = new ArrayList<>();
        Matcher start = MAP.matcher(wikitext);
        while (start.find() && groups.size() < 20)
        {
            int end = templateEnd(wikitext, start.start());
            if (end < 0)
            {
                break;
            }
            String body = TEMPLATE.matcher(LINK.matcher(wikitext.substring(start.end(), end - 2)).replaceAll("$1"))
                .replaceAll("");
            String name = "";
            int plane = 0;
            int mapId = -1;
            Integer x = null;
            Integer y = null;
            List<WorldPoint> points = new ArrayList<>();
            List<String> anonymous = new ArrayList<>();
            for (String part : body.split("\\|"))
            {
                int eq = part.indexOf('=');
                if (eq < 0)
                {
                    anonymous.add(part);
                    continue;
                }
                String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                switch (key)
                {
                    case "name":
                        name = clean(value);
                        break;
                    case "x":
                        x = number(value, -1) >= 0 ? number(value, -1) : null;
                        break;
                    case "y":
                        y = number(value, -1) >= 0 ? number(value, -1) : null;
                        break;
                    case "plane":
                        plane = number(value, 0);
                        break;
                    case "mapid":
                        mapId = number(value, -1);
                        break;
                    default:
                        break;
                }
            }
            for (String part : anonymous)
            {
                // Only plain points: lines and text labels ("mtype:line…") are no place.
                if (!part.contains("mtype") && !part.contains("label"))
                {
                    points(part, plane, points);
                }
            }
            if (points.isEmpty() && x != null && y != null)
            {
                points.add(new WorldPoint(x, y, Math.max(0, Math.min(3, plane))));
            }
            if (!points.isEmpty())
            {
                groups.add(new Group(page, name.isEmpty() ? page : name, "", false, mapId,
                    WorldMapMoves.toWorld(mapId, points)));
            }
        }
        return groups;
    }

    static List<String> mentioned(String page, String wikitext)
    {
        Matcher tail = TAIL.matcher(wikitext);
        String body = tail.find() ? wikitext.substring(0, tail.start()) : wikitext;
        String lower = body.toLowerCase(Locale.ROOT);
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        Matcher link = WIKI_LINK.matcher(body);
        while (link.find())
        {
            String target = link.group(1).trim().replace('_', ' ');
            if (target.isEmpty() || target.contains(":") || NUMBERED.matcher(target).matches()
                || target.equalsIgnoreCase(page))
            {
                continue;
            }
            target = Character.toUpperCase(target.charAt(0)) + target.substring(1);
            if (!counts.containsKey(target))
            {
                counts.put(target, occurrences(lower, target.toLowerCase(Locale.ROOT)));
            }
        }
        List<String> ranked = new ArrayList<>(counts.keySet());
        // Stable: equal counts keep the order they are first linked in.
        ranked.sort((a, b) -> counts.get(b) - counts.get(a));
        return ranked;
    }

    private static int occurrences(String text, String word)
    {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length()))
        {
            n++;
        }
        return n;
    }

    static List<String> variants(String page, List<String> links)
    {
        String name = page.toLowerCase(Locale.ROOT);
        // "Custodian stalkers" also names "custodian stalker" pages.
        String stem = name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        List<String> out = new ArrayList<>();
        for (String link : links)
        {
            String l = link.toLowerCase(Locale.ROOT);
            if (!l.equals(name) && l.contains(stem) && !NOT_VARIANT.matcher(link).find() && !out.contains(link))
            {
                out.add(link);
            }
        }
        return out;
    }

    static String clean(String value)
    {
        String text = LINK.matcher(value).replaceAll("$1");
        text = BOLD.matcher(TAG.matcher(TEMPLATE.matcher(text).replaceAll("")).replaceAll("")).replaceAll("");
        text = EMPTY_BRACKETS.matcher(text).replaceAll("");
        return SPACES.matcher(text).replaceAll(" ").trim();
    }

    private static int number(String value, int fallback)
    {
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }
}
