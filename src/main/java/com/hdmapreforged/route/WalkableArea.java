package com.hdmapreforged.route;

import java.util.ArrayDeque;

/**
 * The walkable area around a tile, on its floor: how far a dungeon, cave or room reaches without its stairs, ladders
 * or entrances. For fitting the map to the dungeon one enters instead of to every dungeon its wiki map holds.
 */
public final class WalkableArea
{
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private WalkableArea()
    {
    }

    /**
     * The bounds {minX, minY, maxX, maxY} of the tiles one can walk to from {@code (x, y, z)} (from the nearest
     * walkable tile within a few tiles), or null when there is none or the area has more than {@code limit} tiles (the
     * surface, a large cave system: no "one dungeon" to fit).
     */
    public static int[] bounds(CollisionMap map, int x, int y, int z, int limit)
    {
        int start = map.walkable(x, y, z) ? Tiles.pack(x, y, z) : map.nearestWalkable(x, y, z, 4);
        if (start < 0)
        {
            return null;
        }
        java.util.BitSet seen = new java.util.BitSet();
        // Tiles by their place within a window around the start: dungeons never span more than this.
        int size = 1024;
        int ox = Tiles.x(start) - size / 2;
        int oy = Tiles.y(start) - size / 2;
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[]{Tiles.x(start), Tiles.y(start)});
        seen.set((Tiles.x(start) - ox) * size + (Tiles.y(start) - oy));
        int[] b = {Tiles.x(start), Tiles.y(start), Tiles.x(start), Tiles.y(start)};
        int count = 0;
        while (!open.isEmpty())
        {
            int[] p = open.poll();
            if (++count > limit)
            {
                return null;
            }
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[0]);
            b[3] = Math.max(b[3], p[1]);
            for (int d = 0; d < 8; d++)
            {
                int nx = p[0] + DX[d];
                int ny = p[1] + DY[d];
                int wx = nx - ox;
                int wy = ny - oy;
                if (wx < 0 || wy < 0 || wx >= size || wy >= size || seen.get(wx * size + wy)
                    || !map.canStep(p[0], p[1], z, DX[d], DY[d]))
                {
                    continue;
                }
                seen.set(wx * size + wy);
                open.add(new int[]{nx, ny});
            }
        }
        return b;
    }
}
