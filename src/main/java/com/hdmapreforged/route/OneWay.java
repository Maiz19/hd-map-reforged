package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** One-way passages ({@code one_way.tsv}, e.g. a slide): a passage of any source going back between the areas is left out. */
public final class OneWay
{
    @RequiredArgsConstructor
    private static final class Rule
    {
        final int from;
        final int to;
        final int radius;
    }

    private final List<Rule> rules;

    private OneWay(List<Rule> rules)
    {
        this.rules = rules;
    }

    public static OneWay load()
    {
        List<Rule> rules = new ArrayList<>();
        for (String line : lines("/com/hdmapreforged/route/one_way.tsv"))
        {
            Rule rule = parse(line);
            if (rule != null)
            {
                rules.add(rule);
            }
        }
        return new OneWay(rules);
    }

    /** A bundled text resource's lines, as far as they could be read. */
    static List<String> lines(String resource)
    {
        List<String> lines = new ArrayList<>();
        InputStream in = OneWay.class.getResourceAsStream(resource);
        if (in != null)
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                for (String line = reader.readLine(); line != null; line = reader.readLine())
                {
                    lines.add(line);
                }
            }
            catch (IOException e)
            {
                // Those read so far.
            }
        }
        return lines;
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
