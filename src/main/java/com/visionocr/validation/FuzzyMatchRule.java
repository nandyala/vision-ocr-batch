package com.visionocr.validation;

import com.visionocr.domain.MappedField;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two fields should refer to the same thing (e.g. customer name vs name on bank account).
 * Similarity = max(token overlap, normalized Levenshtein). Skipped when either side is empty
 * (missing fields are already reported by the required check).
 */
public class FuzzyMatchRule implements CrossFieldRule {

    private final String fieldA;
    private final String fieldB;
    private final double minSimilarity;
    private final String reason;

    public FuzzyMatchRule(String fieldA, String fieldB, double minSimilarity, String reason) {
        this.fieldA = fieldA;
        this.fieldB = fieldB;
        this.minSimilarity = minSimilarity;
        this.reason = reason;
    }

    @Override
    public String check(Map<String, MappedField> fields) {
        MappedField a = fields.get(fieldA);
        MappedField b = fields.get(fieldB);
        if (a == null || b == null || !a.hasValue() || !b.hasValue()) {
            return null;
        }
        return similarity(a.getValue(), b.getValue()) >= minSimilarity ? null : reason;
    }

    static double similarity(String x, String y) {
        String a = clean(x);
        String b = clean(y);
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> ta = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        double tokenScore = (double) inter.size() / Math.min(ta.size(), tb.size());
        int max = Math.max(a.length(), b.length());
        double editScore = max == 0 ? 1.0 : 1.0 - (double) levenshtein(a, b) / max;
        return Math.max(tokenScore, editScore);
    }

    private static String clean(String s) {
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
