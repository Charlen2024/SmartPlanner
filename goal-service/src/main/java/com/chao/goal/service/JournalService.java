package com.chao.goal.service;

import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.mapper.UserJournalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalService {

    private final UserJournalMapper userJournalMapper;
    private final RabbitTemplate rabbitTemplate;

    public UserJournal save(Long userId, Long goalId, String content, String mood) {
        UserJournal journal = new UserJournal();
        journal.setUserId(userId);
        journal.setGoalId(goalId);
        journal.setContent(content);
        journal.setMood(mood);
        journal.setCreatedAt(LocalDateTime.now());
        userJournalMapper.insert(journal);

        // Notify agent-service to index this journal into the vector store
        try {
            NotificationMessage syncMsg = new NotificationMessage();
            syncMsg.setUserId(userId);
            syncMsg.setType("JOURNAL_CREATED");
            syncMsg.setTs(System.currentTimeMillis());
            syncMsg.setPayload(Map.of(
                    "journalId", journal.getId(),
                    "goalId", goalId != null ? goalId : 0,
                    "content", content != null ? content : "",
                    "mood", mood != null ? mood : ""
            ));
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.AGENT_JOURNAL_INDEX_ROUTING_KEY, syncMsg);
        } catch (Exception e) {
            log.warn("JOURNAL_CREATED通知发送失败, userId={}, goalId={}", userId, goalId, e);
        }

        // Check for negative mood and send reminder
        try {
            String c = content != null ? content : "";
            String m = mood != null ? mood : "";
            String combined = (c + "\n" + m).trim();
            if (!combined.isBlank() && containsNegativeWords(combined)) {
                NotificationMessage notif = new NotificationMessage();
                notif.setUserId(userId);
                notif.setType("AGENT_REMINDER");
                notif.setContent("trigger=journal_negative; data=" + Map.of("goalId", goalId, "journalId", journal.getId()));
                notif.setTs(System.currentTimeMillis());
                notif.setPayload(Map.of(
                        "nav", "/journals",
                        "level", "warning",
                        "ai", Map.of(
                                "userPrompt", "触发：journal_negative。用户随笔/心情中出现消极词。请基于原文片段与数据生成 1-2 句安慰提醒，避免固定模板与重复句式，提出一个可执行的小建议。数据：" + Map.of(
                                        "goalId", goalId,
                                        "journalId", journal.getId(),
                                        "excerpt", combined.length() > 120 ? combined.substring(0, 120) : combined
                                )
                        ),
                        "data", Map.of(
                                "goalId", goalId,
                                "journalId", journal.getId()
                        )
                ));
                rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
            }
        } catch (Exception e) {
            log.warn("消极情绪提醒发送失败, userId={}, goalId={}", userId, goalId, e);
        }
        return journal;
    }

    public void delete(Long userId, Long journalId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("未授权");
        }
        if (journalId == null || journalId <= 0) {
            throw new IllegalArgumentException("随笔不存在");
        }
        UserJournal existing = userJournalMapper.selectById(journalId);
        if (existing == null || existing.getUserId() == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("随笔不存在");
        }
        userJournalMapper.deleteById(journalId);
    }

    private boolean containsNegativeWords(String text) {
        String t = text == null ? "" : text;
        if (t.isBlank()) return false;
        String[] words = new String[]{
                "焦虑", "压力", "崩溃", "很累", "抑郁", "难受", "烦", "痛苦", "失眠",
                "不想", "没动力", "坚持不下去", "摆烂", "绝望", "害怕", "内耗"
        };
        for (String w : words) {
            if (w != null && !w.isBlank() && t.contains(w)) return true;
        }
        return false;
    }
}
