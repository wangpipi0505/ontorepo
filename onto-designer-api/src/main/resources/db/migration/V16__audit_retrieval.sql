-- R1-A4：处理审计要素与检索。只追加列/索引，不改写历史行。
ALTER TABLE audit_log ADD COLUMN subject_type varchar(30);
ALTER TABLE audit_log ADD COLUMN version_refs jsonb;
ALTER TABLE audit_log ADD COLUMN input_digest char(64);
ALTER TABLE audit_log ADD COLUMN output_digest char(64);
ALTER TABLE audit_log ADD COLUMN result varchar(20);

CREATE INDEX audit_object ON audit_log(space_id,subject_type,subject_id,id);
CREATE INDEX audit_action ON audit_log(space_id,action,id);
CREATE INDEX audit_time ON audit_log(space_id,created_at,id);
