package com.hdmapreforged.route;

import java.util.*;

/**
 * Walking the way the game walks: a breadth-first search over 128×128 tiles around the player with directions in a
 * fixed order, so the drawn route lies on the tiles the player really walks.
 */
public final class GameWalk
{
    static final int HALF = 64;
    /** Well inside the search area. */
    static final int PIECE = 48;
    private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
    private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

    private GameWalk()
    {
    }

    /** A route's walk as the game would take it; pieces the game cannot do as short keep the route's tiles. */
    public static int[] follow(CollisionMap map, int[] points)
    {
        if (points.length < 3 || Tiles.isSea(points[0]))
        {
            return points;
        }
        int[] out = new int[points.length * 2];
        int n = 0;
        out[n++] = points[0];
        int i = 0;
        while (i < points.length - 1)
        {
            int j = i + 1;
            while (j + 1 < points.length && Tiles.z(points[j + 1]) == Tiles.z(points[i])
                && Tiles.distance(points[i], points[j + 1]) <= PIECE)
            {
                j++;
            }
            int[] piece = Tiles.z(points[j]) == Tiles.z(points[i]) ? path(map, out[n - 1], points[j]) : null;
            if (piece == null || piece.length > j - i + 1 || newObstacle(map, piece, points, i, j))
            {
                piece = Arrays.copyOfRange(points, i, j + 1);
            }
            if (n + piece.length > out.length)
            {
                out = Arrays.copyOf(out, (n + piece.length) * 2);
            }
            System.arraycopy(piece, 1, out, n, piece.length - 1);
            n += piece.length - 1;
            i = j;
        }
        return Arrays.copyOf(out, n);
    }

    /** Whether {@code piece} crosses an obstacle (vines, rocks) that {@code points[i..j]} kept clear of. */
    private static boolean newObstacle(CollisionMap map, int[] piece, int[] points, int i, int j)
    {
        for (int t : piece)
        {
            if (map.isObstacle(Tiles.x(t), Tiles.y(t), t)
                && Arrays.stream(points, i, j + 1).noneMatch(p -> p == t))
            {
                return true;
            }
        }
        return false;
    }

    /** Both ends included, or null. */
    public static int[] path(CollisionMap map, int from, int to)
    {
        int z = Tiles.z(from);
        if (Tiles.z(to) != z)
        {
            return null;
        }
        int ox = Tiles.x(from) - HALF;
        int oy = Tiles.y(from) - HALF;
        int tx = Tiles.x(to) - ox;
        int ty = Tiles.y(to) - oy;
        int size = HALF * 2;
        if (tx < 0 || ty < 0 || tx >= size || ty >= size)
        {
            return null;
        }
        // Direction each tile was reached from; -1 not reached.
        byte[] via = new byte[size * size];
        Arrays.fill(via, (byte) -1);
        int start = HALF * size + HALF;
        via[start] = 8;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        int goal = ty * size + tx;
        while (!queue.isEmpty() && via[goal] < 0)
        {
            int cell = queue.poll();
            int cx = cell % size;
            int cy = cell / size;
            for (int d = 0; d < 8; d++)
            {
                int nx = cx + DX[d];
                int ny = cy + DY[d];
                if (nx < 0 || ny < 0 || nx >= size || ny >= size || via[ny * size + nx] >= 0)
                {
                    continue;
                }
                if (map.canStep(cx + ox, cy + oy, z, DX[d], DY[d]))
                {
                    via[ny * size + nx] = (byte) d;
                    queue.add(ny * size + nx);
                }
            }
        }
        if (via[goal] < 0)
        {
            return null;
        }
        int length = 0;
        int[] back = new int[size * 2];
        int cell = goal;
        while (cell != start)
        {
            if (length == back.length)
            {
                back = Arrays.copyOf(back, length * 2);
            }
            back[length++] = cell;
            int d = via[cell];
            cell = (cell / size - DY[d]) * size + (cell % size - DX[d]);
        }
        int[] path = new int[length + 1];
        path[0] = from;
        for (int k = 0; k < length; k++)
        {
            int c = back[length - 1 - k];
            path[k + 1] = Tiles.pack(c % size + ox, c / size + oy, z);
        }
        return path;
    }
}
