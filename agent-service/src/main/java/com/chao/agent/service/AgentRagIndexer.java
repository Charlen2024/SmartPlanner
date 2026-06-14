package com.chao.agent.service;

import com.chao.agent.util.ChatTextUtils;
import com.chao.agent.util.VectorStoreUtils;
import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.UserInternalClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.*;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRagIndexer {
    private static final Logger log = LoggerFactory.getLogger(AgentRagIndexer.class);

    private final GoalClient goalClient;
    private final PunchClient punchClient;
    private final RedissonClient redissonClient;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final UserInternalClient userInternalClient;

    private final ConcurrentHashMap<Long, Boolean> ragIndexedLocal = new ConcurrentHashMap<>();

    public AgentRagIndexer(GoalClient goalClient,
                           PunchClient punchClient,
                           RedissonClient redissonClient,
                           ObjectProvider<VectorStore> vectorStoreProvider,
                           UserInternalClient userInternalClient) {
        this.goalClient = goalClient;
        this.punchClient = punchClient;
        this.redissonClient = redissonClient;
        this.vectorStoreProvider = vectorStoreProvider;
        this.userInternalClient = userInternalClient;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledFullUserRagIndex() {
        ragIndexedLocal.clear();
        try {
            Result<List<Long>> result = userInternalClient.getAllUserIds();
            List<Long> userIds = (result != null && result.getCode() == 200) ? result.getData() : List.of();
            if (userIds == null || userIds.isEmpty()) return;
            int indexed = 0;
            for (Long userId : userIds) {
                try {
                    ensureUserRagIndexed(userId);
                    indexed++;
                } catch (Exception ignored) {
                }
            }
            log.info("Scheduled RAG index: {}/{} users indexed", indexed, userIds.size());
        } catch (Exception e) {
            log.warn("Scheduled RAG index failed: {}", e.getMessage());
        }
    }

    public void ensureUserRagIndexed(Long userId) {
        if (userId == null) return;
        if (ragIndexedLocal.getOrDefault(userId, false)) {
            log.info("RAG index skipped for userId={}: already indexed in local cache", userId);
            return;
        }
        if (redissonClient == null) return;
        VectorStore vs = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        if (vs == null) {
            log.info("RAG index skipped for userId={}: VectorStore bean not available", userId);
            return;
        }
        String key = "sp:rag:indexed:u:" + userId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        String v = bucket.get();
        if ("1".equals(v)) {
            ragIndexedLocal.put(userId, true);
            log.info("RAG index skipped for userId={}: already indexed per Redis", userId);
            return;
        }

        log.info("RAG index START for userId={}", userId);
        List<Document> docs = new java.util.ArrayList<>();

        try {
            Result<List<GoalDto>> gr = goalClient.listGoals(userId);
            List<GoalDto> goals = gr != null ? gr.getData() : List.of();
            if (goals != null) {
                for (GoalDto g : goals) {
                    if (g == null || g.getId() == null) continue;
                    String text = (g.getTitle() == null ? "" : g.getTitle()) + ". " + (g.getDescription() == null ? "" : g.getDescription());
                    if (text.isBlank()) continue;
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("type", "goal");
                    meta.put("userId", String.valueOf(userId));
                    meta.put("goalId", String.valueOf(g.getId()));
                    if (g.getTitle() != null) meta.put("title", g.getTitle());
                    docs.add(new Document("goal:" + userId + ":" + g.getId(), text, meta));
                }
            }
        } catch (Exception e) {
            log.debug("RAG idx: goals fetch failed for userId={}: {}", userId, e.getMessage());
        }

        try {
            Result<List<GoalTaskDto>> tr = goalClient.getPendingTasks(userId);
            List<GoalTaskDto> tasks = tr != null ? tr.getData() : List.of();
            if (tasks != null) {
                for (GoalTaskDto t : tasks) {
                    if (t == null || t.getId() == null) continue;
                    String text = (t.getTitle() == null ? "" : t.getTitle()) + ". " + (t.getDescription() == null ? "" : t.getDescription());
                    if (text.isBlank()) continue;
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("type", "task");
                    meta.put("userId", String.valueOf(userId));
                    meta.put("taskId", String.valueOf(t.getId()));
                    if (t.getGoalId() != null) meta.put("goalId", String.valueOf(t.getGoalId()));
                    if (t.getTitle() != null) meta.put("title", t.getTitle());
                    docs.add(new Document("task:" + userId + ":" + t.getId(), text, meta));
                }
            }
        } catch (Exception e) {
            log.debug("RAG idx: tasks fetch failed for userId={}: {}", userId, e.getMessage());
        }

        try {
            Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
            List<UserJournalDto> journals = jr != null ? jr.getData() : List.of();
            log.info("RAG idx: journals fetch userId={}, code={}, count={}", userId, jr != null ? jr.getCode() : -1, journals != null ? journals.size() : 0);
            if (journals != null) {
                int added = 0;
                for (UserJournalDto j : journals) {
                    if (j == null || j.getId() == null) continue;
                    String text = j.getContent();
                    if (text == null || text.isBlank()) {
                        log.info("RAG idx: skipping blank journal id={}", j.getId());
                        continue;
                    }
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("type", "journal");
                    meta.put("userId", String.valueOf(userId));
                    meta.put("journalId", String.valueOf(j.getId()));
                    if (j.getGoalId() != null) meta.put("goalId", String.valueOf(j.getGoalId()));
                    meta.put("title", "随笔");
                    if (j.getCreatedAt() != null) meta.put("createdAt", j.getCreatedAt().toString());
                    if (j.getMood() != null && !j.getMood().isBlank()) meta.put("mood", j.getMood());
                    docs.add(new Document("journal:" + userId + ":" + j.getId(), text, meta));
                    added++;
                    if (docs.size() >= 200) break;
                }
                log.info("RAG idx: journals added={}/{}, totalDocs={}", added, journals.size(), docs.size());
            }
        } catch (Exception e) {
            log.warn("RAG idx: journals fetch failed for userId={}: {}", userId, e.getMessage());
        }

        try {
            Result<List<PunchRecordDto>> pr = punchClient.listRecords(userId, null, null, null);
            List<PunchRecordDto> records = pr != null ? pr.getData() : List.of();
            if (records != null) {
                Map<Long, String> taskTitles = new HashMap<>();
                try {
                    List<Long> taskIds = records.stream()
                            .map(PunchRecordDto::getTaskId).filter(java.util.Objects::nonNull).distinct()
                            .collect(java.util.stream.Collectors.toList());
                    if (!taskIds.isEmpty()) {
                        Result<List<GoalTaskDto>> tr = goalClient.getTasksByIds(taskIds);
                        List<GoalTaskDto> tasks = tr != null ? tr.getData() : List.of();
                        if (tasks != null) {
                            for (GoalTaskDto t : tasks) {
                                if (t != null && t.getId() != null) {
                                    taskTitles.put(t.getId(), t.getTitle() != null ? t.getTitle() : "未命名");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("RAG idx: task title batch fetch failed for userId={}: {}", userId, e.getMessage());
                }

                for (PunchRecordDto r : records) {
                    if (r == null || r.getId() == null) continue;
                    String taskName = taskTitles.getOrDefault(r.getTaskId(), "任务" + r.getTaskId());
                    String text = "打卡: " + taskName
                            + " | 时长: " + ChatTextUtils.formatDuration(r.getDurationSeconds())
                            + " | 时间: " + (r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
                    if (text.isBlank()) continue;
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("type", "punch");
                    meta.put("userId", String.valueOf(userId));
                    meta.put("punchId", String.valueOf(r.getId()));
                    if (r.getTaskId() != null) meta.put("taskId", String.valueOf(r.getTaskId()));
                    meta.put("taskTitle", taskName);
                    if (r.getCreatedAt() != null) meta.put("createdAt", r.getCreatedAt().toString());
                    if (r.getDurationSeconds() != null) meta.put("durationSeconds", r.getDurationSeconds());
                    docs.add(new Document("punch:" + userId + ":" + r.getId(), text, meta));
                    if (docs.size() >= 500) break;
                }
            }
        } catch (Exception e) {
            log.debug("RAG idx: punch records fetch failed for userId={}: {}", userId, e.getMessage());
        }

        if (!docs.isEmpty()) {
            log.info("RAG index COMMIT userId={}, totalDocs={}, types: goal={}, task={}, journal={}, punch={}",
                    userId,
                    docs.size(),
                    docs.stream().filter(d -> "goal".equals(d.getMetadata().get("type"))).count(),
                    docs.stream().filter(d -> "task".equals(d.getMetadata().get("type"))).count(),
                    docs.stream().filter(d -> "journal".equals(d.getMetadata().get("type"))).count(),
                    docs.stream().filter(d -> "punch".equals(d.getMetadata().get("type"))).count());
            VectorStoreUtils.deleteByUserId(vs, userId);
            VectorStoreUtils.addDocsInBatches(vs, docs, 20);
            bucket.set("1");
            bucket.expire(Duration.ofDays(3));
            ragIndexedLocal.put(userId, true);
            log.info("RAG index DONE userId={}, cache set, local cache updated", userId);
            return;
        }
        bucket.set("0");
        bucket.expire(Duration.ofHours(6));
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMqConfig.AGENT_JOURNAL_INDEX_QUEUE)
    public void onJournalCreated(NotificationMessage msg) {
        if (msg == null || msg.getUserId() == null) return;
        Long userId = msg.getUserId();
        Object payload = msg.getPayload();
        if (!(payload instanceof Map)) return;
        Map<String, Object> data = (Map<String, Object>) payload;

        String content = data.get("content") instanceof String s ? s : "";
        if (content.isBlank()) return;

        Long goalId = null;
        Object gid = data.get("goalId");
        if (gid instanceof Number n && n.longValue() > 0) goalId = n.longValue();

        VectorStore vs = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        if (vs == null) {
            log.info("Journal RAG index skipped for userId={}: VectorStore bean not available", userId);
            return;
        }

        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "journal");
            meta.put("userId", String.valueOf(userId));
            if (goalId != null) meta.put("goalId", String.valueOf(goalId));
            meta.put("title", "随笔");
            meta.put("mood", data.get("mood") instanceof String s ? s : "");
            meta.put("createdAt", java.time.LocalDateTime.now().toString());
            String docId = "journal:" + userId + ":" + (data.get("journalId") != null ? data.get("journalId") : System.currentTimeMillis());
            vs.add(List.of(new Document(docId, content, meta)));
            log.info("Journal RAG indexed: userId={}, journalId={}", userId, data.get("journalId"));

            // Invalidate RAG cache so next ensureUserRagIndexed re-indexes with this journal
            ragIndexedLocal.remove(userId);
            if (redissonClient != null) {
                try {
                    redissonClient.getBucket("sp:rag:indexed:u:" + userId).delete();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("Journal RAG index failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
