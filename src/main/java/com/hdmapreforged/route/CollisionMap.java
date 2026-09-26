package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Where one can walk, read from the bundled {@code collision.zip} (built from the game cache by
 * {@code local-development/tools/collision}). Per 64×64 region and floor it holds bitsets: walkable tiles, walls on
 * the north and east edges of a tile, and doors on those edges (passable, a little slower); on the ground floor also
 * which tiles are water. Coordinates outside the data are not walkable. Immutable after loading, so any thread may
 * read it.
 */
public final class CollisionMap
{
    public static final String RESOURCE = "/com/hdmapreforged/route/collision.zip";
    private static final int MAGIC = 0x48444d43;
    private static final int WALK = 0;
    private static final int WALL_N = 64;
    private static final int WALL_E = 128;
    private static final int DOOR_N = 192;
    private static final int DOOR_E = 256;
    private static final int LAYERS = 320;

    /** Per region id and floor ({@code region * 4 + plane}): five bitsets of 64 longs, or null. */
    private final long[][] floors = new long[1 << 18][];
    private final long[][] water = new long[1 << 16][];
    private final List<Transition> transitions;
    /** Obstacles cut through on the way, by packed tile: "Chop-down Vines (bring an axe)". */
    private final Map<Integer, String> obstacles;
    /** Regions with obstacles, so the search looks them up only there. */
    private final boolean[] obstacleRegions = new boolean[1 << 16];

    private CollisionMap(List<Transition> transitions, Map<Integer, String> obstacles)
    {
        this.transitions = transitions;
        this.obstacles = obstacles;
        for (int node : obstacles.keySet())
        {
            obstacleRegions[(Tiles.x(node) >> 6) << 8 | (Tiles.y(node) >> 6)] = true;
        }
    }

    /** Reads the bundled map. */
    public static CollisionMap load() throws IOException
    {
        InputStream in = CollisionMap.class.getResourceAsStream(RESOURCE);
        if (in == null)
        {
            throw new IOException("Missing " + RESOURCE);
        }
        try (InputStream stream = in)
        {
            return read(stream);
        }
    }

