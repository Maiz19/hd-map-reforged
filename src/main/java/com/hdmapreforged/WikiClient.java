package com.hdmapreforged;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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
    /** Versions are used as folder names: never "." or "..". */
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}");

    private static final int MAX_PAGES = 500;
    /** Stands for "the wiki found nothing" in {@link #pages}. */
    private static final String NO_PAGE = "";
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PICTURE_BYTES = 2_000_000;

    private final OkHttpClient http;
    private final Gson gson;
    /** Page titles by query, least recently used first. Synchronized on itself. */
    private final Map<String, String> pages = new LinkedHashMap<String, String>(64, 0.75f, true)
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

    /** Name, value pairs, in order. */
    private static HttpUrl api(String... query)
    {
        HttpUrl.Builder url = HttpUrl.get(API).newBuilder();
        for (int i = 0; i < query.length; i += 2)
        {
            url.addQueryParameter(query[i], query[i + 1]);
        }
        return url.build();
    }

    void mapVersion(Consumer<String> callback)
    {
        query(api("action", "query", "meta", "allmessages", "ammessages", "kartographer-map-version",
            "format", "json", "formatversion", "2"), body -> {
                JsonArray messages = gson.fromJson(body, JsonObject.class)
                    .getAsJsonObject("query").getAsJsonArray("allmessages");
                JsonObject message = messages.get(0).getAsJsonObject();
                String version = message.has("content") ? message.get("content").getAsString() : null;
                return isSafeVersion(version) ? version : null;
            }, callback, null);
    }

    /** The map list with its JSON; nulls on failure or without the surface. */
    void baseMaps(String version, BiConsumer<BaseMaps, String> callback)
    {
        if (!isSafeVersion(version))
        {
            callback.accept(null, null);
            return;
        }
        Runnable none = () -> callback.accept(null, null);
        query(HttpUrl.get("https://maps.runescape.wiki/osrs/versions/" + version + "/basemaps.json"), body -> {
            BaseMaps maps = BaseMaps.parse(gson, new StringReader(body));
            return maps.isUsable() ? () -> callback.accept(maps, body) : none;
        }, Runnable::run, none);
    }

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
        query(api("action", "query", "format", "json", "formatversion", "2", "redirects", "1", "generator", "search",
            "gsrsearch", query, "gsrlimit", "1"), body -> {
                JsonObject result = answer(body);
                String title = null;
                if (result != null && result.has("pages"))
                {
                    title = result.getAsJsonArray("pages").get(0).getAsJsonObject().get("title").getAsString();
                }
                synchronized (pages)
                {
                    pages.put(query, title == null ? NO_PAGE : title);
                }
                return title;
            }, callback, null);
    }

    void spawns(String name, Consumer<NpcSpawns> callback)
    {
        HttpUrl url = api("action", "query", "prop", "revisions|links", "rvprop", "content", "rvslots", "main",
            "pllimit", "max", "plnamespace", "0", "redirects", "1", "format", "json", "formatversion", "2",
            "titles", name);
        Runnable none = () -> callback.accept(null);
        query(url, body -> {
            JsonObject page = firstPage(body);
            if (page == null)
            {
                return none;
            }
            String title = page.has("title") ? page.get("title").getAsString() : name;
            String text = content(page);
            List<NpcSpawns.Group> groups = new ArrayList<>(NpcSpawns.parse(title, text).groups);
            List<String> mentioned = NpcSpawns.mentioned(title, text);
            List<String> links = new ArrayList<>();
            if (page.has("links"))
            {
                for (JsonElement link : page.getAsJsonArray("links"))
                {
                    links.add(link.getAsJsonObject().get("title").getAsString());
                }
            }
            return () -> {
                List<String> variants = groups.isEmpty() ? NpcSpawns.variants(title, links) : Collections.emptyList();
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
                    List<NpcSpawns.Group> all = new ArrayList<>(found);
                    if (all.isEmpty())
                    {
                        all.addAll(NpcSpawns.maps(title, text));
                    }
                    callback.accept(new NpcSpawns(title, all, mentioned));
                });
            };
        }, Runnable::run, none);
    }

    static final int MAX_VARIANTS = 40;

    private void variantSpawns(List<String> titles, Consumer<List<NpcSpawns.Group>> callback)
    {
        query(api("action", "query", "prop", "revisions", "rvprop", "content", "rvslots", "main", "format", "json",
            "formatversion", "2", "titles", String.join("|", titles)), body -> {
                Map<String, List<NpcSpawns.Group>> byTitle = new HashMap<>();
                JsonObject query = answer(body);
                if (query != null && query.has("pages"))
                {
                    for (JsonElement element : query.getAsJsonArray("pages"))
                    {
                        JsonObject page = element.getAsJsonObject();
                        if (page.has("title") && !HIDDEN.contains(page.get("title").getAsString().toLowerCase(Locale.ROOT)))
                        {
                            String title = page.get("title").getAsString();
                            byTitle.put(title, NpcSpawns.parse(title, content(page)).groups);
                        }
                    }
                }
                List<NpcSpawns.Group> groups = new ArrayList<>();
                for (String title : titles)
                {
                    groups.addAll(byTitle.getOrDefault(title, Collections.emptyList()));
                }
                return groups;
            }, callback, Collections.emptyList());
    }

    private JsonObject answer(String body)
    {
        return gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
    }

    private JsonObject firstPage(String body)
    {
        JsonObject query = answer(body);
        return query == null || !query.has("pages") ? null : query.getAsJsonArray("pages").get(0).getAsJsonObject();
    }

    private static String content(JsonObject page)
    {
        if (!page.has("revisions"))
        {
            return "";
        }
        return page.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("slots")
            .getAsJsonObject("main").get("content").getAsString();
    }

    /** Null when the wiki cannot be reached. */
    void item(String name, Consumer<ItemSources> callback)
    {
        HttpUrl url = api("action", "query", "prop", "revisions", "rvprop", "content", "rvslots", "main",
            "redirects", "1", "format", "json", "formatversion", "2", "titles", name);
        Runnable none = () -> callback.accept(null);
        query(url, body -> {
            JsonObject page = firstPage(body);
            if (page == null)
            {
                return none;
            }
            String title = page.has("title") ? page.get("title").getAsString() : name;
            List<NpcSpawns.Group> spawns = new ArrayList<>();
            if (page.has("revisions"))
            {
                spawns.addAll(NpcSpawns.parse(title, content(page), NpcSpawns.ITEM_SPAWN_LINE).groups);
            }
            return () -> bucket(ItemSources.storeQuery(title), storeRows -> {
                List<ItemSources.Store> stores = storeRows == null ? new ArrayList<>() : ItemSources.stores(storeRows);
                placeShops(stores, 0, () -> bucket(ItemSources.dropQuery(title), dropRows -> callback.accept(
                    new ItemSources(title, spawns, stores, dropRows == null ? new ArrayList<>()
                        : ItemSources.drops(dropRows)))));
            });
        }, Runnable::run, none);
    }

    void store(String shop, Consumer<List<ItemSources.Ware>> callback)
    {
        bucket(ItemSources.shopQuery(shop), rows -> callback.accept(rows == null ? new ArrayList<>()
            : ItemSources.wares(rows)));
    }

    /** Places shops by their pages' maps, a few per request, then runs {@code done}. */
    private void placeShops(List<ItemSources.Store> stores, int from, Runnable done)
    {
        if (from >= stores.size())
        {
            done.run();
            return;
        }
        List<ItemSources.Store> part = stores.subList(from, Math.min(stores.size(), from + ItemSources.SHOPS_PER_QUERY));
        List<String> pages = new ArrayList<>();
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

    private void bucket(String query, Consumer<JsonArray> callback)
    {
        query(api("action", "bucket", "format", "json", "query", query), ItemSources::rows, callback, null);
    }

    /** Small, so all are kept. */
    private final Map<String, BufferedImage> pictures = new ConcurrentHashMap<>();
    /** Loaded one at a time, so a long list never floods the wiki. */
    private final Deque<Runnable> pictureQueue = new ArrayDeque<>();
    private boolean pictureBusy;

    void file(String name, Consumer<BufferedImage> callback)
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

    /** Thumbnails of several pages, each called back as it arrives. */
    void pageImages(List<String> titles, int size, BiConsumer<String, BufferedImage> callback)
    {
        List<String> wanted = new ArrayList<>();
        for (String title : titles)
        {
            BufferedImage known = pictures.get("page:" + size + ":" + title);
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
        HttpUrl url = api("action", "query", "prop", "pageimages", "piprop", "thumbnail",
            "pithumbsize", Integer.toString(size), "pilimit", "50", "format", "json", "formatversion", "2",
            "titles", String.join("|", wanted));
        get(url, body -> {
            JsonObject query = answer(body);
            if (query == null || !query.has("pages"))
            {
                return;
            }
            // Titles as asked: the answer may normalise them ("Custodian Stalker").
            Map<String, String> asked = new HashMap<>();
            if (query.has("normalized"))
            {
                for (JsonElement n : query.getAsJsonArray("normalized"))
                {
                    asked.put(n.getAsJsonObject().get("to").getAsString(), n.getAsJsonObject().get("from").getAsString());
                }
            }
            for (JsonElement element : query.getAsJsonArray("pages"))
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

    private void picture(String key, HttpUrl url, Consumer<BufferedImage> callback)
    {
        BufferedImage known = pictures.get(key);
        if (known != null)
        {
            callback.accept(known);
            return;
        }
        Runnable load = () -> getBytes(url, bytes -> {
            BufferedImage image = null;
            try
            {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
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

    void pageImage(String title, int size, Consumer<BufferedImage> callback)
    {
        HttpUrl url = api("action", "query", "prop", "pageimages", "piprop", "thumbnail",
            "pithumbsize", Integer.toString(size), "redirects", "1", "format", "json", "formatversion", "2",
            "titles", title);
        get(url, body -> {
            JsonObject page = firstPage(body);
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

    void suggest(String prefix, int limit, Consumer<List<String>> callback)
    {
        query(api("action", "opensearch", "search", prefix, "limit", Integer.toString(limit), "namespace", "0",
            "redirects", "resolve", "format", "json"), body -> {
                List<String> titles = new ArrayList<>();
                JsonArray result = gson.fromJson(body, JsonArray.class);
                if (result != null && result.size() > 1 && result.get(1).isJsonArray())
                {
                    for (JsonElement title : result.get(1).getAsJsonArray())
                    {
                        titles.add(title.getAsString());
                    }
                }
                return titles;
            }, callback, Collections.emptyList());
    }

    /** Lower-case pages left out: spawns nowhere one can go (NpcAudit), or no place found (MonsterRouteAudit). */
    private static final Set<String> HIDDEN = hidden();

    static boolean hiddenFromSearch(String title)
    {
        return HIDDEN.contains(title.toLowerCase(Locale.ROOT));
    }

    private static Set<String> hidden()
    {
        Set<String> names = new HashSet<>();
        readNames("data/npc_hidden.tsv", names);
        readNames("data/npc_no_location.tsv", names);
        return names;
    }

    private static void readNames(String resource, Set<String> names)
    {
        InputStream in = WikiClient.class.getResourceAsStream(resource);
        if (in == null)
        {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (!line.startsWith("#") && !line.trim().isEmpty())
                {
                    names.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        catch (IOException e)
        {
            // Nothing left out then.
        }
    }

    void suggestNpcs(String prefix, int limit, Consumer<List<String>> callback)
    {
        suggestWith(prefix, limit, "Template:LocLine|Template:Infobox Monster|Template:Infobox NPC|Template:Bosses"
            + "|Template:HasTask", HIDDEN, callback);
    }

    void suggestItems(String prefix, int limit, Consumer<List<String>> callback)
    {
        suggestWith(prefix, limit, "Template:Infobox Item", Collections.emptySet(), callback);
    }

    private void suggestWith(String prefix, int limit, String template, Set<String> hidden,
        Consumer<List<String>> callback)
    {
        suggest(prefix, 25, titles -> {
            if (titles.isEmpty())
            {
                callback.accept(titles);
                return;
            }
            query(api("action", "query", "prop", "templates", "tltemplates", template, "tllimit", "max",
                "redirects", "1", "format", "json", "formatversion", "2", "titles", String.join("|", titles)), body -> {
                    Set<String> withTemplate = new HashSet<>();
                    JsonObject query = answer(body);
                    if (query != null && query.has("pages"))
                    {
                        for (JsonElement element : query.getAsJsonArray("pages"))
                        {
                            JsonObject page = element.getAsJsonObject();
                            if (page.has("templates") && page.has("title"))
                            {
                                withTemplate.add(page.get("title").getAsString());
                            }
                        }
                    }
                    List<String> kept = new ArrayList<>();
                    for (String title : titles)
                    {
                        if (withTemplate.contains(title) && !hidden.contains(title.toLowerCase(Locale.ROOT))
                            && kept.size() < limit)
                        {
                            kept.add(title);
                        }
                    }
                    return kept;
                }, callback, Collections.emptyList());
        });
    }

    /** Calls back with the parsed answer, or once with {@code fallback} when there is none. */
    private <T> void query(HttpUrl url, Function<String, T> parse, Consumer<T> callback, T fallback)
    {
        boolean[] answered = new boolean[1];
        get(url, body -> {
            T value = parse.apply(body);
            answered[0] = true;
            callback.accept(value);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(fallback);
            }
        });
    }

    private void get(HttpUrl url, Consumer<String> onBody)
    {
        get(url, onBody, () -> { });
    }

    private void get(HttpUrl url, Consumer<String> onBody, Runnable onFailure)
    {
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
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
                    onBody.accept(new String(TileCache.readBody(body, MAX_BODY_BYTES), StandardCharsets.UTF_8));
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
