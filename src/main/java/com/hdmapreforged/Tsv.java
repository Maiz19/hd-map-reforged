package com.hdmapreforged;

import java.io.*;
import java.util.*;
import net.runelite.api.coords.*;

/** A bundled table: a {@code #}-prefixed header of tab-separated column names, then rows and {@code #} comments. */
final class Tsv
{
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

        /** {@code fallback} when empty. */
        String or(String column, String fallback)
        {
            String value = get(column);
            return value.isEmpty() ? fallback : value;
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

    /** {@code "x y plane"}, or null. */
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
