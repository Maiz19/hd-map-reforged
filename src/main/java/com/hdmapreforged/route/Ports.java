package com.hdmapreforged.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Where a boat lies at each Sailing port (ports.tsv, from the game cache), keyed by SAILING_BOAT_n_PORT values. */
public final class Ports
{
    public static final String RESOURCE = "/com/hdmapreforged/route/ports.tsv";

    private Ports()
    {
    }

    /** Port id → packed tile of the boat. */
    public static Map<Integer, Integer> load() throws IOException
    {
        InputStream in = Ports.class.getResourceAsStream(RESOURCE);
        if (in == null)
        {
            throw new IOException("Missing " + RESOURCE);
        }
        Map<Integer, Integer> ports = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("#") || line.trim().isEmpty())
                {
                    continue;
                }
                String[] c = line.split("\t");
                if (c.length < 3)
                {
                    continue;
                }
                try
                {
                    String[] p = c[2].trim().split("\\s+");
                    ports.put(Integer.parseInt(c[0].trim()), Tiles.pack(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 0));
                }
                catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
                {
                    // Skip the row.
                }
            }
        }
        return Collections.unmodifiableMap(ports);
    }
}
