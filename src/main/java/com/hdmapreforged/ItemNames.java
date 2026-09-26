package com.hdmapreforged;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import javax.swing.SwingUtilities;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/** Item names for requirement lists; looked up on the client thread, read from Swing. */
final class ItemNames implements IntFunction<String>
{
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    /**
     * Ids without a name yet, with when they were looked up: shown as "Item N" and looked up again after a while (the
     * game's item data may not have been ready).
     */
    private final Map<Integer, Long> unnamed = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 30_000;

    ItemNames(ClientThread clientThread, ItemManager itemManager)
    {
        this.clientThread = clientThread;
        this.itemManager = itemManager;
    }

    @Override
    public String apply(int id)
    {
        String name = names.get(id);
        return name != null || !unnamed.containsKey(id) ? name : "Item " + id;
    }

    /** The item's name, or null when the game has none for it (yet). Client thread. */
    private String lookUp(int id)
    {
        try
        {
            ItemComposition item = itemManager.getItemComposition(id);
            String name = item == null ? null : item.getName();
            return name == null || name.isEmpty() || "null".equals(name) ? null : name;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Whether an id needs looking up: no name, and not tried a moment ago. */
    private boolean wanted(int id, long now)
    {
        if (names.containsKey(id))
        {
            return false;
        }
        Long tried = unnamed.get(id);
        return tried == null || now - tried >= RETRY_MS;
    }

    /** Looks up unknown names, then runs {@code done} on the Swing thread if any were added. */
    void resolve(Collection<Integer> ids, Runnable done)
    {
        // Previews run without a client: names then stay "Item N".
        long now = System.currentTimeMillis();
        if (clientThread == null || ids.stream().noneMatch(id -> wanted(id, now)))
        {
            return;
        }
        clientThread.invoke(() -> {
            long at = System.currentTimeMillis();
            for (int id : ids)
            {
                if (!wanted(id, at))
                {
                    continue;
                }
                String name = lookUp(id);
                if (name != null)
                {
                    names.put(id, name);
                    unnamed.remove(id);
                }
                else
                {
                    unnamed.put(id, at);
                }
            }
            SwingUtilities.invokeLater(done);
        });
    }
}
