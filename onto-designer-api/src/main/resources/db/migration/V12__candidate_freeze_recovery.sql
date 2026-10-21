-- 冻结仅有机器处理阶段才设置截止时间；历史人工 REVIEWING 不进入自动回收。
-- 截止时间属于运行进度，不改写既有候选载荷、摘要或已预分配的版本身份。
ALTER TABLE publish_candidate ADD COLUMN freeze_deadline timestamptz;
UPDATE publish_candidate SET freeze_deadline=created_at+interval '15 minutes' WHERE status='FREEZING';
CREATE INDEX candidate_freezing_deadline ON publish_candidate(freeze_deadline,id) WHERE status='FREEZING';
