package com.hdmapreforged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * One of our bundled tables: a first line of {@code #}-prefixed, tab-separated column names, then data rows. Other
 * lines starting with {@code #} are comments (kept as comment rows); points are written {@code "x y plane"}.
 */
final class Tsv
{
    /** A data row, or a comment when {@link #comment} is set. */
    static final class Row
    {
        final String comment;
        private final Map<String, String> columns;

        private Row(String comment, Map<String, String> columns)
        {
            this.comment = comment;
            this.columns = columns;
        }

        boolean isComment()
        {
            return comment != null;
        }

        String get(String column)
        {
            String value = columns.get(column);
            return value == null ? "" : value.trim();
        }

        WorldPoint point(String column)
        {
            return parsePoint(get(column));
        }
    }

    private Tsv()
    {
    }

    static List<Row> parse(Reader source) throws IOException
    {
        BufferedReader reader = new BufferedReader(source);
        String line = reader.readLine();
        if (line == null)
        {
            return Collections.emptyList();
        }
        String[] header = line.replaceFirst("^#\\s*", "").split("\t");
        List<Row> rows = new ArrayList<>();
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().isEmpty())
            {
                continue;
            }
            if (line.startsWith("#"))
            {
                rows.add(new Row(line.substring(1).trim(), Collections.emptyMap()));
                continue;
            }
            String[] cells = line.split("\t", -1);
            Map<String, String> columns = new HashMap<>();
            for (int i = 0; i < header.length && i < cells.length; i++)
            {
                columns.put(header[i].trim(), cells[i]);
            }
            rows.add(new Row(null, columns));
        }
        return rows;
    }

    /** Parses {@code "x y plane"}; returns null for blank or malformed cells. */
    static WorldPoint parsePoint(String cell)
    {
        String[] parts = cell.trim().split("\\s+");
        if (parts.length != 3)
        {
            return null;
        }
        try
        {
            return new WorldPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
