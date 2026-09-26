package com.hdmapreforged;

import lombok.RequiredArgsConstructor;

/** One of the wiki's maps, in world tile coordinates. */
@RequiredArgsConstructor
final class BaseMap
{
    static final int SURFACE = 0;
    static final int FULL = -1;

    final int id;
    final String name;
    final int minX;
    final int minY;
    final int maxX;
    final int maxY;
    final int centerX;
    final int centerY;

    boolean contains(int x, int y)
    {
        return x >= minX && x < maxX && y >= minY && y < maxY;
    }

    long area()
    {
        return (long) (maxX - minX) * (maxY - minY);
    }

    @Override
    public String toString()
    {
        return name;
    }
}
