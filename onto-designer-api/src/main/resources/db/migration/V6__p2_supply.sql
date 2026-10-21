-- 仅追加迁移；授权实时读取，助手和知识定义进入候选冻结载荷。
ALTER TABLE ontology_space ADD COLUMN guard_cfg jsonb NOT NULL DEFAULT '{}';
ALTER TABLE space_grant ADD COLUMN data_scope jsonb;
ALTER TABLE space_grant ADD COLUMN attributes jsonb NOT NULL DEFAULT '{}';
ALTER TABLE ont_operation ADD COLUMN input_schema jsonb;
ALTER TABLE ont_operation ADD COLUMN output_schema jsonb;
ALTER TABLE ont_operation ADD COLUMN assistant_tool jsonb;
ALTER TABLE ont_test_case ADD COLUMN mode varchar(10) NOT NULL DEFAULT 'DIRECT' CHECK(mode IN ('DIRECT','NL'));
CREATE TABLE ont_knowledge(
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES ontology_space(id),
 term varchar(200) NOT NULL, aliases jsonb NOT NULL DEFAULT '[]', definition text NOT NULL,
 concept_ref uuid, source_ref varchar(1000) NOT NULL,
 FOREIGN KEY(concept_ref,space_id) REFERENCES ont_class(id,space_id), UNIQUE(space_id,term)
);
