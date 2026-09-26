package com.hdmapreforged.route;

import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** The result of a search: the steps, and how the target was reached (or why not). */
public final class Route
{
    public enum Outcome
    {
        /** The target itself, or the walkable tile next to a blocked target. */
        FOUND,
        /** The target cannot be reached: the route leads to the nearest spot that can. */
        NEAREST,
        /** Nothing could be reached at all (the start is not on the map). */
        NONE,
        CANCELLED
    }

    /** Something on a walk to cut through, such as "Chop-down Vines (bring an axe)". */
    public static final class Obstacle
    {
        /** Packed tile. */
        public final int at;
        public final String text;

        public Obstacle(int at, String text)
        {
            this.at = at;
            this.text = text;
        }
    }

    /** One part of the route. */
    public static final class Step
    {
        public enum Kind
        {
            WALK, SAIL, STAIRS, ENTRANCE, TELEPORT, TRANSPORT, SHIP, BOARD, DISEMBARK, HOUSE
        }

        public final Kind kind;
        /** Packed nodes: all tiles walked or sea blocks sailed, or the two ends of a jump. */
        public final int[] points;
        public final String name;
        public final String detail;
        /** Half ticks this step takes. */
        public final int cost;
        /** Doors on a walk. */
        public final int doors;
        /** Obstacles on a walk to cut through (vines, jungle, webs): tile and what it takes. */
        public final List<Obstacle> obstacles;
        /** The kind of teleport or transport ("Hot air balloon"), to leave out all of that kind; or null. */
        public final String category;

        public Step(Kind kind, int[] points, String name, String detail, int cost, int doors)
        {
            this(kind, points, name, detail, cost, doors, Collections.emptyList());
        }

        public Step(Kind kind, int[] points, String name, String detail, int cost, int doors, List<Obstacle> obstacles)
        {
            this(kind, points, name, detail, cost, doors, obstacles, null);
        }

        public Step(Kind kind, int[] points, String name, String detail, int cost, int doors, List<Obstacle> obstacles,
            String category)
        {
            this.category = category;
            this.kind = kind;
            this.points = points;
            this.name = name;
            this.detail = detail;
            this.cost = cost;
            this.doors = doors;
            this.obstacles = obstacles;
        }

        public int first()
        {
            return points[0];
        }

        public int last()
        {
            return points[points.length - 1];
        }

        public boolean isJump()
        {
            return kind != Kind.WALK && kind != Kind.SAIL;
        }
    }

    public final Outcome outcome;
    public final List<Step> steps;
    /** Half ticks. */
    public final int cost;
    /** Packed tile the user asked for. */
    public final int target;
    /** Packed tile the route ends on (differs from {@link #target} when that is blocked or unreachable), or -1. */
    public final int end;
    /** True when every reachable place was searched: the target is certainly out of reach. */
    public final boolean exhausted;
    /** True when the search stopped at its node limit. */
    public final boolean limited;
    public final int nodes;

    /** Half ticks the route takes: its steps, without the extra weight of teleports the player keeps for longer trips. */
    public int time()
    {
        int t = 0;
        for (Step step : steps)
        {
            t += step.cost;
        }
        return t;
    }

    public Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited,
        int nodes)
    {
        this.outcome = outcome;
        this.steps = Collections.unmodifiableList(steps);
        this.cost = cost;
        this.target = target;
        this.end = end;
        this.exhausted = exhausted;
        this.limited = limited;
        this.nodes = nodes;
    }

    /** How far from the route the player may be for {@link #ahead} to still find where they are on it. */
    public static final int NEAR = 12;

    /**
     * What is still ahead of the player, like a navigation app: the steps already done are dropped, and the walk (or
     * sail) the player is on starts where they are. The route itself when the player is not near it.
     */
    public Route ahead(int player)
    {
        if (player < 0 || steps.isEmpty())
        {
            return this;
        }
        int bestStep = -1;
        int bestIndex = 0;
        long bestDistance = (long) (NEAR + 1) * (NEAR + 1);
        for (int s = 0; s < steps.size(); s++)
        {
            Step step = steps.get(s);
            if (step.isJump())
            {
                // Standing where a jump lands: everything up to it is done.
                int landing = step.last();
                if (sameLevel(player, landing) && Tiles.distance(player, landing) <= 2)
                {
                    bestStep = s + 1;
                    bestIndex = 0;
                    bestDistance = 0;
                }
                continue;
            }
            for (int i = 0; i < step.points.length; i++)
            {
                int point = step.points[i];
                if (!sameLevel(player, point))
                {
                    continue;
                }
                long dx = Tiles.x(player) - Tiles.x(point);
                long dy = Tiles.y(player) - Tiles.y(point);
                long d = dx * dx + dy * dy;
                if (Tiles.isSea(point))
                {
                    // Sea blocks are coarse: the boat may be anywhere in one.
                    d /= (long) Tiles.CELL * Tiles.CELL;
                }
                // Ties go to the later point, so a route that passes a spot twice follows the player forward.
                if (d <= bestDistance)
                {
                    bestStep = s;
                    bestIndex = i;
                    bestDistance = d;
                }
            }
        }
        if (bestStep < 0 || bestStep == 0 && bestIndex == 0)
        {
            return this;
        }
        List<Step> left = new ArrayList<>();
        for (int s = bestStep; s < steps.size(); s++)
        {
            Step step = steps.get(s);
            if (s == bestStep && bestIndex > 0)
            {
                int[] rest = Arrays.copyOfRange(step.points, bestIndex, step.points.length);
                if (rest.length < 2)
                {
                    continue;
                }
                List<Obstacle> obstaclesLeft = new ArrayList<>();
                for (Obstacle o : step.obstacles)
                {
                    for (int p : rest)
                    {
                        if (p == o.at)
                        {
                            obstaclesLeft.add(o);
                            break;
                        }
                    }
                }
                step = new Step(step.kind, rest, step.name, step.detail, step.cost * rest.length / step.points.length,
                    step.doors, obstaclesLeft, step.category);
            }
            left.add(step);
        }
        return new Route(outcome, left, cost, target, end, exhausted, limited, nodes);
    }

    private static boolean sameLevel(int a, int b)
    {
        return Tiles.isSea(a) || Tiles.isSea(b) || Tiles.z(a) == Tiles.z(b);
    }

    /** Whether the route stops short of the tile asked for (blocked tile, or out of reach). */
    public boolean snapped()
    {
        return end >= 0 && end != target;
    }

    /** Game ticks, rounded up. */
    public int ticks()
    {
        return (cost + 1) / 2;
    }

    public boolean has(Step.Kind kind)
    {
        for (Step step : steps)
        {
            if (step.kind == kind)
            {
                return true;
            }
        }
        return false;
    }

    public Step first(Step.Kind kind)
    {
        for (Step step : steps)
        {
            if (step.kind == kind)
            {
                return step;
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(outcome + " " + ticks() + " ticks, " + nodes + " nodes");
        for (Step step : steps)
        {
            sb.append("\n  ").append(step.kind).append(' ').append(step.name == null ? "" : step.name).append(' ')
                .append(Tiles.format(step.first())).append(" -> ").append(Tiles.format(step.last()));
        }
        return sb.toString();
    }
}
