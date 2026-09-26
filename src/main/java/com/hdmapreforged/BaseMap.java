package com.hdmapreforged;

/** One of the wiki's maps: the surface, a dungeon or another separate area, in world tile coordinates. */
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

    BaseMap(int id, String name, int minX, int minY, int maxX, int maxY, int centerX, int centerY)
    {
        this.id = id;
        this.name = name;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.centerX = centerX;
        this.centerY = centerY;
    }

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
