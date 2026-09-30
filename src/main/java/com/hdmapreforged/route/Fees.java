package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/** Entrance fees the plugin cannot check ({@code fees.tsv}), weighed as extra time so a free way wins. */
public final class Fees
{
    private static final int NEAR = 2;

    @RequiredArgsConstructor
    public static final class Fee
    {
        final int x;
        final int y;
        final int plane;
        /** Extra half ticks. */
        public final int cost;
        public final String text;
    }

    private static final List<Fee> FEES = load();

    private Fees()
    {
    }

    public static Fee at(int x, int y, int plane)
    {
        for (Fee fee : FEES)
        {
            if (fee.plane == plane && Math.abs(fee.x - x) <= NEAR && Math.abs(fee.y - y) <= NEAR)
            {
                return fee;
            }
        }
        return null;
    }

    private static List<Fee> load()
    {
        List<Fee> fees = new ArrayList<>();
        for (String line : OneWay.lines("/com/hdmapreforged/route/fees.tsv"))
        {
            String[] parts = line.split("\t");
            if (line.startsWith("#") || parts.length < 3)
            {
                continue;
            }
            String[] xyz = parts[0].trim().split(" ");
            try
            {
                fees.add(new Fee(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]),
                    Integer.parseInt(parts[1].trim()), parts[2].trim()));
            }
            catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
            {
                // Not a fee.
            }
        }
        return Collections.unmodifiableList(fees);
    }
}
