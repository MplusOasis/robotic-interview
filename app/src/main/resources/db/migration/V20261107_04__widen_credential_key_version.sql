-- ============================================================
-- BYOK 阶段一：修正 user_llm_credential.key_version 类型
--
-- 背景：V20261107_02 建表时把 key_version 定义为 SMALLINT，
-- 而实体 UserLlmCredentialEntity#keyVersion 为 Java Integer，
-- 在 spring.jpa.hibernate.ddl-auto=validate 下会因类型不一致导致启动失败：
--   wrong column type encountered in column [key_version] ... found [int2], expecting [integer]
--
-- 采用"新增迁移"而不是修改历史脚本，保证已执行过 02 的环境（含本地与任何已部署实例）
-- 与全新环境最终结构一致，且不会因 checksum 变化触发 Flyway 校验失败。
-- ============================================================

ALTER TABLE user_llm_credential
  ALTER COLUMN key_version TYPE INTEGER USING key_version::INTEGER;
