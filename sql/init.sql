-- =====================================================
-- 创建数据库
-- =====================================================
CREATE DATABASE IF NOT EXISTS sp_user CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sp_goal CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sp_schedule CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sp_punch CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sp_resource CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =====================================================
-- 1. sp_user 数据库 - 用户相关表
-- =====================================================
USE sp_user;

-- 用户表 (AppUser)
CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    schedule_imported TINYINT(1) NOT NULL DEFAULT 0,
    first_week_monday DATE DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
);

-- =====================================================
-- 2. sp_goal 数据库 - 目标相关表
-- =====================================================
USE sp_goal;

-- 目标表 (GoalDto)
CREATE TABLE IF NOT EXISTS goals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),
    description TEXT,
    status INT DEFAULT 0,
    deadline DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);

-- 目标任务表 (GoalTask)
CREATE TABLE IF NOT EXISTS goal_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    goal_id BIGINT,
    parent_id BIGINT,
    title VARCHAR(200),
    description TEXT,
    priority INT DEFAULT 1,
    estimated_minutes INT,
    status INT DEFAULT 0,
    deadline DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_goal_id (goal_id),
    INDEX idx_parent_id (parent_id)
);

-- 用户日志表 (UserJournal)
CREATE TABLE IF NOT EXISTS user_journals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    goal_id BIGINT,
    content TEXT,
    mood VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_goal_id (goal_id)
);

-- =====================================================
-- 3. sp_schedule 数据库 - 排程相关表
-- =====================================================
USE sp_schedule;

-- 课程表 (ClassSchedule)
CREATE TABLE IF NOT EXISTS class_schedule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    course_name VARCHAR(200),
    day_of_week INT,
    start_time TIME,
    end_time TIME,
    location VARCHAR(200),
    semester VARCHAR(50),
    week_start INT DEFAULT NULL,
    week_end INT DEFAULT NULL,
    week_type VARCHAR(10) DEFAULT NULL,
    INDEX idx_user_id (user_id)
);

-- 任务排程表 (TaskSchedule)
CREATE TABLE IF NOT EXISTS task_schedules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    status INT DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_task_id (task_id),
    INDEX idx_start_time (start_time)
);

-- 计划候选表 (PlanCandidate)
CREATE TABLE IF NOT EXISTS plan_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    plan_date DATE NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    note TEXT,
    free_slots_json MEDIUMTEXT,
    suggested_free_slots_json MEDIUMTEXT,
    schedules_json MEDIUMTEXT,
    suggested_schedules_json MEDIUMTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, plan_date)
);

-- =====================================================
-- 4. sp_punch 数据库 - 打卡相关表
-- =====================================================
USE sp_punch;

-- 用户习惯表 (UserHabit)
CREATE TABLE IF NOT EXISTS user_habits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    morning_person_score INT,
    focus_duration_avg INT,
    procrastination_index FLOAT,
    last_analysis_time DATETIME,
    INDEX idx_user_id (user_id)
);

-- 打卡记录表 (PunchRecord)
CREATE TABLE IF NOT EXISTS punch_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    punch_type INT NOT NULL,
    location_info VARCHAR(500),
    evidence_url VARCHAR(500),
    ai_audit_result INT,
    ai_audit_remark VARCHAR(500),
    task_title VARCHAR(500),
    duration_seconds INT,
    started_at DATETIME,
    ended_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_task_id (task_id),
    INDEX idx_punch_type (punch_type),
    INDEX idx_created_at (created_at)
);

-- =====================================================
-- 5. sp_resource 数据库 - 资源相关表
-- =====================================================
USE sp_resource;

-- 课程资源表 (CourseResource)
CREATE TABLE IF NOT EXISTS course_resources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    topic VARCHAR(200),
    title VARCHAR(200),
    source_url VARCHAR(500),
    platform VARCHAR(100),
    content_summary TEXT,
    vector_id VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_topic (topic),
    INDEX idx_platform (platform)
);

-- =====================================================
-- 验证表创建完成
-- =====================================================
SELECT 'sp_user' AS database_name, COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'sp_user'
UNION ALL
SELECT 'sp_goal', COUNT(*) FROM information_schema.tables WHERE table_schema = 'sp_goal'
UNION ALL
SELECT 'sp_schedule', COUNT(*) FROM information_schema.tables WHERE table_schema = 'sp_schedule'
UNION ALL
SELECT 'sp_punch', COUNT(*) FROM information_schema.tables WHERE table_schema = 'sp_punch'
UNION ALL
SELECT 'sp_resource', COUNT(*) FROM information_schema.tables WHERE table_schema = 'sp_resource';
