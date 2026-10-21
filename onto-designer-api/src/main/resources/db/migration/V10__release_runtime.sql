-- P4仅追加结构；旧候选原始字节/摘要不变，新状态字段不改变冻结内容。
ALTER TABLE ontology_space ADD COLUMN next_version_no bigint NOT NULL DEFAULT 1 CHECK(next_version_no>0);
ALTER TABLE publish_candidate ADD COLUMN pre_release_id uuid UNIQUE;
ALTER TABLE publish_candidate ADD COLUMN pre_version_no bigint;
ALTER TABLE publish_candidate ADD COLUMN bundle_ref jsonb;
ALTER TABLE publish_candidate ADD COLUMN manifest_ref jsonb;
ALTER TABLE publish_candidate ADD COLUMN context_pack_ref jsonb;
ALTER TABLE publish_candidate ADD COLUMN index_ref jsonb;
ALTER TABLE publish_candidate ADD COLUMN manifest_preview jsonb;
ALTER TABLE publish_candidate ADD COLUMN release_id uuid;
ALTER TABLE publish_candidate ADD COLUMN failure_code varchar(100);
ALTER TABLE publish_candidate ADD COLUMN review jsonb;
ALTER TABLE publish_candidate ADD COLUMN allocation_status varchar(10);
ALTER TABLE publish_candidate ADD CONSTRAINT candidate_version_unique UNIQUE(space_id,pre_version_no);
CREATE FUNCTION immutable_candidate_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.space_id IS DISTINCT FROM OLD.space_id
 OR NEW.draft_rev IS DISTINCT FROM OLD.draft_rev OR NEW.sha256 IS DISTINCT FROM OLD.sha256
 OR NEW.payload IS DISTINCT FROM OLD.payload OR NEW.format_version IS DISTINCT FROM OLD.format_version
 OR NEW.capability IS DISTINCT FROM OLD.capability OR NEW.created_at IS DISTINCT FROM OLD.created_at
 OR NEW.pre_release_id IS DISTINCT FROM OLD.pre_release_id OR NEW.pre_version_no IS DISTINCT FROM OLD.pre_version_no
 THEN RAISE EXCEPTION 'candidate content is immutable'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER candidate_no_update ON publish_candidate;
CREATE TRIGGER candidate_no_update BEFORE UPDATE ON publish_candidate FOR EACH ROW EXECUTE FUNCTION immutable_candidate_content();

CREATE TABLE publication_check(id bigserial PRIMARY KEY,candidate_id uuid NOT NULL REFERENCES publish_candidate(id),candidate_sha256 char(64) NOT NULL,kind varchar(20) NOT NULL,conclusion varchar(20) NOT NULL CHECK(conclusion IN('PASSED','FAILED','NOT_CHECKED')),reason varchar(100),report jsonb NOT NULL,checked_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX publication_check_latest ON publication_check(candidate_id,kind,id DESC);
CREATE TABLE test_run(id uuid PRIMARY KEY,candidate_id uuid NOT NULL REFERENCES publish_candidate(id),candidate_sha256 char(64) NOT NULL,enabled_count integer NOT NULL,completed_count integer NOT NULL,passed boolean NOT NULL,report jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
-- 归档前固定确切字节；Lucene段文件包含编译身份，故失败重试必须复用计划而不能重新编译。
CREATE TABLE candidate_artifact_plan(candidate_id uuid NOT NULL REFERENCES publish_candidate(id),kind varchar(30) NOT NULL,object_key text NOT NULL,sha256 char(64) NOT NULL,size bigint NOT NULL,bytes bytea NOT NULL,PRIMARY KEY(candidate_id,kind));
CREATE TRIGGER artifact_plan_no_update BEFORE UPDATE ON candidate_artifact_plan FOR EACH ROW EXECUTE FUNCTION immutable_candidate();
CREATE TABLE ontology_release(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),version_no bigint NOT NULL CHECK(version_no>0),candidate_id uuid NOT NULL UNIQUE REFERENCES publish_candidate(id),status varchar(20) NOT NULL CHECK(status IN('READY','DEPRECATED','CORRUPTED')),bundle_ref jsonb NOT NULL,manifest_ref jsonb NOT NULL,context_pack_ref jsonb NOT NULL,index_ref jsonb NOT NULL,bundle_sha256 char(64) NOT NULL,manifest_sha256 char(64) NOT NULL,published_at timestamptz NOT NULL DEFAULT now(),published_by uuid NOT NULL REFERENCES local_user(id),integrity_error varchar(100),integrity_checked_at timestamptz,UNIQUE(space_id,version_no));
CREATE TABLE space_version(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),release_id uuid NOT NULL UNIQUE REFERENCES ontology_release(id),version_no bigint NOT NULL,status varchar(20) NOT NULL,UNIQUE(space_id,version_no));
CREATE TABLE context_pack(id uuid PRIMARY KEY,release_id uuid NOT NULL UNIQUE REFERENCES ontology_release(id),artifact_ref jsonb NOT NULL);
CREATE TABLE release_entity(release_id uuid NOT NULL REFERENCES ontology_release(id),kind varchar(30) NOT NULL,entity_key text NOT NULL,value jsonb NOT NULL,PRIMARY KEY(release_id,kind,entity_key));
CREATE TABLE service_endpoint(id uuid PRIMARY KEY,release_id uuid NOT NULL REFERENCES ontology_release(id),op_code varchar(80) NOT NULL,route text NOT NULL,status varchar(10) NOT NULL DEFAULT 'ACTIVE',exec_attrs jsonb NOT NULL,UNIQUE(release_id,op_code));
CREATE TABLE endpoint_grant(endpoint_id uuid NOT NULL REFERENCES service_endpoint(id),user_id uuid NOT NULL REFERENCES local_user(id),data_scope jsonb,expires_at timestamptz,status varchar(10) NOT NULL CHECK(status IN('ACTIVE','REVOKED')),PRIMARY KEY(endpoint_id,user_id));
CREATE TABLE api_credential(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),principal_id uuid NOT NULL REFERENCES local_user(id),name varchar(200) NOT NULL,key_hash char(64) NOT NULL UNIQUE,status varchar(10) NOT NULL CHECK(status IN('ACTIVE','REVOKED')),created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz);

