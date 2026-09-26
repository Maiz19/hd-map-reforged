package com.hdmapreforged.route;

import java.util.Arrays;

/** Search state per node in open addressing arrays: best cost, the node before it and the jump taken. */
final class NodeTable
{
    static final int NONE = Integer.MIN_VALUE;
    private int[] keys;
    private int[] costs;
    private int[] parents;
    private int[] via;
    private int size;
    private int mask;

    NodeTable(int capacity)
    {
        int n = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
        allocate(n);
    }

    private void allocate(int n)
    {
        keys = new int[n];
        Arrays.fill(keys, NONE);
        costs = new int[n];
        parents = new int[n];
        via = new int[n];
        mask = n - 1;
        size = 0;
    }

    int size()
    {
        return size;
    }

    private int slot(int key)
    {
        int h = key * 0x9E3779B9;
        int i = (h ^ h >>> 16) & mask;
        while (keys[i] != NONE && keys[i] != key)
        {
            i = (i + 1) & mask;
        }
        return i;
    }

    /** The best known cost of a node, or {@code Integer.MAX_VALUE}. */
    int cost(int key)
    {
        int i = slot(key);
        return keys[i] == NONE ? Integer.MAX_VALUE : costs[i];
    }

    int parent(int key)
    {
        return parents[slot(key)];
    }

    int via(int key)
    {
        return via[slot(key)];
    }

    void put(int key, int cost, int parent, int edge)
    {
        int i = slot(key);
        if (keys[i] == NONE)
        {
            if ((size + 1) * 4 > keys.length * 3)
            {
                grow();
                i = slot(key);
            }
            keys[i] = key;
            size++;
        }
        costs[i] = cost;
        parents[i] = parent;
        via[i] = edge;
    }

    private void grow()
    {
        int[] k = keys;
        int[] c = costs;
        int[] p = parents;
        int[] v = via;
        allocate(k.length * 2);
        for (int j = 0; j < k.length; j++)
        {
            if (k[j] != NONE)
            {
                int i = slot(k[j]);
                keys[i] = k[j];
                costs[i] = c[j];
                parents[i] = p[j];
                via[i] = v[j];
                size++;
            }
        }
    }
}
