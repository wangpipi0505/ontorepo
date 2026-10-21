-- C9：快照规范化摘要；旧快照保持 NULL，读取时按规范化字节可复算但不伪造原摘要。
ALTER TABLE meta_snapshot ADD COLUMN canonical_sha256 char(64);
