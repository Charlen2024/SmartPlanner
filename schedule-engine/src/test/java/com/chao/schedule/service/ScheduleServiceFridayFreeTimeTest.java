package com.chao.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ScheduleClient;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import com.chao.schedule.mapper.UserScheduleConfigMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class ScheduleServiceFridayFreeTimeTest {

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @Test
    void fridayPeriods12_shouldNeverBeFree_forWeeks1to17() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ClassSchedule> dbClasses = new ArrayList<>();

        ClassScheduleMapper classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(),
                new Class[]{ClassScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name)) return new ArrayList<>(dbClasses);
                    if ("selectCount".equals(name)) return (long) dbClasses.size();
                    if ("delete".equals(name)) { dbClasses.clear(); return 1; }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof ClassSchedule c) {
                            dbClasses.add(c);
                            return 1;
                        }
                        return 1;
                    }
                    return nullDefault(method.getReturnType());
                }
        );
        TaskScheduleMapper taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(), new Class[]{TaskScheduleMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        PlanCandidateMapper planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(), new Class[]{PlanCandidateMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        UserScheduleConfigMapper userScheduleConfigMapper = (UserScheduleConfigMapper) Proxy.newProxyInstance(
                UserScheduleConfigMapper.class.getClassLoader(), new Class[]{UserScheduleConfigMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));

        ScheduleService scheduleService = new ScheduleService(
                classScheduleMapper, taskScheduleMapper, planCandidateMapper,
                userScheduleConfigMapper, null, null, objectMapper, null
        );

        // Import CSV without firstWeekMonday (so we skip UserScheduleConfig insert/update logic)
        String csv = """
                课程名称,星期,开始节数,结束节数,地点,周数
                概率论与数理统计,5,1,2,三教408,1-16
                数据库原理课程设计,5,1,2,电教机房406,17
                大学英语4,5,3,4,电教机房203,1-16双
                数据库原理课程设计,5,3,4,电教机房406,17
                数据库原理课程设计,5,5,6,电教机房406,17
                数据库原理课程设计,5,7,8,电教机房406,17
                """;
        MultipartFile file = new ScheduleServiceCsvImportTest.SimpleMultipartFile(
                "friday.csv", csv.getBytes(StandardCharsets.UTF_8));
        scheduleService.parseAndSaveSchedule(1L, file, null);

        LocalDate may29 = LocalDate.of(2026, 5, 29);
        Assertions.assertEquals(5, may29.getDayOfWeek().getValue(), "May 29, 2026 should be Friday (5)");

        // Test week numbers 1 through 17 — periods 1-2 should always be occupied
        for (int wn = 1; wn <= 17; wn++) {
            // Compute a firstWeekMonday that yields this week number for May 29
            // weekNumber = floor(daysBetween / 7) + 1
            // So daysBetween must be in [(wn-1)*7, wn*7-1]
            // Pick daysBetween = (wn-1)*7 → firstWeekMonday = may29 - daysBetween
            int daysBetween = (wn - 1) * 7;
            LocalDate fwm = may29.minusDays(daysBetween);

            List<ScheduleClient.TimeSlot> freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), fwm.toString());

            // Verify 8:00-9:40 is NOT free
            LocalDateTime checkStart = LocalDateTime.of(may29, LocalTime.of(8, 0));
            LocalDateTime checkEnd = LocalDateTime.of(may29, LocalTime.of(9, 40));
            for (ScheduleClient.TimeSlot s : freeSlots) {
                boolean covers = !s.getStart().isAfter(checkStart) && !s.getEnd().isBefore(checkEnd);
                Assertions.assertFalse(covers,
                        "Week " + wn + " (fwm=" + fwm + "): 8:00-9:40 should NOT be free, but found slot " +
                        s.getStart() + " - " + s.getEnd());
            }

            // Also verify the actual week calculation is correct
            long actualDays = java.time.temporal.ChronoUnit.DAYS.between(fwm, may29);
            int actualWn = (int) Math.floor(actualDays / 7.0) + 1;
            Assertions.assertEquals(wn, actualWn, "Week number calculation mismatch for fwm=" + fwm);
        }
    }

    @Test
    void fridayFreeTime_withActualCsv_noWeekFilter_shouldHaveClassAt8am() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ClassSchedule> dbClasses = new ArrayList<>();

        ClassScheduleMapper classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(),
                new Class[]{ClassScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name)) return new ArrayList<>(dbClasses);
                    if ("selectCount".equals(name)) return (long) dbClasses.size();
                    if ("delete".equals(name)) { dbClasses.clear(); return 1; }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof ClassSchedule c) {
                            dbClasses.add(c);
                            return 1;
                        }
                        return 1;
                    }
                    return nullDefault(method.getReturnType());
                }
        );
        TaskScheduleMapper taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(), new Class[]{TaskScheduleMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        PlanCandidateMapper planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(), new Class[]{PlanCandidateMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        UserScheduleConfigMapper userScheduleConfigMapper = (UserScheduleConfigMapper) Proxy.newProxyInstance(
                UserScheduleConfigMapper.class.getClassLoader(), new Class[]{UserScheduleConfigMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));

        ScheduleService scheduleService = new ScheduleService(
                classScheduleMapper, taskScheduleMapper, planCandidateMapper,
                userScheduleConfigMapper, null, null, objectMapper, null
        );

        // Full CSV data
        String csv = """
                课程名称,星期,开始节数,结束节数,地点,周数
                WEB前端技术,1,1,2,一教机房502,12-14
                WEB前端技术,1,3,4,一教机房502,4-16
                WEB前端技术,1,5,6,福祉A504,4-11
                大学英语4,1,7,8,福祉A402,1-16
                操作系统,2,3,4,福祉A504,1-16
                概率论与数理统计,3,1,2,三教408,1-16
                数据库原理,3,3,4,电教机房201,9-16
                大学体育4,3,5,6,,2-17
                操作系统,4,1,2,福祉A503,1-8
                数据库原理,4,3,4,福祉B514,1-16
                概率论与数理统计,5,1,2,三教408,1-16
                数据库原理课程设计,5,1,2,电教机房406,17
                大学英语4,5,3,4,电教机房203,1-16双
                数据库原理课程设计,5,3,4,电教机房406,17
                数据库原理课程设计,5,5,6,电教机房406,17
                数据库原理课程设计,5,7,8,电教机房406,17
                数据库原理课程设计,6,1,2,电教机房406,17
                数据库原理课程设计,6,3,4,电教机房406,17
                数据库原理课程设计,6,5,6,电教机房406,17
                数据库原理课程设计,6,7,8,电教机房406,17
                python语言基础,4,7,8,福祉A505,1-8
                python语言基础,4,9,10,电教机房401,1-16
                """;
        MultipartFile file = new ScheduleServiceCsvImportTest.SimpleMultipartFile(
                "full.csv", csv.getBytes(StandardCharsets.UTF_8));
        scheduleService.parseAndSaveSchedule(1L, file, null);

        Assertions.assertEquals(22, dbClasses.size(), "All 22 rows should be imported");

        LocalDate may29 = LocalDate.of(2026, 5, 29);

        // Test 1: No week filtering (firstWeekMonday = null) — should include ALL classes
        List<ScheduleClient.TimeSlot> freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), null);
        printSlots("No week filter (null firstWeekMonday)", freeSlots);

        // 8:00-9:40 should be occupied by 概率论 or DB课程设计
        LocalDateTime checkStart = LocalDateTime.of(may29, LocalTime.of(8, 0));
        LocalDateTime checkEnd = LocalDateTime.of(may29, LocalTime.of(9, 40));
        for (ScheduleClient.TimeSlot s : freeSlots) {
            boolean covers = !s.getStart().isAfter(checkStart) && !s.getEnd().isBefore(checkEnd);
            Assertions.assertFalse(covers,
                    "Without week filter: 8:00-9:40 should NOT be free. Found slot: " +
                    s.getStart() + " - " + s.getEnd());
        }

        // Test 2: With firstWeekMonday=2026-02-23 (reasonable semester start)
        // For May 29: daysBetween = 95, weekNumber = 14
        freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), "2026-02-23");
        printSlots("Week 14 (fwm=2026-02-23)", freeSlots);
        for (ScheduleClient.TimeSlot s : freeSlots) {
            boolean covers = !s.getStart().isAfter(checkStart) && !s.getEnd().isBefore(checkEnd);
            Assertions.assertFalse(covers,
                    "Week 14: 8:00-9:40 should NOT be free. Found slot: " +
                    s.getStart() + " - " + s.getEnd());
        }

        // Test 3: With firstWeekMonday set to the Monday of the week containing May 29
        // firstWeekMonday = 2026-05-25 (Monday of that week)
        // daysBetween = 4, weekNumber = 0 + 1 = 1
        freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), "2026-05-25");
        printSlots("Week 1 (fwm=2026-05-25, current week monday)", freeSlots);
        for (ScheduleClient.TimeSlot s : freeSlots) {
            boolean covers = !s.getStart().isAfter(checkStart) && !s.getEnd().isBefore(checkEnd);
            Assertions.assertFalse(covers,
                    "Week 1: 8:00-9:40 should NOT be free. Found slot: " +
                    s.getStart() + " - " + s.getEnd());
        }

        // Test 4: Bad firstWeekMonday — set to a future date
        // firstWeekMonday = 2026-06-01, daysBetween = -3, weekNumber = 0
        freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), "2026-06-01");
        printSlots("Week 0 (fwm=2026-06-01, future date)", freeSlots);
        // Week 0 filters out ALL classes with week ranges → entire day free
        // This is expected behavior since week 0 is outside all defined week ranges
        boolean entireDayFree = freeSlots.stream().anyMatch(s -> {
            LocalDateTime dayStart = LocalDateTime.of(may29, LocalTime.of(8, 0));
            LocalDateTime dayEnd = LocalDateTime.of(may29, LocalTime.of(22, 0));
            return !s.getStart().isAfter(dayStart) && !s.getEnd().isBefore(dayEnd);
        });
        if (entireDayFree) {
            System.out.println("Week 0: Entire day free (expected — week 0 outside all ranges)");
        }

        // Test 5: Very large week number (e.g., week 39)
        // firstWeekMonday = 2025-09-01, daysBetween = 270, weekNumber = 39
        freeSlots = scheduleService.calculateFreeTime(1L, may29.toString(), "2025-09-01");
        printSlots("Week 39 (fwm=2025-09-01, distant past)", freeSlots);
    }

    @Test
    void debugFridayClassCoverage() {
        // Check exactly which weeks are covered for Friday periods 1-2 and 3-4
        ObjectMapper objectMapper = new ObjectMapper();
        List<ClassSchedule> dbClasses = new ArrayList<>();

        ClassScheduleMapper classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(),
                new Class[]{ClassScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name)) return new ArrayList<>(dbClasses);
                    if ("selectCount".equals(name)) return (long) dbClasses.size();
                    if ("delete".equals(name)) { dbClasses.clear(); return 1; }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof ClassSchedule c) {
                            dbClasses.add(c);
                            return 1;
                        }
                        return 1;
                    }
                    return nullDefault(method.getReturnType());
                }
        );
        TaskScheduleMapper taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(), new Class[]{TaskScheduleMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        PlanCandidateMapper planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(), new Class[]{PlanCandidateMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        UserScheduleConfigMapper userScheduleConfigMapper = (UserScheduleConfigMapper) Proxy.newProxyInstance(
                UserScheduleConfigMapper.class.getClassLoader(), new Class[]{UserScheduleConfigMapper.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));

        ScheduleService scheduleService = new ScheduleService(
                classScheduleMapper, taskScheduleMapper, planCandidateMapper,
                userScheduleConfigMapper, null, null, objectMapper, null
        );

        // Import the Friday-specific rows
        String csv = """
                课程名称,星期,开始节数,结束节数,地点,周数
                概率论与数理统计,5,1,2,三教408,1-16
                数据库原理课程设计,5,1,2,电教机房406,17
                大学英语4,5,3,4,电教机房203,1-16双
                数据库原理课程设计,5,3,4,电教机房406,17
                数据库原理课程设计,5,5,6,电教机房406,17
                数据库原理课程设计,5,7,8,电教机房406,17
                """;
        MultipartFile file = new ScheduleServiceCsvImportTest.SimpleMultipartFile(
                "friday.csv", csv.getBytes(StandardCharsets.UTF_8));
        scheduleService.parseAndSaveSchedule(1L, file, null);

        System.out.println("=== Friday class coverage analysis ===");
        System.out.println("Friday classes in DB: " + dbClasses.size());

        for (ClassSchedule c : dbClasses) {
            System.out.println("  " + c.getCourseName() + " | dow=" + c.getDayOfWeek() +
                    " | " + c.getStartTime() + "-" + c.getEndTime() +
                    " | weeks=" + c.getWeekStart() + "-" + c.getWeekEnd() +
                    " | type=" + c.getWeekType());
        }

        System.out.println("\nWeek coverage for Friday periods 1-2 (8:00-9:40):");
        for (int wn = 1; wn <= 18; wn++) {
            final int week = wn;
            boolean covered = dbClasses.stream().anyMatch(c ->
                    c.getDayOfWeek() == 5 &&
                    c.getStartTime().equals(LocalTime.of(8, 0)) &&
                    matchesWeekStatic(c, week));
            System.out.println("  Week " + week + ": " + (covered ? "OCCUPIED" : "FREE"));
        }

        System.out.println("\nWeek coverage for Friday periods 3-4 (10:00-11:40):");
        for (int wn = 1; wn <= 18; wn++) {
            final int week = wn;
            boolean covered = dbClasses.stream().anyMatch(c ->
                    c.getDayOfWeek() == 5 &&
                    c.getStartTime().equals(LocalTime.of(10, 0)) &&
                    matchesWeekStatic(c, week));
            System.out.println("  Week " + week + ": " + (covered ? "OCCUPIED" : "FREE"));
        }

        LocalDate may29 = LocalDate.of(2026, 5, 29);
        System.out.println("\nFree time for May 29, 2026 with various firstWeekMonday values:");
        String[] fwmValues = {null, "2026-02-23", "2026-05-25", "2026-06-01"};
        for (String fwm : fwmValues) {
            List<ScheduleClient.TimeSlot> slots = scheduleService.calculateFreeTime(1L, may29.toString(), fwm);
            System.out.println("  fwm=" + fwm + ":");
            for (ScheduleClient.TimeSlot s : slots) {
                System.out.println("    Free: " + s.getStart() + " - " + s.getEnd());
            }
        }
    }

    private static boolean matchesWeekStatic(ClassSchedule cs, int weekNumber) {
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

    private void printSlots(String label, List<ScheduleClient.TimeSlot> slots) {
        System.out.println("--- " + label + " ---");
        for (ScheduleClient.TimeSlot s : slots) {
            System.out.println("  Free: " + s.getStart() + " - " + s.getEnd());
        }
    }
}
