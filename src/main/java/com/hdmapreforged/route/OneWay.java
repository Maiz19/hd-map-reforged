package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Passages the game lets one take in one direction only (an agility course's obstacles, a slide), read from
 * {@code one_way.tsv}: each line is the way it goes, from an area to an area. A passage from any source (the cache's
 * transitions, the map link passages, links.tsv) that goes the other way between those areas is left out, so no route
 * takes it backwards.
 */
public final class OneWay
{
    private static final class Rule
    {
        final int from;
        final int to;
        final int radius;

        Rule(int from, int to, int radius)
        {
            this.from = from;
            this.to = to;
            this.radius = radius;
        }
    }

    private final List<Rule> rules;

    private OneWay(List<Rule> rules)
    {
        this.rules = rules;
    }

    /** The bundled rules; none when the file is missing. */
    public static OneWay load()
    {
        return load("/com/hdmapreforged/route/one_way.tsv");
    }

    private static OneWay load(String resource)
    {
        List<Rule> rules = new ArrayList<>();
        InputStream in = OneWay.class.getResourceAsStream(resource);
        if (in == null)
        {
            return new OneWay(rules);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                Rule rule = parse(line);
                if (rule != null)
                {
                    rules.add(rule);
                }
            }
        }
        catch (IOException e)
        {
            // None then.
        }
        return new OneWay(rules);
    }

    /** "x y plane <tab> x y plane <tab> radius <tab> name": from, to, how far from each (tiles); null when not a rule. */
    static Rule parse(String line)
    {
        String[] parts = line.split("\t");
        if (line.startsWith("#") || parts.length < 3)
        {
            return null;
        }
        int from = Tiles.parse(parts[0]);
        int to = Tiles.parse(parts[1]);
        try
        {
            return from < 0 || to < 0 ? null : new Rule(from, to, Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** Whether a passage from {@code from} to {@code to} goes against a one-way rule. */
    public boolean against(int from, int to)
    {
        for (Rule rule : rules)
        {
            if (near(from, rule.to, rule.radius) && near(to, rule.from, rule.radius))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean near(int a, int b, int radius)
    {
        return !Tiles.isSea(a) && !Tiles.isSea(b) && Tiles.z(a) == Tiles.z(b) && Tiles.distance(a, b) <= radius;
    }
}
