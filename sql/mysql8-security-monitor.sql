-- 智能安防远程监控与告警平台 - MySQL 8 初始化脚本
-- 适用版本：MySQL 8.0+
-- 字符集：utf8mb4

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS `security_monitor`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `security_monitor`;

-- 设备表：对应实体 model.top.rayawa.monitor.Device
CREATE TABLE IF NOT EXISTS `device` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '设备主键',
    `code` VARCHAR(255) NOT NULL COMMENT '设备唯一编码',
    `name` VARCHAR(255) NOT NULL COMMENT '设备名称',
    `type` VARCHAR(255) NOT NULL COMMENT '设备类型',
    `area` VARCHAR(255) NOT NULL COMMENT '所属区域',
    `status` VARCHAR(255) NOT NULL COMMENT '在线状态：在线、离线',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_code` (`code`),
    KEY `idx_device_status` (`status`),
    KEY `idx_device_area_type` (`area`, `type`)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='安防设备信息';

-- 告警表：对应实体 model.top.rayawa.monitor.Alarm
CREATE TABLE IF NOT EXISTS `alarm` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '告警主键',
    `type` VARCHAR(255) NOT NULL COMMENT '告警类型',
    `area` VARCHAR(255) NOT NULL COMMENT '发生区域',
    `level` VARCHAR(255) NOT NULL COMMENT '告警等级：高、中、低',
    `status` VARCHAR(255) NOT NULL COMMENT '处置状态：待处置、处置中、已关闭',
    `source` VARCHAR(255) NOT NULL COMMENT '告警来源',
    `detail` VARCHAR(500) NOT NULL COMMENT '识别结果或告警详情',
    `event_time` DATETIME(6) NOT NULL COMMENT '事件发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_alarm_event_time` (`event_time`),
    KEY `idx_alarm_status_event_time` (`status`, `event_time`),
    KEY `idx_alarm_source_event_time` (`source`, `event_time`),
    KEY `idx_alarm_area_event_time` (`area`, `event_time`)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='安防告警记录';

-- 兼容已执行过旧版脚本的数据库，扩大详情字段以容纳模型结果和图片地址。
ALTER TABLE `alarm` MODIFY COLUMN `detail` VARCHAR(500) NOT NULL COMMENT '识别结果或告警详情';

-- 测试设备数据。
-- 固定主键配合 INSERT IGNORE，使脚本可以重复执行。
INSERT IGNORE INTO `device` (`id`, `code`, `name`, `type`, `area`, `status`) VALUES
    (1, 'CAM-001',  '西门摄像头',     '摄像头', 'A区西门',     '在线'),
    (2, 'CAM-002',  '仓库摄像头',     '摄像头', 'B区仓库',     '在线'),
    (3, 'DOOR-001', '教学楼门禁',     '门禁',   '教学楼',      '在线'),
    (4, 'CAM-003',  '南门摄像头',     '摄像头', 'A区南门',     '在线'),
    (5, 'SMOKE-001','仓库烟感探测器', '烟感',   'B区仓库',     '在线'),
    (6, 'CAM-004',  '停车场摄像头',   '摄像头', '地下停车场',  '离线'),
    (7, 'DOOR-002', '实验楼门禁',     '门禁',   '实验楼',      '在线'),
    (8, 'CAM-005',  '教学楼南侧摄像头','摄像头','教学楼南侧', '在线');

-- 测试告警数据。
-- 样例数据覆盖主要告警类型及三种处置状态。
-- 等级与 AlarmRuleMapper 的映射规则保持一致：人员闯入(中)、人员聚集(高)、物品异常(中)。
-- 注意 INSERT IGNORE 不会覆盖已存在的行：已导入过旧版样例的库需先清空 alarm 表再导入，等级才会更新。
INSERT IGNORE INTO `alarm`
    (`id`, `type`, `area`, `level`, `status`, `source`, `detail`, `event_time`)
VALUES
    (1,  '人员闯入', 'A区西门',    '中', '待处置', 'AI视觉分析', 'AI识别到人员进入限制区域',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 5 MINUTE)),
    (2,  '人员聚集', '教学楼南侧', '高', '已关闭', 'AI视觉分析', 'AI识别到人员聚集',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 9 MINUTE)),
    (3,  '烟火异常', 'B区仓库',    '高', '待处置', 'AI视觉分析', '检测到疑似烟火异常',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 13 MINUTE)),
    (4,  '车辆异常', 'A区南门',    '中', '待处置', 'AI视觉分析', 'AI识别结果：car(94%)',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 30 MINUTE)),
    (5,  '动物进入', 'A区西门',    '中', '已关闭', 'AI视觉分析', 'AI识别结果：dog(92%)',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 1 HOUR)),
    (6,  '物品异常', '教学楼',     '中', '处置中', 'AI视觉分析', 'AI识别结果：backpack(91%)',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 2 HOUR)),
    (7,  '人员闯入', '实验楼',     '中', '处置中', 'AI视觉分析', 'AI识别结果：person(95%)',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 5 HOUR)),
    (8,  '烟火异常', 'B区仓库',    '高', '已关闭', 'AI视觉分析', 'AI识别结果：smoke(97%)，现场已排查',
        DATE_SUB(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 1 DAY));
