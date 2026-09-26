package com.hdmapreforged;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** Where an item can be had (spawns, shops with stock, drops), parsed from wiki answers; {@link WikiClient#item} asks. */
final class ItemSources
{
    static final class Store
    {
        final String shop;
        final String stock;
        /** Empty when the wiki gives no price. */
        final String price;
        /** Null when its page has no map. */
        WorldPoint point;
        int mapId = -1;

        Store(String shop, String stock, String price)
        {
            this.shop = shop;
            this.stock = stock;
            this.price = price;
        }
    }

    static final class Drop
    {
        final String monster;
        /** "Thieving (15)", "Level 74, 92", "Reward"… */
        final String how;
        /** A monster or NPC (killed or stolen from), not a chest, pack or rock. */
        final boolean npc;
        /** "64/128 (5)". */
        final List<String> lines = new ArrayList<>();
        double chance;

        Drop(String monster, String how, boolean npc)
        {
            this.monster = monster;
            this.how = how;
            this.npc = npc;
        }
    }

    static final int LIMIT = 1000;
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

    static String mapQuery(List<String> pages)
    {
        String where = pages.size() == 1 ? "'page_name'," + quote(pages.get(0))
            : pages.stream().map(page -> "{'page_name'," + quote(page) + "}").collect(Collectors.joining(",", "bucket.Or(", ")"));
        return "bucket('map').select('page_name','features','options').where(" + where + ").limit(" + LIMIT + ").run()";
    }

    static JsonArray rows(String body)
    {
        try
        {
            JsonElement bucket = new JsonParser().parse(body).getAsJsonObject().get("bucket");
            return bucket != null && bucket.isJsonArray() ? bucket.getAsJsonArray() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    static List<Store> stores(JsonArray rows)
    {
        Map<String, Store> shops = new LinkedHashMap<>();
        for (JsonObject row : objects(rows))
        {
            String shop = string(row, "sold_by");
            String stock = string(row, "store_stock");
            if (shop.isEmpty() || !inStock(stock) || shops.containsKey(shop))
            {
                continue;
            }
            shops.put(shop, new Store(shop, stock.trim(), price(row)));
        }
        return new ArrayList<>(shops.values());
    }

    static final class Ware
    {
        final String item;
        final String stock;
        final String price;
        /** "Pot.png", or empty. */
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

    static List<Ware> wares(JsonArray rows)
    {
        Map<String, Ware> wares = new LinkedHashMap<>();
        for (JsonObject row : objects(rows))
        {
            String item = string(row, "sold_item").trim();
            if (!item.isEmpty() && !wares.containsKey(item))
            {
                wares.put(item, new Ware(item, string(row, "store_stock").trim(), price(row),
                    imageFile(string(row, "sold_item_image"))));
            }
        }
        return new ArrayList<>(wares.values());
    }

    private static final Pattern IMAGE_FILE =
        Pattern.compile("[^/\\\\?#<>|:]{1,120}\\.(?i)(png|gif|jpg)");
    private static final Pattern FILE_PREFIX = Pattern.compile("^(?i)file:");
    private static final Pattern FRACTION =
        Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_PAIRS = 10_000;

    /** "File:Pot.png" is "Pot.png"; only a plain picture file name, else empty. */
    static String imageFile(String value)
    {
        String name = FILE_PREFIX.matcher(value.trim()).replaceFirst("").trim();
        return IMAGE_FILE.matcher(name).matches() ? name : "";
    }

    /** "1", "1,000" and "∞" are in stock; "0" (a shop that only buys it), "", "N/A" are not. */
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

    private static String price(JsonObject row)
    {
        return price(string(row, "store_sell_price"), string(row, "store_currency"));
    }

    static void place(List<Store> stores, JsonArray mapRows)
    {
        Map<String, JsonObject> first = new LinkedHashMap<>();
        for (JsonObject row : objects(mapRows))
        {
            first.putIfAbsent(string(row, "page_name"), row);
        }
        for (Store store : stores)
        {
            JsonObject row = first.get(store.shop);
            if (row != null && store.point == null)
            {
                int[] at = mapPoint(row);
                if (at != null)
                {
                    // The game has some places elsewhere than the wiki's map (the Kalphite Lair).
                    store.point = WorldMapMoves.toWorld(at[3], new WorldPoint(at[0], at[1], at[2]));
                    store.mapId = at[3];
                }
            }
        }
    }

    /** A map row's first feature (pin or area middle), else the map's middle: {x, y, plane, mapID} or null. */
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
            // Listed without a place.
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
        List<double[]> pairs = new ArrayList<>();
        pairs(geometry.get("coordinates"), pairs, 0);
        if (pairs.isEmpty())
        {
            return null;
        }
        DoubleSummaryStatistics xs = pairs.stream().mapToDouble(p -> p[0]).summaryStatistics();
        DoubleSummaryStatistics ys = pairs.stream().mapToDouble(p -> p[1]).summaryStatistics();
        return valid((xs.getMin() + xs.getMax()) / 2, (ys.getMin() + ys.getMax()) / 2, number(properties, "plane", 0),
            number(properties, "mapID", -1));
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

    static List<Drop> drops(JsonArray rows)
    {
        Map<String, Drop> byMonster = new LinkedHashMap<>();
        for (JsonObject row : objects(rows))
        {
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

    static boolean isNpc(String type)
    {
        String t = type.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.equals("combat") || t.equals("thieving");
    }

    /** "Level 74, 92", "Thieving (15)", else the kind of drop ("Reward"). */
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
            return known ? "Thieving (" + levels + ")" : "Thieving";
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** "64/128", "1/5,000", "~1/300", "Always" as a chance from 0 to 1; words roughly; 0 when unknown. */
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
        Matcher m = FRACTION.matcher(r);
        if (m.find())
        {
            double top = Double.parseDouble(m.group(1));
            double bottom = Double.parseDouble(m.group(2));
            return bottom <= 0 ? 0 : Math.min(1, top / bottom);
        }
        return 0;
    }

    private static List<JsonObject> objects(JsonArray rows)
    {
        List<JsonObject> objects = new ArrayList<>();
        rows.forEach(row -> {
            if (row.isJsonObject())
            {
                objects.add(row.getAsJsonObject());
            }
        });
        return objects;
    }

    private static String string(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        // A repeated field comes as a list: its first value.
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() > 0)
        {
            e = e.getAsJsonArray().get(0);
        }
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
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
