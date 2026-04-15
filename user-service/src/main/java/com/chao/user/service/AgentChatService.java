package com.chao.user.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.TaskScheduleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentChatService {
    private final OpenAiCompatClient openAiCompatClient;
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;

    public String chat(Long userId, String question) {
        String q = question != null ? question.trim() : "";
        if (q.isBlank()) {
            return "你可以问我：今天先做哪个任务？/ 这周目标怎么拆？/ 我最近拖延吗？";
        }

        List<GoalDto> goals = safe(goalClient.listGoals(userId).getData());
        List<GoalTaskDto> pending = safe(goalClient.getPendingTasks(userId).getData());

        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now().plusDays(7);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        String prompt = buildPrompt(goals, pending, schedules, q);
        String text = openAiCompatClient.complete(prompt);
        return extractAnswer(text);
    }

    private String buildPrompt(List<GoalDto> goals, List<GoalTaskDto> pending, List<TaskScheduleDto> schedules, String question) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        StringBuilder sb = new StringBuilder();
        sb.append("你是学习计划助手（Agent）。你需要根据用户最近目标、待办任务与最近排程，回答用户问题。\n");
        sb.append("要求：中文；回答尽量短；直接给出核心结论或建议；不需要废话；不要输出 Markdown。\n\n");

        sb.append("用户目标（最多列出 5 个）：\n");
        int gCount = 0;
        for (GoalDto g : goals) {
            if (g == null || g.getId() == null) continue;
            gCount++;
            sb.append("- goalId=").append(g.getId()).append(", title=").append(safeText(g.getTitle()));
            if (g.getDeadline() != null) {
                sb.append(", deadline=").append(fmt.format(g.getDeadline()));
            }
            sb.append(", status=").append(g.getStatus()).append("\n");
            if (gCount >= 5) break;
        }

        sb.append("\n待办任务（最多列出 10 个）：\n");
        int tCount = 0;
        for (GoalTaskDto t : pending) {
            if (t == null || t.getId() == null) continue;
            tCount++;
            sb.append("- taskId=").append(t.getId()).append(", title=").append(safeText(t.getTitle()));
            if (t.getGoalId() != null) sb.append(", goalId=").append(t.getGoalId());
            if (t.getEstimatedMinutes() != null) sb.append(", estimatedMinutes=").append(t.getEstimatedMinutes());
            sb.append(", status=").append(t.getStatus()).append("\n");
            if (tCount >= 10) break;
        }

        sb.append("\n最近排程（最多列出 10 条）：\n");
        int sCount = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            sCount++;
            sb.append("- ").append(fmt.format(s.getStartTime())).append(" - ").append(fmt.format(s.getEndTime()));
            sb.append(", taskId=").append(s.getTaskId());
            sb.append(", status=").append(s.getStatus());
            sb.append(", title=").append(safeText(s.getTaskTitle()));
            sb.append("\n");
            if (sCount >= 10) break;
        }

        sb.append("\n用户问题：").append(safeText(question)).append("\n");

        return sb.toString();
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
        String s = text.trim();
        if (s.isBlank()) return "";
        return s;
    }
}
