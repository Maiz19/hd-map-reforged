package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * The passages the route planner trusts to lead somewhere else (the game's own map links, map_link_passages.tsv, and
 * the hand links with their source, links.tsv): for icons of the game's that say "dungeon" but have no map link, where
 * the passage beside them leads. Nothing here is guessed; see local-development/APPROACH.md.
 */
final class TrustedPassages
{
    private static final String[] TABLES = {"map_link_passages.tsv", "links.tsv"};
    /** How near the icon the passage must start, in tiles. */
    static final int RADIUS = 2;

    private static volatile List<WorldPoint[]> passages;

    private TrustedPassages()
    {
    }

    /**
     * Where a passage starting beside a point leads to another place (a far leap or another floor), or null. A way on
     * underground wins over a way up to the surface: a dungeon marker stands for the dungeon it leads into (Waterbirth's
     * ladder goes both up to the island and down to the sub-levels; the game's map link beside it already goes up).
     */
    static WorldPoint leadsFrom(WorldPoint at, java.util.function.Predicate<WorldPoint> surface)
    {
        WorldPoint best = null;
        int bestScore = Integer.MAX_VALUE;
        for (WorldPoint[] p : all())
        {
            WorldPoint from = p[0];
            WorldPoint to = p[1];
            int d = Math.max(Math.abs(from.getX() - at.getX()), Math.abs(from.getY() - at.getY()));
            boolean elsewhere = to.getPlane() != from.getPlane() || to.distanceTo2D(from) > 64;
            if (from.getPlane() != at.getPlane() || d > RADIUS || !elsewhere)
            {
                continue;
            }
            int score = d + (surface.test(to) ? 100 : 0);
            if (score < bestScore)
            {
                best = to;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<WorldPoint[]> all()
    {
        List<WorldPoint[]> known = passages;
        if (known == null)
        {
            List<WorldPoint[]> read = new ArrayList<>();
            for (String table : TABLES)
            {
                read(table, read);
            }
            known = Collections.unmodifiableList(read);
            passages = known;
        }
        return known;
    }

    private static void read(String table, List<WorldPoint[]> into)
    {
        InputStream in = TrustedPassages.class.getResourceAsStream("/com/hdmapreforged/route/" + table);
        if (in == null)
        {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] cells = line.split("\t");
                if (line.startsWith("#") || cells.length < 2)
                {
                    continue;
                }
                WorldPoint from = point(cells[0]);
                WorldPoint to = point(cells[1]);
                if (from != null && to != null)
                {
                    into.add(new WorldPoint[]{from, to});
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Without the table, no icon gets a way in from it.
        }
    }

    private static WorldPoint point(String text)
    {
        String[] xyz = text.trim().split(" ");
        try
        {
            return xyz.length == 3 ? new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
                Integer.parseInt(xyz[2])) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
