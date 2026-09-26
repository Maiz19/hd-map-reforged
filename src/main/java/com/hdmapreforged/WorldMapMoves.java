package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Where the world map (and the wiki's) draws part of the game elsewhere (the Kalphite Lair beside the desert caves,
 * floor 2 as floor 0): turns drawn coordinates into the game's own. From {@code data/world_map_moves.tsv}.
 */
@lombok.extern.slf4j.Slf4j
final class WorldMapMoves
{
    private static final class Move
    {
        final int map;
        final int x;
        final int y;
        final int width;
        final int height;
        final int dx;
        final int dy;
        final int plane;
        final int planes;

        Move(int map, int x, int y, int width, int height, int dx, int dy, int plane, int planes)
        {
            this.map = map;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.dx = dx;
            this.dy = dy;
            this.plane = plane;
            this.planes = planes;
        }
    }

    private static final List<Move> MOVES = load();

    private WorldMapMoves()
    {
    }

    static WorldPoint toWorld(int mapId, WorldPoint drawn)
    {
        Move found = null;
        // Whole map squares are listed before zones, so a zone's move (finer) wins, as in the game.
        for (Move move : MOVES)
        {
            if (move.map == mapId && inside(move, drawn.getX(), drawn.getY()))
            {
                found = move;
            }
        }
        if (found == null)
        {
            return drawn;
        }
        int plane = found.plane + Math.min(drawn.getPlane(), found.planes - 1);
        return new WorldPoint(drawn.getX() + found.dx, drawn.getY() + found.dy, Math.min(3, plane));
    }

    /** Whether map {@code mapId} draws a moved part of the game at this spot (the Dagannoth Kings' lair). */
    static boolean covers(int mapId, int x, int y)
    {
        for (Move move : MOVES)
        {
            if (move.map == mapId && inside(move, x, y))
            {
                return true;
            }
        }
        return false;
    }

    static final class Drawn
    {
        final int map;
        final WorldPoint point;

        Drawn(int map, WorldPoint point)
        {
            this.map = map;
            this.point = point;
        }
    }

    static WorldPoint toDrawn(int mapId, WorldPoint game)
    {
        Move found = null;
        for (Move move : MOVES)
        {
            if (move.map == mapId && moves(move, game))
            {
                found = move;
            }
        }
        return found == null ? null : drawnBy(found, game);
    }

    static Drawn drawn(WorldPoint game)
    {
        Move found = null;
        for (Move move : MOVES)
        {
            if (moves(move, game))
            {
                found = move;
            }
        }
        return found == null ? null : new Drawn(found.map, drawnBy(found, game));
    }

    private static boolean moves(Move move, WorldPoint game)
    {
        int floor = game.getPlane() - move.plane;
        return inside(move, game.getX() - move.dx, game.getY() - move.dy) && floor >= 0 && floor < move.planes;
    }

    private static boolean inside(Move move, int x, int y)
    {
        return x >= move.x && x < move.x + move.width && y >= move.y && y < move.y + move.height;
    }

    private static WorldPoint drawnBy(Move move, WorldPoint game)
    {
        return new WorldPoint(game.getX() - move.dx, game.getY() - move.dy, game.getPlane() - move.plane);
    }

    static List<WorldPoint> toWorld(int mapId, List<WorldPoint> drawn)
    {
        List<WorldPoint> points = new ArrayList<>(drawn.size());
        for (WorldPoint p : drawn)
        {
            points.add(toWorld(mapId, p));
        }
        return points;
    }

    private static List<Move> load()
    {
        List<Move> moves = new ArrayList<>();
        InputStream in = WorldMapMoves.class.getResourceAsStream("data/world_map_moves.tsv");
        if (in == null)
        {
            log.warn("world_map_moves.tsv is missing; places the world map draws elsewhere show where they are");
            return moves;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("#") || line.trim().isEmpty())
                {
                    continue;
                }
                try
                {
                    String[] cells = line.split("\t");
                    String[] area = cells[1].split(" ");
                    String[] move = cells[2].split(" ");
                    moves.add(new Move(Integer.parseInt(cells[0]), Integer.parseInt(area[0]), Integer.parseInt(area[1]),
                        Integer.parseInt(area[2]), Integer.parseInt(area[3]), Integer.parseInt(move[0]),
                        Integer.parseInt(move[1]), Integer.parseInt(move[2]),
                        move.length > 3 ? Integer.parseInt(move[3]) : 1));
                }
                catch (RuntimeException e)
                {
                    log.warn("Skipping unreadable line of world_map_moves.tsv: {}", line);
                }
            }
        }
        catch (IOException e)
        {
            log.warn("Could not read world_map_moves.tsv", e);
        }
        return moves;
    }
}
