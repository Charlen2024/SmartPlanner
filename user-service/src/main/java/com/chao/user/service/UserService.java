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

@Service
@RequiredArgsConstructor
public class UserService {
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final AppUserService appUserService;

    public DashboardDto getDashboard(Long userId, String date, String topic) {
        DashboardDto dto = new DashboardDto();

        List<GoalDto> goals = goalClient.listGoals(userId).getData();
        dto.setGoals(goals);

        dto.setGoalProgress(buildGoalProgress(userId, goals));

        dto.setPendingTasks(goalClient.getPendingTasks(userId).getData());

        String dateStr = date != null ? date : LocalDate.now().toString();
        com.chao.user.entity.AppUser user = appUserService.getById(userId);
        String fwm = user != null && user.getFirstWeekMonday() != null ? user.getFirstWeekMonday().toString() : null;
        dto.setFreeTimeSlots(scheduleClient.getFreeTimeSlots(userId, dateStr, fwm).getData());

        dto.setTaskSchedules(scheduleClient.listTaskSchedules(userId, null, null).getData());

        dto.setStreak(punchClient.getStreak(userId).getData());

        dto.setClasses(scheduleClient.listClasses(userId, null).getData());

        if (topic != null && !topic.isBlank()) {
            dto.setResources(resourceClient.searchOnlineCourses(topic).getData());
        } else {
            dto.setResources(java.util.List.of());
        }

        return dto;
    }

    private List<GoalProgressDto> buildGoalProgress(Long userId, List<GoalDto> goals) {
        if (goals == null || goals.isEmpty()) {
            return List.of();
        }
        List<GoalProgressDto> list = new ArrayList<>();
        for (GoalDto g : goals) {
            if (g == null || g.getId() == null) {
                continue;
            }
            List<GoalTaskDto> tasks = goalClient.listTasks(g.getId(), userId).getData();
            int total = tasks != null ? tasks.size() : 0;
            int done = 0;
            if (tasks != null) {
                for (GoalTaskDto t : tasks) {
                    if (t != null && t.getStatus() != null && t.getStatus() == 2) {
                        done++;
                    }
                }
            }
            GoalProgressDto p = new GoalProgressDto();
            p.setGoalId(g.getId());
            p.setTitle(g.getTitle());
            p.setTotalTasks(total);
            p.setDoneTasks(done);
            p.setPercent(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
            list.add(p);
        }
        return list;
    }
}
