-- 对齐详设存储名称，ALTER 保留既有数据、外键和冻结不可变触发器。
ALTER TABLE space RENAME TO ontology_space;
ALTER TABLE space_member RENAME TO space_grant;
ALTER TABLE operation RENAME TO ont_operation;
ALTER TABLE candidate RENAME TO publish_candidate;
ALTER TABLE execution RENAME TO op_execution;
ALTER TABLE execution_step RENAME TO op_execution_step;
ALTER TABLE audit_event RENAME TO audit_log;

-- 老执行若存在则明确标记 LEGACY，不猜测历史身份与摘要；新执行必须提供完整事实。
ALTER TABLE op_execution ADD COLUMN principal_id uuid REFERENCES local_user(id);
ALTER TABLE op_execution ADD COLUMN caller varchar(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE op_execution ADD COLUMN input_digest char(64);
ALTER TABLE op_execution ADD COLUMN output_digest char(64);
ALTER TABLE op_execution ALTER COLUMN caller DROP DEFAULT;
ALTER TABLE op_execution ADD CONSTRAINT execution_trace_unique UNIQUE(trace_id);
ALTER TABLE op_execution ADD CONSTRAINT execution_facts_required CHECK (
    caller='LEGACY' OR (caller='TRIAL' AND principal_id IS NOT NULL AND input_digest IS NOT NULL AND output_digest IS NOT NULL)
);
