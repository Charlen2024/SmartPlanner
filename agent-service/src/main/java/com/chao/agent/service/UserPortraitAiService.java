package com.chao.agent.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.dto.AiPortraitResult;
import com.chao.common.dto.PortraitRecomputeRequest;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.TaskScheduleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserPortraitAiService {
    private static final Logger log = LoggerFactory.getLogger(UserPortraitAiService.class);

    private final OpenAiCompatClient openAiCompatClient;
    private final AgentRagIndexer agentRagIndexer;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public UserPortraitAiService(OpenAiCompatClient openAiCompatClient,
                                 AgentRagIndexer agentRagIndexer,
                                 ObjectProvider<VectorStore> vectorStoreProvider) {
        this.openAiCompatClient = openAiCompatClient;
        this.agentRagIndexer = agentRagIndexer;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    public AiPortraitResult analyze(PortraitRecomputeRequest request) {
        List<String> journalSnippets = fetchJournalSnippets(request);
        return analyze(request, journalSnippets);
    }

    private String buildRagQuery(PortraitRecomputeRequest req) {
        double pro = req.getProcrastinationIndex() != null ? req.getProcrastinationIndex() : 0.0;
        double comp = req.getCompletionRate() != null ? req.getCompletionRate() : 0.0;
        int streak = req.getStreak();

        if (pro > 0.6) return "不想学 没状态 好累 坚持不下去了 烦躁 难过";
        if (comp < 0.5) return "做不完 来不及 总是被打断 想放弃 任务太多了";
        if (streak >= 5) return "坚持下来了 有进步 收获很大 突破了自己 开心 充实";
        if (pro < 0.3 && comp > 0.7) return "效率很高 很专注 很满意 找到了节奏 热血 兴奋";
        return "今天学习怎么样 心情如何 有什么反思";
    }

    private List<String> fetchJournalSnippets(PortraitRecomputeRequest request) {
        Long userId = request.getUserId();
        if (userId == null) return List.of();
        VectorStore vs = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        if (vs == null) return List.of();

        try {
            agentRagIndexer.ensureUserRagIndexed(userId);
            String query = buildRagQuery(request);
            var filter = new FilterExpressionBuilder()
                    .and(new FilterExpressionBuilder().eq("userId", String.valueOf(userId)),
                         new FilterExpressionBuilder().eq("type", "journal"))
                    .build();
            List<Document> docs = vs.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(query)
                            .topK(5)
                            .filterExpression(filter)
                            .build());
            List<String> snippets = new ArrayList<>();
            if (docs != null) {
                for (Document d : docs) {
                    if (d != null && d.getText() != null && !d.getText().isBlank()) {
                        String mood = d.getMetadata() != null && d.getMetadata().get("mood") instanceof String s ? s : "";
                        String createdAt = d.getMetadata() != null && d.getMetadata().get("createdAt") instanceof String s ? s : "";
                        snippets.add((mood.isBlank() ? "" : "[" + mood + "] ") + d.getText()
                                + (createdAt.isBlank() ? "" : " (" + createdAt + ")"));
                    }
                }
            }
            log.info("Portrait RAG: userId={}, query=\"{}\", journalSnippets={}", userId, query, snippets.size());
            return snippets;
        } catch (Exception e) {
            log.warn("Portrait RAG journal fetch failed for userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public AiPortraitResult analyze(PortraitRecomputeRequest request, List<String> journalSnippets) {
        String prompt = buildPrompt(request, journalSnippets);
        try {
            AiPortraitResult result = openAiCompatClient.entity(prompt, AiPortraitResult.class);
            log.info("Portrait AI: morningPersonScore={}, focusDurationAvg={}, tips={}",
                    result.getMorningPersonScore(), result.getFocusDurationAvg(),
                    result.getTips() != null ? result.getTips().size() : 0);
            clampPortraitResult(result);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("画像解析失败", e);
        }
    }

    private void clampPortraitResult(AiPortraitResult r) {
        if (r.getMorningPersonScore() != null)
            r.setMorningPersonScore(Math.max(0, Math.min(100, r.getMorningPersonScore())));
        if (r.getFocusDurationAvg() != null)
            r.setFocusDurationAvg(Math.max(15, Math.min(120, r.getFocusDurationAvg())));
        if (r.getProcrastinationIndex() != null)
            r.setProcrastinationIndex(Math.max(0.0, Math.min(1.0, r.getProcrastinationIndex())));
        if (r.getRecommendation() != null) {
            SchedulePreferenceDto rec = r.getRecommendation();
            if (rec.getFocusMinutes() != null) {
                int f = rec.getFocusMinutes();
                rec.setFocusMinutes(Math.max(25, Math.min(90, f)));
            }
            if (rec.getBreakMinutes() != null) {
                int b = rec.getBreakMinutes();
                rec.setBreakMinutes(Math.max(5, Math.min(25, (int) Math.round(b / 5.0) * 5)));
            }
            if (rec.getMaxDailyMinutes() != null)
                rec.setMaxDailyMinutes(Math.max(120, Math.min(300, rec.getMaxDailyMinutes())));
        }
    }

    private String buildPrompt(PortraitRecomputeRequest req, List<String> journalSnippets) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        List<PunchRecordDto> records = req.getPunchRecords();
        List<TaskScheduleDto> schedules = req.getSchedules();
        int streak = req.getStreak();

        StringBuilder sb = new StringBuilder();
        sb.append("你是学习习惯分析与学习计划助手。根据用户最近7天的排程与打卡，输出学习画像与建议。\n\n");
        sb.append("约束：\n");
        sb.append("- morningPersonScore: 0-100（越高越偏晨型）\n");
        sb.append("- focusDurationAvg: 15-120（分钟，代表用户实际/适合的单次专注时长）\n");
        sb.append("- procrastinationIndex: 0-1（越高越拖延）\n");
        sb.append("- recommendation.focusMinutes: 25-90（连续值，根据用户专注能力和拖延倾向推荐）；breakMinutes: 5-25（约为专注时长的 25%，四舍五入到 5 的倍数）；maxDailyMinutes: 120-300\n");
        sb.append("- tips 给 3-6 条中文短建议。\n\n");

        sb.append("=== 后端已精确计算的指标（请基于这些做分析，可在合理范围内微调） ===\n");
        if (req.getOnTimeRate() != null)
            sb.append("准时率: ").append(String.format("%.0f%%", req.getOnTimeRate() * 100)).append("\n");
        if (req.getAvgDelayMinutes() != null)
            sb.append("平均延迟: ").append(String.format("%.0f min", req.getAvgDelayMinutes())).append("\n");
        if (req.getCompletionRate() != null)
            sb.append("完成率: ").append(String.format("%.0f%%", req.getCompletionRate() * 100)).append("\n");
        if (req.getMatchedPunchCount() != null)
            sb.append("匹配打卡次数: ").append(req.getMatchedPunchCount()).append("\n");
        sb.append("连续打卡天数: ").append(streak).append("\n");
        if (req.getMorningPersonScore() != null)
            sb.append("晨型倾向(本地计算): ").append(req.getMorningPersonScore()).append("\n");
        if (req.getFocusDurationAvg() != null)
            sb.append("平均专注时长(本地计算): ").append(req.getFocusDurationAvg()).append(" min\n");
        if (req.getProcrastinationIndex() != null)
            sb.append("拖延指数(本地计算): ").append(String.format("%.2f", req.getProcrastinationIndex())).append("\n");
        sb.append("\n");

        if (journalSnippets != null && !journalSnippets.isEmpty()) {
            sb.append("用户近期随笔片段（来自向量检索，反映情绪与学习状态）：\n");
            for (String snippet : journalSnippets) {
                sb.append("- ").append(snippet).append("\n");
            }
            sb.append("\n");
        }

        sb.append("=== 原始数据（供交叉验证） ===\n");
        sb.append("打卡记录（start-end, createdAt, taskId, durationMinutes）：\n");
        if (records != null) {
            for (PunchRecordDto r : records) {
                if (r == null || r.getCreatedAt() == null) continue;
                Integer sec = r.getDurationSeconds();
                int mins = sec != null && sec > 0 ? Math.max(1, (int) Math.round(sec / 60.0)) : 0;
                if (r.getStartedAt() != null && r.getEndedAt() != null) {
                    sb.append("- ").append(fmt.format(r.getStartedAt())).append(" - ").append(fmt.format(r.getEndedAt())).append(", ");
                } else {
                    sb.append("- -, ");
                }
                sb.append("createdAt=").append(fmt.format(r.getCreatedAt()))
                        .append(", taskId=").append(r.getTaskId())
                        .append(", durationMinutes=").append(mins)
                        .append("\n");
            }
        }
        sb.append("排程记录（startTime-endTime, taskId, status）：\n");
        if (schedules != null) {
            for (TaskScheduleDto s : schedules) {
                if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
                sb.append("- ").append(fmt.format(s.getStartTime())).append(" - ").append(fmt.format(s.getEndTime()));
                sb.append(", taskId=").append(s.getTaskId()).append(", status=").append(s.getStatus()).append("\n");
            }
        }
        return sb.toString();
    }
}
