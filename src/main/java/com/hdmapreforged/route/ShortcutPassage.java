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
 * A planner passage that is a wiki Agility shortcut (shortcuts.tsv). It knows nothing of the shortcut's level, so
 * RouteSource adds it per request only when the requirements are met.
 */
public final class ShortcutPassage
{
    /** How near the shortcut's start (or end) the passage must be, in tiles. */
    static final int RADIUS = 3;

    public final Edge edge;
    /** As shortcuts.tsv gives them (Needs columns). */
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

    static final class Shortcut
    {
        final String name;
        /** Lower case: "Rocks (Waterbirth Island)" is "rocks". */
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

        /** Its name names the object, and it starts or ends here. */
        boolean stands(Edge e)
        {
            return e.name != null && e.name.toLowerCase(Locale.ROOT).contains(object) && (near(e.from, origin)
                || near(e.to, origin) || destination >= 0 && (near(e.from, destination) || near(e.to, destination)));
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
                String name = cell(header, cells, "Name");
                int origin = Tiles.parse(cell(header, cells, "Origin"));
                // An empty object name would match every edge nearby.
                if (origin < 0 || object(name).isEmpty())
                {
                    continue;
                }
                list.add(new Shortcut(name, origin, Tiles.parse(cell(header, cells, "Destination")),
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
