package com.chao.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class ScheduleServiceCsvImportTest {

    @Test
    void csvImportAndFreeTime_shouldWork() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ClassSchedule> inserted = new ArrayList<>();
        final List<ClassSchedule>[] selectListReturn = new List[]{List.of()};

        ClassScheduleMapper classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(),
                new Class[]{ClassScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("delete".equals(name)) {
                        return 1;
                    }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof ClassSchedule c) {
                            inserted.add(c);
                            return 1;
                        }
                        return 1;
                    }
                    if ("selectList".equals(name)) {
                        return selectListReturn[0];
                    }
                    if ("selectCount".equals(name)) {
                        return 0L;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );
        TaskScheduleMapper taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(),
                new Class[]{TaskScheduleMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );
        PlanCandidateMapper planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(),
                new Class[]{PlanCandidateMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );

        ScheduleService scheduleService = new ScheduleService(
                classScheduleMapper,
                taskScheduleMapper,
                planCandidateMapper,
                null,
                null,
                objectMapper,
                null
        );

        String csv = """
                课程,星期,开始时间,结束时间,地点
                高等数学,1,8:00,9:40,教室A101
                数据结构,3,10:00,11:40,教室B203
                """;
        MultipartFile file = new SimpleMultipartFile("schedule.csv", csv.getBytes(StandardCharsets.UTF_8));

        ScheduleImportResultDto result = scheduleService.parseAndSaveSchedule(1L, file);
        Assertions.assertEquals("csv", result.getFormat());
        Assertions.assertEquals(2, result.getInserted());

        Assertions.assertEquals(2, inserted.size());

        LocalDate monday = LocalDate.of(2026, 4, 13);
        selectListReturn[0] = new ArrayList<>(List.of(
                buildClass(1L, 1, LocalTime.of(8, 0), LocalTime.of(9, 40), "高等数学"),
                buildClass(1L, 1, LocalTime.of(8, 30), LocalTime.of(10, 0), "高数补课")
        ));

        List<ScheduleClient.TimeSlot> freeSlots = scheduleService.calculateFreeTime(1L, monday.toString());
        Assertions.assertEquals(1, freeSlots.size());
        Assertions.assertEquals(LocalDateTime.of(monday, LocalTime.of(10, 0)), freeSlots.get(0).getStart());
        Assertions.assertEquals(LocalDateTime.of(monday, LocalTime.of(22, 0)), freeSlots.get(0).getEnd());
    }

    private static ClassSchedule buildClass(Long userId, int dow, LocalTime start, LocalTime end, String name) {
        ClassSchedule c = new ClassSchedule();
        c.setUserId(userId);
        c.setDayOfWeek(dow);
        c.setStartTime(start);
        c.setEndTime(end);
        c.setCourseName(name);
        return c;
    }

    static class SimpleMultipartFile implements MultipartFile {
        private final String fileName;
        private final byte[] bytes;

        SimpleMultipartFile(String fileName, byte[] bytes) {
            this.fileName = fileName;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return "text/csv";
        }

        @Override
        public boolean isEmpty() {
            return bytes == null || bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes == null ? 0 : bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException();
        }
    }
}
