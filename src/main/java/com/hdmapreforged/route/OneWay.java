package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One-way passages ({@code one_way.tsv}, e.g. a slide): a passage of any source going back between the areas is left out. */
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
        }
        return new OneWay(rules);
    }

    /** "x y plane <tab> x y plane <tab> radius <tab> name"; null when not a rule. */
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
