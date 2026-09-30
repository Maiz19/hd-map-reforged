package com.hdmapreforged;

import com.hdmapreforged.route.*;
import java.util.*;
import java.util.function.*;
import net.runelite.api.coords.*;

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
            int d = PoiLoader.chebyshev(from, at);
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
                for (String[] cells : Pathfinder.rows("/com/hdmapreforged/route/" + table))
                {
                    WorldPoint from = cells.length < 2 ? null : Tsv.parsePoint(cells[0]);
                    WorldPoint to = from == null ? null : Tsv.parsePoint(cells[1]);
                    if (to != null)
                    {
                        read.add(new WorldPoint[]{from, to});
                    }
                }
            }
            known = Collections.unmodifiableList(read);
            passages = known;
        }
        return known;
    }
}
