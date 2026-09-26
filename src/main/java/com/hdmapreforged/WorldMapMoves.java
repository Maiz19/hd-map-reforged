package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * Where the world map (and the wiki's) draws part of the game elsewhere (the Kalphite Lair beside the desert caves,
 * floor 2 as floor 0): turns drawn coordinates into the game's own. From {@code data/world_map_moves.tsv}.
 */
@lombok.extern.slf4j.Slf4j
final class WorldMapMoves
{
    @RequiredArgsConstructor
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
    }

    private static final List<Move> MOVES = load();

    private WorldMapMoves()
    {
    }

    /** The last move that fits: whole map squares are listed before zones, so a zone's (finer) wins, as in the game. */
    private static Move last(Predicate<Move> fits)
    {
        for (int i = MOVES.size() - 1; i >= 0; i--)
        {
            Move move = MOVES.get(i);
            if (fits.test(move))
            {
                return move;
            }
        }
        return null;
    }

    static WorldPoint toWorld(int mapId, WorldPoint drawn)
    {
        Move found = last(move -> move.map == mapId && inside(move, drawn.getX(), drawn.getY()));
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
        return last(move -> move.map == mapId && inside(move, x, y)) != null;
    }

    @RequiredArgsConstructor
    static final class Drawn
    {
        final int map;
        final WorldPoint point;
    }

    static WorldPoint toDrawn(int mapId, WorldPoint game)
    {
        Move found = last(move -> move.map == mapId && moves(move, game));
        return found == null ? null : drawnBy(found, game);
    }

    static Drawn drawn(WorldPoint game)
    {
        Move found = last(move -> moves(move, game));
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
        return drawn.stream().map(p -> toWorld(mapId, p)).collect(Collectors.toList());
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
                    // Map id, x y width height, dx dy floor [floors].
                    int[] v = Arrays.stream(line.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                    moves.add(new Move(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v.length > 8 ? v[8] : 1));
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
