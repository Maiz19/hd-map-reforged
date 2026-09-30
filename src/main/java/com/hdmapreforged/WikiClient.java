package com.hdmapreforged;

import lombok.*;
import com.google.gson.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.extern.slf4j.*;
import okhttp3.*;

/**
 * Read-only requests to the Old School RuneScape Wiki, answered from {@link WikiCache} when it can. Callbacks run on
 * background threads.
 */
@Slf4j
@RequiredArgsConstructor
final class WikiClient
{
    static final String WIKI = "https://oldschool.runescape.wiki";
    private static final String API = WIKI + "/api.php";
    /** Versions are used as folder names: never "." or "..", nor ending in a dot. */
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9_.-]{0,38}[A-Za-z0-9_-])?");

    private static final int MAX_PAGES = 500;
    /** Stands for "the wiki found nothing" in {@link #pages}. */
    private static final String NO_PAGE = "";
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PICTURE_BYTES = 2_000_000;
    private static final int MAX_PICTURE_SIDE = 2048;

    private final OkHttpClient http;
    private final Gson gson;
    /** None: every request asks the wiki. */
    private final WikiCache cache;
    private final Executor io;
    /** Page titles by query, least recently used first. Synchronized on itself. */
    private final Map<String, String> pages = new LinkedHashMap<String, String>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
        {
            return size() > MAX_PAGES;
        }
    };


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
            "format", "json", "formatversion", "2"), false, body -> {
                JsonArray messages = gson.fromJson(body, JsonObject.class)
                    .getAsJsonObject("query").getAsJsonArray("allmessages");
                JsonObject message = messages.get(0).getAsJsonObject();
                String version = message.has("content") ? text(message, "content") : null;
                return isSafeVersion(version) ? version : null;
            }, callback, null);
    }

    /** The map list with its JSON; nulls on failure or without the surface. */
    void baseMaps(String version, BiConsumer<BaseMaps, String> callback)
    {
        Runnable none = () -> callback.accept(null, null);
        if (!isSafeVersion(version))
        {
            none.run();
            return;
        }
        query(HttpUrl.get("https://maps.runescape.wiki/osrs/versions/" + version + "/basemaps.json"), false, body -> {
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
                    title = text(result.getAsJsonArray("pages").get(0), "title");
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
            String title = page.has("title") ? text(page, "title") : name;
            String text = content(page);
            List<NpcSpawns.Group> groups = new ArrayList<>(NpcSpawns.parse(title, text).groups);
            List<String> mentioned = NpcSpawns.mentioned(title, text);
            List<String> links = new ArrayList<>();
            if (page.has("links"))
            {
                page.getAsJsonArray("links").forEach(link -> links.add(text(link, "title")));
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
                for (JsonElement element : pages(body))
                {
                    JsonObject page = element.getAsJsonObject();
                    if (page.has("title") && !hiddenFromSearch(text(page, "title")))
                    {
                        String title = text(page, "title");
                        byTitle.put(title, NpcSpawns.parse(title, content(page)).groups);
                    }
                }
                List<NpcSpawns.Group> groups = new ArrayList<>();
                titles.forEach(title -> groups.addAll(byTitle.getOrDefault(title, Collections.emptyList())));
                return groups;
            }, callback, Collections.emptyList());
    }

    private JsonObject answer(String body)
    {
        return gson.fromJson(body, JsonObject.class).getAsJsonObject("query");
    }

    /** The answer's pages, or none. */
    private JsonArray pages(String body)
    {
        JsonObject query = answer(body);
        return query == null || !query.has("pages") ? new JsonArray() : query.getAsJsonArray("pages");
    }

    /** The page's picture at {@code size}: the address is built here, only the file's name comes from the answer. */
    private static HttpUrl thumbnail(JsonObject page, int size)
    {
        String name = page.has("pageimage") ? ItemSources.imageFile(text(page, "pageimage")) : "";
        return name.isEmpty() ? null : HttpUrl.get(WIKI).newBuilder().addPathSegments("images/thumb")
            .addPathSegment(name).addPathSegment(size + "px-" + name).build();
    }

    private static String text(JsonElement object, String key)
    {
        return object.getAsJsonObject().get(key).getAsString();
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
        return text(page.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("slots").get("main"),
            "content");
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
            String title = page.has("title") ? text(page, "title") : name;
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
        bucket(ItemSources.mapQuery(part.stream().map(store -> store.shop).collect(Collectors.toList())), rows -> {
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
        HttpUrl url = api("action", "query", "prop", "pageimages", "piprop", "name", "pilimit", "50", "format", "json",
            "formatversion", "2", "titles", String.join("|", wanted));
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
                    asked.put(text(n, "to"), text(n, "from"));
                }
            }
            for (JsonElement element : query.getAsJsonArray("pages"))
            {
                JsonObject page = element.getAsJsonObject();
                HttpUrl image = thumbnail(page, size);
                if (image != null && page.has("title"))
                {
                    String key = asked.getOrDefault(text(page, "title"), text(page, "title"));
                    picture("page:" + size + ":" + key, image, loaded -> callback.accept(key, loaded));
                }
            }
        }, () -> { });
    }

    private void picture(String key, HttpUrl url, Consumer<BufferedImage> callback)
    {
        BufferedImage known = pictures.get(key);
        if (known != null)
        {
            callback.accept(known);
            return;
        }
        Runnable load = () -> get(url, MAX_PICTURE_BYTES, true, bytes -> {
            BufferedImage image = null;
            try
            {
                image = decode(bytes);
                if (image != null && pictures.size() < 2000)
                {
                    pictures.put(key, image);
                }
            }
            catch (IOException | RuntimeException e)
            {
                // Not a picture: none.
            }
            finally
            {
                // Even after an Error, so the queue moves on.
                loaded(callback, image);
            }
        }, () -> loaded(callback, null));
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

    /** Null for anything but a picture of sane size, checked before decoding. */
    static BufferedImage decode(byte[] bytes) throws IOException
    {
        // In memory, as TileCache: ImageIO's default stream may cache to a temporary file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
            {
                return null;
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) > MAX_PICTURE_SIDE || reader.getHeight(0) > MAX_PICTURE_SIDE)
                {
                    return null;
                }
                return reader.read(0);
            }
            finally
            {
                reader.dispose();
            }
        }
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
        HttpUrl url = api("action", "query", "prop", "pageimages", "piprop", "name", "redirects", "1", "format", "json",
            "formatversion", "2", "titles", title);
        boolean[] answered = new boolean[1];
        get(url, body -> {
            JsonObject page = firstPage(body);
            HttpUrl image = page == null ? null : thumbnail(page, size);
            answered[0] = true;
            if (image == null)
            {
                callback.accept(null);
                return;
            }
            picture("page:" + size + ":" + title, image, callback);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(null);
            }
        });
    }

    /** Hands over a picture, then loads the next; never throws, so the request is not also called a failure. */
    private void loaded(Consumer<BufferedImage> callback, BufferedImage image)
    {
        try
        {
            callback.accept(image);
        }
        catch (RuntimeException e)
        {
            log.debug("Wiki image callback failed", e);
        }
        finally
        {
            nextPicture();
        }
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
    private static final Set<String> HIDDEN = new HashSet<>();

    static
    {
        readNames("data/npc_hidden.tsv");
        readNames("data/npc_no_location.tsv");
    }

    static boolean hiddenFromSearch(String title)
    {
        return HIDDEN.contains(title.toLowerCase(Locale.ROOT));
    }

    private static void readNames(String resource)
    {
        InputStream in = WikiClient.class.getResourceAsStream(resource);
        if (in == null)
        {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            reader.lines().filter(line -> !line.startsWith("#") && !line.trim().isEmpty())
                .forEach(line -> HIDDEN.add(line.trim().toLowerCase(Locale.ROOT)));
        }
        catch (IOException | UncheckedIOException e)
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
                    for (JsonElement element : pages(body))
                    {
                        JsonObject page = element.getAsJsonObject();
                        if (page.has("templates") && page.has("title"))
                        {
                            withTemplate.add(text(page, "title"));
                        }
                    }
                    return titles.stream().filter(title -> withTemplate.contains(title)
                        && !hidden.contains(title.toLowerCase(Locale.ROOT))).limit(limit).collect(Collectors.toList());
                }, callback, Collections.emptyList());
        });
    }

    private <T> void query(HttpUrl url, Function<String, T> parse, Consumer<T> callback, T fallback)
    {
        query(url, true, parse, callback, fallback);
    }

    /** Calls back with the parsed answer, or once with {@code fallback} when there is none; {@code keep}: cached. */
    private <T> void query(HttpUrl url, boolean keep, Function<String, T> parse, Consumer<T> callback, T fallback)
    {
        boolean[] answered = new boolean[1];
        get(url, MAX_BODY_BYTES, keep, bytes -> {
            T value = parse.apply(new String(bytes, StandardCharsets.UTF_8));
            answered[0] = true;
            callback.accept(value);
        }, () -> {
            if (!answered[0])
            {
                callback.accept(fallback);
            }
        });
    }

    private void get(HttpUrl url, Consumer<String> onBody, Runnable onFailure)
    {
        get(url, MAX_BODY_BYTES, true, bytes -> onBody.accept(new String(bytes, StandardCharsets.UTF_8)), onFailure);
    }

    /**
     * A fresh kept answer, else the wiki's (then kept), else a stale kept one. {@code onFailure} runs when no body
     * comes (failure, an error answer, too large) or {@code onBody} throws.
     */
    private void get(HttpUrl url, int limit, boolean keep, Consumer<byte[]> onBody, Runnable onFailure)
    {
        WikiCache kept = keep ? cache : null;
        if (kept == null)
        {
            fetch(url, limit, onBody, onFailure);
            return;
        }
        try
        {
            io.execute(() -> {
                byte[] fresh = kept.get(url, false);
                if (fresh != null)
                {
                    deliver(fresh, onBody, onFailure);
                    return;
                }
                fetch(url, limit, bytes -> {
                    if (deliver(bytes, onBody, onFailure))
                    {
                        kept.put(url, bytes);
                    }
                }, () -> {
                    byte[] stale = kept.get(url, true);
                    if (stale == null)
                    {
                        onFailure.run();
                    }
                    else
                    {
                        deliver(stale, onBody, onFailure);
                    }
                });
            });
        }
        catch (RejectedExecutionException e)
        {
            // Shutting down.
            onFailure.run();
        }
    }

    /** False when {@code onBody} threw; {@code onFailure} has run then. */
    private static boolean deliver(byte[] bytes, Consumer<byte[]> onBody, Runnable onFailure)
    {
        try
        {
            onBody.accept(bytes);
            return true;
        }
        catch (RuntimeException e)
        {
            log.debug("Unusable wiki answer", e);
            onFailure.run();
            return false;
        }
    }

    private void fetch(HttpUrl url, int limit, Consumer<byte[]> onBody, Runnable onFailure)
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
                    onBody.accept(TileCache.readBody(body, limit));
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
