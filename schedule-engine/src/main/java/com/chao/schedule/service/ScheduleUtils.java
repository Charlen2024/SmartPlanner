package com.chao.schedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.TaskScheduleDto;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ScheduleUtils {

    private final ObjectMapper objectMapper;

    public String sanitizeJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstBrace = s.indexOf('{');
            int lastBrace = s.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                s = s.substring(firstBrace, lastBrace + 1).trim();
            }
        }
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }
        return s;
    }

    public List<FreeSlotDto> normalizeSlots(LocalDate date, List<FreeSlotDto> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<FreeSlotDto> list = slots.stream()
                .filter(s -> s != null && s.getStart() != null && s.getEnd() != null && s.getEnd().isAfter(s.getStart()))
                .filter(s -> s.getStart().toLocalDate().equals(date))
                .sorted((a, b) -> a.getStart().compareTo(b.getStart()))
                .collect(Collectors.toList());
        List<FreeSlotDto> out = new ArrayList<>();
        for (FreeSlotDto s : list) {
            FreeSlotDto last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last == null) {
                out.add(s);
                continue;
            }
            if (!s.getStart().isAfter(last.getEnd())) {
                if (s.getEnd().isAfter(last.getEnd())) {
                    last.setEnd(s.getEnd());
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    public List<TaskScheduleDto> normalizeSchedules(List<TaskScheduleDto> schedules) {
        if (schedules == null) {
            return List.of();
        }
        return schedules.stream()
                .filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null)
                .filter(s -> s.getEndTime().isAfter(s.getStartTime()))
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .collect(Collectors.toList());
    }

    public String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    public <T> T readJsonList(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    public CandidateAiResponse parseCandidateAiResponse(String aiJson) {
        try {
            String sanitized = sanitizeJsonObject(aiJson);
            return objectMapper.readValue(sanitized, CandidateAiResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("候选计划解析失败");
        }
    }

    @Data
    public static class CandidateAiResponse {
        String note;
        List<FreeSlotDto> suggestedFreeSlots;
        List<TaskScheduleDto> candidateSchedules;
        List<TaskScheduleDto> suggestedSchedules;
    }
}
