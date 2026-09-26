package com.hdmapreforged;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Where an item can be had, from the OSRS Wiki: its spawns (the {@code {{ItemSpawnLine}}} templates of its page), the
 * shops that have it in stock (the wiki's {@code storeline} bucket, placed by each shop page's
 * map), and the monsters that drop it ({@code dropsline}). Only parsing here; {@link WikiClient#item} asks.
 */
final class ItemSources
{
    /** A shop selling the item. */
    static final class Store
    {
        final String shop;
        final String stock;
        /** "2 coins", or empty when the wiki gives no price. */
        final String price;
        /** Where the shop is, or null when its page has no map. */
        WorldPoint point;
        int mapId = -1;

        Store(String shop, String stock, String price)
        {
            this.shop = shop;
            this.stock = stock;
            this.price = price;
        }
    }

    /** A monster (or other source) dropping the item, all its drop lines together. */
    static final class Drop
    {
        /** Its wiki page, which the monster search can look up. */
        final String monster;
        /** "Thieving (15)", "Level 74, 92", "Reward"… */
        final String how;
        /** Whether it is a monster or NPC (killed or stolen from), which the monster search can show; not a chest,
         * a pack or a rock. */
        final boolean npc;
        /** The drop lines, rate and quantity: "64/128 (5)", "32/128 (15)". */
        final List<String> lines = new ArrayList<>();
        /** The best chance of any line, for sorting; 0 when not known. */
        double chance;

        Drop(String monster, String how, boolean npc)
        {
            this.monster = monster;
            this.how = how;
            this.npc = npc;
        }
    }

    /** Rows a bucket query may give at most. */
    static final int LIMIT = 1000;
    /** Shop pages asked for at a time. */
    static final int SHOPS_PER_QUERY = 40;

    final String page;
    final List<NpcSpawns.Group> spawns;
    final List<Store> stores;
    final List<Drop> drops;

    ItemSources(String page, List<NpcSpawns.Group> spawns, List<Store> stores, List<Drop> drops)
    {
        this.page = page;
        this.spawns = Collections.unmodifiableList(spawns);
        this.stores = Collections.unmodifiableList(stores);
        this.drops = Collections.unmodifiableList(drops);
    }

    boolean isEmpty()
    {
        return spawns.isEmpty() && stores.isEmpty() && drops.isEmpty();
    }

    // ---- bucket queries ----

    /** A string in a bucket query, quoted: {@code Ava's} gives {@code 'Ava\'s'}. */
    static String quote(String value)
    {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String storeQuery(String item)
    {
        return "bucket('storeline').select('sold_by','store_stock','store_sell_price','store_currency')"
            + ".where('sold_item'," + quote(item) + ").limit(" + LIMIT + ").run()";
    }

    static String dropQuery(String item)
    {
        return "bucket('dropsline').select('page_name','drop_json').where('item_name'," + quote(item) + ").limit("
            + LIMIT + ").run()";
    }

    /** The maps of some shop pages (at most {@link #SHOPS_PER_QUERY}). */
    static String mapQuery(List<String> pages)
    {
        StringBuilder where = new StringBuilder();
        if (pages.size() == 1)
        {
            where.append("'page_name',").append(quote(pages.get(0)));
        }
        else
        {
            where.append("bucket.Or(");
            for (int i = 0; i < pages.size(); i++)
            {
                where.append(i == 0 ? "" : ",").append("{'page_name',").append(quote(pages.get(i))).append('}');
            }
            where.append(')');
        }
        return "bucket('map').select('page_name','features','options').where(" + where + ").limit(" + LIMIT + ").run()";
    }

    /** The rows of a bucket answer; null when it is an error or not one. */
    static JsonArray rows(String body)
    {
        try
        {
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("bucket")
                || !root.getAsJsonObject().get("bucket").isJsonArray())
            {
                return null;
            }
            return root.getAsJsonObject().getAsJsonArray("bucket");
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    // ---- shops ----

    /** Shops with it in stock (or endless stock), each once, in the wiki's order. */
    static List<Store> stores(JsonArray rows)
    {
        Map<String, Store> shops = new LinkedHashMap<>();
        for (JsonElement element : rows)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String shop = string(row, "sold_by");
            String stock = string(row, "store_stock");
            if (shop.isEmpty() || !inStock(stock) || shops.containsKey(shop))
            {
                continue;
            }
            shops.put(shop, new Store(shop, stock.trim(), price(string(row, "store_sell_price"),
                string(row, "store_currency"))));
        }
        return new ArrayList<>(shops.values());
    }

    /** What a shop sells: an item with its stock and price. */
    static final class Ware
    {
        final String item;
        final String stock;
        final String price;
        /** The item's inventory picture on the wiki ("Pot.png"), or empty. */
        final String image;

        Ware(String item, String stock, String price, String image)
        {
            this.item = item;
            this.stock = stock;
            this.price = price;
            this.image = image;
        }
    }

    static String shopQuery(String shop)
    {
        return "bucket('storeline').select('sold_item','store_stock','store_sell_price','store_currency',"
            + "'sold_item_image').where('sold_by'," + quote(shop) + ").limit(" + LIMIT + ").run()";
    }

    /** A shop's wares in the wiki's order, each item once (the first line of it); those out of stock too. */
    static List<Ware> wares(JsonArray rows)
    {
        Map<String, Ware> wares = new LinkedHashMap<>();
        for (JsonElement element : rows)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String item = string(row, "sold_item").trim();
            if (!item.isEmpty() && !wares.containsKey(item))
            {
                wares.put(item, new Ware(item, string(row, "store_stock").trim(), price(string(row, "store_sell_price"),
                    string(row, "store_currency")), imageFile(string(row, "sold_item_image"))));
            }
        }
        return new ArrayList<>(wares.values());
    }

    private static final java.util.regex.Pattern IMAGE_FILE =
        java.util.regex.Pattern.compile("[^/\\\\?#<>|:]{1,120}\\.(?i)(png|gif|jpg)");
    private static final java.util.regex.Pattern FILE_PREFIX = java.util.regex.Pattern.compile("^(?i)file:");
    private static final java.util.regex.Pattern FRACTION =
        java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)");
    private static final java.util.regex.Pattern COMMA = java.util.regex.Pattern.compile("\\s*,\\s*");
    /** Geometry nested deeper than this is not a map shape. */
    private static final int MAX_DEPTH = 8;
    /** Number pairs read from one shape at most. */
    private static final int MAX_PAIRS = 10_000;

    /** "File:Pot.png" is "Pot.png"; only a plain picture file name, else empty. */
    static String imageFile(String value)
    {
        String name = FILE_PREFIX.matcher(value.trim()).replaceFirst("").trim();
        return IMAGE_FILE.matcher(name).matches() ? name : "";
    }

    /**
     * Whether a shop has it in stock: "1", "1,000" and "∞" are; "0" (a shop that only buys it), "", "N/A" are not.
     * Rune scimitars, say, are only in one shop, one at a time.
     */
    static boolean inStock(String stock)
    {
        String s = stock.trim().replace(",", "");
        if (s.equals("∞") || s.equalsIgnoreCase("inf") || s.equalsIgnoreCase("infinite") || s.equalsIgnoreCase("unlimited"))
        {
            return true;
        }
        try
        {
            return Double.parseDouble(s) >= 1;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /** "2 coins", "1,500 tokkul"; empty when the price is not a number. */
    static String price(String price, String currency)
    {
        String p = price.trim().replace(",", "");
        long value;
        try
        {
            value = Math.round(Double.parseDouble(p));
        }
        catch (NumberFormatException e)
        {
            return "";
        }
        if (value < 0)
        {
            return "";
        }
        String unit = currency.trim().isEmpty() ? "coins" : currency.trim().toLowerCase(Locale.ROOT);
        if (value == 1 && unit.endsWith("s") && !unit.endsWith("ss") && !unit.contains(" of "))
        {
            // "1 coin", "1 point".
            unit = unit.substring(0, unit.length() - 1);
        }
        return String.format(Locale.ROOT, "%,d", value) + " " + unit;
    }

    /** Places the shops by their pages' maps (the first map of a page: its infobox's). */
    static void place(List<Store> stores, JsonArray mapRows)
    {
        Map<String, JsonObject> first = new LinkedHashMap<>();
        for (JsonElement element : mapRows)
        {
            if (element.isJsonObject())
            {
                first.putIfAbsent(string(element.getAsJsonObject(), "page_name"), element.getAsJsonObject());
            }
        }
        for (Store store : stores)
        {
            JsonObject row = first.get(store.shop);
            if (row != null && store.point == null)
            {
                int[] at = mapPoint(row);
                if (at != null)
                {
                    // Where the wiki's map draws it; the game has some places elsewhere (the Kalphite Lair).
                    store.point = WorldMapMoves.toWorld(at[3], new WorldPoint(at[0], at[1], at[2]));
                    store.mapId = at[3];
                }
            }
        }
    }

    /**
     * The point a map row shows: its first feature (a pin, or the middle of an area), else the map's own middle;
     * {x, y, plane, mapID} or null.
     */
    static int[] mapPoint(JsonObject row)
    {
        try
        {
            String features = string(row, "features");
            if (!features.isEmpty())
            {
                JsonElement parsed = new JsonParser().parse(features);
                if (parsed.isJsonArray())
                {
                    for (JsonElement f : parsed.getAsJsonArray())
                    {
                        int[] p = featurePoint(f.getAsJsonObject());
                        if (p != null)
                        {
                            return p;
                        }
                    }
                }
            }
            String options = string(row, "options");
            if (!options.isEmpty())
            {
                JsonObject o = new JsonParser().parse(options).getAsJsonObject();
                if (o.has("x") && o.has("y"))
                {
                    return valid(o.get("x").getAsDouble(), o.get("y").getAsDouble(), number(o, "plane", 0),
                        number(o, "mapID", -1));
                }
            }
        }
        catch (RuntimeException e)
        {
            // An unreadable map: the shop is listed without a place.
        }
        return null;
    }

    private static int[] featurePoint(JsonObject feature)
    {
        JsonObject geometry = feature.getAsJsonObject("geometry");
        JsonObject properties = feature.has("properties") ? feature.getAsJsonObject("properties") : new JsonObject();
        if (geometry == null || !geometry.has("coordinates"))
        {
            return null;
        }
        // Every number pair in the geometry, however deeply nested (a point, a line, a polygon's rings).
        List<double[]> pairs = new ArrayList<>();
        pairs(geometry.get("coordinates"), pairs, 0);
        if (pairs.isEmpty())
        {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : pairs)
        {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        return valid((minX + maxX) / 2, (minY + maxY) / 2, number(properties, "plane", 0), number(properties, "mapID", -1));
    }

    private static void pairs(JsonElement element, List<double[]> out, int depth)
    {
        if (!element.isJsonArray() || depth > MAX_DEPTH || out.size() >= MAX_PAIRS)
        {
            return;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() >= 2 && array.get(0).isJsonPrimitive() && array.get(1).isJsonPrimitive())
        {
            out.add(new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble()});
            return;
        }
        for (JsonElement e : array)
        {
            pairs(e, out, depth + 1);
        }
    }

    private static int[] valid(double x, double y, int plane, int mapId)
    {
        if (x < 0 || y < 0 || x >= 20000 || y >= 20000)
        {
            return null;
        }
        return new int[]{(int) Math.floor(x), (int) Math.floor(y), Math.max(0, Math.min(3, plane)), mapId};
    }

    // ---- drops ----

    /** The drop lines by monster, the most likely first. */
    static List<Drop> drops(JsonArray rows)
    {
        Map<String, Drop> byMonster = new LinkedHashMap<>();
        for (JsonElement element : rows)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String monster = string(row, "page_name");
            JsonObject drop;
            try
            {
                drop = new JsonParser().parse(string(row, "drop_json")).getAsJsonObject();
            }
            catch (RuntimeException e)
            {
                drop = new JsonObject();
            }
            if (monster.isEmpty())
            {
                continue;
            }
            String rarity = string(drop, "Rarity");
            String quantity = string(drop, "Drop Quantity");
            String type = string(drop, "Drop type");
            String how = how(type, string(drop, "Drop level"));
            boolean npc = isNpc(type);
            Drop d = byMonster.computeIfAbsent(monster + "\t" + how, k -> new Drop(monster, how, npc));
            String line = (rarity.isEmpty() ? "?" : rarity)
                + (quantity.isEmpty() || quantity.equals("1") ? "" : " (" + quantity + ")");
            if (!d.lines.contains(line))
            {
                d.lines.add(line);
            }
            d.chance = Math.max(d.chance, chance(rarity));
        }
        List<Drop> drops = new ArrayList<>(byMonster.values());
        drops.sort((a, b) -> a.chance != b.chance ? Double.compare(b.chance, a.chance)
            : a.monster.compareToIgnoreCase(b.monster));
        return drops;
    }

    /** Drops of monsters and NPCs: killed (combat, or no type given) or pickpocketed. */
    static boolean isNpc(String type)
    {
        String t = type.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.equals("combat") || t.equals("thieving");
    }

    /**
     * How it is got: "Level 74, 92" for monsters, "Thieving (15)" for pickpocketing and stalls, else the kind of drop
     * ("Reward", "Mining").
     */
    static String how(String type, String level)
    {
        String t = type.trim().toLowerCase(Locale.ROOT);
        String levels = COMMA.matcher(level.trim()).replaceAll(", ");
        boolean known = !levels.isEmpty() && !levels.equalsIgnoreCase("N/A");
        if (t.isEmpty() || t.equals("combat"))
        {
            return known ? "Level " + levels : "";
        }
        if (t.equals("thieving"))
        {
            // Pickpocketing or a stall: the wiki does not say which.
            return known ? "Thieving (" + levels + ")" : "Thieving";
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /**
     * A drop rate as a chance from 0 to 1: "64/128", "1/5,000", "~1/300", "2 × 1/128", "Always"; the wiki's words
     * roughly; 0 when unknown.
     */
    static double chance(String rarity)
    {
        String r = rarity.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("~", "").replace("≈", "");
        if (r.isEmpty())
        {
            return 0;
        }
        switch (r)
        {
            case "always":
                return 1;
            case "common":
                return 1 / 10.0;
            case "uncommon":
                return 1 / 50.0;
            case "rare":
                return 1 / 500.0;
            case "very rare":
                return 1 / 5000.0;
            default:
                break;
        }
        java.util.regex.Matcher m = FRACTION.matcher(r);
        if (m.find())
        {
            double top = Double.parseDouble(m.group(1));
            double bottom = Double.parseDouble(m.group(2));
            return bottom <= 0 ? 0 : Math.min(1, top / bottom);
        }
        return 0;
    }

    private static String string(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull())
        {
            return "";
        }
        if (e.isJsonPrimitive())
        {
            return e.getAsString();
        }
        // A repeated field comes as a list: its first value.
        if (e.isJsonArray() && e.getAsJsonArray().size() > 0 && e.getAsJsonArray().get(0).isJsonPrimitive())
        {
            return e.getAsJsonArray().get(0).getAsString();
        }
        return "";
    }

    private static int number(JsonObject o, String key, int fallback)
    {
        try
        {
            return o.has(key) && !o.get(key).isJsonNull() ? (int) o.get(key).getAsDouble() : fallback;
        }
        catch (RuntimeException e)
        {
            return fallback;
        }
    }
}
