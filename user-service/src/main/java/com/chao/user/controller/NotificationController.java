package com.chao.user.controller;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.UserJournalDto;
import com.chao.common.dto.Result;
import com.chao.user.service.AppUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/user/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RabbitTemplate rabbitTemplate;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final GoalClient goalClient;
    private final AppUserService appUserService;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, String> lastLoginCareSessionKey = new ConcurrentHashMap<>();

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long userId) {
        Long uid = jwt != null ? jwt.getClaim("userId") : userId;
        if (uid == null) {
            throw new IllegalArgumentException("未授权");
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(uid, emitter));
        emitter.onTimeout(() -> removeEmitter(uid, emitter));
        emitter.onError(e -> removeEmitter(uid, emitter));

        try {
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException e) {
            removeEmitter(uid, emitter);
        }

        String sessionKey = buildSessionKey(jwt);
        CompletableFuture.runAsync(() -> publishLoginCare(uid, sessionKey));
        return emitter;
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
    }

    public Set<Long> activeUserIds() {
        return java.util.Set.copyOf(emitters.keySet());
    }

    public void pushNotification(NotificationMessage message) {
        if (message.getTs() == null) {
            message.setTs(System.currentTimeMillis());
        }
        Long userId = message.getUserId();
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            for (SseEmitter emitter : userEmitters) {
                try {
                    emitter.send(SseEmitter.event().name(message.getType()).data(message));
                } catch (IOException e) {
                    removeEmitter(userId, emitter);
                }
            }
        }
    }

    private void publishLoginCare(Long userId, String sessionKey) {
        if (userId == null || userId <= 0) return;
        if (!tryMarkLoginCareSent(userId, sessionKey)) return;

        String now = LocalDateTime.now(SHANGHAI).toString();

        Long streak = null;
        Integer total = null;
        Integer pending = null;
        String latestMood = "";
        String nextTask = "";

        try {
            Result<Long> r = punchClient.getStreak(userId);
            streak = r != null ? r.getData() : null;
        } catch (Exception ignored) {
        }

        try {
            LocalDate d = LocalDate.now(SHANGHAI);
            String from = d + "T00:00:00";
            String to = d + "T23:59:59";
            Result<List<TaskScheduleDto>> r = scheduleClient.listTaskSchedules(userId, from, to);
            List<TaskScheduleDto> list = r != null && r.getData() != null ? r.getData() : List.of();
            total = list.size();
            int p = 0;
            for (TaskScheduleDto s : list) {
                if (s == null) continue;
                if (s.getStatus() == null || s.getStatus() != 1) p++;
                if (nextTask.isBlank() && s.getTaskTitle() != null && !s.getTaskTitle().isBlank()) {
                    nextTask = s.getTaskTitle().trim();
                }
            }
            pending = p;
        } catch (Exception ignored) {
        }

        try {
            Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
            List<UserJournalDto> list = jr != null && jr.getData() != null ? jr.getData() : List.of();
            LocalDate today = LocalDate.now(SHANGHAI);
            for (UserJournalDto j : list) {
                if (j == null || j.getCreatedAt() == null) continue;
                if (j.getCreatedAt().toLocalDate().equals(today) && j.getMood() != null && !j.getMood().trim().isBlank()) {
                    latestMood = j.getMood().trim();
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        // Fetch weather summary for care context
        String weatherInfo = fetchWeatherBrief();

        boolean scheduleImported = false;
        try {
            var user = appUserService.getById(userId);
            scheduleImported = user != null && Boolean.TRUE.equals(user.getScheduleImported());
        } catch (Exception ignored) {}
        boolean isNewUser = !scheduleImported;
        String fallback1;
        String fallback2;
        if (isNewUser) {
            fallback1 = "欢迎来到 SmartPlanner！请先导入课表，然后创建你的第一个学习目标，AI 会帮你智能排程。";
            fallback2 = "小提示：点击左侧菜单的「学习计划」上传课表，「目标」页面创建学习目标后即可一键生成排程。";
        } else {
            fallback1 = buildLoginCareFallback(streak, pending, nextTask, latestMood, 1);
            fallback2 = buildLoginCareFallback(streak, pending, nextTask, latestMood, 2);
        }

        String nav;
        String prompt;
        String followPrompt;
        if (isNewUser) {
            nav = "/schedule";
            prompt = "用户刚注册，还未导入课表，没有任何学习数据。请生成一条约50字中文新用户引导消息，欢迎并引导用户先上传课表、创建学习目标。\n"
                    + "输出要求：温暖、可执行，不要Markdown，不要表情符号。\n"
                    + "引导方向：告诉用户第一步上传课表，然后创建学习目标，AI会自动排程。";
            followPrompt = "用户刚注册，还未导入课表。请生成第二条约40字中文引导提示。\n"
                    + "必须与第一条不同：优先从\"看看左侧菜单/上传课表很简单/先设一个小目标/不用着急慢慢来\"等角度出发。\n"
                    + "输出要求：温和、可执行，不要Markdown，不要表情符号。";
        } else {
            nav = chooseLoginCareNav(pending);
            String weatherLine = weatherInfo.isBlank() ? "" : ",\"weather\":\"" + weatherInfo + "\"";
            prompt = "用户刚登录。请生成一条约50字中文关怀提醒。\n"
                    + "必须引用下面数据中的至少一个具体信息（数字或任务名或心情或天气），否则视为失败。\n"
                    + "不要出现\"词汇积累/背单词/继续努力哦/一步步来吧\"等泛化话术，除非下一项任务本身与英语词汇相关。\n"
                    + "要有人文关怀：允许用户慢一点、先照顾自己，再给一个最小动作建议。\n"
                    + "输出要求：温和、可执行，不要Markdown，不要表情符号。\n\n"
                    + "数据：{"
                    + "\"time\":\"" + now + "\""
                    + ",\"streak\":" + (streak != null ? streak : 0)
                    + ",\"todayTotal\":" + (total != null ? total : 0)
                    + ",\"todayPending\":" + (pending != null ? pending : 0)
                    + ",\"nextTask\":\"" + (nextTask.isBlank() ? "无" : nextTask) + "\""
                    + ",\"latestMood\":\"" + (latestMood.isBlank() ? "无" : latestMood) + "\""
                    + weatherLine
                    + "}";
            followPrompt = "用户刚登录。请生成第二条约50字中文关怀提醒，用于跟进引导。\n"
                    + "必须引用下面数据中的至少一个具体信息（数字或任务名或心情或天气），否则视为失败。\n"
                    + "不要出现\"词汇积累/背单词/继续努力哦/一步步来吧\"等泛化话术。\n"
                    + "要与第一条明显不同：优先从\"喝水/呼吸/拉伸/整理桌面/先打开日程/降低标准/允许自己慢一点\"等角度出发。\n"
                    + "尽量不要重复\"今天还有/未完成/先做/项/先挑1项/10分钟\"这类句式（可用更小的动作代替）。\n"
                    + "输出要求：温和、可执行，不要Markdown，不要表情符号。\n\n"
                    + "数据：{"
                    + "\"time\":\"" + now + "\""
                    + ",\"streak\":" + (streak != null ? streak : 0)
                    + ",\"todayTotal\":" + (total != null ? total : 0)
                    + ",\"todayPending\":" + (pending != null ? pending : 0)
                    + ",\"nextTask\":\"" + (nextTask.isBlank() ? "无" : nextTask) + "\""
                    + ",\"latestMood\":\"" + (latestMood.isBlank() ? "无" : latestMood) + "\""
                    + weatherLine
                    + "}。倾向：给一个\"现在就能做\"的最小动作。";
        }
        try {
            NotificationMessage notif1 = new NotificationMessage();
            notif1.setUserId(userId);
            notif1.setType("AGENT_REMINDER");
            notif1.setContent(fallback1);
            notif1.setTs(System.currentTimeMillis());
            notif1.setPayload(Map.of(
                    "nav", nav,
                    "level", "info",
                    "data", Map.of(
                            "trigger", "login_care",
                            "sessionKey", sessionKey,
                            "dedupKey", "login_care:" + userId + ":" + sessionKey
                    ),
                    "ai", Map.of("userPrompt", prompt)
            ));
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif1);


            NotificationMessage notif2 = new NotificationMessage();
            notif2.setUserId(userId);
            notif2.setType("AGENT_REMINDER");
            notif2.setContent(fallback2);
            notif2.setTs(System.currentTimeMillis() + 1);
            notif2.setPayload(Map.of(
                    "nav", nav,
                    "level", "info",
                    "data", Map.of(
                            "trigger", "login_care_follow",
                            "sessionKey", sessionKey,
                            "dedupKey", "login_care_follow:" + userId + ":" + sessionKey
                    ),
                    "ai", Map.of("userPrompt", followPrompt)
            ));
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif2);
        } catch (Exception ignored) {
        }
    }

    private String chooseLoginCareNav(Integer pending) {
        if (pending != null && pending > 0) return "/schedule";
        return "/goals";
    }

    private boolean tryMarkLoginCareSent(Long userId, String sessionKey) {
        String k = sessionKey != null ? sessionKey.trim() : "";
        if (k.isBlank()) k = "unknown";
        String prev = lastLoginCareSessionKey.get(userId);
        if (k.equals(prev)) return false;
        lastLoginCareSessionKey.put(userId, k);
        return true;
    }

    private String buildSessionKey(Jwt jwt) {
        if (jwt == null) return "no-jwt";
        try {
            if (jwt.getIssuedAt() != null) {
                long iat = jwt.getIssuedAt().getEpochSecond();
                String sig = Integer.toHexString(String.valueOf(jwt.getTokenValue()).hashCode());
                return iat + "-" + sig;
            }
        } catch (Exception ignored) {
        }
        try {
            String sig = Integer.toHexString(String.valueOf(jwt.getTokenValue()).hashCode());
            return "t-" + sig;
        } catch (Exception ignored) {
        }
        return "no-token";
    }

    private String buildLoginCareFallback(Long streak, Integer pending, String nextTask, String latestMood, int variant) {
        String next = nextTask != null ? nextTask.trim() : "";
        String mood = latestMood != null ? latestMood.trim() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("登录提醒");
        sb.append(variant == 2 ? "（跟进）" : "（主）");
        sb.append("：");
        if (pending != null) sb.append("今日待完成=").append(pending).append("；");
        if (streak != null) sb.append("连续天数=").append(streak).append("；");
        if (next != null && !next.isBlank()) sb.append("下一项=").append(next).append("；");
        if (mood != null && !mood.isBlank()) sb.append("心情=").append(mood).append("；");
        return sb.toString();
    }

    private String fetchWeatherBrief() {
        try {
            String url = "https://wttr.in/Shenzhen?format=j1";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "SmartPlanner/1.0");
            conn.setInstanceFollowRedirects(true);
            byte[] bytes = conn.getInputStream().readAllBytes();
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            com.fasterxml.jackson.databind.JsonNode cc = root.path("current_condition");
            if (cc.isArray() && !cc.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode c = cc.get(0);
                double temp = parseDouble(c, "temp_C");
                String desc = c.path("weatherDesc").isArray() && !c.path("weatherDesc").isEmpty()
                        ? c.path("weatherDesc").get(0).path("value").asText().trim() : "";
                String cn = translateWeatherBrief(desc);
                if (temp > 0 || !cn.isBlank()) {
                    return cn + " " + (int) temp + "°C";
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private double parseDouble(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        com.fasterxml.jackson.databind.JsonNode n = parent.path(field);
        if (n.isNull() || n.isMissingNode()) return 0;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText().trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private String translateWeatherBrief(String desc) {
        if (desc == null || desc.isBlank()) return "";
        String d = desc.trim();
        String lower = d.toLowerCase();
        if (lower.contains("sunny") || lower.contains("clear")) return "晴";
        if (lower.contains("cloudy")) return "多云";
        if (lower.contains("overcast")) return "阴";
        if (lower.contains("fog") || lower.contains("mist")) return "雾";
        if (lower.contains("drizzle")) return "毛毛雨";
        if (lower.contains("heavy rain") || lower.contains("torrential")) return "大雨";
        if (lower.contains("rain") || lower.contains("shower")) return "有雨";
        if (lower.contains("thunder")) return "雷暴";
        if (lower.contains("snow") || lower.contains("blizzard")) return "雪";
        return "";
    }

}
