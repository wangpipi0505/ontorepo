-- C4：知识实体/事实关系；缺省 TERM 保持旧知识兼容。
ALTER TABLE ont_knowledge ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'TERM'
  CHECK(kind IN ('TERM','ENTITY','RELATION'));
ALTER TABLE ont_knowledge ADD COLUMN subject_ref varchar(1000);
ALTER TABLE ont_knowledge ADD COLUMN predicate varchar(200);
ALTER TABLE ont_knowledge ADD COLUMN object_ref varchar(1000);
