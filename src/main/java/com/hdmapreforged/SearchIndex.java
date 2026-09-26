package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** The place search: every typed word must fit the name or its place, in any order, a small typo forgiven. */
final class SearchIndex
{
    enum Type
    {
        PLACE(0),
        MAP(1),
        /** "Shark fishing spots". */
        KIND(1),
        ICON(2),
        /** "Shark fishing spots – Catherby". */
        KIND_PLACE(2),
        GAME_ICON(3);

        final int rank;

        Type(int rank)
        {
            this.rank = rank;
        }
    }

    static final class Hit
    {
        final Type type;
        final String label;
        final String[] words;
        final String joined;
        final String[] placeWords;
        /** A {@link PoiLoader.Place}, {@link BaseMap}, {@link Poi} or {@link KindIndex.Kind}. */
        final Object target;
        final WorldPoint point;
        final PoiType icon;
        /** Islands and regions come after towns of the same name. */
        double later;

        Hit(Type type, String label, String words, String place, Object target, WorldPoint point, PoiType icon)
        {
            this.type = type;
            this.label = label;
            this.words = words(words);
            this.joined = String.join(" ", this.words);
            this.placeWords = place == null ? new String[0] : words(place);
            this.target = target;
            this.point = point;
            this.icon = icon;
        }
    }

    private final List<Hit> hits;

    SearchIndex(List<Hit> hits)
    {
        this.hits = hits;
    }

    static SearchIndex build(List<PoiLoader.Place> labels, List<BaseMap> maps, List<Poi> pois, List<Poi> gameIcons,
        KindIndex kinds, Function<WorldPoint, String> placeName)
    {
        List<Hit> hits = new ArrayList<>();
        for (PoiLoader.Place place : labels)
        {
            Hit hit = new Hit(Type.PLACE, place.name, place.name, null, place, place.point, null);
            hit.later = "settlement".equals(place.kind) ? 0 : "island".equals(place.kind) ? 0.1 : 0.2;
            hits.add(hit);
        }
        for (BaseMap map : maps)
        {
            if (map.id != BaseMap.FULL)
            {
                hits.add(new Hit(Type.MAP, "Map: " + map.name, map.name, null, map, null, null));
            }
        }
        for (Poi poi : pois)
        {
            // Stacked teleports, and things at the same place, are found by any of their names.
            List<Poi> names = new ArrayList<>(poi.members());
            for (Poi other : poi.nearby())
            {
                names.addAll(other.members());
            }
            String town = poi.map == null ? null : placeName.apply(poi.location);
            for (Poi member : names)
            {
                hits.add(new Hit(Type.ICON, member.name, member.name, town, poi, null, poi.type));
            }
        }
        for (Poi poi : gameIcons)
        {
            hits.add(new Hit(Type.GAME_ICON, poi.name, poi.name, placeName.apply(poi.location), poi, null, poi.type));
        }
        for (KindIndex.Kind kind : kinds.all())
        {
            hits.add(new Hit(Type.KIND, kind.label + " (" + kind.entries.size() + ")", kind.label, null, kind, null,
                kind.type));
            Set<String> towns = new HashSet<>();
            for (KindIndex.Entry entry : kind.entries)
            {
                String town = placeName.apply(entry.point);
                if (town != null && towns.add(town))
                {
                    hits.add(new Hit(Type.KIND_PLACE, kind.label + " – " + town, kind.label, town, kind, entry.point,
                        kind.type));
                }
            }
        }
        return new SearchIndex(hits);
    }

    List<Hit> find(String query, int limit, Predicate<Hit> allowed)
    {
        String[] typed = words(query);
        if (typed.length == 0)
        {
            return Collections.emptyList();
        }
        String whole = String.join(" ", typed);
        List<Scored> found = new ArrayList<>();
        for (Hit hit : hits)
        {
            double score = score(hit, typed, whole);
            if (Double.isNaN(score) || !allowed.test(hit))
            {
                continue;
            }
            found.add(new Scored(hit, score));
        }
        // Stable: equally good hits keep the index's order.
        found.sort((a, b) -> Double.compare(a.score, b.score));
        List<Hit> best = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Scored scored : found)
        {
            Hit hit = scored.hit;
            if (labels.add(hit.type == Type.MAP ? hit.label : hit.label.toLowerCase(Locale.ROOT)) && best.size() < limit)
            {
                best.add(hit);
            }
        }
        return best;
    }

    private static final class Scored
    {
        final Hit hit;
        final double score;

        Scored(Hit hit, double score)
        {
            this.hit = hit;
            this.score = score;
        }
    }

    /**
     * Lower is better, NaN when a word fits nowhere. A kind at a place needs words fitting both ("shark catherby"), so a
     * town's name alone does not list everything in it.
     */
    static double score(Hit hit, String[] typed, String whole)
    {
        double score = 0;
        boolean ownWord = false;
        boolean placeWord = false;
        for (String word : typed)
        {
            int own = best(word, hit.words);
            int place = best(word, hit.placeWords);
            if (own < 0 && place < 0)
            {
                return Double.NaN;
            }
            if (own >= 0 && (place < 0 || own <= place))
            {
                ownWord = true;
                score += own;
            }
            else
            {
                placeWord = true;
                score += place + 0.5;
            }
        }
        if (!ownWord || hit.type == Type.KIND_PLACE && !placeWord)
        {
            return Double.NaN;
        }
        String name = hit.joined;
        if (name.equals(whole))
        {
            score -= 3;
        }
        else if (name.startsWith(whole))
        {
            score -= 2;
        }
        return score + hit.type.rank * 0.6 + hit.words.length * 0.05 + hit.later;
    }

    /** 0 same word, 1 start of one, 2 inside one (3+ letters), 3 one letter off (5+ letters), -1 none. */
    static int best(String typed, String[] words)
    {
        int best = -1;
        for (String word : words)
        {
            int fit = word.equals(typed) ? 0 : word.startsWith(typed) ? 1
                : typed.length() >= 3 && word.contains(typed) ? 2
                : typed.length() >= 5 && nearlyStarts(word, typed) ? 3 : -1;
            if (fit >= 0 && (best < 0 || fit < best))
            {
                best = fit;
            }
        }
        return best;
    }

    static boolean nearlyStarts(String word, String typed)
    {
        for (int length = typed.length() - 1; length <= typed.length() + 1; length++)
        {
            if (length <= word.length() && edits(word.substring(0, length), typed) <= 1)
            {
                return true;
            }
        }
        return false;
    }

    static int edits(String a, String b)
    {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
        {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
            {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + change);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");

    /** Lower-case words without punctuation: "Phosani's Nightmare" is "phosanis nightmare". */
    static String[] words(String text)
    {
        String clean = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT).replace("'", "")).replaceAll(" ").trim();
        return clean.isEmpty() ? new String[0] : clean.split(" ");
    }
}
