-- ============================================================
-- BYOK 阶段一：业务数据归属（对象级隔离）
--
-- 迁移策略（绝不丢数据、绝不把历史数据分给第一个注册用户）：
--   1. 建立一个 system 历史数据所有者：status=DISABLED、password_hash 为
--      刻意非法值，因此永远无法登录，也不存在任何"默认密码"。
--   2. 新增列先 nullable，回填为 system 用户，再置 NOT NULL 并加外键。
--   3. 唯一约束由"全局唯一"改为"用户内唯一"，否则 A 上传过的文件会让 B 无法上传。
-- ============================================================

-- ---------- 1. 历史数据所有者 ----------
INSERT INTO app_user (username, password_hash, email, role, status, created_at, updated_at)
VALUES ('system', '!disabled-system-account-no-login', NULL, 'ADMIN', 'DISABLED',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---------- 2. resumes ----------
ALTER TABLE resumes ADD COLUMN user_id BIGINT;
UPDATE resumes SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE resumes ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE resumes
  ADD CONSTRAINT fk_resumes_user FOREIGN KEY (user_id) REFERENCES app_user(id);
ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
ALTER TABLE resumes
  ADD CONSTRAINT uk_resumes_user_file_hash UNIQUE (user_id, file_hash);
CREATE INDEX IF NOT EXISTS idx_resume_user ON resumes (user_id);

-- ---------- 3. knowledge_bases ----------
ALTER TABLE knowledge_bases ADD COLUMN user_id BIGINT;
UPDATE knowledge_bases SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE knowledge_bases ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE knowledge_bases
  ADD CONSTRAINT fk_knowledge_bases_user FOREIGN KEY (user_id) REFERENCES app_user(id);
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;
ALTER TABLE knowledge_bases
  ADD CONSTRAINT uk_knowledge_bases_user_file_hash UNIQUE (user_id, file_hash);
CREATE INDEX IF NOT EXISTS idx_kb_user ON knowledge_bases (user_id);

-- ---------- 4. interview_sessions ----------
ALTER TABLE interview_sessions ADD COLUMN user_id BIGINT;
UPDATE interview_sessions SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE interview_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE interview_sessions
  ADD CONSTRAINT fk_interview_sessions_user FOREIGN KEY (user_id) REFERENCES app_user(id);
CREATE INDEX IF NOT EXISTS idx_interview_session_user_created
  ON interview_sessions (user_id, created_at);

-- ---------- 5. rag_chat_sessions ----------
ALTER TABLE rag_chat_sessions ADD COLUMN user_id BIGINT;
UPDATE rag_chat_sessions SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE rag_chat_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE rag_chat_sessions
  ADD CONSTRAINT fk_rag_chat_sessions_user FOREIGN KEY (user_id) REFERENCES app_user(id);
CREATE INDEX IF NOT EXISTS idx_rag_session_user ON rag_chat_sessions (user_id);

-- ---------- 6. interview_schedule ----------
ALTER TABLE interview_schedule ADD COLUMN user_id BIGINT;
UPDATE interview_schedule SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE interview_schedule ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE interview_schedule
  ADD CONSTRAINT fk_interview_schedule_user FOREIGN KEY (user_id) REFERENCES app_user(id);
CREATE INDEX IF NOT EXISTS idx_interview_schedule_user
  ON interview_schedule (user_id, interview_time);

-- ---------- 7. voice_interview_sessions ----------
-- 原 user_id 为 VARCHAR 且恒为 'default'，直接替换为真实用户主键。
ALTER TABLE voice_interview_sessions DROP COLUMN IF EXISTS user_id;
ALTER TABLE voice_interview_sessions ADD COLUMN user_id BIGINT;
UPDATE voice_interview_sessions SET user_id = (SELECT id FROM app_user WHERE username = 'system');
ALTER TABLE voice_interview_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE voice_interview_sessions
  ADD CONSTRAINT fk_voice_interview_sessions_user FOREIGN KEY (user_id) REFERENCES app_user(id);
CREATE INDEX IF NOT EXISTS idx_voice_session_user
  ON voice_interview_sessions (user_id, updated_at);
