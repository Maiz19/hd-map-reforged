package com.hdmapreforged.route;

import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;

/** The result of a search: the steps, and how the target was reached (or why not). */
public final class Route
{
    public enum Outcome
    {
        FOUND,
        NEAREST,
        NONE,
        CANCELLED
    }

    @AllArgsConstructor
    public static final class Obstacle
    {
        public final int at;
        public final String text;
    }

    @AllArgsConstructor
    public static final class Step
    {
        public enum Kind
        {
            WALK, SAIL, STAIRS, ENTRANCE, TELEPORT, TRANSPORT, SHIP, BOARD, DISEMBARK, HOUSE
        }

        public final Kind kind;
        /** Packed: all tiles walked or sea blocks sailed, or the two ends of a jump. */
        public final int[] points;
        public final String name;
        public final String detail;
        public final int cost;
        public final int doors;
        public final List<Obstacle> obstacles;
        /** Teleport or transport kind ("Hot air balloon"), to leave out all of that kind; or null. */
        public final String category;

        public Step(Kind kind, int[] points, String name, String detail, int cost, int doors)
        {
            this(kind, points, name, detail, cost, doors, Collections.emptyList(), null);
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
    public final int target;
    public final int end;
    public final boolean exhausted;
    public final boolean limited;
    public final int nodes;

    /** Without the extra weight of teleports kept for longer trips. */
    public int time()
    {
        return steps.stream().mapToInt(step -> step.cost).sum();
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

    public static final int NEAR = 12;

    /** What is still ahead of the player, like a navigation app; the route itself when the player is not near it. */
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
                    d /= (long) Tiles.CELL * Tiles.CELL;
                }
                // Ties go to the later point, so a route passing a spot twice follows the player forward.
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
                List<Obstacle> obstaclesLeft = step.obstacles.stream()
                    .filter(o -> Arrays.stream(rest).anyMatch(p -> p == o.at)).collect(Collectors.toList());
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

    public boolean snapped()
    {
        return end >= 0 && end != target;
    }

    public int ticks()
    {
        return (cost + 1) / 2;
    }

    public boolean has(Step.Kind kind)
    {
        return first(kind) != null;
    }

    public Step first(Step.Kind kind)
    {
        return steps.stream().filter(step -> step.kind == kind).findFirst().orElse(null);
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
