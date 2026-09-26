package com.hdmapreforged;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Read-only requests to the Old School RuneScape Wiki. Callbacks run on OkHttp threads. */
@Slf4j
final class WikiClient
{
    static final String WIKI = "https://oldschool.runescape.wiki";
    private static final String API = WIKI + "/api.php";
    /** Map versions are used as folder names: letters, digits, dots, dashes and underscores, never "." or "..". */
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}");

    /** Pages found by name kept, the most recently asked for; a name without a page is kept too. */
    private static final int MAX_PAGES = 500;
    /** Stands for "the wiki found nothing" in {@link #pages}. */
    private static final String NO_PAGE = "";
    /** Answers longer than this are refused (the largest wiki pages asked for are well under 2 MB). */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    /** Pictures longer than this are refused. */
    private static final int MAX_PICTURE_BYTES = 2_000_000;

    private final OkHttpClient http;
    private final Gson gson;
    /** Page titles by name asked for, in access order, so the least recently used goes first. Synchronized on itself. */
    private final Map<String, String> pages = new java.util.LinkedHashMap<String, String>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
        {
            return size() > MAX_PAGES;
        }
    };

    WikiClient(OkHttpClient http, Gson gson)
    {
        this.http = http;
        this.gson = gson;
    }

    static String pageUrl(String title)
    {
        return HttpUrl.get(WIKI).newBuilder().addPathSegment("w").addPathSegment(title.replace(' ', '_')).build().toString();
    }

    /** Opens the page directly when the query is an exact title, otherwise the wiki's search results. */
    static String searchUrl(String query)
    {
        return HttpUrl.get(WIKI + "/w/Special:Search").newBuilder()
            .addQueryParameter("search", query)
            .build().toString();
    }

    static boolean isSafeVersion(String version)
    {
        return version != null && SAFE_VERSION.matcher(version).matches() && !version.contains("..");
    }

    /** The map version the wiki currently shows, as used in tile URLs; called back with null on failure. */
    void mapVersion(Consumer<String> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("meta", "allmessages")
            .addQueryParameter("ammessages", "kartographer-map-version")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonArray messages = gson.fromJson(body, JsonObject.class)
                .getAsJsonObject("query").getAsJsonArray("allmessages");
            JsonObject message = messages.get(0).getAsJsonObject();
            String version = message.has("content") ? message.get("content").getAsString() : null;
            answered[0] = true;
            callback.accept(isSafeVersion(version) ? version : null);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /**
     * The map list belonging to a map version, with the JSON it was read from (to keep on disk); called back with
     * nulls on failure or for a list without the surface.
     */
    void baseMaps(String version, java.util.function.BiConsumer<BaseMaps, String> callback)
    {
        if (!isSafeVersion(version))
        {
            callback.accept(null, null);
            return;
        }
        boolean[] answered = new boolean[1];
        get(HttpUrl.get("https://maps.runescape.wiki/osrs/versions/" + version + "/basemaps.json"), body -> {
            BaseMaps maps = BaseMaps.parse(gson, new StringReader(body));
            answered[0] = true;
            if (maps.isUsable())
            {
                callback.accept(maps, body);
            }
            else
            {
                callback.accept(null, null);
            }
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null, null);
            }
        });
    }

    /** The title of the best-matching wiki page; called back with null when nothing is found. */
    void page(String query, Consumer<String> callback)
    {
        String cached;
        synchronized (pages)
        {
            cached = pages.get(query);
        }
        if (cached != null)
        {
            callback.accept(cached.equals(NO_PAGE) ? null : cached);
            return;
        }
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", "1")
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            JsonObject result = root.getAsJsonObject("query");
            String title = null;
            if (result != null && result.has("pages"))
            {
                title = result.getAsJsonArray("pages").get(0).getAsJsonObject().get("title").getAsString();
            }
            synchronized (pages)
            {
                pages.put(query, title == null ? NO_PAGE : title);
            }
            answered[0] = true;
            callback.accept(title);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /**
     * Where a monster or NPC is found, from its wiki page (redirects followed); called back with null when the page
     * cannot be read. A page without locations gives no groups.
     */
    void spawns(String name, Consumer<NpcSpawns> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "revisions|links")
            .addQueryParameter("rvprop", "content")
            .addQueryParameter("rvslots", "main")
            .addQueryParameter("pllimit", "max")
            .addQueryParameter("plnamespace", "0")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", name)
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
            if (query == null || !query.has("pages"))
            {
                answered[0] = true;
                callback.accept(null);
                return;
            }
            JsonObject page = query.getAsJsonArray("pages").get(0).getAsJsonObject();
            String title = page.has("title") ? page.get("title").getAsString() : name;
            String text = content(page);
            java.util.List<NpcSpawns.Group> groups = new java.util.ArrayList<>(NpcSpawns.parse(title, text).groups);
            java.util.List<String> mentioned = NpcSpawns.mentioned(title, text);
            java.util.List<String> links = new java.util.ArrayList<>();
            if (page.has("links"))
            {
                for (com.google.gson.JsonElement link : page.getAsJsonArray("links"))
                {
                    links.add(link.getAsJsonObject().get("title").getAsString());
                }
            }
            answered[0] = true;
            java.util.List<String> variants = groups.isEmpty() ? NpcSpawns.variants(title, links)
                : java.util.Collections.emptyList();
            if (variants.isEmpty())
            {
                if (groups.isEmpty())
                {
                    groups.addAll(NpcSpawns.maps(title, text));
                }
                callback.accept(new NpcSpawns(title, groups, mentioned));
                return;
            }
            // A page about several kinds (Custodian stalker): the spawns of each kind it links to.
            variantSpawns(variants.subList(0, Math.min(variants.size(), MAX_VARIANTS)), found -> {
                java.util.List<NpcSpawns.Group> all = new java.util.ArrayList<>(found);
                if (all.isEmpty())
                {
                    all.addAll(NpcSpawns.maps(title, text));
                }
                callback.accept(new NpcSpawns(title, all, mentioned));
            });
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /** Variant pages asked for at most, in one request. */
    static final int MAX_VARIANTS = 40;

    /** The spawns of several pages together, in the order given; an empty list on failure. */
    private void variantSpawns(java.util.List<String> titles, Consumer<java.util.List<NpcSpawns.Group>> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "revisions")
            .addQueryParameter("rvprop", "content")
            .addQueryParameter("rvslots", "main")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", String.join("|", titles))
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            java.util.Map<String, java.util.List<NpcSpawns.Group>> byTitle = new java.util.HashMap<>();
            JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
            if (query != null && query.has("pages"))
            {
                for (com.google.gson.JsonElement element : query.getAsJsonArray("pages"))
                {
                    JsonObject page = element.getAsJsonObject();
                    if (page.has("title") && !HIDDEN.contains(page.get("title").getAsString().toLowerCase(java.util.Locale.ROOT)))
                    {
                        String title = page.get("title").getAsString();
                        byTitle.put(title, NpcSpawns.parse(title, content(page)).groups);
                    }
                }
            }
            java.util.List<NpcSpawns.Group> groups = new java.util.ArrayList<>();
            for (String title : titles)
            {
                groups.addAll(byTitle.getOrDefault(title, java.util.Collections.emptyList()));
            }
            answered[0] = true;
            callback.accept(groups);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(java.util.Collections.emptyList());
            }
        });
    }

    /** A page's wikitext from a revisions answer, or empty. */
    private static String content(JsonObject page)
    {
        if (!page.has("revisions"))
        {
            return "";
        }
        return page.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("slots")
            .getAsJsonObject("main").get("content").getAsString();
    }

    /**
     * Where an item can be had (spawns, shops with it in stock, drops), its page found by name with
     * redirects followed; called back with null when the wiki cannot be reached. Asks one thing at a time.
     */
    void item(String name, Consumer<ItemSources> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "revisions")
            .addQueryParameter("rvprop", "content")
            .addQueryParameter("rvslots", "main")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", name)
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
            if (query == null || !query.has("pages"))
            {
                answered[0] = true;
                callback.accept(null);
                return;
            }
            JsonObject page = query.getAsJsonArray("pages").get(0).getAsJsonObject();
            String title = page.has("title") ? page.get("title").getAsString() : name;
            java.util.List<NpcSpawns.Group> spawns = new java.util.ArrayList<>();
            if (page.has("revisions"))
            {
                spawns.addAll(NpcSpawns.parse(title, content(page), NpcSpawns.ITEM_SPAWN_LINE).groups);
            }
            answered[0] = true;
            bucket(ItemSources.storeQuery(title), storeRows -> {
                java.util.List<ItemSources.Store> stores = storeRows == null ? new java.util.ArrayList<>()
                    : ItemSources.stores(storeRows);
                placeShops(stores, 0, () -> bucket(ItemSources.dropQuery(title), dropRows -> callback.accept(
                    new ItemSources(title, spawns, stores, dropRows == null ? new java.util.ArrayList<>()
                        : ItemSources.drops(dropRows)))));
            });
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /** What a shop (by its wiki page) sells; called back with an empty list when nothing is found. */
    void store(String shop, Consumer<java.util.List<ItemSources.Ware>> callback)
    {
        bucket(ItemSources.shopQuery(shop), rows -> callback.accept(rows == null ? new java.util.ArrayList<>()
            : ItemSources.wares(rows)));
    }

    /** Places the shops from {@code from} on by their pages' maps, a few pages per request, then runs {@code done}. */
    private void placeShops(java.util.List<ItemSources.Store> stores, int from, Runnable done)
    {
        if (from >= stores.size())
        {
            done.run();
            return;
        }
        java.util.List<ItemSources.Store> part = stores.subList(from, Math.min(stores.size(), from + ItemSources.SHOPS_PER_QUERY));
        java.util.List<String> pages = new java.util.ArrayList<>();
        for (ItemSources.Store store : part)
        {
            pages.add(store.shop);
        }
        bucket(ItemSources.mapQuery(pages), rows -> {
            if (rows != null)
            {
                ItemSources.place(part, rows);
            }
            placeShops(stores, from + ItemSources.SHOPS_PER_QUERY, done);
        });
    }

    /** The rows of a wiki bucket query; called back with null on failure. */
    private void bucket(String query, Consumer<JsonArray> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "bucket")
            .addQueryParameter("format", "json")
            .addQueryParameter("query", query)
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonArray rows = ItemSources.rows(body);
            answered[0] = true;
            callback.accept(rows);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /** Pictures loaded before, by file name or page and size: small, so all are kept. */
    private final Map<String, java.awt.image.BufferedImage> pictures = new ConcurrentHashMap<>();
    /** Pictures to load, one at a time, so a long list never floods the wiki. */
    private final java.util.Deque<Runnable> pictureQueue = new java.util.ArrayDeque<>();
    private boolean pictureBusy;

    /**
     * A picture file of the wiki by name ("Pot.png", an item's inventory picture); called back (on an OkHttp thread)
     * with it, or null when it cannot be had.
     */
    void file(String name, Consumer<java.awt.image.BufferedImage> callback)
    {
        if (!ItemSources.imageFile(name).equals(name) || name.isEmpty())
        {
            callback.accept(null);
            return;
        }
        HttpUrl url = HttpUrl.get(WIKI).newBuilder().addPathSegment("images").addPathSegment(name.replace(' ', '_'))
            .build();
        picture("file:" + name, url, callback);
    }

    /**
     * The main pictures of several pages (monsters, say) as thumbnails of {@code size}, each called back when it
     * arrives (or with null); one request for all their addresses, then one picture at a time.
     */
    void pageImages(java.util.List<String> titles, int size, java.util.function.BiConsumer<String, java.awt.image.BufferedImage> callback)
    {
        java.util.List<String> wanted = new java.util.ArrayList<>();
        for (String title : titles)
        {
            java.awt.image.BufferedImage known = pictures.get("page:" + size + ":" + title);
            if (known != null)
            {
                callback.accept(title, known);
            }
            else if (wanted.size() < 50)
            {
                wanted.add(title);
            }
        }
        if (wanted.isEmpty())
        {
            return;
        }
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "pageimages")
            .addQueryParameter("piprop", "thumbnail")
            .addQueryParameter("pithumbsize", Integer.toString(size))
            .addQueryParameter("pilimit", "50")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", String.join("|", wanted))
            .build();
        get(url, body -> {
            JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
            if (query == null || !query.has("pages"))
            {
                return;
            }
            // Titles as asked: the answer may normalise them ("Custodian Stalker").
            java.util.Map<String, String> asked = new java.util.HashMap<>();
            if (query.has("normalized"))
            {
                for (com.google.gson.JsonElement n : query.getAsJsonArray("normalized"))
                {
                    asked.put(n.getAsJsonObject().get("to").getAsString(), n.getAsJsonObject().get("from").getAsString());
                }
            }
            for (com.google.gson.JsonElement element : query.getAsJsonArray("pages"))
            {
                JsonObject page = element.getAsJsonObject();
                if (!page.has("thumbnail") || !page.has("title"))
                {
                    continue;
                }
                String title = page.get("title").getAsString();
                String key = asked.getOrDefault(title, title);
                HttpUrl image = HttpUrl.parse(page.getAsJsonObject("thumbnail").get("source").getAsString());
                if (image != null && image.host().equals(HttpUrl.get(WIKI).host()))
                {
                    picture("page:" + size + ":" + key, image, loaded -> callback.accept(key, loaded));
                }
            }
        });
    }

    /** A picture from the wiki, from memory when loaded before, else queued behind the others. */
    private void picture(String key, HttpUrl url, Consumer<java.awt.image.BufferedImage> callback)
    {
        java.awt.image.BufferedImage known = pictures.get(key);
        if (known != null)
        {
            callback.accept(known);
            return;
        }
        Runnable load = () -> getBytes(url, bytes -> {
            java.awt.image.BufferedImage image = null;
            try
            {
                image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            }
            catch (IOException | RuntimeException e)
            {
                // Not a picture: none.
            }
            if (image != null && pictures.size() < 2000)
            {
                pictures.put(key, image);
            }
            try
            {
                callback.accept(image);
            }
            finally
            {
                nextPicture();
            }
        }, () -> {
            try
            {
                callback.accept(null);
            }
            finally
            {
                nextPicture();
            }
        });
        synchronized (pictureQueue)
        {
            if (pictureQueue.size() > 300)
            {
                callback.accept(null);
                return;
            }
            pictureQueue.add(load);
            if (pictureBusy)
            {
                return;
            }
            pictureBusy = true;
        }
        nextPicture();
    }

    private void nextPicture()
    {
        Runnable next;
        synchronized (pictureQueue)
        {
            next = pictureQueue.poll();
            if (next == null)
            {
                pictureBusy = false;
                return;
            }
        }
        next.run();
    }

    /**
     * A page's main picture as a small thumbnail (the monster in its infobox), loaded in turn with the other pictures;
     * called back with null when it has none or it cannot be had.
     */
    void pageImage(String title, int size, Consumer<java.awt.image.BufferedImage> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "pageimages")
            .addQueryParameter("piprop", "thumbnail")
            .addQueryParameter("pithumbsize", Integer.toString(size))
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", title)
            .build();
        get(url, body -> {
            JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
            JsonObject page = query == null || !query.has("pages") ? null : query.getAsJsonArray("pages").get(0).getAsJsonObject();
            if (page == null || !page.has("thumbnail"))
            {
                callback.accept(null);
                return;
            }
            HttpUrl image = HttpUrl.parse(page.getAsJsonObject("thumbnail").get("source").getAsString());
            // Only the wiki's own images.
            if (image == null || !image.host().equals(HttpUrl.get(WIKI).host()))
            {
                callback.accept(null);
                return;
            }
            picture("page:" + size + ":" + title, image, callback);
        }, () -> callback.accept(null));
    }

    /** {@code onFailure} runs when no bytes come (failure, an error answer, too large). */
    private void getBytes(HttpUrl url, Consumer<byte[]> onBytes, Runnable onFailure)
    {
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Wiki image failed: {}", url, e);
                onFailure.run();
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                boolean done = false;
                try (ResponseBody body = response.body())
                {
                    if (response.isSuccessful() && body != null)
                    {
                        byte[] bytes = TileCache.readBody(body, MAX_PICTURE_BYTES);
                        done = true;
                        onBytes.accept(bytes);
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("Unreadable wiki image {}", url, e);
                }
                if (!done)
                {
                    onFailure.run();
                }
            }
        });
    }

    /** Wiki page titles starting with {@code prefix}, for suggestions; an empty list on failure. */
    void suggest(String prefix, int limit, Consumer<java.util.List<String>> callback)
    {
        HttpUrl url = HttpUrl.get(API).newBuilder()
            .addQueryParameter("action", "opensearch")
            .addQueryParameter("search", prefix)
            .addQueryParameter("limit", Integer.toString(limit))
            .addQueryParameter("namespace", "0")
            .addQueryParameter("redirects", "resolve")
            .addQueryParameter("format", "json")
            .build();
        boolean[] answered = new boolean[1];
        get(url, body -> {
            java.util.List<String> titles = new java.util.ArrayList<>();
            JsonArray result = gson.fromJson(body, JsonArray.class);
            if (result != null && result.size() > 1 && result.get(1).isJsonArray())
            {
                for (com.google.gson.JsonElement title : result.get(1).getAsJsonArray())
                {
                    titles.add(title.getAsString());
                }
            }
            answered[0] = true;
            callback.accept(titles);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(java.util.Collections.emptyList());
            }
        });
    }

    /**
     * Monster and NPC pages left out of the suggestions, lower case: those whose spawns are nowhere one can go
     * (instances such as the Realm of Memories, cutscenes; found by NpcAudit), and those the search finds no place for
     * at all (found by MonsterRouteAudit; both in the test sources).
     */
    private static final java.util.Set<String> HIDDEN = hidden();

    /** Whether the monster search leaves a page out of its suggestions. */
    static boolean hiddenFromSearch(String title)
    {
        return HIDDEN.contains(title.toLowerCase(java.util.Locale.ROOT));
    }

    private static java.util.Set<String> hidden()
    {
        java.util.Set<String> names = new java.util.HashSet<>();
        readNames("data/npc_hidden.tsv", names);
        readNames("data/npc_no_location.tsv", names);
        return names;
    }

    private static void readNames(String resource, java.util.Set<String> names)
    {
        java.io.InputStream in = WikiClient.class.getResourceAsStream(resource);
        if (in == null)
        {
            return;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (!line.startsWith("#") && !line.trim().isEmpty())
                {
                    names.add(line.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        catch (IOException e)
        {
            // Nothing left out then.
        }
    }

    /**
     * Monsters and NPCs whose name starts with {@code prefix}: the wiki's title suggestions, keeping only pages that
     * list spawn locations ({@code {{LocLine}}}) or are about a monster, NPC or boss. An empty list on failure.
     */
    void suggestNpcs(String prefix, int limit, Consumer<java.util.List<String>> callback)
    {
        // Pages with spawns, and boss, monster and NPC pages whose places are found another way (their variants, the
        // place they are fought in).
        suggestWith(prefix, limit, "Template:LocLine|Template:Infobox Monster|Template:Infobox NPC|Template:Bosses"
            + "|Template:HasTask", HIDDEN, callback);
    }

    /** Items whose name starts with {@code prefix}: the wiki's title suggestions that are item pages. */
    void suggestItems(String prefix, int limit, Consumer<java.util.List<String>> callback)
    {
        suggestWith(prefix, limit, "Template:Infobox Item", java.util.Collections.emptySet(), callback);
    }

    /** The wiki's title suggestions, keeping only pages that use {@code template} and are not {@code hidden}. */
    private void suggestWith(String prefix, int limit, String template, java.util.Set<String> hidden,
        Consumer<java.util.List<String>> callback)
    {
        suggest(prefix, 25, titles -> {
            if (titles.isEmpty())
            {
                callback.accept(titles);
                return;
            }
            HttpUrl url = HttpUrl.get(API).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "templates")
                .addQueryParameter("tltemplates", template)
                .addQueryParameter("tllimit", "max")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .addQueryParameter("titles", String.join("|", titles))
                .build();
            boolean[] answered = new boolean[1];
            get(url, body -> {
                java.util.Set<String> withTemplate = new java.util.HashSet<>();
                JsonObject query = gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
                if (query != null && query.has("pages"))
                {
                    for (com.google.gson.JsonElement element : query.getAsJsonArray("pages"))
                    {
                        JsonObject page = element.getAsJsonObject();
                        if (page.has("templates") && page.has("title"))
                        {
                            withTemplate.add(page.get("title").getAsString());
                        }
                    }
                }
                java.util.List<String> kept = new java.util.ArrayList<>();
                for (String title : titles)
                {
                    if (withTemplate.contains(title) && !hidden.contains(title.toLowerCase(java.util.Locale.ROOT))
                        && kept.size() < limit)
                    {
                        kept.add(title);
                    }
                }
                answered[0] = true;
                callback.accept(kept);
            }, () -> {
                if (!answered[0])
                {
                    callback.accept(java.util.Collections.emptyList());
                }
            });
        });
    }

    private void get(HttpUrl url, Consumer<String> onBody)
    {
        get(url, onBody, () -> { });
    }

    private void get(HttpUrl url, Consumer<String> onBody, Runnable onFailure)
    {
        Request request = new Request.Builder().url(url).build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Wiki request failed: {}", url, e);
                onFailure.run();
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (ResponseBody body = response.body())
                {
                    if (!response.isSuccessful() || body == null)
                    {
                        log.debug("Wiki request {} returned {}", url, response.code());
                        onFailure.run();
                        return;
                    }
                    onBody.accept(new String(TileCache.readBody(body, MAX_BODY_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8));
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("Unexpected wiki response from {}", url, e);
                    onFailure.run();
                }
            }
        });
    }
}
