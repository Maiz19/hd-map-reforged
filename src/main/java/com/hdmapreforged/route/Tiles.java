package com.hdmapreforged.route;

/**
 * Nodes of the route graph as ints: a game tile (x and y below 16384, floor 0-3) or, with {@link #SEA} set, a 4×4
 * block of sea tiles on the ground floor that a boat sails through.
 */
public final class Tiles
{
    public static final int SEA = 1 << 30;
    /** Sea blocks are this many tiles wide. */
    public static final int CELL = 4;

    private Tiles()
    {
    }

    public static int pack(int x, int y, int z)
    {
        return (z & 3) << 28 | (x & 0x3fff) << 14 | (y & 0x3fff);
    }

    public static int x(int node)
    {
        return isSea(node) ? cellX(node) * CELL + CELL / 2 : node >> 14 & 0x3fff;
    }

    public static int y(int node)
    {
        return isSea(node) ? cellY(node) * CELL + CELL / 2 : node & 0x3fff;
    }

    public static int z(int node)
    {
        return isSea(node) ? 0 : node >> 28 & 3;
    }

    public static boolean isSea(int node)
    {
        return (node & SEA) != 0;
    }

    public static int sea(int cellX, int cellY)
    {
        return SEA | (cellX & 0xfff) << 12 | (cellY & 0xfff);
    }

    public static int cellX(int node)
    {
        return node >> 12 & 0xfff;
    }

    public static int cellY(int node)
    {
        return node & 0xfff;
    }

    /** The sea block containing a tile. */
    public static int seaAt(int x, int y)
    {
        return sea(x / CELL, y / CELL);
    }

    /** A tile written {@code "x y plane"} as our tables do, packed; -1 when it is not one (or outside the world). */
    public static int parse(String text)
    {
        String[] xyz = text.trim().split("\\s+");
        if (xyz.length != 3)
        {
            return -1;
        }
        for (String part : xyz)
        {
            // More digits than any coordinate has: not a tile (and no overflow).
            if (part.isEmpty() || part.length() > 5 || !part.chars().allMatch(Character::isDigit))
            {
                return -1;
            }
        }
        int x = Integer.parseInt(xyz[0]);
        int y = Integer.parseInt(xyz[1]);
        int z = Integer.parseInt(xyz[2]);
        return x < 1 << 14 && y < 1 << 14 && z < 4 ? pack(x, y, z) : -1;
    }

    public static String format(int node)
    {
        return x(node) + ", " + y(node) + ", " + z(node) + (isSea(node) ? " (sea)" : "");
    }

    /** Chebyshev distance in tiles, ignoring floors. */
    public static int distance(int a, int b)
    {
        return Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
    }
}
