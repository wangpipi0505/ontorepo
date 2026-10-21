-- 历史候选仅补只读能力元信息，原始payload、摘要和既有字节保持不变。
ALTER TABLE publish_candidate ADD COLUMN format_version integer NOT NULL DEFAULT 1;
ALTER TABLE publish_candidate ADD COLUMN capability varchar(20) NOT NULL DEFAULT 'MODEL_ONLY';
