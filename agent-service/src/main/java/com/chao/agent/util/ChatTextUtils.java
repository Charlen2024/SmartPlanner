package com.chao.agent.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatTextUtils {

    private ChatTextUtils() {}

    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern PENDING_TASK_ITEM_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*\\.\\s*(.+)$");

    public static String todayPrefix() {
        LocalDate d = LocalDate.now(ZONE_SHANGHAI);
        String[] wd = {"日", "一", "二", "三", "四", "五", "六"};
        return "[今天是" + d + "，星期" + wd[d.getDayOfWeek().getValue() % 7] + "] ";
    }

    public static String formatDuration(Integer seconds) {
        if (seconds == null || seconds <= 0) return "0秒";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return s > 0 ? m + "分" + s + "秒" : m + "分钟";
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        return m > 0 ? h + "小时" + m + "分" : h + "小时";
    }

    public static String safeSnippet(String s) {
        if (s == null) return "";
        String x = s.replace("\n", " ").replace("\r", " ").trim();
        if (x.length() <= 180) return x;
        return x.substring(0, 180);
    }

    static String normalizeLineEndings(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    public static String sanitizeStreamChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        return chunk;
    }

    public static String extractAnswer(String text) {
        if (text == null) return "";
        String s = normalizeLineEndings(text).trim();
        if (s.isBlank()) return "";
        s = stripMarkdown(s);
        s = cleanupWhitespace(s);
        s = rewritePendingTasksIfPresent(s);
        s = maybeAppendNavHints(s);
        return s.trim();
    }

    private static String maybeAppendNavHints(String s) {
        if (s == null) return "";
        String out = s.trim();
        if (out.isBlank()) return out;
        if (shouldSuggestJournalJump(out) && !hasNavigateDirective(out)) {
            return out + "\n跳转: /journals";
        }
        return out;
    }

    private static boolean shouldSuggestJournalJump(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        boolean hasJournal = t.contains("随笔") || t.contains("日记") || t.contains("记录一下");
        if (!hasJournal) return false;
        boolean hasWrite = t.contains("写") || t.contains("创建") || t.contains("记录") || t.contains("现在就写") || t.contains("表达");
        boolean hasMood = t.contains("心情") || t.contains("情绪") || t.contains("难过") || t.contains("不开心") || t.contains("心里");
        return hasWrite || hasMood;
    }

    private static boolean hasNavigateDirective(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        return Pattern.compile("(?m)^(?:跳转|打开|进入)\\s*[:：]\\s*/[a-z0-9\\-\\/]+\\s*$", Pattern.CASE_INSENSITIVE).matcher(t).find();
    }

    static String stripMarkdown(String s) {
        String out = s == null ? "" : s;
        out = normalizeLineEndings(out);

        out = out.replaceAll("(?s)```[a-zA-Z0-9_-]*\\n(.*?)\\n```", "$1");
        out = out.replaceAll("(?m)^\\s*```\\s*$", "");

        out = out.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        out = out.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1（$2）");
        out = out.replaceAll("(?m)^\\[([^\\]]+)]\\s*:\\s*(\\S+)\\s*$", "$1（$2）");

        out = out.replace("`", "");

        out = out.replace("**", "");
        out = out.replace("__", "");
        out = out.replace("~~", "");
        out = out.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1");
        out = out.replaceAll("(?<!_)_([^_\\n]+)_(?!_)", "$1");

        out = out.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        out = out.replaceAll("(?m)^\\s*>\\s?", "");
        out = out.replaceAll("(?m)^\\s*([-*_]\\s*){3,}\\s*$", "");

        out = out.replaceAll("(?m)^\\s*[\\*\\+]\\s+", "- ");
        out = out.replaceAll("(?m)^\\s*-\\s*\\[( |x|X)]\\s+", "- ");

        return out;
    }

    static String cleanupWhitespace(String s) {
        String out = s == null ? "" : s;
        out = normalizeLineEndings(out);
        out = out.replaceAll("(?m)[ \\t]+$", "");
        out = out.replaceAll("[ \\t]{2,}", " ");
        out = out.replaceAll("\\n{3,}", "\n\n");
        return out;
    }

    static String rewritePendingTasksIfPresent(String s) {
        if (s == null) return "";
        if (!s.contains("待办任务")) return s;

        String[] lines = normalizeLineEndings(s).split("\\n");
        Pattern itemPattern = PENDING_TASK_ITEM_PATTERN;
        List<String> items = new ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            Matcher m = itemPattern.matcher(line);
            if (!m.find()) continue;
            String item = m.group(2);
            if (item == null) continue;
            String cleaned = item.trim();
            if (!cleaned.isBlank()) items.add(cleaned);
        }

        if (items.size() < 2) return s;

        List<String> out = new ArrayList<>();
        out.add("你当前有以下待办任务（显示前 " + items.size() + " 条）：");
        for (int i = 0; i < items.size(); i++) {
            String cleaned = items.get(i)
                    .replaceAll("\\s*-\\s*", " - ")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
            out.add((i + 1) + ") " + cleaned);
        }
        out.add("你可以访问对应页面进行操作：");
        return String.join("\n", out);
    }

    public static String extractJson(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        // Strip markdown code fences before extraction
        s = s.replaceAll("(?s)^```(?:json|JSON)?\\s*\\n(.*)\\n```\\s*$", "$1");
        s = s.trim();
        int first = s.indexOf('{');
        if (first < 0) return s;
        // Depth tracking: find matching closing brace. When the LLM omits the
        // outer '}', lastIndexOf would match an inner '}' and truncate the JSON.
        int depth = 0;
        boolean inString = false;
        int lastMatching = -1;
        for (int i = first; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == first || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) lastMatching = i;
                }
            }
        }
        if (lastMatching > first) return s.substring(first, lastMatching + 1).trim();
        // Unclosed — return from first to end so repair logic can fix it
        if (depth > 0) return s.substring(first).trim();
        return s;
    }
}
