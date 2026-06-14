package com.chao.goal.service;

import com.chao.common.dto.GoalTaskDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AiResponseParser {

    List<GoalTaskDto> sanitizeTasks(List<GoalTaskDto> input) {
        if (input == null || input.isEmpty()) return List.of();
        return input.stream()
                .filter(Objects::nonNull)
                .map(this::sanitizeOne)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    GoalTaskDto sanitizeOne(GoalTaskDto t) {
        String title = t.getTitle() == null ? "" : t.getTitle().trim();
        if (title.isBlank()) return null;
        if (isForbiddenTitle(title)) return null;
        if (containsDateOrTime(title)) return null;

        String desc = t.getDescription() == null ? "" : t.getDescription().trim();
        if (containsDateOrTime(desc)) return null;
        if (isForbiddenDescription(desc)) return null;

        Integer minutes = t.getEstimatedMinutes();
        if (minutes == null || minutes < 15 || minutes > 240) {
            minutes = 45;
        }

        Integer pr = t.getPriority();
        if (pr == null) pr = 1;
        if (pr < 0) pr = 0;
        if (pr > 2) pr = 2;

        GoalTaskDto out = new GoalTaskDto();
        out.setTitle(title);
        out.setDescription(desc);
        out.setEstimatedMinutes(minutes);
        out.setPriority(pr);
        if (t.getSubTasks() != null && !t.getSubTasks().isEmpty()) {
            List<GoalTaskDto> subs = t.getSubTasks().stream()
                    .filter(Objects::nonNull)
                    .map(this::sanitizeOne)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            out.setSubTasks(subs);
        } else {
            out.setSubTasks(List.of());
        }
        return out;
    }

    private boolean isForbiddenTitle(String title) {
        String s = title.replace(" ", "");
        return s.contains("制定学习计划") || s.contains("生成学习计划") || s.contains("安排学习计划")
                || s.contains("安排日程") || s.contains("排程") || s.contains("设置提醒") || s.contains("整理计划");
    }

    private boolean isForbiddenDescription(String desc) {
        String s = desc.replace(" ", "");
        return s.contains("制定学习计划") || s.contains("生成学习计划") || s.contains("安排日程") || s.contains("排程") || s.contains("设置提醒");
    }

    boolean containsDateOrTime(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*\\d{4}-\\d{2}-\\d{2}.*") || text.matches(".*\\b\\d{1,2}:\\d{2}\\b.*");
    }
}
