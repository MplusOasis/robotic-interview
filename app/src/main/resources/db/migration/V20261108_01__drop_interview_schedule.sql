-- 下线面试日程管理功能，移除对应业务表。
-- V1__init_schema.sql 与 V20261107_03__add_user_ownership_to_business_tables.sql
-- 为已应用的历史迁移，保持原样以避免 checksum 校验失败；
-- 本脚本在已有库和全新库中都会执行，故使用 IF EXISTS 保证可回放。
DROP TABLE IF EXISTS interview_schedule;
