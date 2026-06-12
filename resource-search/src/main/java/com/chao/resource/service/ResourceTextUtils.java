package com.chao.resource.service;

/**
 * Pure static text processing utilities shared by ResourceService (search/AI)
 * and BilibiliCrawlerService (quality filtering).
 */
public final class ResourceTextUtils {

    private ResourceTextUtils() {}

    public static String normalizeText(String s) {
        if (s == null || s.isBlank()) return "";
        return s.toLowerCase()
                .replaceAll("[\\u2000-\\u206F\\u3000\\uFEFF\\u00A0]+", " ")
                .replaceAll("[`'\".,;:!?@#$%^&*()\\[\\]{}<>|/~\\-_=+]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Bigram character-level similarity in [0, 1].
     */
    public static double bigramSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        java.util.Set<String> setA = bigrams(a);
        java.util.Set<String> setB = bigrams(b);
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        return (double) intersection.size() / union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    /**
     * Count bigram matches between query and (title + summary).
     */
    public static int bigramMatchCount(String q, String t, String s) {
        if (q == null || q.isBlank()) return 0;
        String normQ = normalizeText(q);
        if (normQ.length() < 2) return 0;
        java.util.Set<String> qBigrams = bigrams(normQ);

        StringBuilder combined = new StringBuilder();
        if (t != null && !t.isBlank()) {
            combined.append(normalizeText(t));
        }
        if (s != null && !s.isBlank()) {
            if (combined.length() > 0) combined.append(" ");
            combined.append(normalizeText(s));
        }
        if (combined.isEmpty()) return 0;

        java.util.Set<String> docBigrams = bigrams(combined.toString());
        java.util.Set<String> intersection = new java.util.HashSet<>(qBigrams);
        intersection.retainAll(docBigrams);
        return intersection.size();
    }

    public static boolean isCJK(String s) {
        if (s == null || s.isBlank()) return false;
        return s.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS);
    }
}
