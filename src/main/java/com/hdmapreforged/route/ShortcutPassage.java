package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A passage of the planner's (a cache transition, a map link or hand link passage) that is an Agility shortcut of the
 * wiki's (shortcuts.tsv): the same object, used at the shortcut. Such a passage knows nothing of the shortcut's level,
 * so the planner does not keep it as an always open passage; each route request adds it only when the shortcut's
 * requirements are met (RouteSource), and names them when a route ignores them.
 */
public final class ShortcutPassage
{
    /** How near the shortcut's start (or, for the way back, its end) the passage must be, in tiles. */
    static final int RADIUS = 3;

    public final Edge edge;
    /** The shortcut's name and requirements, as shortcuts.tsv gives them (Needs columns). */
    public final String name;
    public final String skills;
    public final String items;
    public final String quests;
    public final String varbits;

    ShortcutPassage(Edge edge, Shortcut shortcut)
    {
        this.edge = edge;
        this.name = shortcut.name;
        this.skills = shortcut.skills;
        this.items = shortcut.items;
        this.quests = shortcut.quests;
        this.varbits = shortcut.varbits;
    }

    /** One row of shortcuts.tsv. */
    static final class Shortcut
    {
        final String name;
        /** The object's name, lower case: "Rocks (Waterbirth Island)" is "rocks". */
        final String object;
        final int origin;
        final int destination;
        final String skills;
        final String items;
        final String quests;
        final String varbits;

        Shortcut(String name, int origin, int destination, String skills, String items, String quests, String varbits)
        {
            this.name = name;
            this.object = object(name);
            this.origin = origin;
            this.destination = destination;
            this.skills = skills;
            this.items = items;
            this.quests = quests;
            this.varbits = varbits;
        }

        /** Whether a passage is this shortcut's object used at it: its name names the object, and it starts or ends here. */
        boolean stands(Edge e)
        {
            if (e.name == null || !e.name.toLowerCase(Locale.ROOT).contains(object))
            {
                return false;
            }
            return near(e.from, origin) || near(e.to, origin)
                || destination >= 0 && (near(e.from, destination) || near(e.to, destination));
        }

        private static boolean near(int a, int b)
        {
            return Tiles.z(a) == Tiles.z(b) && Math.abs(Tiles.x(a) - Tiles.x(b)) <= RADIUS
                && Math.abs(Tiles.y(a) - Tiles.y(b)) <= RADIUS;
        }
    }

    /** "Rocks (Waterbirth Island)" → "rocks"; "Pillar Jump (Easy) (Revenant Caves)" → "pillar". */
    static String object(String name)
    {
        String plain = name.replaceAll("\\s*\\(.*$", "").trim().toLowerCase(Locale.ROOT);
        return plain.startsWith("pillar jump") ? "pillar" : plain;
    }

    /** The bundled shortcuts (data/shortcuts.tsv); empty when it cannot be read. */
    static List<Shortcut> shortcuts()
    {
        InputStream in = ShortcutPassage.class.getResourceAsStream("/com/hdmapreforged/data/shortcuts.tsv");
        if (in == null)
        {
            return Collections.emptyList();
        }
        List<Shortcut> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String[] header = null;
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("# Name"))
                {
                    header = line.substring(2).split("\t", -1);
                    continue;
                }
                if (line.startsWith("#") || line.trim().isEmpty() || header == null)
                {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                int origin = Tiles.parse(cell(header, cells, "Origin"));
                if (origin < 0)
                {
                    continue;
                }
                list.add(new Shortcut(cell(header, cells, "Name"), origin, Tiles.parse(cell(header, cells, "Destination")),
                    cell(header, cells, "Skills"), cell(header, cells, "Items"), cell(header, cells, "Quests"),
                    cell(header, cells, "Varbits")));
            }
        }
        catch (IOException | RuntimeException e)
        {
            return list;
        }
        return list;
    }

    private static String cell(String[] header, String[] cells, String column)
    {
        for (int i = 0; i < header.length; i++)
        {
            if (header[i].trim().equals(column))
            {
                return i < cells.length ? cells[i].trim() : "";
            }
        }
        return "";
    }
}
