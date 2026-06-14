package com.chao.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.common.client.ScheduleClient;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.UserScheduleConfig;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.UserScheduleConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeTimeCalculator {

    private final ClassScheduleMapper classScheduleMapper;
    private final UserScheduleConfigMapper userScheduleConfigMapper;

    public List<ClassSchedule> listClassSchedules(Long userId, Integer dayOfWeek, String date, String firstWeekMonday) {
        if (dayOfWeek == null && date != null && !date.isBlank()) {
            dayOfWeek = LocalDate.parse(date).getDayOfWeek().getValue();
        }
        LambdaQueryWrapper<ClassSchedule> qw = new LambdaQueryWrapper<ClassSchedule>()
                .eq(ClassSchedule::getUserId, userId)
                .orderByAsc(ClassSchedule::getDayOfWeek)
                .orderByAsc(ClassSchedule::getStartTime);
        if (dayOfWeek != null) {
            qw.eq(ClassSchedule::getDayOfWeek, dayOfWeek);
        }
        List<ClassSchedule> classes = classScheduleMapper.selectList(qw);

        if (date != null && !date.isBlank() && !classes.isEmpty()) {
            if (firstWeekMonday == null || firstWeekMonday.isBlank()) {
                try {
                    UserScheduleConfig cfg = userScheduleConfigMapper.selectById(userId);
                    if (cfg != null && cfg.getFirstWeekMonday() != null) {
                        firstWeekMonday = cfg.getFirstWeekMonday().toString();
                    }
                } catch (Exception ignored) {
                }
            }
            if (firstWeekMonday != null && !firstWeekMonday.isBlank()) {
                LocalDate targetDate = LocalDate.parse(date);
                LocalDate fwm = LocalDate.parse(firstWeekMonday);
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(fwm, targetDate);
                int weekNumber = (int) Math.floor(daysBetween / 7.0) + 1;
                classes = classes.stream().filter(c -> matchesWeek(c, weekNumber)).collect(Collectors.toList());
            }
        }
        return classes;
    }

    public List<ScheduleClient.TimeSlot> calculateFreeTime(Long userId, String dateStr) {
        String fwm = null;
        try {
            UserScheduleConfig cfg = userScheduleConfigMapper.selectById(userId);
            if (cfg != null && cfg.getFirstWeekMonday() != null) {
                fwm = cfg.getFirstWeekMonday().toString();
            }
        } catch (Exception ignored) {
        }
        return calculateFreeTime(userId, dateStr, fwm);
    }

    public List<ScheduleClient.TimeSlot> calculateFreeTime(Long userId, String dateStr, String firstWeekMonday) {
        LocalDate date = LocalDate.parse(dateStr);
        int dayOfWeek = date.getDayOfWeek().getValue();

        log.info("计算用户 {} 在 {} (星期{}) 的空闲时间, firstWeekMonday={}", userId, date, dayOfWeek, firstWeekMonday);

        Integer weekNumber = null;
        if (firstWeekMonday != null && !firstWeekMonday.isBlank()) {
            LocalDate fwm = LocalDate.parse(firstWeekMonday);
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(fwm, date);
            weekNumber = (int) Math.floor(daysBetween / 7.0) + 1;
        }

        List<ClassSchedule> classes = classScheduleMapper.selectList(new LambdaQueryWrapper<ClassSchedule>()
                .eq(ClassSchedule::getUserId, userId)
                .eq(ClassSchedule::getDayOfWeek, dayOfWeek)
                .orderByAsc(ClassSchedule::getStartTime));

        if (weekNumber != null) {
            final Integer wn = weekNumber;
            List<ClassSchedule> filtered = classes.stream().filter(c -> matchesWeek(c, wn)).collect(Collectors.toList());
            if (filtered.isEmpty() && !classes.isEmpty() && (wn < 1 || wn > 20)) {
                log.warn("周过滤后无课程（weekNumber={}），且周号异常，回退为不过滤。请检查 firstWeekMonday 配置是否正确。", wn);
            } else {
                classes = filtered;
            }
        }

        log.info("找到 {} 门课程（周过滤后）", classes.size());

        LocalTime studyStart = LocalTime.of(8, 0);
        LocalTime studyEnd = LocalTime.of(22, 0);

        List<ScheduleClient.TimeSlot> freeSlots = new ArrayList<>();

        if (classes.isEmpty()) {
            freeSlots.add(createSlot(date, studyStart, studyEnd));
            Long total = classScheduleMapper.selectCount(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
            if (total != null && total > 0) {
                log.info("当天无课（课表共 {} 门课程），空闲时间: {} - {}", total, studyStart, studyEnd);
            } else {
                log.info("课表为空，空闲时间: {} - {}", studyStart, studyEnd);
            }
            return freeSlots;
        }

        ClassSchedule lunch = new ClassSchedule();
        lunch.setStartTime(LocalTime.of(12, 0));
        lunch.setEndTime(LocalTime.of(14, 0));
        classes.add(lunch);

        classes.sort((a, b) -> a.getStartTime().compareTo(b.getStartTime()));

        ClassSchedule firstClass = classes.get(0);
        if (firstClass.getStartTime().isAfter(studyStart)) {
            freeSlots.add(createSlot(date, studyStart, firstClass.getStartTime()));
        }

        LocalTime lastEnd = firstClass.getEndTime();
        for (int i = 1; i < classes.size(); i++) {
            ClassSchedule current = classes.get(i);

            if (current.getStartTime().isBefore(lastEnd)) {
                if (current.getEndTime().isAfter(lastEnd)) {
                    lastEnd = current.getEndTime();
                }
                continue;
            }

            if (current.getStartTime().isAfter(lastEnd)) {
                LocalTime freeStart = lastEnd.isBefore(studyStart) ? studyStart : lastEnd;
                LocalTime freeEnd = current.getStartTime().isAfter(studyEnd) ? studyEnd : current.getStartTime();
                if (freeEnd.isAfter(freeStart)) {
                    freeSlots.add(createSlot(date, freeStart, freeEnd));
                }
            }

            lastEnd = current.getEndTime().isAfter(lastEnd) ? current.getEndTime() : lastEnd;
        }

        if (lastEnd.isBefore(studyEnd)) {
            LocalTime freeStart = lastEnd.isBefore(studyStart) ? studyStart : lastEnd;
            if (studyEnd.isAfter(freeStart)) {
                freeSlots.add(createSlot(date, freeStart, studyEnd));
            }
        }

        freeSlots.removeIf(slot -> {
            long minutes = java.time.Duration.between(slot.getStart(), slot.getEnd()).toMinutes();
            return minutes < 30;
        });

        log.info("计算出 {} 个空闲时段（已过滤 <30分钟）", freeSlots.size());
        for (ScheduleClient.TimeSlot slot : freeSlots) {
            log.debug("空闲时段: {} - {}", slot.getStart(), slot.getEnd());
        }

        return freeSlots;
    }

    private ScheduleClient.TimeSlot createSlot(LocalDate date, LocalTime start, LocalTime end) {
        ScheduleClient.TimeSlot slot = new ScheduleClient.TimeSlot();
        slot.setStart(LocalDateTime.of(date, start));
        slot.setEnd(LocalDateTime.of(date, end));
        return slot;
    }

    boolean matchesWeek(ClassSchedule cs, int weekNumber) {
        Integer ws = cs.getWeekStart();
        Integer we = cs.getWeekEnd();
        if (ws == null || we == null) return true;
        if (weekNumber < ws || weekNumber > we) return false;
        String wt = cs.getWeekType();
        if (wt == null) return true;
        if ("even".equalsIgnoreCase(wt)) return weekNumber % 2 == 0;
        if ("odd".equalsIgnoreCase(wt)) return weekNumber % 2 == 1;
        return true;
    }
}
