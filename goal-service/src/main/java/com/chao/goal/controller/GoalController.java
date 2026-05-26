package com.chao.goal.controller;

import com.chao.common.util.DateUtils;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.GoalDto;
import com.chao.goal.entity.Goal;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.service.GoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {
    private final GoalService goalService;

    /**
     * 创建学习目标并自动拆解任务
     * 输入示例: "我要学习分布式系统"
     */
    @PostMapping
    public Result<GoalDto> createGoal(@RequestParam Long userId, @RequestBody String goalDescription) {
        return Result.success(goalService.createGoalAndStartAi(userId, goalDescription));
    }

    @PostMapping("/create")
    public Result<Goal> createGoalRecord(
            @RequestParam Long userId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String deadline) {
        return Result.success(goalService.createGoal(userId, title, description, DateUtils.parseLocalDateTime(deadline)));
    }

    @GetMapping
    public Result<List<Goal>> listGoals(@RequestParam Long userId) {
        return Result.success(goalService.listGoals(userId));
    }

    @GetMapping("/{goalId}")
    public Result<Goal> getGoal(@PathVariable Long goalId) {
        return Result.success(goalService.getGoal(goalId));
    }

    @PutMapping("/{goalId}")
    public Result<String> updateGoal(
            @PathVariable Long goalId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String deadline) {
        goalService.updateGoal(goalId, title, description, status, DateUtils.parseLocalDateTime(deadline));
        return Result.success("更新成功");
    }

    @DeleteMapping("/{goalId}")
    public Result<String> deleteGoal(@PathVariable Long goalId) {
        goalService.deleteGoal(goalId);
        return Result.success("删除成功");
    }

    @PostMapping("/{goalId}/tasks")
    public Result<GoalTask> createTask(
            @PathVariable Long goalId,
            @RequestParam Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "0") Integer priority,
            @RequestParam(required = false) Integer estimatedMinutes,
            @RequestParam(required = false) String deadline) {
        try {
            return Result.success(goalService.createTask(userId, goalId, parentId, title, description, priority, estimatedMinutes, DateUtils.parseLocalDateTime(deadline)));
        } catch (GoalService.DuplicateTaskException e) {
            return Result.fail(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{goalId}/tasks")
    public Result<List<GoalTask>> listTasks(@PathVariable Long goalId, @RequestParam Long userId) {
        return Result.success(goalService.listTasksByGoal(userId, goalId));
    }

    @GetMapping("/journals")
    public Result<List<UserJournal>> listJournals(@RequestParam Long userId, @RequestParam(required = false) Long goalId) {
        return Result.success(goalService.listJournals(userId, goalId));
    }

    /**
     * 获取待办任务
     */
    @GetMapping("/pending-tasks")
    public Result<List<GoalTaskDto>> getPendingTasks(@RequestParam Long userId) {
        return Result.success(goalService.getPendingTasks(userId));
    }

    /**
     * 更新任务状态
     */
    @PostMapping("/tasks/status")
    public Result<String> updateTaskStatus(@RequestParam Long taskId, @RequestParam Integer status) {
        goalService.updateTaskStatus(taskId, status);
        return Result.success("状态更新成功");
    }

    @PostMapping("/tasks/move")
    public Result<String> moveTaskToGoal(@RequestParam Long userId, @RequestParam Long taskId, @RequestParam Long goalId) {
        goalService.moveTaskToGoal(userId, taskId, goalId);
        return Result.success("任务已归并到目标");
    }

    @GetMapping("/tasks/by-ids")
    public Result<List<GoalTaskDto>> getTasksByIds(@RequestParam List<Long> taskIds) {
        return Result.success(goalService.getTasksByIds(taskIds));
    }

    @PostMapping("/{goalId}/tasks/regenerate")
    public Result<String> regenerateTasks(@PathVariable Long goalId, @RequestParam Long userId, @RequestBody(required = false) String feedback) {
        goalService.regenerateTasks(userId, goalId, feedback);
        return Result.success("已提交重新生成");
    }

    /**
     * 发表随笔
     */
    @PostMapping("/journal")
    public Result<String> createJournal(
            @RequestParam Long userId,
            @RequestParam(required = false) Long goalId,
            @RequestParam String content,
            @RequestParam(required = false) String mood) {
        goalService.saveJournal(userId, goalId, content, mood);
        return Result.success("随笔已保存");
    }

    @DeleteMapping("/journals/{journalId}")
    public Result<String> deleteJournal(@RequestParam Long userId, @PathVariable Long journalId) {
        goalService.deleteJournal(userId, journalId);
        return Result.success("删除成功");
    }
    @GetMapping("/topics")
    public Result<java.util.List<String>> getDistinctTopics() {
        java.util.List<String> topics = goalService.getDistinctGoalTopics();
        return Result.success(topics != null ? topics : java.util.List.of());
    }
}