    /** Reads a zip holding {@code collision.bin} and {@code transitions.tsv}. */
    public static CollisionMap read(InputStream zipped) throws IOException
    {
        byte[] bin = null;
        List<Transition> transitions = new ArrayList<>();
        Map<Integer, String> obstacles = new HashMap<>();
        ZipInputStream zip = new ZipInputStream(zipped);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null)
        {
            if ("collision.bin".equals(entry.getName()))
            {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                while ((n = zip.read(buffer)) > 0)
                {
                    bytes.write(buffer, 0, n);
                }
                bin = bytes.toByteArray();
            }
            else if ("transitions.tsv".equals(entry.getName()))
            {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null)
                {
                    Transition t = Transition.parse(line);
                    if (t != null)
                    {
                        transitions.add(t);
                    }
                }
            }
            else if ("obstacles.tsv".equals(entry.getName()))
            {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null)
                {
                    obstacle(obstacles, line);
                }
            }
        }
        if (bin == null)
        {
            throw new IOException("No collision.bin");
        }
        CollisionMap map = new CollisionMap(Collections.unmodifiableList(transitions), obstacles);
        map.decode(bin);
        return map;
    }

    private void decode(byte[] bin) throws IOException
    {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bin));
        if (in.readInt() != MAGIC || in.readInt() != 1)
        {
            throw new IOException("Unknown collision data");
        }
        int count = in.readInt();
        if (count < 0 || count > 1 << 16)
        {
            throw new IOException("Bad region count " + count);
        }
        for (int i = 0; i < count; i++)
        {
            int region = in.readUnsignedShort();
            int mask = in.readUnsignedByte();
            for (int z = 0; z < 4; z++)
            {
                if ((mask & 1 << z) != 0)
                {
                    long[] layers = new long[LAYERS];
                    for (int j = 0; j < LAYERS; j++)
                    {
                        layers[j] = in.readLong();
                    }
                    floors[region << 2 | z] = layers;
                }
            }
            if ((mask & 16) != 0)
            {
                long[] bits = new long[64];
                for (int j = 0; j < 64; j++)
                {
                    bits[j] = in.readLong();
                }
                water[region] = bits;
            }
        }
    }

    /** "x y plane <tab> name <tab> action <tab> what it takes"; bad lines are skipped. */
    private static void obstacle(Map<Integer, String> obstacles, String line)
    {
        String[] parts = line.split("\t");
        if (line.startsWith("#") || parts.length < 4)
        {
            return;
        }
        int at = Tiles.parse(parts[0]);
        if (at < 0)
        {
            return;
        }
        String action = parts[2].isEmpty() ? parts[2] : Character.toUpperCase(parts[2].charAt(0)) + parts[2].substring(1);
        obstacles.put(at, action + " " + parts[1] + " (bring " + parts[3] + ")");
    }

    public List<Transition> transitions()
    {
        return transitions;
    }

    /** What it takes to get through the obstacle on a tile, as "Chop-down Vines (bring an axe)", or null. */
    public String obstacle(int node)
    {
        return isObstacle(Tiles.x(node), Tiles.y(node), node) ? obstacles.get(node) : null;
    }

    /** Whether a tile holds an obstacle to cut through; quick where there are none. */
    public boolean isObstacle(int x, int y, int node)
    {
        return x >= 0 && y >= 0 && x < 1 << 14 && y < 1 << 14 && obstacleRegions[(x >> 6) << 8 | (y >> 6)]
            && obstacles.containsKey(node);
    }

    private long[] layers(int x, int y, int z)
    {
        if (x < 0 || y < 0 || x >= 1 << 14 || y >= 1 << 14 || z < 0 || z > 3)
        {
            return null;
        }
        int index = ((x >> 6) << 8 | (y >> 6)) << 2 | z;
        return floors[index];
    }

    private static boolean bit(long[] layers, int offset, int x, int y)
    {
        int i = (x & 63) << 6 | (y & 63);
        return (layers[offset + (i >> 6)] & 1L << (i & 63)) != 0;
    }

    public boolean walkable(int x, int y, int z)
    {
        long[] layers = layers(x, y, z);
        return layers != null && bit(layers, WALK, x, y);
    }

    /** Whether a region has any data for a floor. */
    public boolean hasFloor(int region, int z)
    {
        return region >= 0 && region < 1 << 16 && z >= 0 && z < 4 && floors[region << 2 | z] != null;
    }

    /** Whether a region has any water on the ground floor. */
    public boolean hasWater(int region)
    {
        return region >= 0 && region < 1 << 16 && water[region] != null;
    }

    /** Water on the ground floor: rivers, lakes and the sea. */
    public boolean water(int x, int y)
    {
        if (x < 0 || y < 0 || x >= 1 << 14 || y >= 1 << 14)
        {
            return false;
        }
        long[] bits = water[(x >> 6) << 8 | (y >> 6)];
        if (bits == null)
        {
            return false;
        }
        int i = (x & 63) << 6 | (y & 63);
        return (bits[i >> 6] & 1L << (i & 63)) != 0;
    }

    private boolean edge(int x, int y, int z, int offset)
    {
        long[] layers = layers(x, y, z);
        return layers != null && bit(layers, offset, x, y);
    }

    /** A straight step from a walkable tile to a neighbour: blocked by walls, open through doors. */
    public boolean canStep(int x, int y, int z, int dx, int dy)
    {
        int nx = x + dx;
        int ny = y + dy;
        if (!walkable(nx, ny, z))
        {
            return false;
        }
        if (dx != 0 && dy != 0)
        {
            // Diagonal: both straight routes around the corner must be open, and no door on the way.
            return walkable(x + dx, y, z) && walkable(x, y + dy, z)
                && straightOpen(x, y, z, dx, 0) && straightOpen(x + dx, y, z, 0, dy)
                && straightOpen(x, y, z, 0, dy) && straightOpen(x, y + dy, z, dx, 0)
                && !door(x, y, z, dx, 0) && !door(x + dx, y, z, 0, dy) && !door(x, y, z, 0, dy) && !door(x, y + dy, z, dx, 0);
        }
        return straightOpen(x, y, z, dx, dy);
    }

    private boolean straightOpen(int x, int y, int z, int dx, int dy)
    {
        if (dx == 1)
        {
            return !edge(x, y, z, WALL_E);
        }
        if (dx == -1)
        {
            return !edge(x - 1, y, z, WALL_E);
        }
        if (dy == 1)
        {
            return !edge(x, y, z, WALL_N);
        }
        return !edge(x, y - 1, z, WALL_N);
    }

    /** Whether a straight step crosses a door. */
    public boolean door(int x, int y, int z, int dx, int dy)
    {
        if (dx == 1)
        {
            return edge(x, y, z, DOOR_E);
        }
        if (dx == -1)
        {
            return edge(x - 1, y, z, DOOR_E);
        }
        if (dy == 1)
        {
            return edge(x, y, z, DOOR_N);
        }
        if (dy == -1)
        {
            return edge(x, y - 1, z, DOOR_N);
        }
        return false;
    }

    /** Wall flags of a tile for drawing and tests: 1 north wall, 2 east wall, 4 north door, 8 east door. */
    public int edges(int x, int y, int z)
    {
        return (edge(x, y, z, WALL_N) ? 1 : 0) | (edge(x, y, z, WALL_E) ? 2 : 0) | (edge(x, y, z, DOOR_N) ? 4 : 0)
            | (edge(x, y, z, DOOR_E) ? 8 : 0);
    }

    /**
     * The walkable tile nearest to a point on the same floor within {@code radius} (Chebyshev rings, then straight
     * distance), packed with {@link Tiles#pack}, or -1.
     */
    public int nearestWalkable(int x, int y, int z, int radius)
    {
        return nearestWalkable(x, y, z, radius, x, y);
    }

    /**
     * The walkable tile nearest to a spot; of equally near ones, the one farthest from {@code (awayX, awayY)}: the
     * near side of a shortcut whose ends stand on water or lava, not a pocket between its stones.
     */
    public int nearestWalkable(int x, int y, int z, int radius, int awayX, int awayY)
    {
        for (int r = 0; r <= radius; r++)
        {
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !walkable(x + dx, y + dy, z))
                    {
                        continue;
                    }
                    double away = (x + dx - awayX) * (double) (x + dx - awayX) + (y + dy - awayY) * (double) (y + dy - awayY);
                    // Nearness first; farther from the other end breaks ties.
                    double d = (dx * dx + dy * dy) * 1e6 - away;
                    if (d < bestDistance)
                    {
                        bestDistance = d;
                        best = Tiles.pack(x + dx, y + dy, z);
                    }
                }
            }
            if (best >= 0)
            {
                return best;
            }
        }
        return -1;
    }
}
