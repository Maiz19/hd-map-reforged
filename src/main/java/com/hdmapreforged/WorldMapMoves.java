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
 * Where the world map draws a part of the game somewhere else than it is (the Kalphite Lair beside the desert caves,
 * its floor 2 drawn as floor 0), or an upper floor as the ground floor (the God Wars Dungeon's boss rooms): the OSRS
 * Wiki's map does the same, so the coordinates of its location lines are where a place is drawn, not where it is.
 * Turns them into the game's own, which the player, the route planner and the map icons use. From
 * {@code data/world_map_moves.tsv}, built from the game cache.
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

    /** Where a point a wiki map with id {@code mapId} draws is in the game; the point itself where nothing moves. */
    static WorldPoint toWorld(int mapId, WorldPoint drawn)
    {
        Move found = null;
        // The file lists whole map squares before zones, so a zone's move (finer) wins, as in the game.
        for (Move move : MOVES)
        {
            if (move.map == mapId && drawn.getX() >= move.x && drawn.getX() < move.x + move.width
                && drawn.getY() >= move.y && drawn.getY() < move.y + move.height)
            {
                found = move;
            }
        }
        if (found == null)
        {
            return drawn;
        }
        // The floor drawn counts up from the lowest floor moved, as far as the floors it moves.
        int plane = found.plane + Math.min(drawn.getPlane(), found.planes - 1);
        return new WorldPoint(drawn.getX() + found.dx, drawn.getY() + found.dy, Math.min(3, plane));
    }

    /**
     * Whether the map with id {@code mapId} draws a moved part of the game at this spot (the Dagannoth Kings' lair on the
     * Waterbirth Dungeon map, over where Ardougne's underground is in the game): what is really at the spot is not
     * what that map shows there.
     */
    static boolean covers(int mapId, int x, int y)
    {
        for (Move move : MOVES)
        {
            if (move.map == mapId && x >= move.x && x < move.x + move.width && y >= move.y && y < move.y + move.height)
            {
                return true;
            }
        }
        return false;
    }

    /** Where a map draws a point of the game: the map's id and the point as drawn there. */
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

    /**
     * Where the map with id {@code mapId} draws a point of the game when it draws it elsewhere (the inverse of
     * {@link #toWorld(int, WorldPoint)}), or null when that map does not move it.
     */
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

    /**
     * The map that draws a point of the game elsewhere, and where: the Kalphite Lair (floor 2) on the Kharidian Desert
     * Underground beside the desert caves, as floor 0. Null when no map moves it. When several do, the last listed
     * (the finest) wins, as in {@link #toWorld(int, WorldPoint)}.
     */
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

    /** Whether a move takes this point of the game: in its area as it is in the game, on one of its floors. */
    private static boolean moves(Move move, WorldPoint game)
    {
        int x = game.getX() - move.dx;
        int y = game.getY() - move.dy;
        int floor = game.getPlane() - move.plane;
        return x >= move.x && x < move.x + move.width && y >= move.y && y < move.y + move.height && floor >= 0
            && floor < move.planes;
    }

    private static WorldPoint drawnBy(Move move, WorldPoint game)
    {
        return new WorldPoint(game.getX() - move.dx, game.getY() - move.dy, game.getPlane() - move.plane);
    }

    /** {@link #toWorld(int, WorldPoint)} for each point. */
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
            // Then points stay as drawn.
            log.warn("Could not read world_map_moves.tsv", e);
        }
        return moves;
    }
}
