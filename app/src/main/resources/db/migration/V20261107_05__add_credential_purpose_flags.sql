-- ============================================================
-- BYOK 阶段二：凭证用途标记（Chat / Embedding）
-- 目的：区分同一份用户凭证可用于哪些能力，避免把不能做 Embedding 的
--       供应商 Key（如 DeepSeek）误用于知识库向量化。
-- 兼容性：只新增列并给存量数据回填默认值，不修改、不删除任何已有列，
--         阶段一已保存的用户凭证数据完整保留。
-- ============================================================

ALTER TABLE user_llm_credential
  ADD COLUMN IF NOT EXISTS chat_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS embedding_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 阶段一保存的凭证全部是 Chat 用途，chat_enabled 默认 TRUE 已覆盖；
-- 对平台侧声明支持 Embedding 的供应商回填 embedding_enabled。
UPDATE user_llm_credential
   SET embedding_enabled = TRUE
 WHERE provider_key IN ('dashscope', 'glm');

COMMENT ON COLUMN user_llm_credential.chat_enabled IS '该凭证可用于对话模型调用';
COMMENT ON COLUMN user_llm_credential.embedding_enabled IS '该凭证可用于 Embedding（知识库向量化/检索）';

CREATE INDEX IF NOT EXISTS idx_user_llm_credential_embedding
  ON user_llm_credential (user_id, embedding_enabled);
