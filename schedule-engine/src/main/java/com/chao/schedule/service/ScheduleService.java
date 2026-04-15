package com.chao.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.GeneratePlanCandidateRequest;
import com.chao.common.dto.PlanCandidateDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.Result;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.PlanCandidate;
import com.chao.schedule.entity.TaskSchedule;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.multipart.MultipartFile;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ClassScheduleMapper classScheduleMapper;
    private final TaskScheduleMapper taskScheduleMapper;
    private final PlanCandidateMapper planCandidateMapper;
    private final GoalClient goalClient;
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;
    private final PlanCandidateWorker planCandidateWorker;

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long AI_TIMEOUT_SECONDS = 55;
    private static final int SESSION_MINUTES = 45;
    private static final int BREAK_MINUTES = 10;
    private static final int MAX_STUDY_MINUTES_PER_DAY = 240;

    private static final int CANDIDATE_STATUS_READY = 0;
    private static final int CANDIDATE_STATUS_ACCEPTED = 1;
    private static final int CANDIDATE_STATUS_REJECTED = 2;
    private static final int CANDIDATE_STATUS_GENERATING = 3;

    private static final String PLAN_SYSTEM_PROMPT = """
        你是一个学习计划排程助手。你将收到：
        1) 用户某天的空闲时间段列表（freeSlots）
        2) 用户待办学习任务列表（tasks）
        你的目标是给出“候选排程建议”（candidateSchedules），并且在你认为用户给出的 freeSlots 不合理/可优化时，给出 suggestedFreeSlots 以及基于 suggestedFreeSlots 的 suggestedSchedules。

        输出必须是严格 JSON（不要 Markdown、不要额外文字），格式：
        {
          "note": "简短说明",
          "suggestedFreeSlots": [{"start":"YYYY-MM-DDTHH:mm:ss","end":"YYYY-MM-DDTHH:mm:ss"}],
          "candidateSchedules": [{"taskId":123,"startTime":"YYYY-MM-DDTHH:mm:ss","endTime":"YYYY-MM-DDTHH:mm:ss"}],
          "suggestedSchedules": [{"taskId":123,"startTime":"YYYY-MM-DDTHH:mm:ss","endTime":"YYYY-MM-DDTHH:mm:ss"}]
        }

        约束：
        - candidateSchedules 的时间必须落在给定 freeSlots 内
        - 如果你输出了 suggestedFreeSlots，则 suggestedSchedules 的时间必须落在 suggestedFreeSlots 内
        - 两组 schedules 内部都不要重叠
        - 优先安排 priority 更高的任务；estimatedMinutes 越大越适合放到更长的空闲段
        - 每天最多 3 个深度任务（estimatedMinutes>=60 的视为深度任务）
        """;

    private static final String DAILY_PLAN_SYSTEM_PROMPT = """
        你是一个学习计划排程助手。请把任务分散安排到 freeSlots 中，严禁与课表冲突（必须完全落在 freeSlots 内）。
        请避免精疲力尽：采用 45 分钟学习 + 10 分钟休息的节奏，当天学习总时长不超过 240 分钟。

        输出必须是严格 JSON（不要 Markdown、不要额外文字），格式：
        {"note":"说明","candidateSchedules":[{"taskId":123,"taskTitle":"任务标题","startTime":"YYYY-MM-DDTHH:mm:ss","endTime":"YYYY-MM-DDTHH:mm:ss"}]}

        约束：
        - taskId 必须从 tasks 列表中的 id 里选择（严禁编造/使用序号）
        - taskTitle 必须与该 taskId 对应的 title 完全一致
        """;

    public ScheduleImportResultDto parseAndSaveSchedule(Long userId, MultipartFile file) {
        String fileName = file != null ? file.getOriginalFilename() : null;
        log.info("用户 {} 上传课表: {}", userId, fileName);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("课表文件为空");
        }

        String ext = fileName != null && fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";

        if (!"ics".equals(ext) && !"xlsx".equals(ext) && !"xls".equals(ext) && !"csv".equals(ext)) {
            throw new IllegalArgumentException("仅支持 .ics / .xlsx / .xls / .csv");
        }

        classScheduleMapper.delete(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));

        ScheduleImportResultDto result = new ScheduleImportResultDto();
        result.setFileName(fileName);
        result.setFormat(ext);
        result.setWarnings(new ArrayList<>());

        if ("ics".equals(ext)) {
            importFromIcs(userId, file, result);
            return result;
        }
        if ("csv".equals(ext)) {
            importFromCsv(userId, file, result);
            return result;
        }

        importFromExcel(userId, file, result);
        return result;
    }

    private void importFromIcs(Long userId, MultipartFile file, ScheduleImportResultDto result) {
        int inserted = 0;
        int skipped = 0;
        int total = 0;
        try {
            CalendarBuilder builder = new CalendarBuilder();
            Calendar calendar = builder.build(file.getInputStream());
            java.util.Set<String> dedup = new java.util.HashSet<>();
            for (Object component : calendar.getComponents(Component.VEVENT)) {
                total++;
                try {
                    VEvent event = (VEvent) component;
                    if (event.getSummary() == null || event.getStartDate() == null || event.getEndDate() == null) {
                        skipped++;
                        continue;
                    }
                    String courseName = event.getSummary().getValue();
                    LocalDateTime start = event.getStartDate().getDate().toInstant().atZone(APP_ZONE).toLocalDateTime();
                    LocalDateTime end = event.getEndDate().getDate().toInstant().atZone(APP_ZONE).toLocalDateTime();

                    if (!end.isAfter(start)) {
                        skipped++;
                        continue;
                    }

                    java.util.List<Integer> dows = new java.util.ArrayList<>();
                    net.fortuna.ical4j.model.Property rrule = event.getProperty(net.fortuna.ical4j.model.Property.RRULE);
                    if (rrule != null && rrule.getValue() != null) {
                        String v = rrule.getValue().toUpperCase(java.util.Locale.ROOT);
                        int idx = v.indexOf("BYDAY=");
                        if (idx >= 0) {
                            String rest = v.substring(idx + 6);
                            int semi = rest.indexOf(';');
                            if (semi >= 0) {
                                rest = rest.substring(0, semi);
                            }
                            for (String code : rest.split(",")) {
                                String c = code.trim();
                                if (c.endsWith("MO")) dows.add(1);
                                else if (c.endsWith("TU")) dows.add(2);
                                else if (c.endsWith("WE")) dows.add(3);
                                else if (c.endsWith("TH")) dows.add(4);
                                else if (c.endsWith("FR")) dows.add(5);
                                else if (c.endsWith("SA")) dows.add(6);
                                else if (c.endsWith("SU")) dows.add(7);
                            }
                        }
                    }
                    if (dows.isEmpty()) {
                        dows.add(start.getDayOfWeek().getValue());
                    }

                    String location = event.getLocation() != null ? event.getLocation().getValue() : null;
                    for (Integer dow : dows) {
                        if (dow == null) continue;
                        ClassSchedule schedule = new ClassSchedule();
                        schedule.setUserId(userId);
                        schedule.setCourseName(courseName);
                        schedule.setDayOfWeek(dow);
                        schedule.setStartTime(start.toLocalTime());
                        schedule.setEndTime(end.toLocalTime());
                        schedule.setLocation(location);
                        String key = (courseName == null ? "" : courseName) + "|" + dow + "|" + schedule.getStartTime() + "|" + schedule.getEndTime() + "|" + (location == null ? "" : location);
                        if (!dedup.add(key)) {
                            continue;
                        }
                        classScheduleMapper.insert(schedule);
                        inserted++;
                    }
                } catch (Exception rowEx) {
                    skipped++;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 .ics 失败：" + e.getMessage());
        }

        result.setTotal(total);
        result.setInserted(inserted);
        result.setSkipped(skipped);
        if (skipped > 0) {
            result.getWarnings().add("存在无法解析的事件，已跳过：" + skipped);
        }
    }

    private void importFromExcel(Long userId, MultipartFile file, ScheduleImportResultDto result) {
        int inserted = 0;
        int skipped = 0;
        int total = 0;
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                throw new IllegalArgumentException("Excel 为空");
            }

            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("缺少表头行");
            }

            Map<String, Integer> idx = resolveHeaderIndex(header);
            if (!idx.containsKey("course") || !idx.containsKey("dow") || !idx.containsKey("start") || !idx.containsKey("end")) {
                throw new IllegalArgumentException("表头至少需要：课程名/星期/开始时间/结束时间");
            }

            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String course = getCellString(row.getCell(idx.get("course")));
                String dowStr = getCellString(row.getCell(idx.get("dow")));
                String startStr = getCellString(row.getCell(idx.get("start")));
                String endStr = getCellString(row.getCell(idx.get("end")));
                String location = idx.containsKey("loc") ? getCellString(row.getCell(idx.get("loc"))) : null;

                boolean empty = (course == null || course.isBlank()) && (dowStr == null || dowStr.isBlank()) && (startStr == null || startStr.isBlank()) && (endStr == null || endStr.isBlank());
                if (empty) {
                    continue;
                }
                total++;
                try {
                    Integer dow = parseDayOfWeek(dowStr);
                    LocalTime start = parseTime(row.getCell(idx.get("start")), startStr);
                    LocalTime end = parseTime(row.getCell(idx.get("end")), endStr);
                    if (course == null || course.isBlank() || dow == null || start == null || end == null) {
                        skipped++;
                        continue;
                    }
                    if (!end.isAfter(start)) {
                        skipped++;
                        result.getWarnings().add("第 " + (r + 1) + " 行结束时间不晚于开始时间，已跳过");
                        continue;
                    }

                    ClassSchedule schedule = new ClassSchedule();
                    schedule.setUserId(userId);
                    schedule.setCourseName(course.trim());
                    schedule.setDayOfWeek(dow);
                    schedule.setStartTime(start);
                    schedule.setEndTime(end);
                    schedule.setLocation(location != null && !location.isBlank() ? location.trim() : null);
                    classScheduleMapper.insert(schedule);
                    inserted++;
                } catch (Exception rowEx) {
                    skipped++;
                    result.getWarnings().add("第 " + (r + 1) + " 行解析失败，已跳过");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 Excel 失败：" + e.getMessage());
        }

        result.setTotal(total);
        result.setInserted(inserted);
        result.setSkipped(skipped);
        if (inserted == 0) {
            result.getWarnings().add("未导入任何课程，请检查表头与时间格式");
        }
    }

    private void importFromCsv(Long userId, MultipartFile file, ScheduleImportResultDto result) {
        int inserted = 0;
        int skipped = 0;
        int total = 0;
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            // 处理BOM头
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            String[] lines = text.split("\\r?\\n");
            if (lines.length <= 1) {
                throw new IllegalArgumentException("CSV 内容为空");
            }
            String[] header = lines[0].split(",");
            // 清理表头中的BOM和空格
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].replace("\uFEFF", "").trim();
            }

            Map<String, Integer> idx = resolveHeaderIndex(header);
            if (!idx.containsKey("course") || !idx.containsKey("dow") || !idx.containsKey("start") || !idx.containsKey("end")) {
                throw new IllegalArgumentException("CSV 表头至少需要：课程,星期,开始时间,结束时间");
            }

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",");
                total++;
                try {
                    String course = getArray(cols, idx.get("course"));
                    String dowStr = getArray(cols, idx.get("dow"));
                    String startStr = getArray(cols, idx.get("start"));
                    String endStr = getArray(cols, idx.get("end"));
                    String location = idx.containsKey("loc") ? getArray(cols, idx.get("loc")) : null;

                    // 添加调试日志
                    log.debug("解析行 {}: 课程={}, 星期={}, 开始={}, 结束={}",
                            i, course, dowStr, startStr, endStr);

                    Integer dow = parseDayOfWeek(dowStr);
                    LocalTime start = parseTime(null, startStr);
                    LocalTime end = parseTime(null, endStr);

                    if (course == null || course.isBlank() || dow == null || start == null || end == null) {
                        log.warn("跳过行 {}: 必要字段缺失 - course={}, dow={}, start={}, end={}",
                                i, course, dow, start, end);
                        skipped++;
                        continue;
                    }
                    if (!end.isAfter(start)) {
                        log.warn("跳过行 {}: 结束时间不晚于开始时间 - start={}, end={}", i, start, end);
                        skipped++;
                        continue;
                    }

                    ClassSchedule schedule = new ClassSchedule();
                    schedule.setUserId(userId);
                    schedule.setCourseName(course.trim());
                    schedule.setDayOfWeek(dow);
                    schedule.setStartTime(start);
                    schedule.setEndTime(end);
                    schedule.setLocation(location != null && !location.isBlank() ? location.trim() : null);
                    classScheduleMapper.insert(schedule);
                    inserted++;

                    log.info("成功导入课程: {}", schedule);
                } catch (Exception ex) {
                    log.error("解析第 {} 行失败: {}", i, ex.getMessage(), ex);
                    skipped++;
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 CSV 失败", e);
            throw new IllegalArgumentException("解析 CSV 失败：" + e.getMessage());
        }
        result.setTotal(total);
        result.setInserted(inserted);
        result.setSkipped(skipped);
        if (skipped > 0) {
            result.getWarnings().add("存在无法解析的行，已跳过：" + skipped);
        }
    }

    private Map<String, Integer> resolveHeaderIndex(String[] header) {
        Map<String, Integer> m = new HashMap<>();
        for (int c = 0; c < header.length; c++) {
            String v = header[c];
            if (v == null) continue;
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.contains("课程") || s.contains("course")) {
                m.put("course", c);
            } else if (s.contains("星期") || s.contains("周几") || s.contains("dayofweek") || s.contains("dow")) {
                m.put("dow", c);
            } else if (s.contains("开始") || s.contains("start")) {
                m.put("start", c);
            } else if (s.contains("结束") || s.contains("end")) {
                m.put("end", c);
            } else if (s.contains("地点") || s.contains("教室") || s.contains("location")) {
                m.put("loc", c);
            }
        }
        return m;
    }

    private String getArray(String[] cols, Integer idx) {
        if (idx == null || idx < 0 || idx >= cols.length) return null;
        return cols[idx];
    }

    private Map<String, Integer> resolveHeaderIndex(Row header) {
        Map<String, Integer> m = new HashMap<>();
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            String v = getCellString(cell);
            if (v == null) {
                continue;
            }
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            if (s.contains("课程") || s.contains("course")) {
                m.put("course", c);
            } else if (s.contains("星期") || s.contains("周几") || s.contains("dayofweek") || s.contains("dow")) {
                m.put("dow", c);
            } else if (s.contains("开始") || s.contains("start")) {
                m.put("start", c);
            } else if (s.contains("结束") || s.contains("end")) {
                m.put("end", c);
            } else if (s.contains("地点") || s.contains("教室") || s.contains("location")) {
                m.put("loc", c);
            }
        }
        return m;
    }

    private String getCellString(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            if (v == Math.floor(v)) {
                return String.valueOf((long) v);
            }
            return String.valueOf(v);
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return null;
    }

    private Integer parseDayOfWeek(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }

        // 尝试解析数字
        try {
            int n = Integer.parseInt(v);
            if (n == 0) {
                return 7; // 周日
            }
            if (n >= 1 && n <= 7) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // 不是数字，继续尝试中文
        }

        // 中文星期
        if (v.contains("一") || v.equals("周一") || v.equals("星期一")) return 1;
        if (v.contains("二") || v.equals("周二") || v.equals("星期二")) return 2;
        if (v.contains("三") || v.equals("周三") || v.equals("星期三")) return 3;
        if (v.contains("四") || v.equals("周四") || v.equals("星期四")) return 4;
        if (v.contains("五") || v.equals("周五") || v.equals("星期五")) return 5;
        if (v.contains("六") || v.equals("周六") || v.equals("星期六")) return 6;
        if (v.contains("日") || v.contains("天") || v.equals("周日") || v.equals("星期天") || v.equals("星期日")) return 7;

        // 英文星期
        String lower = v.toLowerCase();
        if (lower.startsWith("mon")) return 1;
        if (lower.startsWith("tue")) return 2;
        if (lower.startsWith("wed")) return 3;
        if (lower.startsWith("thu")) return 4;
        if (lower.startsWith("fri")) return 5;
        if (lower.startsWith("sat")) return 6;
        if (lower.startsWith("sun")) return 7;

        log.warn("无法解析星期: {}", v);
        return null;
    }

    private LocalTime parseTime(Cell cell, String fallback) {
        try {
            if (cell != null && cell.getCellType() == CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                java.util.Date d = cell.getDateCellValue();
                return d.toInstant().atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0);
            }
        } catch (Exception ignored) {
        }

        if (fallback == null) {
            return null;
        }
        String s = fallback.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains("-")) {
            s = s.split("-")[0].trim();
        }
        if (s.length() == 4 && s.charAt(1) == ':' ) {
            s = "0" + s;
        }
        if (s.length() == 5) {
            return LocalTime.parse(s);
        }
        if (s.length() == 8) {
            return LocalTime.parse(s).withSecond(0).withNano(0);
        }
        return null;
    }

    public List<ClassSchedule> listClassSchedules(Long userId, Integer dayOfWeek) {
        LambdaQueryWrapper<ClassSchedule> qw = new LambdaQueryWrapper<ClassSchedule>()
                .eq(ClassSchedule::getUserId, userId)
                .orderByAsc(ClassSchedule::getDayOfWeek)
                .orderByAsc(ClassSchedule::getStartTime);
        if (dayOfWeek != null) {
            qw.eq(ClassSchedule::getDayOfWeek, dayOfWeek);
        }
        return classScheduleMapper.selectList(qw);
    }

    public void deleteClassSchedules(Long userId) {
        classScheduleMapper.delete(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
    }

    public List<ScheduleClient.TimeSlot> calculateFreeTime(Long userId, String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        int dayOfWeek = date.getDayOfWeek().getValue();

        log.info("计算用户 {} 在 {} (星期{}) 的空闲时间", userId, date, dayOfWeek);

        // 1. 获取当天的课程
        List<ClassSchedule> classes = classScheduleMapper.selectList(new LambdaQueryWrapper<ClassSchedule>()
                .eq(ClassSchedule::getUserId, userId)
                .eq(ClassSchedule::getDayOfWeek, dayOfWeek)
                .orderByAsc(ClassSchedule::getStartTime));

        log.info("找到 {} 门课程", classes.size());

        // 2. 计算空闲时间 (假设学习时间为 08:00 - 22:00)
        LocalTime studyStart = LocalTime.of(8, 0);
        LocalTime studyEnd = LocalTime.of(22, 0);

        List<ScheduleClient.TimeSlot> freeSlots = new ArrayList<>();

        if (classes.isEmpty()) {
            // 全天没课，整个学习时间段都是空闲
            freeSlots.add(createSlot(date, studyStart, studyEnd));
            Long total = classScheduleMapper.selectCount(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
            if (total != null && total > 0) {
                log.info("当天无课（课表共 {} 门课程），空闲时间: {} - {}", total, studyStart, studyEnd);
            } else {
                log.info("课表为空，空闲时间: {} - {}", studyStart, studyEnd);
            }
            return freeSlots;
        }

        // 按开始时间排序
        classes.sort((a, b) -> a.getStartTime().compareTo(b.getStartTime()));

        // 处理第一节课前的空闲时间
        ClassSchedule firstClass = classes.get(0);
        if (firstClass.getStartTime().isAfter(studyStart)) {
            freeSlots.add(createSlot(date, studyStart, firstClass.getStartTime()));
        }

        // 处理课间空闲时间
        LocalTime lastEnd = firstClass.getEndTime();
        for (int i = 1; i < classes.size(); i++) {
            ClassSchedule current = classes.get(i);

            // 如果有重叠，扩展结束时间
            if (current.getStartTime().isBefore(lastEnd)) {
                if (current.getEndTime().isAfter(lastEnd)) {
                    lastEnd = current.getEndTime();
                }
                continue;
            }

            // 课间空闲时间
            if (current.getStartTime().isAfter(lastEnd)) {
                LocalTime freeStart = lastEnd.isBefore(studyStart) ? studyStart : lastEnd;
                LocalTime freeEnd = current.getStartTime().isAfter(studyEnd) ? studyEnd : current.getStartTime();
                if (freeEnd.isAfter(freeStart)) {
                    freeSlots.add(createSlot(date, freeStart, freeEnd));
                }
            }

            lastEnd = current.getEndTime().isAfter(lastEnd) ? current.getEndTime() : lastEnd;
        }

        // 处理最后一节课后的空闲时间
        if (lastEnd.isBefore(studyEnd)) {
            LocalTime freeStart = lastEnd.isBefore(studyStart) ? studyStart : lastEnd;
            if (studyEnd.isAfter(freeStart)) {
                freeSlots.add(createSlot(date, freeStart, studyEnd));
            }
        }

        log.info("计算出 {} 个空闲时段", freeSlots.size());
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

    @lombok.AllArgsConstructor
    static class TimeRange {
        LocalTime start;
        LocalTime end;
    }

    @lombok.AllArgsConstructor
    static class TimePoint {
        LocalTime time;
        boolean isStart;
    }

    public void smartSchedule(Long userId) {
        log.info("开始为用户 {} 进行智能排程", userId);
        
        // 1. 获取待办任务
        Result<List<GoalTaskDto>> tasksResult = goalClient.getPendingTasks(userId);
        if (tasksResult.getCode() != 200 || tasksResult.getData().isEmpty()) {
            return;
        }
        List<GoalTaskDto> tasks = tasksResult.getData();

        // 2. 获取未来 3 天的空闲时段
        List<ScheduleClient.TimeSlot> allFreeSlots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            allFreeSlots.addAll(calculateFreeTime(userId, LocalDate.now(APP_ZONE).plusDays(i).toString()));
        }

        // 3. 调用 AI 决策
        String taskListStr = tasks.stream()
                .map(t -> String.format("ID:%d, Title:%s, Duration:%dmin, Priority:%d", t.getId(), t.getTitle(), t.getEstimatedMinutes(), t.getPriority()))
                .collect(Collectors.joining("; "));
        
        String slotsStr = allFreeSlots.stream()
                .map(s -> String.format("[%s - %s]", s.getStart(), s.getEnd()))
                .collect(Collectors.joining(", "));

        String prompt = String.format("""
            用户有以下待办任务：%s
            未来空闲时段有：%s
            请根据任务优先级和耗时，将任务合理分配到空闲时段。
            请直接返回 JSON 数组格式，不要有 Markdown 格式或额外文字。
            JSON 结构示例: [{"taskId": 1, "startTime": "2026-04-10T14:00:00", "endTime": "2026-04-10T15:00:00"}]
            """, taskListStr, slotsStr);

        try {
            String response;
            try {
                response = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(prompt))
                        .orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();
                log.info("AI 智能排程决策: {}", response);
            } catch (Exception aiEx) {
                log.error("AI 排程调用失败，使用本地降级方案: {}", aiEx.getMessage());
                response = writeJson(buildLocalSchedules(tasks, allFreeSlots));
            }

            // 4. 解析并保存决策结果
            List<TaskSchedule> schedules = objectMapper.readValue(response, new TypeReference<List<TaskSchedule>>() {});
            
            // 清理未来 3 天的旧排程，避免重复
            LocalDateTime from = LocalDate.now(APP_ZONE).atStartOfDay();
            taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, from)
                    .eq(TaskSchedule::getStatus, 0));

            for (TaskSchedule s : schedules) {
                s.setUserId(userId);
                s.setStatus(0); // 未开始
                taskScheduleMapper.insert(s);
                
                // 更新任务状态为“进行中” (1)
                goalClient.updateTaskStatus(s.getTaskId(), 1);
            }
        } catch (Exception e) {
            log.error("智能排程处理失败", e);
        }
    }

    @Async
    public void smartScheduleAsync(Long userId) {
        smartSchedule(userId);
    }

    public PlanCandidateDto generatePlanCandidate(Long userId, GeneratePlanCandidateRequest request) {
        LocalDate date = request != null && request.getDate() != null ? request.getDate() : LocalDate.now(APP_ZONE);
        List<FreeSlotDto> inputSlots = request != null ? request.getFreeSlots() : null;
        List<FreeSlotDto> normalizedSlots = normalizeSlots(date, inputSlots);
        if (normalizedSlots.isEmpty()) {
            Long cnt = classScheduleMapper.selectCount(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
            if (cnt == null || cnt <= 0) {
                throw new IllegalArgumentException("请先导入/更新课表后再生成计划");
            }
            normalizedSlots = calculateFreeTime(userId, date.toString()).stream().map(s -> {
                FreeSlotDto dto = new FreeSlotDto();
                dto.setStart(s.getStart());
                dto.setEnd(s.getEnd());
                return dto;
            }).collect(Collectors.toList());
        }
        if (normalizedSlots.isEmpty()) {
            throw new IllegalArgumentException("当天无可用空闲时间段");
        }

        Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
        List<GoalTaskDto> tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("暂无可排程任务，请先生成学习目标与任务");
        }

        PlanCandidate entity = new PlanCandidate();
        entity.setUserId(userId);
        entity.setPlanDate(date);
        entity.setStatus(CANDIDATE_STATUS_GENERATING);
        entity.setNote("AI 生成中…");
        entity.setFreeSlotsJson(writeJson(normalizedSlots));
        entity.setSuggestedFreeSlotsJson(null);
        entity.setSchedulesJson("[]");
        entity.setSuggestedSchedulesJson(null);
        entity.setCreatedAt(LocalDateTime.now(APP_ZONE));
        planCandidateMapper.insert(entity);

        planCandidateWorker.generate(entity.getId(), userId, date, normalizedSlots, tasks);

        PlanCandidateDto dto = new PlanCandidateDto();
        dto.setId(entity.getId());
        dto.setUserId(userId);
        dto.setPlanDate(date);
        dto.setStatus(entity.getStatus());
        dto.setNote(entity.getNote());
        dto.setFreeSlots(normalizedSlots);
        dto.setSuggestedFreeSlots(List.of());
        dto.setSchedules(List.of());
        dto.setSuggestedSchedules(List.of());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void report(String stage, int progress, String message);
    }

    public DailyPlanCommitResponse commitDailyPlan(Long userId, DailyPlanCommitRequest request) {
        return commitDailyPlan(userId, request, null);
    }

    public DailyPlanCommitResponse commitDailyPlan(Long userId, DailyPlanCommitRequest request, ProgressReporter reporter) {
        LocalDate date = request != null && request.getDate() != null ? request.getDate() : LocalDate.now(APP_ZONE);
        String mode = request != null && request.getMode() != null ? request.getMode().trim().toLowerCase(Locale.ROOT) : "replace";
        if (!"append".equals(mode) && !"replace".equals(mode)) {
            mode = "replace";
        }
        report(reporter, "PREPARE", 5, "正在计算空闲时间");

        List<FreeSlotDto> freeSlots = calculateFreeTime(userId, date.toString()).stream().map(s -> {
            FreeSlotDto dto = new FreeSlotDto();
            dto.setStart(s.getStart());
            dto.setEnd(s.getEnd());
            return dto;
        }).collect(Collectors.toList());

        if (freeSlots.isEmpty()) {
            throw new IllegalArgumentException("当天无可用空闲时间段");
        }

        report(reporter, "FETCH_TASKS", 15, "正在获取待办任务");
        List<GoalTaskDto> tasks = List.of();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
                tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
            } catch (Exception ignored) {
                tasks = List.of();
            }
            if (tasks != null && !tasks.isEmpty()) {
                break;
            }
            if (attempt < 4) {
                report(reporter, "WAIT_TASKS", 15 + (attempt + 1) * 2, "任务生成可能有延迟，正在重试获取任务（" + (attempt + 1) + "/5）");
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (tasks.isEmpty()) {
            boolean created = false;
            try {
                Result<List<GoalDto>> goalsRes = goalClient.listGoals(userId);
                List<GoalDto> goals = goalsRes != null && goalsRes.getCode() == 200 && goalsRes.getData() != null ? goalsRes.getData() : List.of();
                if (goals.isEmpty()) {
                    report(reporter, "AUTO_CREATE_GOAL", 22, "未检测到学习目标，正在自动生成目标与任务");
                    List<ClassSchedule> allClasses = classScheduleMapper.selectList(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
                    String courseText = allClasses == null ? "" : allClasses.stream()
                            .filter(c -> c != null && c.getCourseName() != null && !c.getCourseName().isBlank())
                            .map(ClassSchedule::getCourseName)
                            .distinct()
                            .limit(6)
                            .collect(Collectors.joining("、"));
                    String desc = courseText.isBlank()
                            ? "请为我生成一个本周学习计划目标，并拆解为可执行任务。"
                            : "请为我生成本周学习计划，围绕课程：" + courseText + "，拆解为可执行任务。";
                    goalClient.createGoalByAi(userId, desc);
                    created = true;
                } else {
                    GoalDto latest = goals.stream()
                            .filter(g -> g != null && g.getId() != null)
                            .max((a, b) -> {
                                if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                                if (a.getCreatedAt() == null) return -1;
                                if (b.getCreatedAt() == null) return 1;
                                return a.getCreatedAt().compareTo(b.getCreatedAt());
                            })
                            .orElse(null);
                    if (latest != null) {
                        report(reporter, "AUTO_CREATE_TASKS", 22, "检测到学习目标但暂无待办任务，正在自动生成任务");
                        goalClient.regenerateTasks(latest.getId(), userId, "请生成可排程的学习任务清单");
                        created = true;
                    }
                }
            } catch (Exception ignored) {
            }

            if (created) {
                for (int attempt = 0; attempt < 120; attempt++) {
                    try {
                        Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
                        tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
                    } catch (Exception ignored) {
                        tasks = List.of();
                    }
                    if (tasks != null && !tasks.isEmpty()) {
                        break;
                    }
                    int p = Math.min(44, 22 + (attempt + 1) / 3);
                    report(reporter, "WAIT_TASKS", p, "正在等待任务生成完成（" + (attempt + 1) + "/120）");
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("暂无可排程任务（可能任务生成中），请先生成学习目标与任务，或稍后再试");
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<TaskSchedule> existing = taskScheduleMapper.selectList(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, dayStart)
                .lt(TaskSchedule::getStartTime, dayEnd)
                .orderByAsc(TaskSchedule::getStartTime));

        if ("replace".equals(mode)) {
            report(reporter, "CLEAR_EXISTING", 25, "正在清理当天未完成排程");
            taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, dayStart)
                    .lt(TaskSchedule::getStartTime, dayEnd)
                    .eq(TaskSchedule::getStatus, 0));
            existing = taskScheduleMapper.selectList(new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, dayStart)
                    .lt(TaskSchedule::getStartTime, dayEnd)
                    .orderByAsc(TaskSchedule::getStartTime));
        }

        List<Long> alreadyScheduledTaskIds = existing == null ? List.of() : existing.stream()
                .map(TaskSchedule::getTaskId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        List<GoalTaskDto> remainingTasks = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .filter(t -> !alreadyScheduledTaskIds.contains(t.getId()))
                .collect(Collectors.toList());

        if (remainingTasks.isEmpty()) {
            DailyPlanCommitResponse resp = new DailyPlanCommitResponse();
            resp.setDate(date);
            resp.setMode(mode);
            resp.setNote("当天暂无可新增排程任务");
            resp.setFreeSlots(freeSlots);
            resp.setSchedules(listTaskSchedules(userId, dayStart, dayEnd));
            return resp;
        }

        List<FreeSlotDto> remainingFree = subtractOccupied(freeSlots, existing);
        if (remainingFree.isEmpty()) {
            throw new IllegalArgumentException("当天空闲时间已被占满");
        }

        CommitAiResponse ai;
        try {
            report(reporter, "CALL_AI", 45, "正在调用模型进行智能排程");
            String prompt = buildDailyPlanPrompt(date, remainingFree, remainingTasks);
            log.info("调用模型: userId={}, date={}, freeSlots={}, tasks={}", userId, date, remainingFree != null ? remainingFree.size() : 0, remainingTasks != null ? remainingTasks.size() : 0);
            String aiJson = CompletableFuture
                    .supplyAsync(() -> openAiCompatClient.complete(prompt))
                    .orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            log.info("智能排程模型返回长度: {}", aiJson != null ? aiJson.length() : 0);
            if (aiJson != null) {
                String preview = aiJson.length() <= 900 ? aiJson : aiJson.substring(0, 900);
                log.info("智能排程模型返回预览: {}", preview);
            }
            ai = parseCommitAiResponse(aiJson);
        } catch (Exception e) {
            report(reporter, "FALLBACK", 55, "智能排程失败，正在使用规则降级排程");
            log.warn("智能排程调用失败: {}", e.getMessage());
            ai = new CommitAiResponse();
            ai.note = "已生成当日计划（规则）";
            ai.candidateSchedules = planCandidateWorker.generateRuleBasedSchedules(remainingFree, remainingTasks);
        }

        List<TaskScheduleDto> candidate = ai.candidateSchedules != null ? ai.candidateSchedules : List.of();
        candidate = candidate.stream().filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null).collect(Collectors.toList());
        candidate = normalizeTaskIds(candidate, remainingTasks, reporter);
        candidate = filterOverlaps(candidate);
        candidate = enforceMinGap(candidate, BREAK_MINUTES);
        candidate = clampDailyMinutes(candidate, MAX_STUDY_MINUTES_PER_DAY);

        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("当前空闲时间段不足以安排任务，请增加时间段后重试");
        }
        report(reporter, "VALIDATE", 70, "正在校验冲突与总时长");
        if (!allWithinFreeSlots(candidate, remainingFree)) {
            throw new IllegalArgumentException("排程与课表/空闲时间冲突，已拒绝写入");
        }

        report(reporter, "WRITE_DB", 85, "正在写入日程");
        for (TaskScheduleDto s : candidate) {
            TaskSchedule ts = new TaskSchedule();
            ts.setUserId(userId);
            ts.setTaskId(s.getTaskId());
            ts.setStartTime(s.getStartTime());
            ts.setEndTime(s.getEndTime());
            ts.setStatus(0);
            taskScheduleMapper.insert(ts);
            try {
                goalClient.updateTaskStatus(s.getTaskId(), 1);
            } catch (Exception ignored) {
            }
        }

        try {
            report(reporter, "ASSIGN_GOALS", 92, "正在归并任务到最相近目标");
            assignMissingGoalIds(userId, candidate, reporter);
        } catch (Exception e) {
            log.warn("任务归并到目标失败: {}", e.getMessage());
        }

        DailyPlanCommitResponse resp = new DailyPlanCommitResponse();
        resp.setDate(date);
        resp.setMode(mode);
        resp.setNote(ai.note == null || ai.note.isBlank() ? "已生成当日计划" : ai.note);
        resp.setFreeSlots(freeSlots);
        resp.setSchedules(listTaskSchedules(userId, dayStart, dayEnd));
        report(reporter, "DONE", 100, "已完成");
        return resp;
    }

    private void report(ProgressReporter reporter, String stage, int progress, String message) {
        if (reporter == null) {
            return;
        }
        try {
            log.info("排程进度 stage={} progress={} message={}", stage, progress, message);
        } catch (Exception ignored) {
        }
        try {
            reporter.report(stage, progress, message);
        } catch (Exception ignored) {
        }
    }

    private List<TaskScheduleDto> normalizeTaskIds(List<TaskScheduleDto> candidate, List<GoalTaskDto> tasks, ProgressReporter reporter) {
        if (candidate == null || candidate.isEmpty() || tasks == null || tasks.isEmpty()) {
            return candidate;
        }
        Map<Long, GoalTaskDto> byId = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .collect(Collectors.toMap(GoalTaskDto::getId, t -> t, (a, b) -> a));

        List<GoalTaskDto> ordered = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .sorted((a, b) -> {
                    int ap = a.getPriority() != null ? a.getPriority() : 0;
                    int bp = b.getPriority() != null ? b.getPriority() : 0;
                    if (ap != bp) return Integer.compare(bp, ap);
                    int am = a.getEstimatedMinutes() != null ? a.getEstimatedMinutes() : 0;
                    int bm = b.getEstimatedMinutes() != null ? b.getEstimatedMinutes() : 0;
                    if (am != bm) return Integer.compare(bm, am);
                    return Long.compare(a.getId(), b.getId());
                })
                .collect(Collectors.toList());

        int changed = 0;
        for (TaskScheduleDto s : candidate) {
            if (s == null || s.getTaskId() == null) {
                continue;
            }
            if (byId.containsKey(s.getTaskId())) {
                continue;
            }
            GoalTaskDto mapped = null;
            if (s.getTaskTitle() != null && !s.getTaskTitle().isBlank()) {
                mapped = bestMatchByTitle(s.getTaskTitle(), ordered);
            }
            long raw = s.getTaskId();
            if (raw >= 1 && raw <= ordered.size()) {
                mapped = ordered.get((int) raw - 1);
            }
            if (mapped == null && !ordered.isEmpty()) {
                mapped = ordered.get(0);
            }
            if (mapped != null) {
                s.setTaskId(mapped.getId());
                s.setTaskTitle(mapped.getTitle());
                changed++;
            }
        }
        if (changed > 0) {
            report(reporter, "MAP_TASKS", 60, "模型返回的任务编号与系统不一致，已自动纠正 " + changed + " 条任务归属");
        }
        return candidate;
    }

    private GoalTaskDto bestMatchByTitle(String title, List<GoalTaskDto> tasks) {
        if (title == null || title.isBlank() || tasks == null || tasks.isEmpty()) {
            return null;
        }
        String q = normalizeText(title);
        GoalTaskDto best = null;
        double bestScore = 0.0;
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getTitle() == null) continue;
            String cand = normalizeText(t.getTitle());
            double score = textSimilarity(q, cand);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (bestScore >= 0.2) {
            return best;
        }
        return null;
    }

    private void assignMissingGoalIds(Long userId, List<TaskScheduleDto> candidate, ProgressReporter reporter) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        List<Long> taskIds = candidate.stream()
                .filter(s -> s != null && s.getTaskId() != null)
                .map(TaskScheduleDto::getTaskId)
                .distinct()
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }

        List<GoalTaskDto> tasks = List.of();
        try {
            Result<List<GoalTaskDto>> r = goalClient.getTasksByIds(taskIds);
            tasks = r != null && r.getCode() == 200 && r.getData() != null ? r.getData() : List.of();
        } catch (Exception ignored) {
        }
        List<GoalTaskDto> missing = tasks.stream()
                .filter(t -> t != null && t.getId() != null && t.getGoalId() == null)
                .collect(Collectors.toList());
        if (missing.isEmpty()) {
            return;
        }

        List<GoalDto> goals = List.of();
        try {
            Result<List<GoalDto>> g = goalClient.listGoals(userId);
            goals = g != null && g.getCode() == 200 && g.getData() != null ? g.getData() : List.of();
        } catch (Exception ignored) {
        }
        if (goals.isEmpty()) {
            return;
        }

        Map<Long, Long> mapping = null;
        try {
            mapping = assignByAi(goals, missing);
        } catch (Exception ignored) {
        }
        if (mapping == null || mapping.isEmpty()) {
            mapping = assignByRule(goals, missing);
        }

        int moved = 0;
        for (GoalTaskDto t : missing) {
            Long gid = mapping.get(t.getId());
            if (gid == null) continue;
            boolean exists = goals.stream().anyMatch(g -> g != null && g.getId() != null && g.getId().equals(gid));
            if (!exists) continue;
            try {
                goalClient.moveTaskToGoal(userId, t.getId(), gid);
                moved++;
            } catch (Exception ignored) {
            }
        }
        if (moved > 0) {
            report(reporter, "ASSIGN_GOALS", 98, "已归并 " + moved + " 个任务到目标");
        }
    }

    private Map<Long, Long> assignByAi(List<GoalDto> goals, List<GoalTaskDto> tasks) {
        List<Map<String, Object>> goalList = goals.stream()
                .filter(g -> g != null && g.getId() != null)
                .map(g -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", g.getId());
                    m.put("title", g.getTitle());
                    m.put("description", g.getDescription());
                    return m;
                }).collect(Collectors.toList());
        List<Map<String, Object>> taskList = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId());
                    m.put("title", t.getTitle());
                    m.put("description", t.getDescription());
                    return m;
                }).collect(Collectors.toList());

        String prompt = ""
                + "你是目标归并助手。你将收到 goals（目标列表）与 tasks（未归属目标的任务）。\n"
                + "请为每个 task 选择一个最相近的 goal，并输出严格 JSON 对象：key 为 taskId（数字），value 为 goalId（数字）。\n"
                + "只输出 JSON，不要 Markdown，不要额外文字。\n\n"
                + "goals: " + writeJson(goalList) + "\n"
                + "tasks: " + writeJson(taskList) + "\n";

        String text = openAiCompatClient.complete(prompt);
        String json = sanitizeJsonObject(text);
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            Map<Long, Long> out = new HashMap<>();
            root.fields().forEachRemaining(e -> {
                try {
                    Long taskId = Long.valueOf(e.getKey());
                    Long goalId = e.getValue().asLong();
                    out.put(taskId, goalId);
                } catch (Exception ignored) {
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<Long, Long> assignByRule(List<GoalDto> goals, List<GoalTaskDto> tasks) {
        Map<Long, Long> out = new HashMap<>();
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getId() == null) continue;
            GoalDto best = null;
            double bestScore = 0.0;
            String q = normalizeText((t.getTitle() != null ? t.getTitle() : "") + " " + (t.getDescription() != null ? t.getDescription() : ""));
            for (GoalDto g : goals) {
                if (g == null || g.getId() == null) continue;
                String cand = normalizeText((g.getTitle() != null ? g.getTitle() : "") + " " + (g.getDescription() != null ? g.getDescription() : ""));
                double score = textSimilarity(q, cand);
                if (score > bestScore) {
                    bestScore = score;
                    best = g;
                }
            }
            if (best != null) {
                out.put(t.getId(), best.getId());
            }
        }
        return out;
    }

    private String normalizeText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private double textSimilarity(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if (a.contains(b) || b.contains(a)) {
            return 1.0;
        }
        java.util.Set<Integer> sa = a.chars().boxed().collect(java.util.stream.Collectors.toSet());
        java.util.Set<Integer> sb = b.chars().boxed().collect(java.util.stream.Collectors.toSet());
        int inter = 0;
        for (Integer c : sa) {
            if (sb.contains(c)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0.0 : (inter * 1.0 / union);
    }

    public void decidePlanCandidate(Long userId, Long candidateId, Boolean accept, Boolean useSuggestedSlots) {
        PlanCandidate c = planCandidateMapper.selectById(candidateId);
        if (c == null || c.getUserId() == null || !c.getUserId().equals(userId)) {
            throw new IllegalArgumentException("候选计划不存在");
        }
        if (c.getStatus() != null && c.getStatus() == CANDIDATE_STATUS_GENERATING) {
            throw new IllegalArgumentException("候选排程生成中，请稍后再试");
        }
        if (c.getStatus() != null && c.getStatus() != CANDIDATE_STATUS_READY) {
            return;
        }
        if (accept == null || !accept) {
            PlanCandidate upd = new PlanCandidate();
            upd.setId(candidateId);
            upd.setStatus(CANDIDATE_STATUS_REJECTED);
            planCandidateMapper.updateById(upd);
            return;
        }

        List<TaskScheduleDto> schedules = readJsonList(c.getSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {});
        schedules = schedules != null ? schedules : List.of();
        List<TaskScheduleDto> suggestedSchedules = readJsonList(c.getSuggestedSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {});
        suggestedSchedules = suggestedSchedules != null ? suggestedSchedules : List.of();

        boolean useSuggested = useSuggestedSlots != null && useSuggestedSlots && !suggestedSchedules.isEmpty();
        List<TaskScheduleDto> decided = useSuggested ? suggestedSchedules : schedules;
        List<FreeSlotDto> allowedSlots = useSuggested
                ? readJsonList(c.getSuggestedFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {})
                : readJsonList(c.getFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {});
        allowedSlots = allowedSlots != null ? allowedSlots : List.of();

        List<TaskScheduleDto> filtered = filterOverlaps(decided);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("当前空闲时间段不足以安排任务，请增加时间段后重试");
        }
        if (!allowedSlots.isEmpty() && !allWithinFreeSlots(filtered, allowedSlots)) {
            throw new IllegalArgumentException("排程与课表/空闲时间冲突，已拒绝写入");
        }

        LocalDate date = c.getPlanDate() != null ? c.getPlanDate() : LocalDate.now(APP_ZONE);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, dayStart)
                .lt(TaskSchedule::getStartTime, dayEnd)
                .eq(TaskSchedule::getStatus, 0));

        for (TaskScheduleDto s : filtered) {
            TaskSchedule ts = new TaskSchedule();
            ts.setUserId(userId);
            ts.setTaskId(s.getTaskId());
            ts.setStartTime(s.getStartTime());
            ts.setEndTime(s.getEndTime());
            ts.setStatus(0);
            taskScheduleMapper.insert(ts);
        }

        PlanCandidate upd = new PlanCandidate();
        upd.setId(candidateId);
        upd.setStatus(CANDIDATE_STATUS_ACCEPTED);
        planCandidateMapper.updateById(upd);
    }

    public List<PlanCandidateDto> listPlanCandidates(Long userId, String date) {
        LambdaQueryWrapper<PlanCandidate> qw = new LambdaQueryWrapper<PlanCandidate>()
                .eq(PlanCandidate::getUserId, userId)
                .orderByDesc(PlanCandidate::getCreatedAt);
        if (date != null && !date.isBlank()) {
            qw.eq(PlanCandidate::getPlanDate, LocalDate.parse(date.trim()));
        }
        List<PlanCandidate> list = planCandidateMapper.selectList(qw);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<PlanCandidateDto> out = new ArrayList<>();
        for (PlanCandidate c : list) {
            PlanCandidateDto dto = new PlanCandidateDto();
            dto.setId(c.getId());
            dto.setUserId(c.getUserId());
            dto.setPlanDate(c.getPlanDate());
            dto.setStatus(c.getStatus());
            dto.setNote(c.getNote());
            dto.setFreeSlots(readJsonList(c.getFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {}));
            dto.setSuggestedFreeSlots(readJsonList(c.getSuggestedFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {}));
            dto.setSchedules(readJsonList(c.getSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {}));
            dto.setSuggestedSchedules(readJsonList(c.getSuggestedSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {}));
            dto.setCreatedAt(c.getCreatedAt());
            out.add(dto);
        }
        return out;
    }

    private List<TaskScheduleDto> filterOverlaps(List<TaskScheduleDto> schedules) {
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
            if (lastEnd != null && !s.getStartTime().isAfter(lastEnd)) {
                continue;
            }
            out.add(s);
            lastEnd = s.getEndTime();
        }
        return out;
    }

    private List<TaskScheduleDto> enforceMinGap(List<TaskScheduleDto> schedules, int gapMinutes) {
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
                    continue;
                }
            }
            out.add(s);
            lastEnd = s.getEndTime();
        }
        return out;
    }

    private List<TaskScheduleDto> clampDailyMinutes(List<TaskScheduleDto> schedules, int maxMinutes) {
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

    private List<FreeSlotDto> subtractOccupied(List<FreeSlotDto> freeSlots, List<TaskSchedule> occupiedSchedules) {
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
                    cur = b.plusMinutes(BREAK_MINUTES);
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

    private String buildDailyPlanPrompt(LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks) {
        String freeJson = writeJson(freeSlots);
        List<Map<String, Object>> simpleTasks = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            return m;
        }).collect(Collectors.toList());
        String taskJson = writeJson(simpleTasks);
        return DAILY_PLAN_SYSTEM_PROMPT + "\nplanDate: " + date + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private CommitAiResponse parseCommitAiResponse(String aiJson) {
        try {
            String sanitized = sanitizeJsonObject(aiJson);
            return objectMapper.readValue(sanitized, CommitAiResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("候选计划解析失败");
        }
    }

    private String sanitizeJsonObject(String text) {
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

    @lombok.Data
    static class CommitAiResponse {
        private String note;
        private List<TaskScheduleDto> candidateSchedules;
    }

    private boolean allWithinFreeSlots(List<TaskScheduleDto> schedules, List<FreeSlotDto> freeSlots) {
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

    private List<FreeSlotDto> normalizeSlots(LocalDate date, List<FreeSlotDto> slots) {
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

    private List<TaskScheduleDto> normalizeSchedules(List<TaskScheduleDto> schedules) {
        if (schedules == null) {
            return List.of();
        }
        return schedules.stream()
                .filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null)
                .filter(s -> s.getEndTime().isAfter(s.getStartTime()))
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .collect(Collectors.toList());
    }

    private String buildPlanPrompt(LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks) {
        String freeJson = writeJson(freeSlots);
        List<Map<String, Object>> simpleTasks = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            return m;
        }).collect(Collectors.toList());
        String taskJson = writeJson(simpleTasks);
        return PLAN_SYSTEM_PROMPT + "\nplanDate: " + date + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private CandidateAiResponse parseCandidateAiResponse(String aiJson) {
        try {
            String sanitized = sanitizeJsonObject(aiJson);
            return objectMapper.readValue(sanitized, CandidateAiResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("候选计划解析失败");
        }
    }

    private List<TaskSchedule> buildLocalSchedules(List<GoalTaskDto> tasks, List<ScheduleClient.TimeSlot> slots) {
        if (tasks == null || tasks.isEmpty() || slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<GoalTaskDto> sortedTasks = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .sorted((a, b) -> {
                    int ap = a.getPriority() == null ? 0 : a.getPriority();
                    int bp = b.getPriority() == null ? 0 : b.getPriority();
                    if (bp != ap) return Integer.compare(bp, ap);
                    int am = a.getEstimatedMinutes() == null ? 30 : a.getEstimatedMinutes();
                    int bm = b.getEstimatedMinutes() == null ? 30 : b.getEstimatedMinutes();
                    return Integer.compare(bm, am);
                })
                .collect(Collectors.toList());

        List<TaskSchedule> out = new ArrayList<>();
        int taskIdx = 0;
        int deepCount = 0;

        for (ScheduleClient.TimeSlot slot : slots) {
            if (slot == null || slot.getStart() == null || slot.getEnd() == null || !slot.getEnd().isAfter(slot.getStart())) {
                continue;
            }
            LocalDateTime cursor = slot.getStart();
            while (taskIdx < sortedTasks.size() && cursor.isBefore(slot.getEnd())) {
                GoalTaskDto t = sortedTasks.get(taskIdx);
                int minutes = t.getEstimatedMinutes() == null || t.getEstimatedMinutes() <= 0 ? 30 : t.getEstimatedMinutes();
                boolean isDeep = minutes >= 60;
                if (isDeep && deepCount >= 3 && cursor.toLocalDate().equals(slot.getStart().toLocalDate())) {
                    taskIdx++;
                    continue;
                }
                long remaining = java.time.Duration.between(cursor, slot.getEnd()).toMinutes();
                if (remaining < 15) {
                    break;
                }
                int useMinutes = (int) Math.min(Math.max(15, minutes), remaining);
                LocalDateTime end = cursor.plusMinutes(useMinutes);
                TaskSchedule s = new TaskSchedule();
                s.setTaskId(t.getId());
                s.setStartTime(cursor);
                s.setEndTime(end);
                out.add(s);
                if (isDeep && useMinutes >= 60) {
                    deepCount++;
                }
                cursor = end;
                taskIdx++;
            }
            if (taskIdx >= sortedTasks.size()) {
                break;
            }
        }
        return out;
    }

    private List<TaskScheduleDto> ruleBasedCandidateSchedules(List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks) {
        if (freeSlots == null || freeSlots.isEmpty() || tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<GoalTaskDto> sortedTasks = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .sorted((a, b) -> {
                    int ap = a.getPriority() == null ? 0 : a.getPriority();
                    int bp = b.getPriority() == null ? 0 : b.getPriority();
                    if (bp != ap) return Integer.compare(bp, ap);
                    int am = a.getEstimatedMinutes() == null ? 30 : a.getEstimatedMinutes();
                    int bm = b.getEstimatedMinutes() == null ? 30 : b.getEstimatedMinutes();
                    return Integer.compare(bm, am);
                })
                .collect(Collectors.toList());

        List<FreeSlotDto> slots = freeSlots.stream()
                .filter(s -> s != null && s.getStart() != null && s.getEnd() != null && s.getEnd().isAfter(s.getStart()))
                .sorted((a, b) -> a.getStart().compareTo(b.getStart()))
                .collect(Collectors.toList());

        List<TaskScheduleDto> out = new ArrayList<>();
        int taskIdx = 0;
        int deepCount = 0;
        LocalDate current = slots.get(0).getStart().toLocalDate();

        for (FreeSlotDto slot : slots) {
            if (!slot.getStart().toLocalDate().equals(current)) {
                current = slot.getStart().toLocalDate();
                deepCount = 0;
            }
            LocalDateTime cursor = slot.getStart();
            while (taskIdx < sortedTasks.size() && cursor.isBefore(slot.getEnd())) {
                GoalTaskDto t = sortedTasks.get(taskIdx);
                int minutes = t.getEstimatedMinutes() == null || t.getEstimatedMinutes() <= 0 ? 30 : t.getEstimatedMinutes();
                boolean isDeep = minutes >= 60;
                if (isDeep && deepCount >= 3) {
                    taskIdx++;
                    continue;
                }
                long remaining = java.time.Duration.between(cursor, slot.getEnd()).toMinutes();
                if (remaining < 15) {
                    break;
                }
                int useMinutes = (int) Math.min(Math.max(15, minutes), remaining);
                TaskScheduleDto s = new TaskScheduleDto();
                s.setTaskId(t.getId());
                s.setStartTime(cursor);
                s.setEndTime(cursor.plusMinutes(useMinutes));
                s.setStatus(0);
                out.add(s);
                if (isDeep && useMinutes >= 60) {
                    deepCount++;
                }
                cursor = s.getEndTime();
                taskIdx++;
            }
            if (taskIdx >= sortedTasks.size()) {
                break;
            }
        }
        return out;
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private <T> T readJsonList(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    @lombok.Data
    static class CandidateAiResponse {
        private String note;
        private List<FreeSlotDto> suggestedFreeSlots;
        private List<TaskScheduleDto> candidateSchedules;
        private List<TaskScheduleDto> suggestedSchedules;
    }

    public List<TaskScheduleDto> listTaskSchedules(Long userId, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<TaskSchedule> qw = new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .orderByAsc(TaskSchedule::getStartTime);
        if (from != null) {
            qw.ge(TaskSchedule::getStartTime, from);
        }
        if (to != null) {
            qw.le(TaskSchedule::getStartTime, to);
        }
        List<TaskSchedule> list = taskScheduleMapper.selectList(qw);
        if (list == null || list.isEmpty()) {
            return List.of();
        }

        List<Long> taskIds = list.stream().map(TaskSchedule::getTaskId).distinct().collect(Collectors.toList());
        Map<Long, String> titleMap = new HashMap<>();
        try {
            Result<List<GoalTaskDto>> r = goalClient.getTasksByIds(taskIds);
            if (r != null && r.getCode() == 200 && r.getData() != null) {
                for (GoalTaskDto t : r.getData()) {
                    if (t != null && t.getId() != null) {
                        titleMap.put(t.getId(), t.getTitle());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return list.stream().map(s -> {
            TaskScheduleDto dto = new TaskScheduleDto();
            dto.setId(s.getId());
            dto.setUserId(s.getUserId());
            dto.setTaskId(s.getTaskId());
            dto.setTaskTitle(titleMap.get(s.getTaskId()));
            dto.setStartTime(s.getStartTime());
            dto.setEndTime(s.getEndTime());
            dto.setStatus(s.getStatus());
            return dto;
        }).collect(Collectors.toList());
    }

    public void updateTaskScheduleStatus(Long scheduleId, Integer status) {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(scheduleId);
        ts.setStatus(status);
        taskScheduleMapper.updateById(ts);
    }

    public void deleteFutureTaskSchedules(Long userId) {
        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, LocalDateTime.now()));
    }
}
