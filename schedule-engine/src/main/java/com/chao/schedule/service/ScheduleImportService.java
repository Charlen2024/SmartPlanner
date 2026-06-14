package com.chao.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.UserScheduleConfig;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.UserScheduleConfigMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleImportService {

    private final ClassScheduleMapper classScheduleMapper;
    private final UserScheduleConfigMapper userScheduleConfigMapper;

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalTime[] PERIOD_START = {
        null, // index 0 unused
        LocalTime.of(8, 0),   // 第1节
        LocalTime.of(8, 55),  // 第2节
        LocalTime.of(10, 0),  // 第3节
        LocalTime.of(10, 55), // 第4节
        LocalTime.of(14, 0),  // 第5节
        LocalTime.of(14, 55), // 第6节
        LocalTime.of(16, 0),  // 第7节
        LocalTime.of(16, 55), // 第8节
        LocalTime.of(19, 0),  // 第9节
        LocalTime.of(19, 55), // 第10节
    };
    private static final LocalTime[] PERIOD_END = {
        null, // index 0 unused
        LocalTime.of(8, 45),  // 第1节
        LocalTime.of(9, 40),  // 第2节
        LocalTime.of(10, 45), // 第3节
        LocalTime.of(11, 40), // 第4节
        LocalTime.of(14, 45), // 第5节
        LocalTime.of(15, 40), // 第6节
        LocalTime.of(16, 45), // 第7节
        LocalTime.of(17, 40), // 第8节
        LocalTime.of(19, 45), // 第9节
        LocalTime.of(20, 40), // 第10节
    };

    public ScheduleImportResultDto parseAndSaveSchedule(Long userId, MultipartFile file, String firstWeekMonday) {
        String fileName = file != null ? file.getOriginalFilename() : null;
        log.info("用户 {} 上传课表: {} firstWeekMonday={}", userId, fileName, firstWeekMonday);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("课表文件为空");
        }

        String ext = fileName != null && fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";

        if (!"ics".equals(ext) && !"xlsx".equals(ext) && !"xls".equals(ext) && !"csv".equals(ext)) {
            throw new IllegalArgumentException("仅支持 .ics / .xlsx / .xls / .csv");
        }

        classScheduleMapper.delete(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));

        if (firstWeekMonday != null && !firstWeekMonday.isBlank()) {
            UserScheduleConfig cfg = new UserScheduleConfig();
            cfg.setUserId(userId);
            cfg.setFirstWeekMonday(LocalDate.parse(firstWeekMonday));
            userScheduleConfigMapper.insertOrUpdate(cfg);
        }

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

    public void deleteClassSchedules(Long userId) {
        classScheduleMapper.delete(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
    }

    public void saveFirstWeekMonday(Long userId, LocalDate firstWeekMonday) {
        UserScheduleConfig cfg = new UserScheduleConfig();
        cfg.setUserId(userId);
        cfg.setFirstWeekMonday(firstWeekMonday);
        userScheduleConfigMapper.insertOrUpdate(cfg);
    }

    public void clearFirstWeekMonday(Long userId) {
        UserScheduleConfig cfg = userScheduleConfigMapper.selectById(userId);
        if (cfg != null) {
            cfg.setFirstWeekMonday(null);
            userScheduleConfigMapper.updateById(cfg);
        }
    }

    // ────────────────────── private helpers ──────────────────────

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
                        String v = rrule.getValue().toUpperCase(Locale.ROOT);
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
            boolean hasPeriod = idx.containsKey("startPeriod") && idx.containsKey("endPeriod");
            boolean hasTime = idx.containsKey("start") && idx.containsKey("end");
            boolean hasWeeks = idx.containsKey("weeks");

            if (!idx.containsKey("course") || !idx.containsKey("dow")) {
                throw new IllegalArgumentException("表头至少需要：课程名,星期");
            }
            if (!hasPeriod && !hasTime) {
                throw new IllegalArgumentException("表头需要：开始节数+结束节数 或 开始时间+结束时间");
            }

            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String course = getCellString(row.getCell(idx.get("course")));
                String dowStr = getCellString(row.getCell(idx.get("dow")));
                String location = idx.containsKey("loc") ? getCellString(row.getCell(idx.get("loc"))) : null;
                String weekStr = hasWeeks ? getCellString(row.getCell(idx.get("weeks"))) : null;

                boolean empty = (course == null || course.isBlank()) && (dowStr == null || dowStr.isBlank());
                if (empty) {
                    continue;
                }
                total++;
                try {
                    Integer dow = parseDayOfWeek(dowStr);
                    if (course == null || course.isBlank() || dow == null) {
                        skipped++;
                        continue;
                    }

                    LocalTime start;
                    LocalTime end;
                    if (hasPeriod) {
                        String startPeriodStr = getCellString(row.getCell(idx.get("startPeriod")));
                        String endPeriodStr = getCellString(row.getCell(idx.get("endPeriod")));
                        Integer sp = parseIntSafe(startPeriodStr);
                        Integer ep = parseIntSafe(endPeriodStr);
                        if (sp == null || ep == null) {
                            skipped++;
                            continue;
                        }
                        start = periodToStartTime(sp);
                        end = periodToEndTime(ep);
                    } else {
                        String startStr = getCellString(row.getCell(idx.get("start")));
                        String endStr = getCellString(row.getCell(idx.get("end")));
                        start = parseTime(row.getCell(idx.get("start")), startStr);
                        end = parseTime(row.getCell(idx.get("end")), endStr);
                    }

                    if (start == null || end == null || !end.isAfter(start)) {
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

                    if (weekStr != null && !weekStr.isBlank()) {
                        WeekRange wr = parseWeekRange(weekStr);
                        if (wr != null) {
                            schedule.setWeekStart(wr.start);
                            schedule.setWeekEnd(wr.end);
                            schedule.setWeekType(wr.weekType);
                        }
                    }

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
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            text = text.replace('，', ',');
            String[] lines = text.split("\\r?\\n");
            if (lines.length <= 1) {
                throw new IllegalArgumentException("CSV 内容为空");
            }
            String[] header = lines[0].split(",");
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].replace("﻿", "").trim();
            }

            Map<String, Integer> idx = resolveHeaderIndex(header);
            boolean hasPeriod = idx.containsKey("startPeriod") && idx.containsKey("endPeriod");
            boolean hasTime = idx.containsKey("start") && idx.containsKey("end");
            boolean hasWeeks = idx.containsKey("weeks");

            if (!idx.containsKey("course") || !idx.containsKey("dow")) {
                throw new IllegalArgumentException("CSV 表头至少需要：课程名称,星期");
            }
            if (!hasPeriod && !hasTime) {
                throw new IllegalArgumentException("CSV 表头需要：开始节数+结束节数 或 开始时间+结束时间");
            }

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",");
                total++;
                try {
                    String course = getArray(cols, idx.get("course"));
                    String dowStr = getArray(cols, idx.get("dow"));
                    String location = idx.containsKey("loc") ? getArray(cols, idx.get("loc")) : null;
                    String weekStr = hasWeeks ? getArray(cols, idx.get("weeks")) : null;

                    Integer dow = parseDayOfWeek(dowStr);
                    if (course == null || course.isBlank() || dow == null) {
                        skipped++;
                        continue;
                    }

                    LocalTime start;
                    LocalTime end;
                    if (hasPeriod) {
                        String startPeriodStr = getArray(cols, idx.get("startPeriod"));
                        String endPeriodStr = getArray(cols, idx.get("endPeriod"));
                        Integer sp = parseIntSafe(startPeriodStr);
                        Integer ep = parseIntSafe(endPeriodStr);
                        if (sp == null || ep == null) {
                            skipped++;
                            continue;
                        }
                        start = periodToStartTime(sp);
                        end = periodToEndTime(ep);
                    } else {
                        String startStr = getArray(cols, idx.get("start"));
                        String endStr = getArray(cols, idx.get("end"));
                        start = parseTime(null, startStr);
                        end = parseTime(null, endStr);
                    }

                    if (start == null || end == null || !end.isAfter(start)) {
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

                    if (weekStr != null && !weekStr.isBlank()) {
                        WeekRange wr = parseWeekRange(weekStr);
                        if (wr != null) {
                            schedule.setWeekStart(wr.start);
                            schedule.setWeekEnd(wr.end);
                            schedule.setWeekType(wr.weekType);
                        }
                    }

                    classScheduleMapper.insert(schedule);
                    inserted++;
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

    private Integer parseIntSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
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
            } else if (s.contains("开始节数") || s.contains("startperiod")) {
                m.put("startPeriod", c);
            } else if (s.contains("结束节数") || s.contains("endperiod")) {
                m.put("endPeriod", c);
            } else if (s.contains("周数") || s.contains("weeks")) {
                m.put("weeks", c);
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
            } else if (s.contains("开始节数") || s.contains("startperiod")) {
                m.put("startPeriod", c);
            } else if (s.contains("结束节数") || s.contains("endperiod")) {
                m.put("endPeriod", c);
            } else if (s.contains("周数") || s.contains("weeks")) {
                m.put("weeks", c);
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

    private WeekRange parseWeekRange(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = normalizeFullWidth(raw).trim();
        boolean even = false;
        boolean odd = false;
        if (s.contains("双")) { even = true; s = s.replace("双", ""); }
        else if (s.contains("单")) { odd = true; s = s.replace("单", ""); }
        else if (s.contains("偶")) { even = true; s = s.replace("偶", ""); }
        else if (s.contains("奇")) { odd = true; s = s.replace("奇", ""); }

        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("(\\d+)\\s*月\\s*(\\d+)\\s*日?").matcher(s);
        if (m1.find()) {
            WeekRange r = new WeekRange();
            r.start = Integer.parseInt(m1.group(1));
            r.end = Integer.parseInt(m1.group(2));
            r.weekType = even ? "even" : (odd ? "odd" : null);
            return r;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(s);
        if (m2.find()) {
            WeekRange r = new WeekRange();
            r.start = Integer.parseInt(m2.group(1));
            r.end = Integer.parseInt(m2.group(2));
            r.weekType = even ? "even" : (odd ? "odd" : null);
            return r;
        }
        try {
            int n = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            WeekRange r = new WeekRange();
            r.start = n;
            r.end = n;
            return r;
        } catch (NumberFormatException ignored) {
        }
        log.warn("无法解析周数: {}", raw);
        return null;
    }

    @Data
    private static class WeekRange {
        int start;
        int end;
        String weekType;
    }

    private LocalTime periodToStartTime(int period) {
        if (period >= 1 && period < PERIOD_START.length && PERIOD_START[period] != null) {
            return PERIOD_START[period];
        }
        return null;
    }

    private LocalTime periodToEndTime(int period) {
        if (period >= 1 && period < PERIOD_END.length && PERIOD_END[period] != null) {
            return PERIOD_END[period];
        }
        return null;
    }

    private String normalizeFullWidth(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c == '－') {
                sb.append('-');
            } else if (c == '，') {
                sb.append(',');
            } else if (c == '：') {
                sb.append(':');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    Integer parseDayOfWeek(String s) {
        if (s == null) {
            return null;
        }
        String v = normalizeFullWidth(s).trim();
        if (v.isEmpty()) {
            return null;
        }

        try {
            int n = Integer.parseInt(v);
            if (n == 0) {
                return 7;
            }
            if (n >= 1 && n <= 7) {
                return n;
            }
        } catch (NumberFormatException ignored) {
        }

        if (v.contains("一") || v.equals("周一") || v.equals("星期一")) return 1;
        if (v.contains("二") || v.equals("周二") || v.equals("星期二")) return 2;
        if (v.contains("三") || v.equals("周三") || v.equals("星期三")) return 3;
        if (v.contains("四") || v.equals("周四") || v.equals("星期四")) return 4;
        if (v.contains("五") || v.equals("周五") || v.equals("星期五")) return 5;
        if (v.contains("六") || v.equals("周六") || v.equals("星期六")) return 6;
        if (v.contains("日") || v.contains("天") || v.equals("周日") || v.equals("星期天") || v.equals("星期日")) return 7;

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
        String s = normalizeFullWidth(fallback).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains("-")) {
            s = s.split("-")[0].trim();
        }
        if (s.length() == 4 && s.charAt(1) == ':') {
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
}