CREATE TABLE publish_outbox(id bigserial PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),release_id uuid NOT NULL REFERENCES ontology_release(id),type varchar(30) NOT NULL,bound_status varchar(20) NOT NULL,payload jsonb NOT NULL,status varchar(10) NOT NULL DEFAULT 'PENDING',retry_count integer NOT NULL DEFAULT 0,attempt_count integer NOT NULL DEFAULT 0,next_retry_at timestamptz NOT NULL,lease_token uuid,lease_until timestamptz,last_error varchar(300),created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(release_id,type));
CREATE INDEX publish_outbox_pending ON publish_outbox(release_id,id) WHERE status='PENDING';
CREATE INDEX publish_outbox_due ON publish_outbox(next_retry_at,id) WHERE status='PENDING';
CREATE TABLE publish_delivery_attempt(id bigserial PRIMARY KEY,event_id bigint NOT NULL REFERENCES publish_outbox(id),attempt_no integer NOT NULL,lease_token uuid NOT NULL,outcome varchar(20) NOT NULL,error varchar(300),created_at timestamptz NOT NULL,UNIQUE(event_id,attempt_no));
CREATE TABLE publish_delivery_alert(event_id bigint PRIMARY KEY REFERENCES publish_outbox(id),code varchar(80) NOT NULL,created_at timestamptz NOT NULL,resolved_at timestamptz);
CREATE TABLE publish_inbox(event_id bigint PRIMARY KEY,release_id uuid NOT NULL,type varchar(30) NOT NULL,received_at timestamptz NOT NULL,receipt jsonb NOT NULL);

ALTER TABLE op_execution DROP CONSTRAINT execution_subject_id_fkey;
ALTER TABLE op_execution DROP CONSTRAINT execution_facts_required;
ALTER TABLE op_execution ADD CONSTRAINT execution_facts_required CHECK(caller='LEGACY' OR(caller IN('TRIAL','LOCAL_TEST','SERVICE','EXTERNAL') AND principal_id IS NOT NULL AND input_digest IS NOT NULL AND output_digest IS NOT NULL));
ALTER TABLE op_execution ADD COLUMN "authorization" jsonb NOT NULL DEFAULT '[]';
CREATE FUNCTION valid_execution_subject() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.subject_type='CANDIDATE' AND NOT EXISTS(SELECT 1 FROM publish_candidate WHERE id=NEW.subject_id AND space_id=NEW.space_id))
 OR (NEW.subject_type='RELEASE' AND NOT EXISTS(SELECT 1 FROM ontology_release WHERE id=NEW.subject_id AND space_id=NEW.space_id))
 OR NEW.subject_type NOT IN('CANDIDATE','RELEASE') THEN RAISE EXCEPTION 'invalid execution subject'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_subject_valid BEFORE INSERT OR UPDATE ON op_execution FOR EACH ROW EXECUTE FUNCTION valid_execution_subject();
ALTER TABLE op_execution_step ADD COLUMN created_at timestamptz;
ALTER TABLE op_execution_step ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE evidence_ref ADD COLUMN created_at timestamptz;
ALTER TABLE evidence_ref ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE audit_log ADD COLUMN subject_id text;
ALTER TABLE audit_log ADD COLUMN detail jsonb NOT NULL DEFAULT '{}';
ALTER TABLE audit_log ALTER COLUMN actor_id DROP NOT NULL;
-- 恢复草稿前保存被替换的原快照事实；不依赖当前草稿数据源FK，原字段仍不可修改。
CREATE TABLE meta_snapshot_archive(id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES ontology_space(id),datasource_id uuid NOT NULL,sha256 char(64) NOT NULL,resources jsonb NOT NULL,captured_at timestamptz NOT NULL);
CREATE TRIGGER meta_archive_no_update BEFORE UPDATE OR DELETE ON meta_snapshot_archive FOR EACH ROW EXECUTE FUNCTION immutable_candidate();
CREATE FUNCTION immutable_release_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-ARRAY['status','integrity_error','integrity_checked_at']) IS DISTINCT FROM
    (to_jsonb(OLD)-ARRAY['status','integrity_error','integrity_checked_at'])
 THEN RAISE EXCEPTION 'release content is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER release_content_no_update BEFORE UPDATE ON ontology_release FOR EACH ROW EXECUTE FUNCTION immutable_release_content();
CREATE FUNCTION immutable_outbox_payload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.space_id IS DISTINCT FROM OLD.space_id OR NEW.release_id IS DISTINCT FROM OLD.release_id
 OR NEW.type IS DISTINCT FROM OLD.type OR NEW.bound_status IS DISTINCT FROM OLD.bound_status OR NEW.payload IS DISTINCT FROM OLD.payload
 OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'event identity/payload is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER outbox_payload_no_update BEFORE UPDATE ON publish_outbox FOR EACH ROW EXECUTE FUNCTION immutable_outbox_payload();
