package dev.qqbot.memory;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public final class SearchText {
    private static final Pattern PARAGRAPH = Pattern.compile("(?:\\r?\\n[\\t ]*){2,}");

    private SearchText() {}

    public static List<String> terms(String text, int limit) {
        int[] points =
                Normalizer.normalize(text, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .codePoints()
                        .toArray();
        var terms = new LinkedHashSet<String>();
        for (int offset = 0; offset < points.length && terms.size() < limit; ) {
            int start = offset;
            if (han(points[offset])) {
                while (offset < points.length && han(points[offset])) {
                    offset++;
                }
                if (offset - start == 1) {
                    terms.add(new String(points, start, 1));
                } else {
                    for (int part = start; part < offset - 1 && terms.size() < limit; part++) {
                        terms.add(new String(points, part, 2));
                    }
                }
            } else if (word(points[offset])) {
                while (offset < points.length && word(points[offset])) {
                    offset++;
                }
                terms.add(new String(points, start, Math.min(128, offset - start)));
            } else {
                offset++;
            }
        }
        return List.copyOf(terms);
    }

    public static List<String> queryTerms(String question) {
        return terms(question, 32);
    }

    public static double score(String body, List<String> query) {
        if (query.isEmpty()) {
            return 0;
        }
        Set<String> found = new HashSet<>(terms(body, Integer.MAX_VALUE));
        return query.stream().filter(found::contains).count() / (double) query.size();
    }

    public static List<String> paragraphs(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        var boundaries = PARAGRAPH.matcher(text);
        int start = 0;
        while (boundaries.find()) {
            result.add(text.substring(start, boundaries.end()));
            start = boundaries.end();
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return List.copyOf(result);
    }

    public static List<String> chunks(String text) {
        var result = new ArrayList<String>();
        for (String paragraph : paragraphs(text)) {
            int offset = 0;
            while (offset < paragraph.length()) {
                int count = Math.min(500, paragraph.codePointCount(offset, paragraph.length()));
                int end = paragraph.offsetByCodePoints(offset, count);
                result.add(paragraph.substring(offset, end));
                offset = end;
            }
        }
        return List.copyOf(result);
    }

    private static boolean han(int point) {
        return Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN;
    }

    private static boolean word(int point) {
        return point >= 'a' && point <= 'z' || Character.isDigit(point);
    }
}
