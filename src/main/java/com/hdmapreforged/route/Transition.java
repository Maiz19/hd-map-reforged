package com.hdmapreforged.route;

/** A ladder, staircase, trapdoor or entrance from the game cache: an object's footprint and where it leads. */
public final class Transition
{
    public final int x;
    public final int y;
    public final int plane;
    public final int sizeX;
    public final int sizeY;
    /** Packed destination tile. */
    public final int to;
    public final String name;
    public final String action;
    /** Packed tiles one uses it from. */
    public final int[] origins;
    /** A solid object walked through (an "Entry", a gate, a gap), found by shape: it may ask for a quest or level. */
    public final boolean through;

    Transition(int x, int y, int plane, int sizeX, int sizeY, int to, String name, String action, int[] origins,
        boolean through)
    {
        this.origins = origins;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.to = to;
        this.name = name;
        this.action = action;
        this.through = through;
    }

    /**
     * {@code "x y plane sizeX sizeY <tab> x y plane <tab> name <tab> action <tab> x y;x y"} (the tiles it is used
     * from); null for comments and bad lines.
     */
    static Transition parse(String line)
    {
        if (line.isEmpty() || line.startsWith("#"))
        {
            return null;
        }
        String[] cols = line.split("\t");
        if (cols.length < 5)
        {
            return null;
        }
        try
        {
            String[] from = cols[0].trim().split("\\s+");
            String[] to = cols[1].trim().split("\\s+");
            if (from.length != 5 || to.length != 3)
            {
                return null;
            }
            int x = Integer.parseInt(from[0]);
            int y = Integer.parseInt(from[1]);
            int z = Integer.parseInt(from[2]);
            int sx = Integer.parseInt(from[3]);
            int sy = Integer.parseInt(from[4]);
            int tx = Integer.parseInt(to[0]);
            int ty = Integer.parseInt(to[1]);
            int tz = Integer.parseInt(to[2]);
            if (!valid(x, y, z) || !valid(tx, ty, tz) || sx < 1 || sy < 1 || sx > 16 || sy > 16)
            {
                return null;
            }
            String[] tiles = cols[4].trim().split(";");
            int[] origins = new int[tiles.length];
            for (int i = 0; i < tiles.length; i++)
            {
                String[] xy = tiles[i].trim().split("\\s+");
                int ox = Integer.parseInt(xy[0]);
                int oy = Integer.parseInt(xy[1]);
                if (xy.length != 2 || !valid(ox, oy, z))
                {
                    return null;
                }
                origins[i] = Tiles.pack(ox, oy, z);
            }
            return new Transition(x, y, z, sx, sy, Tiles.pack(tx, ty, tz), cols[2].trim(), cols[3].trim(), origins,
                cols.length > 5 && "through".equals(cols[5].trim()));
        }
        catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
        {
            return null;
        }
    }

    private static boolean valid(int x, int y, int z)
    {
        return x >= 0 && y >= 0 && x < 1 << 14 && y < 1 << 14 && z >= 0 && z < 4;
    }
}
