package com.chao.user.service;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.CourseResourceDto;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.UserJournalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.chao.user.entity.AppUser;
import com.chao.user.mapper.AppUserMapper;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentChatService {
    static {
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
    }

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private final ChatModel chatModel;
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final RedissonClient redissonClient;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AppUserMapper appUserMapper;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private volatile ReactAgent agent;

    private static final String SYSTEM_PROMPT = """
            你是 SmartPlanner 的学习助理（Agent）。
            你可以通过工具读取用户的目标/任务/排程/打卡/随笔/课程资源。
            你仅能读取数据，不能修改或创建任何数据。
            原则：
            1) 先问清楚用户意图（查询/建议），再决定是否调用工具。
            1) 用户意图是查询数据时，必须先调用对应工具获取真实数据，再基于数据回答，严禁跳过工具直接回复。
            2) 用户问"我有哪些目标/任务/排程/是否完成/我今天做什么/我的任务"时，必须立即调用对应工具，不得跳过。
            4) 不要建议用户修改、创建或执行任何操作（如设定已完成、添加任务、生成排程等）。这些需要用户自己到对应页面操作。
            5) 用户要学习指导时：先用工具获取今天排程，然后给出学习建议。
            5) 用户问"我今天有哪些任务/今天做什么/我的日程"时，必须立即调用 listTaskSchedules 工具获取今天排程，列出每个任务及其时间段，然后加跳转链接。只返回跳转链接不给任务列表是不合格的回答。
            7) 用户问"我的心情怎么样"或类似情绪问题时，请用 searchNotesAndCourses 工具以"心情 情绪 焦虑 开心 压力"等语义检索随笔向量库，获取最相关随笔片段后再综合分析。同时用 listRecentJournals 补充最近的 mood 字段作为参照。
            8) 当你向用户展示任务/排程/目标/随笔等信息后，必须在回答末尾追加对应页面的跳转链接，方便用户导航。
            - 仪表盘：/
            - 学习计划：/plan
            - 目标：/goals
            - 随笔：/journals
            - 日程：/schedule
            - 资源：/resources
            - 打卡：/punch
            - 画像：/profile
            - 2048：/games/2048

            跳转输出格式（用于前端识别并展示“跳转按钮”）：
            - 当用户明确要求"打开/进入/跳转到某页面"时，你需要在回答末尾追加一行：跳转: /path
            - 当你在回答中建议用户查看排程、写随笔、打卡等操作时，也请在末尾追加对应的跳转链接，例如：跳转 /schedule
            - 回答中优先用工具获取真实数据，列出近期任务时需同时关注用户问到的其他方面（如心情），不要只回答任务列表
            - 重要：你不能代替用户执行任何写操作，只能提供数据查询和页面导航。
            """;

    public String chat(Long userId, String question) {
        String q = question != null ? question.trim() : "";
        if (q.isBlank()) {
            return "你可以问我：今天先做哪个任务？/ 这周目标怎么拆？/ 我最近拖延吗？";
        }
        if (isIdentityQuery(q)) {
            return "我是 SmartPlanner 的学习助手。我会基于你近期的排程任务给出执行建议，也可以针对某个任务帮你拆解、讲解知识点、给练习与验收标准。";
        }
        String navPath = detectNavPath(q);
        if (navPath != null) {
            return buildNavigateAnswer(navPath);
        }

        currentUserId.set(userId);
        try {
            try {
                ReactAgent a = ensureAgent();
                RunnableConfig config = RunnableConfig.builder().threadId("u:" + userId).build();
                AssistantMessage msg = a.call(q, config);
                return extractAnswer(msg != null ? msg.getText() : null);
            } catch (Throwable e) {
                log.error("agent chat failed, userId={}, q={}", userId, q, e);
                return "助手暂时不可用，请稍后重试。";
            }
        } finally {
            currentUserId.remove();
        }
    }

    public Flux<String> chatStream(Long userId, String question) {
        String q = question != null ? question.trim() : "";
        if (q.isBlank()) {
            return Flux.just("你可以问我：今天先做哪个任务？/ 这周目标怎么拆？/ 我最近拖延吗？");
        }
        if (userId == null) {
            return Flux.just("未登录或登录已过期，请重新登录后再试。");
        }
        if ("流式测试".equals(q)) {
            return Flux.interval(Duration.ofMillis(200))
                    .take(30)
                    .map(i -> "流式测试片段 " + (i + 1) + " " + "—".repeat(120) + "\n");
        }
        if (isIdentityQuery(q)) {
            return Flux.just("我是 SmartPlanner 的学习助手。我会基于你近期的排程任务给出执行建议，也可以针对某个任务帮你拆解、讲解知识点、给练习与验收标准。");
        }
        String navPath = detectNavPath(q);
        if (navPath != null) {
            return Flux.just(buildNavigateAnswer(navPath));
        }

        try {
            String answer = chat(userId, q);
            if (answer == null || answer.isEmpty()) {
                return Flux.just("我暂时没想好，可以换个问法吗？");
            }
            int len = answer.length();
            if (len <= 6) {
                return Flux.just(answer);
            }
            int chunkSize = Math.max(1, len / 20);
            java.util.List<String> chunks = new java.util.ArrayList<>();
            for (int i = 0; i < len; i += chunkSize) {
                int end = Math.min(len, i + chunkSize);
                chunks.add(answer.substring(i, end));
            }
            return Flux.fromIterable(chunks)
                    .delayElements(Duration.ofMillis(30))
                    .timeout(Duration.ofSeconds(45), Flux.just(answer));
        } catch (Throwable e) {
            return Flux.just(chat(userId, q));
        }
    }

    private String buildNavigateAnswer(String path) {
        String p = path != null ? path.trim() : "";
        if (p.isBlank()) return "我没识别到要跳转的页面。";
        String title = switch (p) {
            case "/" -> "仪表盘";
            case "/plan" -> "学习计划";
            case "/goals" -> "目标";
            case "/journals" -> "随笔";
            case "/schedule" -> "日程";
            case "/resources" -> "资源";
            case "/punch" -> "打卡";
            case "/profile" -> "画像";
            case "/games/2048" -> "2048";
            default -> "页面";
        };
        return "好的，已为你准备跳转到：" + title + "\n" + "跳转: " + p;
    }

    private String detectNavPath(String q) {
        if (q == null) return null;
        String s = q.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();

        Matcher m = Pattern.compile("(?:^|\\s)(/[a-z0-9\\-\\/]+)(?:\\s|$)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            String p = m.group(1);
            if (isAllowedNavPath(p)) return p;
        }

        boolean hasVerb = s.contains("打开") || s.contains("进入") || s.contains("跳转") || s.contains("导航") || s.contains("带我去") || s.contains("去");
        boolean hasHint = s.contains("页面") || s.contains("界面") || s.contains("菜单") || s.contains("功能") || s.contains("模块");
        if (!hasVerb && !hasHint) return null;

        if (s.contains("2048") || s.contains("小游戏") || lower.contains("2048")) return "/games/2048";
        if (s.contains("仪表盘") || s.contains("首页") || s.contains("主页")) return "/";
        if (s.contains("导入课表") || s.contains("上传课表") || s.contains("课表") || lower.contains("plan")) return "/plan";
        if (s.contains("学习计划") || (s.contains("计划") && !s.contains("排程"))) return "/plan";
        if (s.contains("目标")) return "/goals";
        if (s.contains("任务")) return "/goals";
        if (s.contains("随笔") || s.contains("日记") || s.contains("复盘")) return "/journals";
        if (s.contains("日程") || s.contains("排程") || s.contains("日历")) return "/schedule";
        if (s.contains("资源") || s.contains("课程")) return "/resources";
        if (s.contains("打卡")) return "/punch";
        if (s.contains("画像")) return "/profile";

        return null;
    }

    private boolean isAllowedNavPath(String p) {
        if (p == null) return false;
        return switch (p) {
            case "/", "/plan", "/goals", "/journals", "/schedule", "/resources", "/punch", "/profile", "/games/2048" -> true;
            default -> false;
        };
    }


    private boolean isIdentityQuery(String q) {
        if (q == null) return false;
        String s = q.trim();
        if (s.isEmpty()) return false;
        if (s.contains("你是谁") || s.contains("你是") || s.contains("你叫什么")) return true;
        if ("who are you".equalsIgnoreCase(s) || "who r u".equalsIgnoreCase(s)) return true;
        return false;
    }



    private String sanitizeStreamChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        String s = chunk;
        s = s.replace("```", "");
        s = s.replace("**", "");
        s = s.replace("__", "");
        s = s.replace("~~", "");
        s = s.replace("`", "");
        s = s.replace("*", "");
        s = s.replace("_", "");
        s = s.replace("#", "");
        return s;
    }

    private String extractStreamText(Object out) {
        if (out == null) return "";
        if (out instanceof StreamingOutput<?> so) {
            String chunk = so.chunk();
            return chunk != null ? chunk : "";
        }
        if (out instanceof CharSequence cs) {
            return cs.toString();
        }

        String s = invokeNoArgStringMethod(out, "chunk");
        if (s != null) return s;
        s = invokeNoArgStringMethod(out, "getText");
        if (s != null) return s;
        s = invokeNoArgStringMethod(out, "text");
        if (s != null) return s;
        s = invokeNoArgStringMethod(out, "getContent");
        if (s != null) return s;
        s = invokeNoArgStringMethod(out, "content");
        if (s != null) return s;
        return "";
    }

    private String invokeNoArgStringMethod(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            if (m.getReturnType() != String.class) return null;
            Object v = m.invoke(target);
            return v != null ? (String) v : "";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ReactAgent ensureAgent() {
        ReactAgent a = this.agent;
        if (a != null) return a;
        synchronized (this) {
            if (this.agent != null) return this.agent;
            this.agent = buildAgent(() -> currentUserId.get());
            return this.agent;
        }
    }

    private ReactAgent buildAgent(Supplier<Long> userIdSupplier) {
        SmartPlannerTools tools = new SmartPlannerTools(
                goalClient,
                scheduleClient,
                punchClient,
                resourceClient,
                redissonClient,
                vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null,
                userIdSupplier
        );
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();
        return ReactAgent.builder()
                .name("smartplanner_agent")
                .model(chatModel)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(toolCallbacks)
                .hooks(new MessageTrimmingHook())
                .saver(RedisSaver.builder()
                        .redisson(redissonClient)
                        .stateSerializer(new SpringAIStateSerializer())
                        .build())
                .build();
    }

    private String safeText(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    private String extractAnswer(String text) {
        if (text == null) return "";
        String s = normalizeLineEndings(text).trim();
        if (s.isBlank()) return "";
        s = stripMarkdown(s);
        s = cleanupWhitespace(s);
        s = rewritePendingTasksIfPresent(s);
        s = maybeAppendNavHints(s);
        return s.trim();
    }

    private String maybeAppendNavHints(String s) {
        if (s == null) return "";
        String out = s.trim();
        if (out.isBlank()) return out;
        if (shouldSuggestJournalJump(out) && !hasNavigateDirective(out)) {
            return out + "\n跳转: /journals";
        }
        return out;
    }

    private boolean shouldSuggestJournalJump(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        boolean hasJournal = t.contains("随笔") || t.contains("日记") || t.contains("记录一下");
        if (!hasJournal) return false;
        boolean hasWrite = t.contains("写") || t.contains("创建") || t.contains("记录") || t.contains("现在就写") || t.contains("表达");
        boolean hasMood = t.contains("心情") || t.contains("情绪") || t.contains("难过") || t.contains("不开心") || t.contains("心里");
        return hasWrite || hasMood;
    }

    private boolean hasNavigateDirective(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        return Pattern.compile("(?m)^(?:跳转|打开|进入)\\s*[:：]\\s*/[a-z0-9\\-\\/]+\\s*$", Pattern.CASE_INSENSITIVE).matcher(t).find();
    }

    public List<String> splitForStream(String answer) {
        String s = answer == null ? "" : answer;
        if (s.isBlank()) return List.of();
        int chunkSize = 120;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += chunkSize) {
            out.add(s.substring(i, Math.min(s.length(), i + chunkSize)));
        }
        return out;
    }

    private String normalizeLineEndings(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String stripMarkdown(String s) {
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

    private String cleanupWhitespace(String s) {
        String out = s == null ? "" : s;
        out = normalizeLineEndings(out);
        out = out.replaceAll("(?m)[ \\t]+$", "");
        out = out.replaceAll("[ \\t]{2,}", " ");
        out = out.replaceAll("\\n{3,}", "\n\n");
        return out;
    }

    private String rewritePendingTasksIfPresent(String s) {
        if (s == null) return "";
        if (!s.contains("待办任务")) return s;

        String[] lines = normalizeLineEndings(s).split("\\n");
        Pattern itemPattern = Pattern.compile("^\\s*(\\d+)\\s*\\.\\s*(.+)$");
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

    @HookPositions({HookPosition.BEFORE_MODEL})
    static class MessageTrimmingHook extends MessagesModelHook {
        private static final int MAX_MESSAGES = 24;

        @Override
        public String getName() {
            return "message_trimming";
        }

        @Override
        public AgentCommand beforeModel(List<org.springframework.ai.chat.messages.Message> previousMessages, RunnableConfig config) {
            if (previousMessages == null || previousMessages.isEmpty()) {
                return new AgentCommand(previousMessages);
            }

            List<org.springframework.ai.chat.messages.Message> sanitized = sanitizeToolMessageOrdering(previousMessages);
            if (sanitized.size() <= MAX_MESSAGES) {
                return new AgentCommand(sanitized, UpdatePolicy.REPLACE);
            }

            org.springframework.ai.chat.messages.Message first = sanitized.get(0);
            List<org.springframework.ai.chat.messages.Message> tail = sanitized.subList(sanitized.size() - (MAX_MESSAGES - 1), sanitized.size());
            List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
            out.add(first);
            out.addAll(tail);
            out = sanitizeToolMessageOrdering(out);
            return new AgentCommand(out, UpdatePolicy.REPLACE);
        }

        private static List<org.springframework.ai.chat.messages.Message> sanitizeToolMessageOrdering(List<org.springframework.ai.chat.messages.Message> in) {
            if (in == null || in.isEmpty()) return in;

            List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>(in.size());
            boolean allowToolResponses = false;

            for (org.springframework.ai.chat.messages.Message m : in) {
                if (m == null) continue;

                if (hasToolCalls(m)) {
                    allowToolResponses = true;
                    out.add(m);
                    continue;
                }

                if (isToolRoleMessage(m)) {
                    if (allowToolResponses) {
                        out.add(m);
                    }
                    continue;
                }

                allowToolResponses = false;
                out.add(m);
            }

            return out;
        }

        private static boolean hasToolCalls(org.springframework.ai.chat.messages.Message m) {
            if (!(m instanceof AssistantMessage am)) return false;
            try {
                Object toolCalls = am.getToolCalls();
                if (toolCalls instanceof List<?> list) {
                    return !list.isEmpty();
                }
                return toolCalls != null;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean isToolRoleMessage(org.springframework.ai.chat.messages.Message m) {
            try {
                Object mt = m.getMessageType();
                if (mt != null && "TOOL".equalsIgnoreCase(String.valueOf(mt))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            String name = m.getClass().getName().toLowerCase();
            if (m instanceof AssistantMessage) return false;
            return name.contains("tool") && name.contains("message");
        }
    }


    /** 每日凌晨3点全量用户索引入库，跳过已索引用户 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledFullUserRagIndex() {
        try {
            java.util.List<AppUser> users = appUserMapper.selectList(null);
            if (users == null || users.isEmpty()) return;
            int indexed = 0;
            for (AppUser user : users) {
                try {
                    ensureUserRagIndexed(user.getId());
                    indexed++;
                } catch (Exception ignored) {
                }
            }
            log.info("Scheduled RAG index: {}/{} users indexed", indexed, users.size());
        } catch (Exception e) {
            log.warn("Scheduled RAG index failed: {}", e.getMessage());
        }
    }

    /** 公开索引方法：InfoController 等调用 */
    public void ensureUserRagIndexed(Long userId) {
        if (userId == null) return;
        if (redissonClient == null) return;
        VectorStore vs = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        if (vs == null) return;
        String key = "sp:rag:indexed:u:" + userId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        String v = bucket.get();
        if ("1".equals(v)) return;

        java.util.List<Document> docs = new java.util.ArrayList<>();

        try {
            Result<java.util.List<GoalDto>> gr = goalClient.listGoals(userId);
            java.util.List<GoalDto> goals = gr != null ? gr.getData() : java.util.List.of();
            if (goals != null) {
                for (GoalDto g : goals) {
                    if (g == null || g.getId() == null) continue;
                    String text = (g.getTitle() == null ? "" : g.getTitle()) + ". " + (g.getDescription() == null ? "" : g.getDescription());
                    if (text.isBlank()) continue;
                    java.util.Map<String, Object> meta = new java.util.HashMap<>();
                    meta.put("type", "goal");
                    meta.put("userId", userId);
                    meta.put("goalId", g.getId());
                    meta.put("title", g.getTitle());
                    docs.add(new Document("goal:" + userId + ":" + g.getId(), text, meta));
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Result<java.util.List<GoalTaskDto>> tr = goalClient.getPendingTasks(userId);
            java.util.List<GoalTaskDto> tasks = tr != null ? tr.getData() : java.util.List.of();
            if (tasks != null) {
                for (GoalTaskDto t : tasks) {
                    if (t == null || t.getId() == null) continue;
                    String text = (t.getTitle() == null ? "" : t.getTitle()) + ". " + (t.getDescription() == null ? "" : t.getDescription());
                    if (text.isBlank()) continue;
                    java.util.Map<String, Object> meta = new java.util.HashMap<>();
                    meta.put("type", "task");
                    meta.put("userId", userId);
                    meta.put("taskId", t.getId());
                    meta.put("goalId", t.getGoalId());
                    meta.put("title", t.getTitle());
                    docs.add(new Document("task:" + userId + ":" + t.getId(), text, meta));
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Result<java.util.List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
            java.util.List<UserJournalDto> journals = jr != null ? jr.getData() : java.util.List.of();
            if (journals != null) {
                for (UserJournalDto j : journals) {
                    if (j == null || j.getId() == null) continue;
                    String text = j.getContent();
                    if (text == null || text.isBlank()) continue;
                    java.util.Map<String, Object> meta = new java.util.HashMap<>();
                    meta.put("type", "journal");
                    meta.put("userId", userId);
                    meta.put("journalId", j.getId());
                    meta.put("goalId", j.getGoalId());
                    meta.put("title", "随笔");
                    docs.add(new Document("journal:" + userId + ":" + j.getId(), text, meta));
                    if (docs.size() >= 200) break;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Result<java.util.List<CourseResourceDto>> rr = resourceClient.listResources(null);
            java.util.List<CourseResourceDto> resources = rr != null ? rr.getData() : java.util.List.of();
            if (resources != null) {
                for (CourseResourceDto r : resources) {
                    if (r == null || r.getId() == null) continue;
                    String text = (r.getTitle() == null ? "" : r.getTitle()) + "\n" + (r.getContentSummary() == null ? "" : r.getContentSummary());
                    if (text.isBlank()) continue;
                    java.util.Map<String, Object> meta = new java.util.HashMap<>();
                    meta.put("type", "course");
                    meta.put("resourceId", r.getId());
                    meta.put("title", r.getTitle());
                    meta.put("platform", r.getPlatform());
                    meta.put("url", r.getSourceUrl());
                    meta.put("topic", r.getTopic());
                    docs.add(new Document("course:" + r.getId(), text, meta));
                    if (docs.size() >= 500) break;
                }
            }
        } catch (Exception ignored) {
        }

        if (!docs.isEmpty()) {
            addDocsInBatches(vs, docs, 20);
            bucket.set("1");
            bucket.expire(java.time.Duration.ofDays(3));
            return;
        }
        bucket.set("0");
        bucket.expire(java.time.Duration.ofHours(6));
    }

    private void addDocsInBatches(VectorStore vectorStore, java.util.List<Document> docs, int batchSize) {
        if (vectorStore == null || docs == null || docs.isEmpty()) return;
        int size = Math.max(1, Math.min(batchSize, 25));
        for (int i = 0; i < docs.size(); i += size) {
            java.util.List<Document> part = docs.subList(i, Math.min(docs.size(), i + size));
            vectorStore.add(part);
        }
    }

    static class SmartPlannerTools {
        private final GoalClient goalClient;
        private final ScheduleClient scheduleClient;
        private final PunchClient punchClient;
        private final ResourceClient resourceClient;
        private final RedissonClient redissonClient;
        private final VectorStore vectorStore;
        private final Supplier<Long> userIdSupplier;

        SmartPlannerTools(
                GoalClient goalClient,
                ScheduleClient scheduleClient,
                PunchClient punchClient,
                ResourceClient resourceClient,
                RedissonClient redissonClient,
                VectorStore vectorStore,
                Supplier<Long> userIdSupplier) {
            this.goalClient = goalClient;
            this.scheduleClient = scheduleClient;
            this.punchClient = punchClient;
            this.resourceClient = resourceClient;
            this.redissonClient = redissonClient;
            this.vectorStore = vectorStore;
            this.userIdSupplier = userIdSupplier;
        }

        @Tool(description = "查询当前用户的目标列表。返回 JSON 数组，每个元素包含 id、title、description、deadline、status。")
        public List<Map<String, Object>> listGoals() {
            Long userId = requireUserId();
            Result<List<GoalDto>> res = goalClient.listGoals(userId);
            List<GoalDto> goals = res != null ? res.getData() : List.of();
            goals = goals == null ? List.of() : goals;
            List<Map<String, Object>> out = new ArrayList<>();
            for (GoalDto g : goals) {
                if (g == null || g.getId() == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", g.getId());
                m.put("title", g.getTitle());
                m.put("description", g.getDescription());
                m.put("status", g.getStatus());
                m.put("deadline", g.getDeadline());
                out.add(m);
                if (out.size() >= 20) break;
            }
            return out;
        }

        @Tool(description = "查询当前用户的目标级待办任务定义（goal tasks，不含排程时间，仅含任务标题/描述/优先级等）。注意：此工具不返回排程信息！当用户问【今天有什么任务/今天做什么/我的日程】时必须改用 listTaskSchedules。返回 JSON 数组，每个元素包含 id、goalId、title、description、estimatedMinutes、priority、status。")
        public List<Map<String, Object>> listPendingTasks() {
            Long userId = requireUserId();
            Result<List<GoalTaskDto>> res = goalClient.getPendingTasks(userId);
            List<GoalTaskDto> tasks = res != null ? res.getData() : List.of();
            tasks = tasks == null ? List.of() : tasks;
            List<Map<String, Object>> out = new ArrayList<>();
            for (GoalTaskDto t : tasks) {
                if (t == null || t.getId() == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", t.getId());
                m.put("goalId", t.getGoalId());
                m.put("title", t.getTitle());
                m.put("description", t.getDescription());
                m.put("estimatedMinutes", t.getEstimatedMinutes());
                m.put("priority", t.getPriority());
                m.put("status", t.getStatus());
                out.add(m);
                if (out.size() >= 30) break;
            }
            return out;
        }

        @Tool(description = "查询某个目标下的任务列表。用于用户问“这个目标有哪些任务”。")
        public List<Map<String, Object>> listGoalTasks(@ToolParam(description = "目标ID") Long goalId) {
            Long userId = requireUserId();
            if (goalId == null) return List.of();
            Result<List<GoalTaskDto>> res = goalClient.listTasks(goalId, userId);
            List<GoalTaskDto> tasks = res != null ? res.getData() : List.of();
            tasks = tasks == null ? List.of() : tasks;
            List<Map<String, Object>> out = new ArrayList<>();
            for (GoalTaskDto t : tasks) {
                if (t == null || t.getId() == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", t.getId());
                m.put("goalId", t.getGoalId());
                m.put("title", t.getTitle());
                m.put("description", t.getDescription());
                m.put("estimatedMinutes", t.getEstimatedMinutes());
                m.put("priority", t.getPriority());
                m.put("status", t.getStatus());
                out.add(m);
                if (out.size() >= 50) break;
            }
            return out;
        }

        @Tool(description = "查询当前用户已排程的任务列表（task_schedule，包含具体开始/结束时间）。当用户询问【今天有什么任务/我的日程/排程/今天做什么】时使用。返回每个任务的具体时间段。")
        public List<Map<String, Object>> listTaskSchedules(
                @ToolParam(description = "起始时间（ISO-8601，如 2026-05-22T00:00:00）", required = false) @Nullable String from,
                @ToolParam(description = "结束时间（ISO-8601，如 2026-05-22T23:59:59）", required = false) @Nullable String to) {
            Long userId = requireUserId();
            Result<List<TaskScheduleDto>> res = scheduleClient.listTaskSchedules(userId, from, to);
            List<TaskScheduleDto> list = res != null ? res.getData() : List.of();
            list = list == null ? List.of() : list;
            List<Map<String, Object>> out = new ArrayList<>();
            for (TaskScheduleDto s : list) {
                if (s == null || s.getTaskId() == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", s.getId());
                m.put("taskId", s.getTaskId());
                m.put("taskTitle", s.getTaskTitle());
                m.put("startTime", s.getStartTime());
                m.put("endTime", s.getEndTime());
                m.put("status", s.getStatus());
                out.add(m);
                if (out.size() >= 50) break;
            }
            return out;
        }

        @Tool(description = "查询当前用户的打卡记录列表。用于判断任务是否真的完成。")
        public List<Map<String, Object>> listPunchRecords(
                @ToolParam(description = "任务ID，可空", required = false) @Nullable Long taskId,
                @ToolParam(description = "起始时间（ISO-8601），可空", required = false) @Nullable String from,
                @ToolParam(description = "结束时间（ISO-8601），可空", required = false) @Nullable String to) {
            Long userId = requireUserId();
            Result<List<PunchRecordDto>> res = punchClient.listRecords(userId, taskId, from, to);
            List<PunchRecordDto> list = res != null ? res.getData() : List.of();
            list = list == null ? List.of() : list;
            List<Map<String, Object>> out = new ArrayList<>();
            for (PunchRecordDto p : list) {
                if (p == null || p.getId() == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.getId());
                m.put("taskId", p.getTaskId());
                m.put("startedAt", p.getStartedAt());
                m.put("endedAt", p.getEndedAt());
                m.put("durationSeconds", p.getDurationSeconds());
                m.put("aiAuditResult", p.getAiAuditResult());
                m.put("aiAuditRemark", p.getAiAuditRemark());
                out.add(m);
                if (out.size() >= 60) break;
            }
            return out;
        }

        // @Tool(description = "创建一条随笔/日记记录。写操作已禁用，仅保留方法供内部使用")
        public String createJournal(
                @ToolParam(description = "随笔内容") String content,
                @ToolParam(description = "关联目标ID，可空", required = false) @Nullable Long goalId,
                @ToolParam(description = "心情标签，可空", required = false) @Nullable String mood) {
            Long userId = requireUserId();
            String c = content == null ? "" : content.trim();
            if (c.isBlank()) return "内容为空";
            Result<String> res = goalClient.createJournal(userId, goalId, c, mood);
            if (res != null && res.getCode() == 200) {
                try {
                    indexSingleJournal(userId, goalId, c);
                } catch (Exception ignored) {
                }
                return "已记录";
            }
            return "记录失败";
        }

        @Tool(description = "查询最近 N 天的随笔列表（包含心情 mood）。用于复盘/总结/找出情绪与学习模式。返回 JSON 数组，每个元素包含 id、goalId、createdAt、mood、text。")
        public List<Map<String, Object>> listRecentJournals(
                @ToolParam(description = "最近天数，1-30，可空，默认 7", required = false) @Nullable Integer days,
                @ToolParam(description = "目标ID，可空", required = false) @Nullable Long goalId,
                @ToolParam(description = "最多返回条数 1-80，可空，默认 30", required = false) @Nullable Integer limit) {
            Long userId = requireUserId();
            int d = days == null ? 7 : Math.max(1, Math.min(days, 30));
            int lim = limit == null ? 30 : Math.max(1, Math.min(limit, 80));
            LocalDateTime from = LocalDateTime.now().minusDays(d);

            Result<List<UserJournalDto>> res = goalClient.listJournals(userId, goalId);
            List<UserJournalDto> list = res != null ? res.getData() : List.of();
            list = list == null ? List.of() : list;

            List<UserJournalDto> filtered = new ArrayList<>();
            for (UserJournalDto j : list) {
                if (j == null || j.getId() == null) continue;
                LocalDateTime at = j.getCreatedAt();
                if (at == null || at.isBefore(from)) continue;
                filtered.add(j);
            }
            filtered.sort((a, b) -> {
                LocalDateTime x = a != null ? a.getCreatedAt() : null;
                LocalDateTime y = b != null ? b.getCreatedAt() : null;
                if (x == null && y == null) return 0;
                if (x == null) return 1;
                if (y == null) return -1;
                return y.compareTo(x);
            });

            List<Map<String, Object>> out = new ArrayList<>();
            for (UserJournalDto j : filtered) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", j.getId());
                m.put("goalId", j.getGoalId());
                m.put("createdAt", j.getCreatedAt());
                m.put("mood", j.getMood());
                m.put("text", safeSnippet(j.getContent()));
                out.add(m);
                if (out.size() >= lim) break;
            }
            return out;
        }

        // @Tool(description = "为某个目标添加一个学习任务。写操作已禁用，仅保留方法供内部使用")
        public String addTask(
                @ToolParam(description = "目标ID") Long goalId,
                @ToolParam(description = "任务标题") String title,
                @ToolParam(description = "任务描述，可空", required = false) @Nullable String description,
                @ToolParam(description = "优先级 0-2，可空", required = false) @Nullable Integer priority,
                @ToolParam(description = "预计分钟数，可空", required = false) @Nullable Integer estimatedMinutes) {
            Long userId = requireUserId();
            if (goalId == null) return "缺少 goalId";
            String t = title == null ? "" : title.trim();
            if (t.isBlank()) return "任务标题不能为空";
            Result<GoalTaskDto> res = goalClient.createTask(goalId, userId, null, t, description, priority != null ? priority : 0, estimatedMinutes, null);
            if (res != null && res.getCode() == 200 && res.getData() != null && res.getData().getId() != null) {
                return "已添加 taskId=" + res.getData().getId();
            }
            if (res != null && res.getMessage() != null && !res.getMessage().isBlank()) {
                return res.getMessage();
            }
            return "添加失败";
        }

        @Tool(description = "混合检索：从用户随笔 + 课程资源中检索与 query 最相关的内容。优先走向量检索（RedisStack），并在必要时用关键词检索补充。返回 JSON 数组。")
        public List<Map<String, Object>> searchNotesAndCourses(
                @ToolParam(description = "检索关键词/问题") String query,
                @ToolParam(description = "返回条数 1-10", required = false) @Nullable Integer topK) {
            Long userId = requireUserId();
            String q = query == null ? "" : query.trim();
            if (q.isBlank()) return List.of();
            int k = topK == null ? 6 : Math.max(1, Math.min(topK, 10));

            List<Map<String, Object>> out = new ArrayList<>();

            try {
                ensureUserRagIndexed(userId);
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(k).build());
                if (docs != null) {
                    for (Document d : docs) {
                        if (d == null) continue;
                        Map<String, Object> m = new HashMap<>();
                        Object type = d.getMetadata() != null ? d.getMetadata().get("type") : null;
                        m.put("type", type);
                        m.put("text", safeSnippet(d.getText()));
                        if (d.getMetadata() != null) {
                            m.put("title", d.getMetadata().get("title"));
                            m.put("url", d.getMetadata().get("url"));
                            m.put("platform", d.getMetadata().get("platform"));
                            m.put("topic", d.getMetadata().get("topic"));
                            m.put("goalId", d.getMetadata().get("goalId"));
                        }
                        out.add(m);
                        if (out.size() >= k) break;
                    }
                }
            } catch (Exception ignored) {
            }

            if (out.size() < k) {
                int remain = k - out.size();
                out.addAll(keywordFallback(userId, q, remain));
            }
            return out;
        }

        // @Tool(description = "触发日计划排程 job。写操作已禁用，仅保留方法供内部使用")
        public Map<String, Object> startDailyPlanJob(
                @ToolParam(description = "日期（YYYY-MM-DD）") String date,
                @ToolParam(description = "模式：replace 或 merge", required = false) @Nullable String mode,
                @ToolParam(description = "目标ID，可空", required = false) @Nullable Long goalId,
                @ToolParam(description = "任务ID列表，可空", required = false) @Nullable List<Long> taskIds) {
            Long userId = requireUserId();
            com.chao.common.dto.DailyPlanJobStartRequest req = new com.chao.common.dto.DailyPlanJobStartRequest();
            req.setDate(date == null || date.isBlank() ? null : LocalDate.parse(date.trim()));
            req.setMode(mode);
            req.setGoalId(goalId);
            req.setTaskIds(taskIds);
            Result<com.chao.common.dto.DailyPlanJobStartResponse> res = scheduleClient.startDailyPlanJob(userId, req);
            String jobId = res != null && res.getData() != null ? res.getData().getJobId() : null;
            Map<String, Object> out = new HashMap<>();
            out.put("jobId", jobId);
            return out;
        }

        // @Tool(description = "查询日计划排程 job 状态。写操作已禁用，仅保留方法供内部使用")
        public Map<String, Object> getDailyPlanJobStatus(@ToolParam(description = "jobId") String jobId) {
            Long userId = requireUserId();
            Result<com.chao.common.dto.DailyPlanJobStatusResponse> res = scheduleClient.getDailyPlanJobStatus(userId, jobId);
            Map<String, Object> out = new HashMap<>();
            if (res != null && res.getData() != null) {
                out.put("status", res.getData().getStatus());
                out.put("stage", res.getData().getStage());
                out.put("progress", res.getData().getProgress());
                out.put("message", res.getData().getMessage());
                out.put("error", res.getData().getError());
            }
            return out;
        }

        private Long requireUserId() {
            Long userId = userIdSupplier != null ? userIdSupplier.get() : null;
            if (userId == null) throw new IllegalStateException("userId missing");
            return userId;
        }

        private void ensureUserRagIndexed(Long userId) {
            if (userId == null) return;
            if (redissonClient == null || vectorStore == null) return;
            String key = "sp:rag:indexed:u:" + userId;
            RBucket<String> bucket = redissonClient.getBucket(key);
            String v = bucket.get();
            if ("1".equals(v)) return;

            List<Document> docs = new ArrayList<>();

            try {
                Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
                List<UserJournalDto> journals = jr != null ? jr.getData() : List.of();
                if (journals != null) {
                    for (UserJournalDto j : journals) {
                        if (j == null || j.getId() == null) continue;
                        String text = j.getContent();
                        if (text == null || text.isBlank()) continue;
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("type", "journal");
                        meta.put("userId", userId);
                        meta.put("journalId", j.getId());
                        meta.put("goalId", j.getGoalId());
                        meta.put("title", "随笔");
                        docs.add(new Document("journal:" + userId + ":" + j.getId(), text, meta));
                        if (docs.size() >= 200) break;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Result<List<CourseResourceDto>> rr = resourceClient.listResources(null);
                List<CourseResourceDto> resources = rr != null ? rr.getData() : List.of();
                if (resources != null) {
                    for (CourseResourceDto r : resources) {
                        if (r == null || r.getId() == null) continue;
                        String text = (r.getTitle() == null ? "" : r.getTitle()) + "\n" + (r.getContentSummary() == null ? "" : r.getContentSummary());
                        if (text.isBlank()) continue;
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("type", "course");
                        meta.put("resourceId", r.getId());
                        meta.put("title", r.getTitle());
                        meta.put("platform", r.getPlatform());
                        meta.put("url", r.getSourceUrl());
                        meta.put("topic", r.getTopic());
                        docs.add(new Document("course:" + r.getId(), text, meta));
                        if (docs.size() >= 500) break;
                    }
                }
            } catch (Exception ignored) {
            }

            if (!docs.isEmpty()) {
                addDocsInBatches(vectorStore, docs, 20);
                bucket.set("1");
                bucket.expire(java.time.Duration.ofDays(3));
                return;
            }
            bucket.set("0");
            bucket.expire(java.time.Duration.ofHours(6));
        }

        private void addDocsInBatches(VectorStore vectorStore, List<Document> docs, int batchSize) {
            if (vectorStore == null || docs == null || docs.isEmpty()) return;
            int size = Math.max(1, Math.min(batchSize, 25));
            for (int i = 0; i < docs.size(); i += size) {
                List<Document> part = docs.subList(i, Math.min(docs.size(), i + size));
                vectorStore.add(part);
            }
        }

        private void indexSingleJournal(Long userId, Long goalId, String content) {
            if (vectorStore == null) return;
            if (userId == null) return;
            String text = content == null ? "" : content.trim();
            if (text.isBlank()) return;
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "journal");
            meta.put("userId", userId);
            meta.put("goalId", goalId);
            meta.put("title", "随笔");
            vectorStore.add(List.of(new Document("journal:" + userId + ":" + System.currentTimeMillis(), text, meta)));
        }

        private List<Map<String, Object>> keywordFallback(Long userId, String q, int limit) {
            if (limit <= 0) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();

            try {
                Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
                List<UserJournalDto> journals = jr != null ? jr.getData() : List.of();
                if (journals != null) {
                    for (UserJournalDto j : journals) {
                        if (j == null) continue;
                        String text = j.getContent();
                        if (text != null && text.contains(q)) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("type", "journal");
                            m.put("goalId", j.getGoalId());
                            m.put("text", safeSnippet(text));
                            out.add(m);
                            if (out.size() >= limit) return out;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Result<List<ResourceClient.CourseResource>> rr = resourceClient.searchOnlineCourses(q);
                List<ResourceClient.CourseResource> list = rr != null ? rr.getData() : List.of();
                if (list != null) {
                    for (ResourceClient.CourseResource r : list) {
                        if (r == null) continue;
                        Map<String, Object> m = new HashMap<>();
                        m.put("type", "course");
                        m.put("title", r.getTitle());
                        m.put("platform", r.getPlatform());
                        m.put("url", r.getUrl());
                        m.put("text", safeSnippet(r.getSummary()));
                        out.add(m);
                        if (out.size() >= limit) return out;
                    }
                }
            } catch (Exception ignored) {
            }

            return out;
        }

        private String safeSnippet(String s) {
            if (s == null) return "";
            String x = s.replace("\n", " ").replace("\r", " ").trim();
            if (x.length() <= 180) return x;
            return x.substring(0, 180);
        }
    }
}