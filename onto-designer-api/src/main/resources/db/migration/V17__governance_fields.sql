-- C1：空间与数据源治理字段；旧数据说明为空、更新时间回填创建时间，不伪造维护主体。
ALTER TABLE ontology_space ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE ontology_space ADD COLUMN updated_at timestamptz;
UPDATE ontology_space SET updated_at=created_at;
ALTER TABLE ontology_space ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE ontology_space ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE datasource ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE datasource ADD COLUMN owner_id uuid REFERENCES local_user(id);
ALTER TABLE datasource ADD COLUMN tenant varchar(200);
