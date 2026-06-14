package com.chao.user.controller;

import com.chao.common.client.AgentAdviceClient;
import com.chao.common.client.GoalClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class AssistantController {

    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final AgentAdviceClient agentAdviceClient;
    private final UserControllerSupport support;

    @GetMapping("/assistant/advice")
    public Result<String> assistantAdvice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        String d = date != null ? date.trim() : "";
        if (d.isBlank()) {
            d = LocalDate.now().toString();
        }
        String from = d + "T00:00:00";
        String to = d + "T23:59:59";

        List<TaskScheduleDto> schedules = scheduleClient.listTaskSchedules(uid, from, to).getData();
        schedules = schedules != null ? schedules : List.of();
        if (schedules.isEmpty()) {
            return Result.success("今天暂无排程任务");
        }

        List<Long> taskIds = schedules.stream()
                .map(TaskScheduleDto::getTaskId)
                .filter(x -> x != null && x > 0)
                .distinct()
                .toList();

        List<GoalTaskDto> tasks = goalClient.getTasksByIds(taskIds).getData();
        tasks = tasks != null ? tasks : List.of();
        Map<Long, GoalTaskDto> taskMap = new HashMap<>();
        for (GoalTaskDto t : tasks) {
            if (t != null && t.getId() != null) taskMap.put(t.getId(), t);
        }

        List<ScheduleAdviceItem> items = new ArrayList<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getTaskId() == null) continue;
            GoalTaskDto t = taskMap.get(s.getTaskId());
            ScheduleAdviceItem it = new ScheduleAdviceItem();
            it.setTaskId(s.getTaskId());
            it.setTitle(support.safeText(s.getTaskTitle() != null ? s.getTaskTitle() : (t != null ? t.getTitle() : null)));
            it.setDescription(support.safeText(t != null ? t.getDescription() : null));
            it.setStartTime(s.getStartTime() != null ? s.getStartTime().toString() : null);
            it.setEndTime(s.getEndTime() != null ? s.getEndTime().toString() : null);
            it.setTimeBudgetMinutes(support.budgetMinutes(s.getStartTime(), s.getEndTime()));
            items.add(it);
        }

        String moodHint = support.buildMoodHint(uid);
        ScheduleAdviceRequest req = new ScheduleAdviceRequest();
        req.setItems(items);
        req.setMoodHint(moodHint);
        ScheduleAdviceResponse advice = agentAdviceClient.adviseSchedules(req).getData();
        String header = support.safeText(advice != null ? advice.getHeader() : null);
        if (header.isBlank()) {
            header = "今天的小建议：先把每个任务的第一步做完；每项完成到[能复述/能交付]就算达标。";
        }

        Map<Long, TaskAdviceDto> perTask = advice != null && advice.getItems() != null ? advice.getItems() : Map.of();
        StringBuilder out = new StringBuilder();
        out.append(header);

        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        int i = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getTaskId() == null) continue;
            i++;
            Long tid = s.getTaskId();
            GoalTaskDto t = taskMap.get(tid);
            String title = support.safeText(s.getTaskTitle() != null ? s.getTaskTitle() : (t != null ? t.getTitle() : ("任务 " + tid)));
            String range = support.fmtHm(hm, s.getStartTime()) + "-" + support.fmtHm(hm, s.getEndTime());

            out.append("\n\n").append(i).append(") ").append(title).append("（").append(range).append("）");

            TaskAdviceDto a = perTask.get(tid);
            String start = support.safeText(a != null ? a.getStart() : null);
            String done = support.safeText(a != null ? a.getDone() : null);
            if (start.isBlank()) start = "先用 10 分钟把第一步做完（只做最小可推进的动作）。";
            if (done.isBlank()) done = "在这个时间段内完成一个可验收产出（笔记/小测/代码/总结）。";

            out.append("\n开始：").append(start);
            out.append("\n完成：").append(done);

            List<CourseResourceDto> rs = support.recommendCourseResources(uid, t, 2);
            if (rs != null && !rs.isEmpty()) {
                String joined = rs.stream()
                        .map(r -> {
                            if (r == null) return null;
                            String name = support.safeText(r.getTitle());
                            if (name.isBlank()) name = "课程资源";
                            String platform = support.safeText(r.getPlatform());
                            return platform.isBlank() ? name : platform + "：" + name;
                        })
                        .filter(x -> x != null && !x.isBlank())
                        .limit(2)
                        .collect(Collectors.joining("；"));
                if (!joined.isBlank()) {
                    out.append("\n资料：").append(joined);
                }
            }
        }

        return Result.success(out.toString());
    }
}
