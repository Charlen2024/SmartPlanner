package com.chao.agent.service;

import com.chao.agent.tool.SmartPlannerTools;
import com.chao.agent.util.AgentUserContext;
import com.chao.agent.util.ChatTextUtils;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.TaskScheduleDto;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentChatService {
    static {
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
    }
    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final Pattern NAV_PATH_PATTERN = Pattern.compile("(?:^|\\s)(/[a-z0-9\\-\\/]+)(?:\\s|$)", Pattern.CASE_INSENSITIVE);

    private final ChatModel chatModel;
    private final RedissonClient redissonClient;
    private final SmartPlannerTools smartPlannerTools;
    private final com.chao.common.client.GoalClient goalClient;
    private final PunchClient punchClient;
    private final ScheduleClient scheduleClient;
    private final Executor aiTaskExecutor;
    private final Map<Long, ReactAgent> agents = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, String>> toolCache = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static final String SYSTEM_PROMPT = """
            你是SmartPlanner学习助手。用口语化的中文回复，像朋友聊天一样自然。
            使用Markdown格式让回复清晰，格式规范：

            ## 标题分段
            - 列表项用短横线，每条一行，不堆砌成段落
            **加粗**强调关键数字和日期
            [页面名](/路径) 做导航链接

            ——周总结输出格式——
            ## 本周打卡
            - 共 X 次打卡，分布在 Y 天
            - 日期1：任务名，N次，共M分钟
            - 日期2：...

            ## 随笔回顾
            - 日期 - 心情：一句话概括

            ## 小结
            2-3句总结，不提建议（除非用户追问）

            工具使用规则：
            - 严禁在回复文本中输出任何 JSON 格式的工具调用（如 {"name": "..."}），工具调用由系统内部处理
            - 需要信息时直接使用系统工具获取，不要在文字中说"调用工具"或写出工具名和参数
            - 调用多个工具时先在思考中列出执行计划（1→2→3），再按顺序执行
            - 问"今天做什么/日程/排程"：调 listTodaySchedules（无参数），按时间段列出。排程任务≠学校课程
            - 问"课表/课程/上课安排"：调 listClasses，必须传 date 参数（今天日期），直接呈现返回文本
            - 问"本周总结/进度"：先调 listPunchRecords，再调 listRecentJournals(days=7)，数据齐后按周总结格式输出
            - 问"随笔/日记/复盘"或想查看用户的随笔记录时：调 listRecentJournals 获取随笔列表，不要用 searchPersonalData 查随笔列表
            - 问"学习建议"：先调工具拿排程和随笔数据，基于实际情况给建议，不空泛说教
            - 查询类问题必须先调工具拿到真实数据再回答，绝不编造
            - 今天没有排程就说"今天暂无排程"
            - 不重复已经说过的内容
            - 始终使用 taskTitle 字段（任务名），禁止暴露 taskId（如"任务132"）
            - 回复末尾附上页面链接：跳转: /路径

            可用页面：/ /plan /goals /journals /schedule /resources /punch /profile /games/2048

            ——工具使用示例——
            示例1：用户问"我今天有什么任务"
            助手调用 listTodaySchedules() 获取今日排程 → 按时间段列出任务

            示例2：用户问"本周学习情况怎么样"
            助手先调 listPunchRecords() 获取打卡数据 → 再调 listRecentJournals(days=7) 获取随笔 → 按周总结格式输出

            示例3：用户问"明天有什么课"
            助手调用 listClasses(date="2026-06-09") → 直接呈现课表文本

            示例4：用户问"帮我查高数相关的资料"
            助手调用 searchPersonalData(query="高数", topK=5) → 列出检索到的随笔、打卡和课程资源

            示例5：用户问"高数目标下有什么任务"
            助手调用 listGoalTasks(goalId=目标ID) → 列出该目标下的任务清单
            """;

    public void warmup(Long userId) {
        CompletableFuture.runAsync(() -> {
            try {
                ensureAgent(userId);
                log.info("Agent warmed up for userId={}", userId);
            } catch (Exception e) {
                log.warn("Agent warmup failed for userId={}", userId, e);
            }
        }, aiTaskExecutor);
    }

    private String buildStatusPrefix(Long userId) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[当前状态] ");
            try {
                Long streak = punchClient.getStreak(userId).getData();
                if (streak != null && streak > 0) sb.append("连续打卡").append(streak).append("天 | ");
            } catch (Exception ignored) {}
            try {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                String from = today + "T00:00:00";
                String to = today + "T23:59:59";
                var todayList = scheduleClient.listTaskSchedules(userId, from, to);
                if (todayList != null && todayList.getData() != null) {
                    int pending = 0;
                    for (TaskScheduleDto s : todayList.getData()) {
                        if (s != null && (s.getStatus() == null || s.getStatus() != 1)) pending++;
                    }
                    if (pending > 0) sb.append("今日待完成").append(pending).append("项 | ");
                }
            } catch (Exception ignored) {}
            try {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                String weekFrom = today.minusDays(7) + "T00:00:00";
                String weekTo = today + "T23:59:59";
                var weekList = scheduleClient.listTaskSchedules(userId, weekFrom, weekTo);
                if (weekList != null && weekList.getData() != null && !weekList.getData().isEmpty()) {
                    var data = weekList.getData();
                    long done = data.stream().filter(s -> s != null && s.getStatus() != null && s.getStatus() == 1).count();
                    sb.append("本周完成率").append(Math.round(done * 100.0 / data.size())).append("% | ");
                }
            } catch (Exception ignored) {}
            if (sb.length() > 8) {
                sb.setLength(sb.length() - 3);
                sb.append("\n\n");
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String buildJournalPrefix(Long userId, String query) {
        if (userId == null || query == null) return "";
        String q = query.trim();
        boolean listing = q.length() <= 50 &&
                (q.contains("随笔") || q.contains("日记") || q.contains("复盘") ||
                 q.contains("journal"));
        if (!listing) return "";
        try {
            com.chao.common.dto.Result<java.util.List<com.chao.common.dto.UserJournalDto>> result = goalClient.listJournals(userId, null);
            if (result == null || result.getCode() != 200) return "";
            java.util.List<com.chao.common.dto.UserJournalDto> journals = result.getData();
            if (journals == null || journals.isEmpty()) return "\n[系统] 用户暂无随笔记录。\n";
            StringBuilder sb = new StringBuilder();
            sb.append("\n[系统] 用户最近的随笔记录如下（已按时间倒序排列）：\n");
            java.util.List<com.chao.common.dto.UserJournalDto> sorted = new java.util.ArrayList<>(journals);
            sorted.sort((a, b) -> {
                java.time.LocalDateTime x = a != null ? a.getCreatedAt() : null;
                java.time.LocalDateTime y = b != null ? b.getCreatedAt() : null;
                if (x == null && y == null) return 0;
                if (x == null) return 1;
                if (y == null) return -1;
                return y.compareTo(x);
            });
            int count = 0;
            for (com.chao.common.dto.UserJournalDto j : sorted) {
                if (j == null || j.getId() == null) continue;
                sb.append("- ").append(j.getCreatedAt() != null ? j.getCreatedAt().toString() : "");
                sb.append(" | ").append(j.getMood() != null ? j.getMood() : "无");
                sb.append(" | ").append(j.getContent() != null ? j.getContent() : "");
                sb.append("\n");
                if (++count >= 20) break;
            }
            log.info("buildJournalPrefix userId={}, injected journalCount={}", userId, count);
            return sb.toString();
        } catch (Exception e) {
            log.debug("buildJournalPrefix failed userId={}: {}", userId, e.getMessage());
            return "";
        }
    }

    public String chat(Long userId, String question) {
        log.info("chat() called userId={}, qLen={}", userId, question != null ? question.length() : 0);
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

        AgentUserContext.set(userId);
        try {
            ReactAgent a = ensureAgent(userId);
            RunnableConfig config = RunnableConfig.builder().threadId("u:" + userId).build();
            String prompt = buildStatusPrefix(userId) + buildJournalPrefix(userId, q) + ChatTextUtils.todayPrefix() + q;
            AssistantMessage msg = a.call(prompt, config);
            return ChatTextUtils.extractAnswer(msg != null ? msg.getText() : null);
        } catch (Throwable e) {
            log.error("agent chat failed, userId={}, q={}", userId, q, e);
            return "助手暂时不可用，请稍后重试。";
        } finally {
            AgentUserContext.clear();
            toolCache.remove();
        }
    }

    public Flux<String> chatStream(Long userId, String question) {
        log.info("chatStream() called userId={}, qLen={}", userId, question != null ? question.length() : 0);
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

        AgentUserContext.set(userId);
        ReactAgent a;
        try {
            a = ensureAgent(userId);
        } catch (Throwable e) {
            log.error("buildAgent failed in chatStream, userId={}", userId, e);
            try { return Flux.just(chat(userId, q)); } finally { AgentUserContext.clear(); }
        }
        RunnableConfig config = RunnableConfig.builder().threadId("u:" + userId).build();
        String prompt = buildStatusPrefix(userId) + buildJournalPrefix(userId, q) + ChatTextUtils.todayPrefix() + q;
        long startNs = System.nanoTime();
        AtomicReference<String> maxPrevious = new AtomicReference<>("");
        AtomicReference<String> lastToolEvent = new AtomicReference<>("");

        Flux<Object> raw;
        try {
            raw = (Flux<Object>) (Flux<?>) a.stream(prompt, config)
                    .contextCapture().doOnNext(out -> {
                        long ms = (System.nanoTime() - startNs) / 1_000_000L;
                        String type = out == null ? "null" : out.getClass().getName();
                        log.debug("agent stream event: type={}, t={}ms", type, ms);
                    });
        } catch (Throwable e) {
            try { return Flux.just(chat(userId, q)); } finally { AgentUserContext.clear(); toolCache.remove(); }
        }
        return raw.handle((Object out, reactor.core.publisher.SynchronousSink<String> sink) -> {
                    if (out instanceof StreamingOutput<?> so) {
                        OutputType type = so.getOutputType();
                        if (type == OutputType.AGENT_TOOL_STREAMING) {
                            String toolInfo = extractToolInfo(so);
                            if (toolInfo != null && !toolInfo.equals(lastToolEvent.get())) {
                                lastToolEvent.set(toolInfo);
                                sink.next("__SP_TOOL:CALL:" + toolInfo + "__");
                            }
                            return;
                        }
                        if (type == OutputType.AGENT_TOOL_FINISHED) {
                            sink.next("__SP_TOOL:DONE__");
                            return;
                        }
                    }
                    String text = extractStreamText(out);
                    if (text == null || text.isEmpty()) return;
                    if (!lastToolEvent.get().isEmpty()) {
                        lastToolEvent.set("");
                    }

                    String maxPrev = maxPrevious.get();
                    if (text.equals(maxPrev)) return;
                    if (maxPrev != null && text.startsWith(maxPrev) && text.length() > maxPrev.length()) {
                        String delta = text.substring(maxPrev.length());
                        maxPrevious.set(text);
                        if (delta.isEmpty()) return;
                        String cleaned = ChatTextUtils.sanitizeStreamChunk(delta);
                        if (!cleaned.isEmpty()) sink.next(cleaned);
                        return;
                    }
                    if (maxPrev != null && maxPrev.startsWith(text)) return;
                    maxPrevious.set(text);
                    String cleaned = ChatTextUtils.sanitizeStreamChunk(text);
                    if (!cleaned.isEmpty()) sink.next(cleaned);
                })
                .filter(s -> s != null && !s.isEmpty())
                .doFinally(s -> { AgentUserContext.clear(); toolCache.remove(); })
                .onErrorResume(e -> {
                    AgentUserContext.set(userId);
                    try {
                        return Flux.just(chat(userId, q));
                    } finally {
                        AgentUserContext.clear();
                        toolCache.remove();
                    }
                });
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
        Matcher m = NAV_PATH_PATTERN.matcher(s);
        if (m.find()) {
            String p = m.group(1);
            if (isAllowedNavPath(p)) return p;
        }

        boolean hasVerb = s.contains("打开") || s.contains("进入") || s.contains("跳转") || s.contains("导航") || s.contains("带我去") || s.contains("去");
        boolean hasHint = s.contains("页面") || s.contains("界面") || s.contains("菜单") || s.contains("功能") || s.contains("模块");
        if (!hasVerb && !hasHint) return null;

        if (s.contains("2048") || s.contains("小游戏") || s.toLowerCase().contains("2048")) return "/games/2048";
        if (s.contains("仪表盘") || s.contains("首页") || s.contains("主页")) return "/";
        if (s.contains("导入课表") || s.contains("上传课表") || s.contains("课表") || s.toLowerCase().contains("plan")) return "/plan";
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

    private String extractStreamText(Object out) {
        if (out == null) return "";
        if (out instanceof StreamingOutput<?> so) {
            OutputType type = so.getOutputType();
            if (type == OutputType.AGENT_MODEL_STREAMING) {
                String chunk = so.chunk();
                return chunk != null ? chunk : "";
            }
            if (type == null) {
                String chunk = so.chunk();
                return chunk != null ? chunk : "";
            }
            return "";
        }
        return "";
    }

    private String extractToolInfo(StreamingOutput<?> so) {
        String chunk = so.chunk();
        if (chunk != null && !chunk.isBlank()) return chunk;
        try {
            var msg = so.message();
            if (msg != null) {
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    return text.length() > 60 ? text.substring(0, 60) + "..." : text;
                }
            }
        } catch (Exception ignored) {}
        return "工具调用中";
    }

    private ReactAgent ensureAgent(Long userId) {
        ReactAgent existing = agents.get(userId);
        if (existing != null) return existing;
        if (agents.size() > 1000) {
            var iter = agents.keySet().iterator();
            int toRemove = agents.size() / 2;
            for (int i = 0; i < toRemove && iter.hasNext(); i++) {
                iter.next();
                iter.remove();
            }
            log.info("Agent cache pruned {} entries, size now {}", toRemove, agents.size());
        }
        return agents.computeIfAbsent(userId, uid -> buildAgent(uid));
    }

    private ReactAgent buildAgent(Long userId) {
        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(smartPlannerTools)
                .build()
                .getToolCallbacks();
        ToolCallback[] toolCallbacks = new ToolCallback[rawCallbacks.length];
        for (int i = 0; i < rawCallbacks.length; i++) {
            toolCallbacks[i] = new UserContextToolCallback(rawCallbacks[i], userId);
        }
        return ReactAgent.builder()
                .name("smartplanner_agent")
                .model(chatModel)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(toolCallbacks)
                .hooks(
                    SummarizationHook.builder()
                        .model(chatModel)
                        .messagesToKeep(8)
                        .keepFirstUserMessage(true)
                        .maxTokensBeforeSummary(6000)
                        .summaryPrompt("请用中文简要总结以下对话历史，保留关键信息：用户正在处理的任务、日程安排、学习偏好、以及任何重要的上下文。")
                        .build(),
                    ModelCallLimitHook.builder()
                        .threadLimit(10)
                        .runLimit(20)
                        .build()
                )
                .saver(RedisSaver.builder()
                        .redisson(redissonClient)
                        .stateSerializer(new SpringAIStateSerializer())
                        .build())
                .build();
    }

    private class UserContextToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final Long userId;

        UserContextToolCallback(ToolCallback delegate, Long userId) {
            this.delegate = delegate;
            this.userId = userId;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return callWithCache(toolInput);
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            return callWithCache(toolInput);
        }

        private String callWithCache(String toolInput) {
            String toolName = delegate.getToolDefinition().name();
            String cacheKey = toolName + ":" + (toolInput != null ? toolInput : "");
            Map<String, String> cache = toolCache.get();
            String cached = cache.get(cacheKey);
            if (cached != null) {
                log.debug("Tool cache hit: {}", toolName);
                return cached;
            }
            AgentUserContext.set(userId);
            try {
                String result = delegate.call(toolInput);
                cache.put(cacheKey, result);
                return result;
            } finally {
                AgentUserContext.clear();
            }
        }
    }
}
