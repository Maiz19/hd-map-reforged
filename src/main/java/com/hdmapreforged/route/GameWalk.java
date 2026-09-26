package com.hdmapreforged.route;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Walking the way the game walks. Between two tiles there are often many equally short paths; the game picks one by
 * a breadth-first search over a 128×128 area around the player, trying the directions in a fixed order (west, east,
 * south, north, then south-west, south-east, north-west, north-east). Redoing a route's walks this way puts the drawn
 * route on the tiles the player really walks when clicking ahead.
 */
public final class GameWalk
{
    /** The game searches this far from the player in each direction. */
    static final int HALF = 64;
    /** Legs are redone in pieces this long, well inside the search area. */
    static final int PIECE = 48;
    private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
    private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

    private GameWalk()
    {
    }

    /**
     * The walked tiles of a route's walk, as the game would take them, piece by piece along the route. Pieces the
     * game's search cannot do as short (or at all) keep the route's own tiles.
     */
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
            // The furthest point of the route still close enough, on the same floor, for one search.
            int j = i + 1;
            while (j + 1 < points.length && Tiles.z(points[j + 1]) == Tiles.z(points[i])
                && Tiles.distance(points[i], points[j + 1]) <= PIECE)
            {
                j++;
            }
            int[] piece = Tiles.z(points[j]) == Tiles.z(points[i]) ? path(map, out[n - 1], points[j]) : null;
            if (piece == null || piece.length > j - i + 1)
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

    /** The game's path from one tile to another on the same floor (both ends included), or null. */
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
        // Where each tile was reached from, as a direction index; -1 not reached.
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
        // Back from the goal to the start.
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
