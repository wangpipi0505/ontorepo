-- P1 新增表仅追加，历史候选字节不重写。所有草稿写入仍经空间锁。
ALTER TABLE ont_property ADD COLUMN facets jsonb NOT NULL DEFAULT '{}';
ALTER TABLE ont_class ADD COLUMN source_iri text;
ALTER TABLE ont_property ADD COLUMN source_iri text;
CREATE TABLE ont_codebook(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),code varchar(80) NOT NULL,name varchar(200) NOT NULL,kind varchar(10) NOT NULL CHECK(kind IN('UNIT','VALUE')),entries jsonb NOT NULL,UNIQUE(space_id,code));
CREATE TABLE ont_relation(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(200) NOT NULL,from_class_id uuid NOT NULL REFERENCES ont_class(id),to_class_id uuid NOT NULL REFERENCES ont_class(id),cardinality varchar(20) NOT NULL,inverse_of uuid REFERENCES ont_relation(id) DEFERRABLE INITIALLY DEFERRED,transitive boolean NOT NULL,"symmetric" boolean NOT NULL,description text NOT NULL,source_iri text,UNIQUE(space_id,name));
CREATE TABLE ont_constraint(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(200) NOT NULL,kind varchar(20) NOT NULL,target_type varchar(20) NOT NULL,target_ref uuid NOT NULL,expr jsonb NOT NULL,UNIQUE(space_id,name));
CREATE TABLE ont_rule(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(200) NOT NULL,applicable_class_id uuid NOT NULL REFERENCES ont_class(id),kind varchar(20) NOT NULL,condition text NOT NULL,action jsonb NOT NULL,scoring jsonb,severity varchar(10) NOT NULL,UNIQUE(space_id,name));
CREATE TABLE ont_strategy(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(200) NOT NULL,policy jsonb NOT NULL,bound_ops jsonb NOT NULL,UNIQUE(space_id,name));
ALTER TABLE ont_operation ADD COLUMN strategy_ref uuid REFERENCES ont_strategy(id);
ALTER TABLE ont_operation ADD COLUMN rule_ids jsonb NOT NULL DEFAULT '[]';
CREATE TABLE ont_test_case(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),name varchar(200) NOT NULL,question text NOT NULL,operation_id uuid NOT NULL REFERENCES ont_operation(id),input jsonb NOT NULL,expected jsonb NOT NULL,match_mode varchar(10) NOT NULL,enabled boolean NOT NULL,UNIQUE(space_id,name));
CREATE TABLE ont_import(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),kind varchar(5) NOT NULL,content text NOT NULL,target_draft_rev bigint NOT NULL,status varchar(10) NOT NULL,report jsonb NOT NULL,applied boolean NOT NULL DEFAULT false);
CREATE TABLE ont_rdf_document(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),content text NOT NULL,format varchar(5) NOT NULL,unsupported jsonb NOT NULL);
CREATE TABLE candidate_check(id uuid PRIMARY KEY,candidate_id uuid NOT NULL REFERENCES publish_candidate(id),candidate_sha256 char(64) NOT NULL,report jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE gate_waiver(id uuid PRIMARY KEY,candidate_id uuid NOT NULL REFERENCES publish_candidate(id),candidate_sha256 char(64) NOT NULL,kind varchar(30) NOT NULL CHECK(kind='SUBSET_EXCEEDED'),reason text NOT NULL,approver uuid NOT NULL REFERENCES local_user(id),draft_rev bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE op_execution DROP CONSTRAINT execution_facts_required;
ALTER TABLE op_execution ADD CONSTRAINT execution_facts_required CHECK(caller='LEGACY' OR (caller IN('TRIAL','LOCAL_TEST') AND principal_id IS NOT NULL AND input_digest IS NOT NULL AND output_digest IS NOT NULL));
