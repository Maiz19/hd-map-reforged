package com.hdmapreforged.route;

import java.io.BufferedReader;
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
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Where one can walk, from the bundled collision.zip: per region and floor, bitsets of walkable tiles and of walls and
 * doors on the north and east edges, plus ground-floor water. Immutable after loading, so any thread may read it.
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

    /** By {@code region * 4 + plane}: five bitsets of 64 longs, or null. */
    private final long[][] floors = new long[1 << 18][];
    private final long[][] water = new long[1 << 16][];
    private final List<Transition> transitions;
    /** By packed tile: "Chop-down Vines (bring an axe)". */
    private final Map<Integer, String> obstacles;
    private final boolean[] obstacleRegions = new boolean[1 << 16];

    private CollisionMap(List<Transition> transitions, Map<Integer, String> obstacles)
    {
        this.transitions = transitions;
        this.obstacles = obstacles;
        for (int node : obstacles.keySet())
        {
            obstacleRegions[region(Tiles.x(node), Tiles.y(node))] = true;
        }
    }

    public static CollisionMap load() throws IOException
    {
        InputStream in = CollisionMap.class.getResourceAsStream(RESOURCE);
        if (in == null)
        {
            throw new IOException("Missing " + RESOURCE);
        }
        try (in)
        {
            return read(in);
        }
    }

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
                bin = zip.readAllBytes();
            }
            else if ("transitions.tsv".equals(entry.getName()))
            {
                lines(zip, line -> {
                    Transition t = Transition.parse(line);
                    if (t != null)
                    {
                        transitions.add(t);
                    }
                });
            }
            else if ("obstacles.tsv".equals(entry.getName()))
            {
                lines(zip, line -> obstacle(obstacles, line));
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

    private static void lines(InputStream in, Consumer<String> each) throws IOException
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null)
        {
            each.accept(line);
        }
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
                    floors[region << 2 | z] = longs(in, LAYERS);
                }
            }
            if ((mask & 16) != 0)
            {
                water[region] = longs(in, 64);
            }
        }
    }

    private static long[] longs(DataInputStream in, int count) throws IOException
    {
        long[] longs = new long[count];
        for (int j = 0; j < count; j++)
        {
            longs[j] = in.readLong();
        }
        return longs;
    }

    /** The region of a tile, or -1 off the world. */
    private static int region(int x, int y)
    {
        return (x | y) >>> 14 == 0 ? (x >> 6) << 8 | (y >> 6) : -1;
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

    public String obstacle(int node)
    {
        return isObstacle(Tiles.x(node), Tiles.y(node), node) ? obstacles.get(node) : null;
    }

    public boolean isObstacle(int x, int y, int node)
    {
        int region = region(x, y);
        return region >= 0 && obstacleRegions[region] && obstacles.containsKey(node);
    }

    private long[] layers(int x, int y, int z)
    {
        int region = region(x, y);
        return region < 0 || z < 0 || z > 3 ? null : floors[region << 2 | z];
    }

    private static boolean bit(long[] layers, int offset, int x, int y)
    {
        int i = (x & 63) << 6 | (y & 63);
        return (layers[offset + (i >> 6)] & 1L << (i & 63)) != 0;
    }

    public boolean walkable(int x, int y, int z)
    {
        return edge(x, y, z, WALK);
    }

    public boolean hasFloor(int region, int z)
    {
        return region >= 0 && region < 1 << 16 && z >= 0 && z < 4 && floors[region << 2 | z] != null;
    }

    public boolean hasWater(int region)
    {
        return region >= 0 && region < 1 << 16 && water[region] != null;
    }

    public boolean water(int x, int y)
    {
        int region = region(x, y);
        return region >= 0 && water[region] != null && bit(water[region], 0, x, y);
    }

    private boolean edge(int x, int y, int z, int offset)
    {
        long[] layers = layers(x, y, z);
        return layers != null && bit(layers, offset, x, y);
    }

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
            return walkable(x + dx, y, z) && walkable(x, y + dy, z) && clear(x, y, z, dx, 0) && clear(x + dx, y, z, 0, dy)
                && clear(x, y, z, 0, dy) && clear(x, y + dy, z, dx, 0);
        }
        return straightOpen(x, y, z, dx, dy);
    }

    private boolean straightOpen(int x, int y, int z, int dx, int dy)
    {
        return !crosses(x, y, z, dx, dy, WALL_N, WALL_E);
    }

    private boolean clear(int x, int y, int z, int dx, int dy)
    {
        return straightOpen(x, y, z, dx, dy) && !door(x, y, z, dx, dy);
    }

    public boolean door(int x, int y, int z, int dx, int dy)
    {
        return (dx == 1 || dx == -1 || dy == 1 || dy == -1) && crosses(x, y, z, dx, dy, DOOR_N, DOOR_E);
    }

    private boolean crosses(int x, int y, int z, int dx, int dy, int north, int east)
    {
        return dx == 1 ? edge(x, y, z, east) : dx == -1 ? edge(x - 1, y, z, east)
            : dy == 1 ? edge(x, y, z, north) : edge(x, y - 1, z, north);
    }

    /** 1 north wall, 2 east wall, 4 north door, 8 east door. */
    public int edges(int x, int y, int z)
    {
        int edges = 0;
        for (int i = 0; i < 4; i++)
        {
            // WALL_N, WALL_E, DOOR_N, DOOR_E.
            edges |= edge(x, y, z, WALL_N * (i + 1)) ? 1 << i : 0;
        }
        return edges;
    }

    /** Nearest walkable tile within {@code radius} (Chebyshev rings, then straight distance), packed, or -1. */
    public int nearestWalkable(int x, int y, int z, int radius)
    {
        return nearestWalkable(x, y, z, radius, x, y);
    }

    /** Ties go farthest from {@code (awayX, awayY)}: a shortcut's near side, not a pocket between its stones. */
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
