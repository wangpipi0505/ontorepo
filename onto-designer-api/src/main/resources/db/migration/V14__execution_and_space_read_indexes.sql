-- 只补实际缺失的查询前缀；已有类/操作/规则等空间唯一键及evidence_execution索引保持不变。
CREATE INDEX op_execution_space_recent ON op_execution(space_id,created_at DESC,id DESC);
CREATE INDEX op_execution_step_order ON op_execution_step(execution_id,step_order);
CREATE INDEX ont_property_space ON ont_property(space_id);
CREATE INDEX ont_import_space ON ont_import(space_id);
CREATE INDEX ont_rdf_document_space ON ont_rdf_document(space_id);
