package com.hdmapreforged;

import java.util.*;
import java.util.function.*;
import lombok.*;

/** Turns requirement columns ({@link Needs}) into readable lines. */
final class Requirements
{
    private static final int MAX_ALTERNATIVES = 3;

    @RequiredArgsConstructor
    static final class Line
    {
        final String text;
        final String skill;
        final int level;
        final String quest;
    }

    private Requirements()
    {
    }

    /** Lines such as {@code "25 Magic"}, {@code "3 × Air rune"} and {@code "Quest: Sea Slug"}. */
    static List<Line> describe(Needs needs, IntFunction<String> itemNames)
    {
        List<Line> lines = new ArrayList<>();
        for (String skill : needs.skills.split(";"))
        {
            String[] parts = skill.trim().split("\\s+", 2);
            if (parts.length == 2 && isNumber(parts[0]))
            {
                String name = parts[1].trim();
                String shown = name.equals("Quest") ? " quest points" : " " + name;
                lines.add(new Line(parts[0] + shown, name, Integer.parseInt(parts[0]), null));
            }
            else if (!skill.trim().isEmpty())
            {
                lines.add(new Line(skill.trim(), null, 0, null));
            }
        }
        for (String item : items(needs.items, itemNames))
        {
            lines.add(new Line(item, null, 0, null));
        }
        for (String quest : needs.quests.split(";"))
        {
            if (!quest.trim().isEmpty())
            {
                lines.add(new Line("Quest: " + quest.trim(), null, 0, quest.trim()));
            }
        }
        return lines;
    }

    /** {@code "AIR_RUNE=3&&FIRE_RUNE=1"}: all needed; {@code "1706=1||1708=1"}: alternatives. {@code itemNames} may return null. */
    static List<String> items(String column, IntFunction<String> itemNames)
    {
        List<String> lines = new ArrayList<>();
        if (column.trim().isEmpty())
        {
            return lines;
        }
        for (String all : column.split("&&"))
        {
            Set<String> alternatives = new LinkedHashSet<>();
            int quantity = 1;
            for (String alternative : all.split("\\|\\|"))
            {
                String[] parts = alternative.trim().split("=");
                if (parts[0].isEmpty())
                {
                    continue;
                }
                if (parts.length > 1)
                {
                    try
                    {
                        quantity = Integer.parseInt(parts[1].trim());
                    }
                    catch (NumberFormatException ignored)
                    {
                    }
                }
                alternatives.add(itemName(parts[0].trim(), itemNames));
            }
            if (alternatives.isEmpty())
            {
                continue;
            }
            List<String> names = new ArrayList<>(alternatives);
            String text = String.join(" or ", names.subList(0, Math.min(MAX_ALTERNATIVES, names.size())));
            if (names.size() > MAX_ALTERNATIVES)
            {
                text += " or others";
            }
            lines.add(quantity > 1 ? quantity + " × " + text : text);
        }
        return lines;
    }

    private static String itemName(String token, IntFunction<String> itemNames)
    {
        if (isNumber(token))
        {
            String name = itemNames.apply(Integer.parseInt(token));
            // Charged variants such as "Amulet of glory(4)" collapse into one entry.
            return name == null ? "Item " + token : name.replaceFirst("\\s*\\(\\d+\\)$", "");
        }
        String words = token.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static List<Integer> itemIds(String column)
    {
        List<Integer> ids = new ArrayList<>();
        for (String token : column.split("&&|\\|\\|"))
        {
            String id = token.split("=")[0].trim();
            if (isNumber(id))
            {
                ids.add(Integer.parseInt(id));
            }
        }
        return ids;
    }

    private static boolean isNumber(String text)
    {
        return !text.isEmpty() && text.length() <= 9 && text.chars().allMatch(Character::isDigit);
    }
}
