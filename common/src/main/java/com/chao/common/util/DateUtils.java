package com.chao.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class DateUtils {
    public static LocalDateTime parseLocalDateTime(String s) {
        if (s == null) {
            return null;
        }
        String text = s.trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }

        DateTimeFormatter[] fmts = new DateTimeFormatter[] {
                DateTimeFormatter.ofPattern("yyyy/M/d a h:mm", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy/M/d a h:mm:ss", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.CHINA),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.CHINA)
        };
        for (DateTimeFormatter f : fmts) {
            try {
                return LocalDateTime.parse(text, f);
            } catch (DateTimeParseException ignored) {
            }
        }

        try {
            LocalDate d = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            return d.atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate d = LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/M/d", Locale.CHINA));
            return d.atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }
}
