-- P3业务权威全部在PG；仅追加结构，禁止改写历史候选字节。
CREATE TABLE data_file(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(240) NOT NULL,media_type varchar(100) NOT NULL,size bigint NOT NULL CHECK(size BETWEEN 1 AND 8388608),sha256 char(64) NOT NULL,resource varchar(100) NOT NULL UNIQUE,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE datasource(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),code varchar(80) NOT NULL,name varchar(200) NOT NULL,kind varchar(30) NOT NULL,dialect varchar(30) NOT NULL,conn_cfg text NOT NULL,access_scope jsonb NOT NULL,status varchar(20) NOT NULL DEFAULT 'UNTESTED',last_test_at timestamptz,UNIQUE(space_id,code));
CREATE TABLE meta_snapshot(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),datasource_id uuid NOT NULL REFERENCES datasource(id),sha256 char(64) NOT NULL,resources jsonb NOT NULL,captured_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER meta_snapshot_no_update BEFORE UPDATE ON meta_snapshot FOR EACH ROW EXECUTE FUNCTION immutable_candidate();
CREATE TABLE data_mapping(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),kind varchar(10) NOT NULL CHECK(kind IN('OBJECT','PROPERTY','RELATION')),definition jsonb NOT NULL);
ALTER TABLE ont_operation ADD COLUMN data_query jsonb;
CREATE TABLE modeling_suggestion(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),batch_id uuid NOT NULL,source_kind varchar(10) NOT NULL,kind varchar(10) NOT NULL,payload jsonb NOT NULL,confidence numeric NOT NULL,location jsonb NOT NULL,status varchar(10) NOT NULL DEFAULT 'PENDING',entity_id uuid,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE evidence_ref(id bigserial PRIMARY KEY,execution_id uuid NOT NULL REFERENCES op_execution(id),kind varchar(30) NOT NULL,ref jsonb NOT NULL);
CREATE INDEX mapping_space_kind ON data_mapping(space_id,kind);
CREATE INDEX suggestion_batch ON modeling_suggestion(space_id,batch_id);
CREATE INDEX evidence_execution ON evidence_ref(execution_id,id);
