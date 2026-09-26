package com.hdmapreforged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;

/**
 * What the place search finds, word by word: every word typed must fit somewhere (a word of the name, or of the
 * place it is in), in any order, with a small typo forgiven. "shark catherby" finds the shark fishing spots at
 * Catherby, "bank varrock" Varrock's banks, "draynr" Draynor Village.
 */
final class SearchIndex
{
    /** What a hit is. */
    enum Type
    {
        /** A town, island or region. */
        PLACE(0),
        /** A map of its own (a dungeon). */
        MAP(1),
        /** Every place of a kind: "Shark fishing spots". */
        KIND(1),
        /** An icon of ours. */
        ICON(2),
        /** A kind at one place: "Shark fishing spots – Catherby". */
        KIND_PLACE(2),
        /** One of the game's own icons in the tiles. */
        GAME_ICON(3);

        /** Lower comes first when equally good. */
        final int rank;

        Type(int rank)
        {
            this.rank = rank;
        }
    }

    /** One thing to find. */
    static final class Hit
    {
        final Type type;
        final String label;
        /** The words it is found by: its own name's. */
        final String[] words;
        /** Those words joined by spaces, for a whole-name match. */
        final String joined;
        /** The words of where it is (a town), found by too; for a kind at a place, the place's. */
        final String[] placeWords;
        /** What it stands for: a {@link PoiLoader.Place}, {@link BaseMap}, {@link Poi}, {@link KindIndex.Kind}. */
        final Object target;
        /** For a kind at one place: that place's point. */
        final WorldPoint point;
        final PoiType icon;
        /** A little later when equally good: islands and regions after towns of the same name. */
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

    /**
     * Everything to find: places, maps, our icons (by every name stacked on them, and the town they are in), the
     * game's icons, the kinds of places, and each kind at each town ("Shark fishing spots – Catherby").
     */
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

    /**
     * The best hits for what was typed, at most {@code limit}, each label once; {@code allowed} leaves some out
     * (icons one cannot use, the game's icons when hidden).
     */
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

    /** A hit with its score for one search. */
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
     * How well a hit fits the words typed: lower is better (below zero for its very name), NaN when a word fits
     * nowhere. A kind at a place is only
     * found when words fit both the kind and the place ("shark catherby"), so a town's name alone does not list
     * everything in it.
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

    /**
     * How a typed word fits the best of some words: 0 the same word, 1 the start of one, 2 inside one (three letters
     * or more), 3 one letter off (five letters or more); -1 not at all.
     */
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

    /** Whether a word starts with the typed word but for one letter wrong, missing or extra. */
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

    /** The edit distance of two short words. */
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

    private static final java.util.regex.Pattern NOT_WORD = java.util.regex.Pattern.compile("[^a-z0-9]+");

    /** Lower-case words without punctuation: "Phosani's Nightmare" is "phosanis nightmare". */
    static String[] words(String text)
    {
        String clean = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT).replace("'", "")).replaceAll(" ").trim();
        return clean.isEmpty() ? new String[0] : clean.split(" ");
    }
}
