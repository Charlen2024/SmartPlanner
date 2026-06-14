package com.chao.goal.service;

import com.chao.common.client.ScheduleClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.goal.entity.Goal;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.mapper.GoalMapper;
import com.chao.goal.mapper.GoalTaskMapper;
import com.chao.goal.mapper.UserJournalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalCrudService {

    private final GoalMapper goalMapper;
    private final GoalTaskMapper goalTaskMapper;
    private final UserJournalMapper userJournalMapper;
    private final ScheduleClient scheduleClient;

    public Goal createGoal(Long userId, String title, String description, LocalDateTime deadline) {
        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setTitle(title);
        goal.setDescription(description);
        goal.setDeadline(deadline);
        goal.setStatus(0);
        goal.setCreatedAt(LocalDateTime.now());
        goalMapper.insert(goal);
        return goal;
    }

    public List<Goal> listGoals(Long userId) {
        return goalMapper.selectList(new LambdaQueryWrapper<Goal>()
                .eq(Goal::getUserId, userId)
                .orderByDesc(Goal::getCreatedAt));
    }

    public Goal getGoal(Long goalId) {
        return goalMapper.selectById(goalId);
    }

    public void updateGoal(Long goalId, String title, String description, Integer status, LocalDateTime deadline) {
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setTitle(title);
        goal.setDescription(description);
        goal.setStatus(status);
        goal.setDeadline(deadline);
        goalMapper.updateById(goal);
    }

    public long countUnfinishedTasks(Long goalId) {
        return goalTaskMapper.selectCount(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getGoalId, goalId)
                .ne(GoalTask::getStatus, 1));
    }

    @Transactional
    public void deleteGoal(Long goalId) {
        Goal goal = goalMapper.selectById(goalId);
        Long userId = goal != null ? goal.getUserId() : null;
        List<Long> taskIds = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                        .eq(GoalTask::getGoalId, goalId))
                .stream()
                .map(GoalTask::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (userId != null && userId > 0 && !taskIds.isEmpty()) {
            try {
                scheduleClient.deleteTaskSchedulesByTaskIds(userId, taskIds);
            } catch (Exception e) {
                log.warn("清理排程失败, goalId={}, userId={}", goalId, userId, e);
            }
        }
        goalMapper.deleteById(goalId);
        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>().eq(GoalTask::getGoalId, goalId));
        userJournalMapper.delete(new LambdaQueryWrapper<UserJournal>().eq(UserJournal::getGoalId, goalId));
    }

    public List<UserJournal> listJournals(Long userId, Long goalId) {
        LambdaQueryWrapper<UserJournal> qw = new LambdaQueryWrapper<UserJournal>()
                .eq(UserJournal::getUserId, userId)
                .orderByDesc(UserJournal::getCreatedAt);
        if (goalId != null) {
            qw.eq(UserJournal::getGoalId, goalId);
        }
        return userJournalMapper.selectList(qw);
    }

    public java.util.List<String> getDistinctGoalTopics() {
        java.util.List<Goal> goals = goalMapper.selectList(null);
        if (goals == null || goals.isEmpty()) return java.util.List.of();
        return goals.stream()
                .map(Goal::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }
}
