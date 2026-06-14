package com.chao.schedule.service;

import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.entity.TaskSchedule;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ScheduleValidator {

    public List<TaskScheduleDto> filterOverlaps(List<TaskScheduleDto> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return List.of();
        }
        List<TaskScheduleDto> list = schedules.stream()
                .filter(s -> s.getStartTime() != null && s.getEndTime() != null && s.getTaskId() != null)
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .collect(Collectors.toList());
        List<TaskScheduleDto> out = new ArrayList<>();
        LocalDateTime lastEnd = null;
        for (TaskScheduleDto s : list) {
            if (lastEnd != null && s.getStartTime().isBefore(lastEnd)) {
                continue;
            }
            out.add(s);
            lastEnd = s.getEndTime();
        }
        return out;
    }

    public List<TaskScheduleDto> enforceMinGap(List<TaskScheduleDto> schedules, int gapMinutes) {
        if (schedules == null || schedules.isEmpty()) {
            return List.of();
        }
        List<TaskScheduleDto> list = schedules.stream()
                .filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null)
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .collect(Collectors.toList());
        List<TaskScheduleDto> out = new ArrayList<>();
        LocalDateTime lastEnd = null;
        for (TaskScheduleDto s : list) {
            if (lastEnd != null) {
                LocalDateTime minStart = lastEnd.plusMinutes(gapMinutes);
                if (s.getStartTime().isBefore(minStart)) {
                    long duration = java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
                    if (duration <= 0) continue;
                    s.setStartTime(minStart);
                    s.setEndTime(minStart.plusMinutes(duration));
                }
            }
            out.add(s);
            lastEnd = s.getEndTime();
        }
        return out;
    }

    public List<TaskScheduleDto> clampDailyMinutes(List<TaskScheduleDto> schedules, int maxMinutes) {
        if (schedules == null || schedules.isEmpty()) {
            return List.of();
        }
        int used = 0;
        List<TaskScheduleDto> out = new ArrayList<>();
        for (TaskScheduleDto s : schedules) {
            long m = java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
            if (m <= 0) {
                continue;
            }
            if (used >= maxMinutes) {
                break;
            }
            int allow = maxMinutes - used;
            if (m > allow) {
                TaskScheduleDto cut = new TaskScheduleDto();
                cut.setTaskId(s.getTaskId());
                cut.setTaskTitle(s.getTaskTitle());
                cut.setStartTime(s.getStartTime());
                cut.setEndTime(s.getStartTime().plusMinutes(allow));
                cut.setStatus(0);
                out.add(cut);
                used = maxMinutes;
                break;
            }
            out.add(s);
            used += (int) m;
        }
        return out;
    }

    public List<FreeSlotDto> subtractOccupied(List<FreeSlotDto> freeSlots, List<TaskSchedule> occupiedSchedules, int breakMinutes) {
        if (freeSlots == null || freeSlots.isEmpty()) {
            return List.of();
        }
        List<FreeSlotDto> slots = freeSlots.stream()
                .filter(s -> s != null && s.getStart() != null && s.getEnd() != null && s.getEnd().isAfter(s.getStart()))
                .sorted((a, b) -> a.getStart().compareTo(b.getStart()))
                .collect(Collectors.toList());
        if (occupiedSchedules == null || occupiedSchedules.isEmpty()) {
            return slots;
        }
        List<FreeSlotDto> out = new ArrayList<>();
        for (FreeSlotDto slot : slots) {
            LocalDateTime cur = slot.getStart();
            for (TaskSchedule occ : occupiedSchedules) {
                if (occ == null || occ.getStartTime() == null || occ.getEndTime() == null || !occ.getEndTime().isAfter(occ.getStartTime())) {
                    continue;
                }
                if (!occ.getStartTime().isBefore(slot.getEnd()) || !occ.getEndTime().isAfter(slot.getStart())) {
                    continue;
                }
                LocalDateTime a = occ.getStartTime().isBefore(slot.getStart()) ? slot.getStart() : occ.getStartTime();
                LocalDateTime b = occ.getEndTime().isAfter(slot.getEnd()) ? slot.getEnd() : occ.getEndTime();
                if (a.isAfter(cur)) {
                    FreeSlotDto f = new FreeSlotDto();
                    f.setStart(cur);
                    f.setEnd(a);
                    out.add(f);
                }
                if (b.isAfter(cur)) {
                    cur = b.plusMinutes(breakMinutes);
                }
                if (!cur.isBefore(slot.getEnd())) {
                    break;
                }
            }
            if (cur.isBefore(slot.getEnd())) {
                FreeSlotDto f = new FreeSlotDto();
                f.setStart(cur);
                f.setEnd(slot.getEnd());
                out.add(f);
            }
        }
        return out.stream()
                .filter(s -> java.time.Duration.between(s.getStart(), s.getEnd()).toMinutes() >= 10)
                .collect(Collectors.toList());
    }

    public boolean allWithinFreeSlots(List<TaskScheduleDto> schedules, List<FreeSlotDto> freeSlots) {
        if (schedules == null || schedules.isEmpty()) {
            return true;
        }
        if (freeSlots == null || freeSlots.isEmpty()) {
            return true;
        }
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null || s.getEndTime() == null) {
                return false;
            }
            boolean ok = false;
            for (FreeSlotDto f : freeSlots) {
                if (f == null || f.getStart() == null || f.getEnd() == null) {
                    continue;
                }
                boolean within = !s.getStartTime().isBefore(f.getStart()) && !s.getEndTime().isAfter(f.getEnd());
                if (within) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
