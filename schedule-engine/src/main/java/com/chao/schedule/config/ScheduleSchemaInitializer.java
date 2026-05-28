package com.chao.schedule.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduleSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS plan_candidates (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                user_id BIGINT NOT NULL,
                plan_date DATE NOT NULL,
                status TINYINT NOT NULL DEFAULT 0 COMMENT '0-PENDING,1-ACCEPTED,2-REJECTED',
                note TEXT,
                free_slots_json MEDIUMTEXT,
                suggested_free_slots_json MEDIUMTEXT,
                schedules_json MEDIUMTEXT,
                suggested_schedules_json MEDIUMTEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_user_date (user_id, plan_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);

        try {
            jdbcTemplate.execute("ALTER TABLE plan_candidates ADD COLUMN suggested_schedules_json MEDIUMTEXT");
        } catch (Exception ignored) {
        }

        try {
            jdbcTemplate.execute("ALTER TABLE class_schedule ADD COLUMN week_start INT");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("ALTER TABLE class_schedule ADD COLUMN week_end INT");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("ALTER TABLE class_schedule ADD COLUMN week_type VARCHAR(10)");
        } catch (Exception ignored) {
        }

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_schedule_config (
                user_id BIGINT PRIMARY KEY,
                first_week_monday DATE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
    }
}
