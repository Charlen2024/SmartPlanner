package com.chao.common.util;

import java.util.HashSet;
import java.util.Set;

public final class TextSimilarity {

    private TextSimilarity() {}

    /**
     * Bigram Jaccard similarity between two strings.
     * Returns 0.0 if either string is empty or has no bigrams.
     */
    public static double bigramJaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        int inter = 0;
        for (String g : sa) {
            if (sb.contains(g)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        return union <= 0 ? 0.0 : (inter * 1.0 / union);
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String x = s.trim();
        if (x.isEmpty()) return out;
        if (x.length() == 1) {
            out.add(x);
            return out;
        }
        for (int i = 0; i < x.length() - 1; i++) {
            out.add(x.substring(i, i + 2));
        }
        return out;
    }
}
