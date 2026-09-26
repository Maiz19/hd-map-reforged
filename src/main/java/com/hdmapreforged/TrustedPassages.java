package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;

/** The planner's trusted passages, for game "dungeon" icons without a map link. Nothing guessed; see APPROACH.md. */
final class TrustedPassages
{
    private static final String[] TABLES = {"map_link_passages.tsv", "links.tsv"};
    static final int RADIUS = 2;

    private static volatile List<WorldPoint[]> passages;

    private TrustedPassages()
    {
    }

    /** A way underground wins over one up to the surface (Waterbirth's ladder goes both ways). */
    static WorldPoint leadsFrom(WorldPoint at, Predicate<WorldPoint> surface)
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
