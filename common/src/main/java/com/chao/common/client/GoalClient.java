package com.chao.common.client;

import com.chao.common.dto.Result;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.UserJournalDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

@FeignClient(name = "goal-service")
public interface GoalClient {
    @PostMapping("/api/goals")
    Result<GoalDto> createGoalByAi(@RequestParam("userId") Long userId, @RequestBody String goalDescription);

    @PostMapping("/api/goals/create")
    Result<GoalDto> createGoalRecord(
            @RequestParam("userId") Long userId,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "deadline", required = false) String deadline);

    @GetMapping("/api/goals")
    Result<List<GoalDto>> listGoals(@RequestParam("userId") Long userId);

    @GetMapping("/api/goals/{goalId}")
    Result<GoalDto> getGoal(@PathVariable("goalId") Long goalId);

    @PutMapping("/api/goals/{goalId}")
    Result<String> updateGoal(
            @PathVariable("goalId") Long goalId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "deadline", required = false) String deadline);

    @DeleteMapping("/api/goals/{goalId}")
    Result<String> deleteGoal(@PathVariable("goalId") Long goalId);

    @PostMapping("/api/goals/{goalId}/tasks")
    Result<GoalTaskDto> createTask(
            @PathVariable("goalId") Long goalId,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "parentId", required = false) Long parentId,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "priority", required = false) Integer priority,
            @RequestParam(value = "estimatedMinutes", required = false) Integer estimatedMinutes,
            @RequestParam(value = "deadline", required = false) String deadline);

    @GetMapping("/api/goals/{goalId}/tasks")
    Result<List<GoalTaskDto>> listTasks(@PathVariable("goalId") Long goalId, @RequestParam("userId") Long userId);

    @GetMapping("/api/goals/journals")
    Result<List<UserJournalDto>> listJournals(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "goalId", required = false) Long goalId);

    @PostMapping("/api/goals/journal")
    Result<String> createJournal(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "goalId", required = false) Long goalId,
            @RequestParam("content") String content,
            @RequestParam(value = "mood", required = false) String mood);

    @DeleteMapping("/api/goals/journals/{journalId}")
    Result<String> deleteJournal(@RequestParam("userId") Long userId, @PathVariable("journalId") Long journalId);

    @GetMapping("/api/goals/pending-tasks")
    Result<List<GoalTaskDto>> getPendingTasks(@RequestParam("userId") Long userId);

    @PostMapping("/api/goals/tasks/status")
    Result<String> updateTaskStatus(@RequestParam("taskId") Long taskId, @RequestParam("status") Integer status);

    @GetMapping("/api/goals/tasks/by-ids")
    Result<List<GoalTaskDto>> getTasksByIds(@RequestParam("taskIds") List<Long> taskIds);

    @PostMapping("/api/goals/tasks/move")
    Result<String> moveTaskToGoal(
            @RequestParam("userId") Long userId,
            @RequestParam("taskId") Long taskId,
            @RequestParam("goalId") Long goalId);

    @PostMapping("/api/goals/{goalId}/tasks/regenerate")
    Result<String> regenerateTasks(@PathVariable("goalId") Long goalId, @RequestParam("userId") Long userId, @RequestBody(required = false) String feedback);

    @GetMapping("/api/goals/topics")
    Result<java.util.List<String>> getDistinctTopics();
}
