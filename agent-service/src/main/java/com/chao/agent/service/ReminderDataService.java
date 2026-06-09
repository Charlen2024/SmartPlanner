package com.chao.agent.service;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.UserJournalDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
class ReminderDataService {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final GoalClient goalClient;

    TodayData loadToday(Long userId, LocalDate date) {
        String from = date + "T00:00:00";
        String to = date + "T23:59:59";
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from, to).getData());
        List<PunchRecordDto> records = safe(punchClient.listRecords(userId, null, from, to).getData());
        long streak = safeLong(punchClient.getStreak(userId).getData());

        Set<Long> punchedTaskIds = new HashSet<>();
        List<Integer> completionHours = new ArrayList<>();
        for (PunchRecordDto r : records) {
            if (r == null) continue;
            if (r.getTaskId() != null) punchedTaskIds.add(r.getTaskId());
            if (r.getCreatedAt() != null) completionHours.add(r.getCreatedAt().getHour());
        }

        List<String> completedTitles = new ArrayList<>();
        List<String> pendingTitles = new ArrayList<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            Long tid = s.getTaskId();
            boolean done = (s.getStatus() != null && s.getStatus() == 1) || (tid != null && punchedTaskIds.contains(tid));
            String title = s.getTaskTitle() != null && !s.getTaskTitle().isBlank() ? s.getTaskTitle().trim() : ("任务 " + (tid != null ? tid : ""));
            if (done) completedTitles.add(title);
            else pendingTitles.add(title);
        }

        completedTitles.sort(String::compareTo);
        pendingTitles.sort(String::compareTo);
        completionHours.sort(Comparator.naturalOrder());

        TodayData out = new TodayData();
        out.completedTitles = completedTitles;
        out.pendingTitles = pendingTitles;
        out.streak = streak;
        out.totalPlanCount = schedules.size();
        out.completionHours = completionHours;
        return out;
    }

    JournalData loadJournals(Long userId, LocalDateTime since) {
        List<UserJournalDto> all = safe(goalClient.listJournals(userId, null).getData());
        List<UserJournalDto> list = new ArrayList<>();
        for (UserJournalDto j : all) {
            if (j == null || j.getCreatedAt() == null) continue;
            if (j.getCreatedAt().isBefore(since)) continue;
            list.add(j);
        }
        list.sort(Comparator.comparing(UserJournalDto::getCreatedAt));

        JournalData out = new JournalData();
        out.noJournalDays = computeNoJournalDays(list);
        out.hasStressWords = containsStressWords(list);
        out.shortJournal7Days = isShortJournal7Days(list);
        return out;
    }

    String detectOftenDelayedTaskTitle(Long userId, LocalDateTime now) {
        LocalDateTime from = now.minusDays(7).toLocalDate().atStartOfDay();
        LocalDateTime to = now.toLocalDate().atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<String, Integer> lateCounts = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            String title = s.getTaskTitle() != null ? s.getTaskTitle().trim() : "";
            if (title.isBlank()) continue;
            LocalDateTime end = s.getEndTime();
            if (end == null || end.isAfter(now)) continue;
            if (s.getStatus() != null && s.getStatus() == 1) continue;
            lateCounts.put(title, (lateCounts.get(title) == null ? 0 : lateCounts.get(title)) + 1);
        }
        return lateCounts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= 3)
                .max(Comparator.comparingInt(e -> e.getValue() != null ? e.getValue() : 0))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    String detectStalledTaskTitle3Days(Long userId, LocalDate today) {
        LocalDate d1 = today.minusDays(1);
        LocalDate d2 = today.minusDays(2);
        LocalDateTime from = d2.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<String, Map<LocalDate, int[]>> stats = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            String title = s.getTaskTitle() != null ? s.getTaskTitle().trim() : "";
            if (title.isBlank()) continue;
            LocalDateTime st = s.getStartTime();
            if (st == null) continue;
            LocalDate day = st.toLocalDate();
            if (!(day.equals(today) || day.equals(d1) || day.equals(d2))) continue;
            Map<LocalDate, int[]> byDay = stats.computeIfAbsent(title, k -> new HashMap<>());
            int[] arr = byDay.computeIfAbsent(day, k -> new int[]{0, 0});
            arr[0] += 1;
            if (s.getStatus() != null && s.getStatus() == 1) arr[1] += 1;
        }

        for (var e : stats.entrySet()) {
            String title = e.getKey();
            Map<LocalDate, int[]> byDay = e.getValue();
            if (byDay == null) continue;
            if (!byDay.containsKey(today) || !byDay.containsKey(d1) || !byDay.containsKey(d2)) continue;
            if (byDay.get(today)[0] == 0 || byDay.get(d1)[0] == 0 || byDay.get(d2)[0] == 0) continue;
            if (byDay.get(today)[1] != 0) continue;
            if (byDay.get(d1)[1] != 0) continue;
            if (byDay.get(d2)[1] != 0) continue;
            return title;
        }
        return "";
    }

    boolean weekendOverloaded(Long userId, LocalDate today) {
        LocalDate sat = today.with(DayOfWeek.SATURDAY);
        if (!sat.isAfter(today)) sat = sat.plusWeeks(1);
        LocalDate sun = sat.plusDays(1);
        LocalDateTime from = sat.atStartOfDay();
        LocalDateTime to = sun.atTime(23, 59, 59);

        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());
        long minutes = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            long m = Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
            if (m > 0 && m < 24 * 60) minutes += m;
        }
        return minutes >= 8 * 60;
    }

    boolean isLowCompletion3Days(Long userId, LocalDate today) {
        LocalDate d0 = today, d1 = today.minusDays(1), d2 = today.minusDays(2);
        LocalDateTime from = d2.atStartOfDay();
        LocalDateTime to = d0.atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<LocalDate, int[]> map = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null) continue;
            LocalDate day = s.getStartTime().toLocalDate();
            if (!(day.equals(d0) || day.equals(d1) || day.equals(d2))) continue;
            int[] arr = map.computeIfAbsent(day, k -> new int[]{0, 0});
            arr[0] += 1;
            if (s.getStatus() != null && s.getStatus() == 1) arr[1] += 1;
        }
        if (!map.containsKey(d0) || !map.containsKey(d1) || !map.containsKey(d2)) return false;
        for (LocalDate d : List.of(d0, d1, d2)) {
            int[] arr = map.get(d);
            if (arr == null || arr[0] <= 0) return false;
            if (arr[1] * 1.0d / Math.max(1, arr[0]) >= 0.3d) return false;
        }
        return true;
    }

    boolean isNoPunch2Days(Long userId, LocalDate today) {
        LocalDate d0 = today, d1 = today.minusDays(1);
        LocalDateTime from = d1.atStartOfDay();
        LocalDateTime to = d0.atTime(23, 59, 59);
        List<PunchRecordDto> records = safe(punchClient.listRecords(userId, null, from.toString(), to.toString()).getData());
        boolean has0 = false, has1 = false;
        for (PunchRecordDto r : records) {
            if (r == null || r.getCreatedAt() == null) continue;
            LocalDate d = r.getCreatedAt().toLocalDate();
            if (d.equals(d0)) has0 = true;
            if (d.equals(d1)) has1 = true;
        }
        return !has0 && !has1;
    }

    // --- helpers ---
    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    private static long safeLong(Long v) {
        return v != null ? v : 0L;
    }

    private int computeNoJournalDays(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return 999;
        LocalDate latest = list.get(list.size() - 1).getCreatedAt().toLocalDate();
        LocalDate today = LocalDate.now(SHANGHAI);
        long days = Duration.between(latest.atStartOfDay(), today.atStartOfDay()).toDays();
        return (int) Math.max(0, days);
    }

    private boolean containsStressWords(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return false;
        Set<String> words = Set.of("焦虑", "压力", "崩溃", "很累", "抑郁", "烦", "难受");
        for (int i = Math.max(0, list.size() - 6); i < list.size(); i++) {
            UserJournalDto j = list.get(i);
            if (j == null) continue;
            String c = j.getContent() != null ? j.getContent() : "";
            for (String w : words) {
                if (!w.isBlank() && c.contains(w)) return true;
            }
            String mood = j.getMood() != null ? j.getMood() : "";
            for (String w : words) {
                if (!w.isBlank() && mood.contains(w)) return true;
            }
        }
        return false;
    }

    private boolean isShortJournal7Days(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return false;
        LocalDate today = LocalDate.now(SHANGHAI);
        Map<LocalDate, Integer> maxLen = new HashMap<>();
        for (UserJournalDto j : list) {
            if (j == null || j.getCreatedAt() == null) continue;
            LocalDate d = j.getCreatedAt().toLocalDate();
            if (d.isBefore(today.minusDays(6))) continue;
            int len = j.getContent() != null ? j.getContent().trim().length() : 0;
            maxLen.put(d, Math.max(maxLen.getOrDefault(d, 0), len));
        }
        if (maxLen.size() < 7) return false;
        for (int i = 0; i < 7; i++) {
            if (maxLen.getOrDefault(today.minusDays(i), 0) >= 60) return false;
        }
        return true;
    }

    static class TodayData {
        List<String> completedTitles = List.of();
        List<String> pendingTitles = List.of();
        long streak;
        int totalPlanCount;
        List<Integer> completionHours = List.of();
    }

    static class JournalData {
        int noJournalDays;
        boolean hasStressWords;
        boolean shortJournal7Days;
    }
}
