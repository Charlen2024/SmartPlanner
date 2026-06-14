package com.chao.goal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.GoalAiTaskMessage;
import com.chao.common.dto.GoalDto;
import com.chao.goal.config.AiPromptConfig;
import com.chao.goal.entity.Goal;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.mapper.GoalMapper;
import com.chao.goal.mapper.GoalTaskMapper;
import com.chao.goal.mapper.UserJournalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskOrchestrator {

    private final GoalMapper goalMapper;
    private final GoalTaskMapper goalTaskMapper;
    private final UserJournalMapper userJournalMapper;
    private final RabbitTemplate rabbitTemplate;
    private final AiPromptConfig aiPromptConfig;

    public GoalDto createGoalAndStartAi(Long userId, String goalDescription) {
        String text = goalDescription == null ? "" : goalDescription.trim();
        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setTitle(text.length() > 80 ? text.substring(0, 80) : text);
        goal.setDescription(text);
        goal.setStatus(0);
        goal.setCreatedAt(LocalDateTime.now());
        goalMapper.insert(goal);

        GoalAiTaskMessage msg = new GoalAiTaskMessage();
        msg.setUserId(userId);
        msg.setGoalId(goal.getId());
        msg.setGoalDescription(text);
        msg.setSystemPrompt(aiPromptConfig.getSystem());
        rabbitTemplate.convertAndSend(RabbitMqConfig.GOAL_EXCHANGE, RabbitMqConfig.GOAL_AI_ROUTING_KEY, msg);

        GoalDto dto = new GoalDto();
        dto.setId(goal.getId());
        dto.setUserId(goal.getUserId());
        dto.setTitle(goal.getTitle());
        dto.setDescription(goal.getDescription());
        dto.setStatus(goal.getStatus());
        dto.setDeadline(goal.getDeadline());
        dto.setCreatedAt(goal.getCreatedAt());
        return dto;
    }

    @Transactional
    public void regenerateTasks(Long userId, Long goalId, String feedback) {
        Goal goal = goalMapper.selectById(goalId);
        if (goal == null || goal.getUserId() == null || !goal.getUserId().equals(userId)) {
            throw new IllegalArgumentException("目标不存在");
        }

        List<GoalTask> learnedTasks = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId)
                .orderByAsc(GoalTask::getId));
        List<UserJournal> journals = userJournalMapper.selectList(new LambdaQueryWrapper<UserJournal>()
                .eq(UserJournal::getUserId, userId)
                .orderByDesc(UserJournal::getCreatedAt)
                .last("LIMIT 5"));

        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId));

        StringBuilder userPrompt = new StringBuilder();
        if (learnedTasks != null && !learnedTasks.isEmpty()) {
            userPrompt.append("=== 用户该目标下的全部任务（含已完成和未完成） ===\n");
            for (GoalTask t : learnedTasks) {
                String status = t.getStatus() != null && t.getStatus() == 1 ? "[已完成]" : "[未完成]";
                userPrompt.append("- ").append(status).append(" ").append(t.getTitle());
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    userPrompt.append("（").append(t.getDescription()).append("）");
                }
                userPrompt.append("\n");
            }
            userPrompt.append("\n");
        }
        if (journals != null && !journals.isEmpty()) {
            userPrompt.append("=== 用户近期学习随笔（反映学习状态与困惑） ===\n");
            for (UserJournal j : journals) {
                if (j.getContent() != null && !j.getContent().isBlank()) {
                    String snippet = j.getContent().length() > 200
                            ? j.getContent().substring(0, 200) : j.getContent();
                    userPrompt.append("- ").append(snippet).append("\n");
                }
            }
            userPrompt.append("\n");
        }
        userPrompt.append("=== 当前学习目标 ===\n");
        userPrompt.append(goal.getDescription());
        userPrompt.append("\n\n请基于上述全部任务，生成更高阶的进阶学习任务，跳过已完成的任务、从已有基础上深入，避免重复。");

        String fb = feedback == null ? "" : feedback.trim();
        String prompt = aiPromptConfig.getAdvancedSystem();
        if (!fb.isBlank()) {
            prompt = prompt + "\n用户对上一版计划的意见：" + fb + "\n请根据意见重新生成任务。";
        }

        GoalAiTaskMessage msg = new GoalAiTaskMessage();
        msg.setUserId(userId);
        msg.setGoalId(goalId);
        msg.setGoalDescription(userPrompt.toString());
        msg.setSystemPrompt(prompt);
        rabbitTemplate.convertAndSend(RabbitMqConfig.GOAL_EXCHANGE, RabbitMqConfig.GOAL_AI_ROUTING_KEY, msg);
    }
}
