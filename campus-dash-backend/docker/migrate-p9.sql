-- P9 增量迁移，可重复执行。用于已有 CampusDash 数据库。
SET NAMES utf8mb4;

SET @has_delivered_at := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND COLUMN_NAME='delivered_at');
SET @ddl := IF(@has_delivered_at=0,
  'ALTER TABLE errand ADD COLUMN delivered_at DATETIME(3) NULL COMMENT ''送达时间，自动结算扫描用'' AFTER locked_at',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_source_type := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND COLUMN_NAME='source_type');
SET @ddl := IF(@has_source_type=0,
  'ALTER TABLE errand ADD COLUMN source_type VARCHAR(24) NULL COMMENT ''NORMAL/RUSH_CAMPAIGN'' AFTER delivered_at',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_source_request := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND COLUMN_NAME='source_request_id');
SET @ddl := IF(@has_source_request=0,
  'ALTER TABLE errand ADD COLUMN source_request_id VARCHAR(64) NULL AFTER source_type',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_source_index := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND INDEX_NAME='uk_errand_source_request');
SET @ddl := IF(@has_source_index=0,
  'ALTER TABLE errand ADD UNIQUE KEY uk_errand_source_request (source_request_id)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS rush_campaign (
  id BIGINT NOT NULL, campus_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
  start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL,
  quota_total INT NOT NULL, sold_count INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), KEY idx_campus_time (campus_id,status,start_at),
  CONSTRAINT ck_rush_quota CHECK (quota_total>0 AND sold_count>=0 AND sold_count<=quota_total),
  CONSTRAINT ck_rush_window CHECK (end_at>start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rush_request (
  request_id VARCHAR(64) NOT NULL, campaign_id BIGINT NOT NULL, campus_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL, status VARCHAR(40) NOT NULL, title VARCHAR(64) NOT NULL,
  reward_amount BIGINT NOT NULL, errand_id BIGINT NULL, error_code VARCHAR(48) NULL,
  accepted_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(request_id), UNIQUE KEY uk_rush_campaign_user(campaign_id,user_id),
  UNIQUE KEY uk_rush_errand(errand_id), KEY idx_rush_user_time(user_id,created_at),
  KEY idx_rush_status_time(status,updated_at),
  CONSTRAINT ck_rush_reward CHECK (reward_amount>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS runner_workload (
  runner_id BIGINT NOT NULL, ongoing_count INT NOT NULL DEFAULT 0,
  max_ongoing INT NOT NULL DEFAULT 5, version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(runner_id),
  CONSTRAINT ck_runner_workload CHECK (ongoing_count>=0 AND ongoing_count<=max_ongoing)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS runner_capacity_assignment (
  errand_id BIGINT NOT NULL, round INT NOT NULL, runner_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL, released_by VARCHAR(32) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), released_at DATETIME(3) NULL,
  PRIMARY KEY(errand_id,round), KEY idx_capacity_runner_status(runner_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
