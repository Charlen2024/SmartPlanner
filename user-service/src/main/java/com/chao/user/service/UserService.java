package com.chao.user.service;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.user.dto.DashboardDto;
import com.chao.user.dto.GoalProgressDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class UserService {
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final AppUserService appUserService;

    public DashboardDto getDashboard(Long userId, String date, String topic) {
        String dateStr = date != null ? date : LocalDate.now().toString();

        // 三类独立调用并行发起
        CompletableFuture<List<GoalDto>> goalsFut = CompletableFuture.supplyAsync(() ->
                safe(() -> goalClient.listGoals(userId).getData()));
        CompletableFuture<List<GoalTaskDto>> pendingFut = CompletableFuture.supplyAsync(() ->
                safe(() -> goalClient.getPendingTasks(userId).getData()));
        CompletableFuture<com.chao.user.entity.AppUser> userFut = CompletableFuture.supplyAsync(() ->
                safe(() -> appUserService.getById(userId)));

        // 依赖 goals 完成
        List<GoalDto> goals = goalsFut.join();
        List<GoalProgressDto> progress = buildGoalProgress(userId, goals);

        // 依赖 user 完成 → 再查 freeTimeSlots
        com.chao.user.entity.AppUser user = userFut.join();
        String fwm = user != null && user.getFirstWeekMonday() != null ? user.getFirstWeekMonday().toString() : null;
        CompletableFuture<?> freeTimeFut = CompletableFuture.supplyAsync(() ->
                safe(() -> scheduleClient.getFreeTimeSlots(userId, dateStr, fwm).getData()));

        // 其余独立调用并行
        CompletableFuture<?> schedulesFut = CompletableFuture.supplyAsync(() ->
                safe(() -> scheduleClient.listTaskSchedules(userId, null, null).getData()));
        CompletableFuture<?> streakFut = CompletableFuture.supplyAsync(() ->
                safe(() -> punchClient.getStreak(userId).getData()));
        CompletableFuture<?> classesFut = CompletableFuture.supplyAsync(() ->
                safe(() -> scheduleClient.listClasses(userId, null, null, null).getData()));
        CompletableFuture<?> resourcesFut = CompletableFuture.supplyAsync(() -> {
            if (topic != null && !topic.isBlank()) {
                return safe(() -> resourceClient.searchOnlineCourses(topic).getData());
            }
            return List.of();
        });

        // 组装
        DashboardDto dto = new DashboardDto();
        dto.setGoals(goals);
        dto.setGoalProgress(progress);
        dto.setPendingTasks(safeJoin(pendingFut));
        dto.setFreeTimeSlots(safeJoin(freeTimeFut));
        dto.setTaskSchedules(safeJoin(schedulesFut));
        dto.setStreak(safeJoin(streakFut));
        dto.setClasses(safeJoin(classesFut));
        dto.setResources(safeJoin(resourcesFut));
        return dto;
    }

    private List<GoalProgressDto> buildGoalProgress(Long userId, List<GoalDto> goals) {
        if (goals == null || goals.isEmpty()) {
            return List.of();
        }
        // Fire all task-fetch calls in parallel
        List<CompletableFuture<GoalProgressDto>> futures = new ArrayList<>();
        for (GoalDto g : goals) {
            if (g == null || g.getId() == null) continue;
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<GoalTaskDto> tasks = safe(() -> goalClient.listTasks(g.getId(), userId).getData());
                int total = tasks != null ? tasks.size() : 0;
                int done = 0;
                if (tasks != null) {
                    for (GoalTaskDto t : tasks) {
                        if (t != null && t.getStatus() != null && t.getStatus() == 2) done++;
                    }
                }
                GoalProgressDto p = new GoalProgressDto();
                p.setGoalId(g.getId());
                p.setTitle(g.getTitle());
                p.setTotalTasks(total);
                p.setDoneTasks(done);
                p.setPercent(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
                return p;
            }));
        }
        return futures.stream()
                .map(f -> safe(() -> f.join()))
                .filter(p -> p != null)
                .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static <T> T safeJoin(CompletableFuture<?> fut) {
        try {
            return (T) fut.join();
        } catch (Exception e) {
            return (T) List.of();
        }
    }

    @FunctionalInterface
    private interface SafeSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T safe(SafeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}
