-- 新功能权限叠加既有空间角色，禁止给系统管理员隐式空间成员资格。
ALTER TABLE local_user ADD COLUMN status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DISABLED'));
ALTER TABLE local_user ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0);
ALTER TABLE local_user ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE local_role(id uuid PRIMARY KEY,code varchar(80) NOT NULL UNIQUE,name varchar(200) NOT NULL,description text NOT NULL DEFAULT '',built_in boolean NOT NULL DEFAULT false,status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DISABLED')),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0));
CREATE TABLE user_role(user_id uuid NOT NULL REFERENCES local_user(id),role_id uuid NOT NULL REFERENCES local_role(id),PRIMARY KEY(user_id,role_id));
CREATE INDEX user_role_by_role ON user_role(role_id,user_id);
CREATE TABLE role_permission(role_id uuid NOT NULL REFERENCES local_role(id) ON DELETE CASCADE,permission_code varchar(80) NOT NULL,PRIMARY KEY(role_id,permission_code));
CREATE TABLE iam_state(id integer PRIMARY KEY CHECK(id=1),initialized boolean NOT NULL DEFAULT false);
INSERT INTO iam_state(id) VALUES(1);
CREATE TABLE iam_audit(id bigserial PRIMARY KEY,actor_id uuid NOT NULL REFERENCES local_user(id),action varchar(80) NOT NULL,target_id uuid NOT NULL,before_state jsonb NOT NULL,after_state jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX iam_audit_created ON iam_audit(created_at DESC,id DESC);
INSERT INTO local_role(id,code,name,description,built_in) VALUES('00000000-0000-0000-0000-000000000001','SYSTEM_ADMIN','系统管理员','管理所有功能权限；仍须独立空间及数据授权',true),('00000000-0000-0000-0000-000000000002','MEMBER','业务成员','保留原业务功能；无系统管理权限',true);
-- 仅升级时保留既有用户功能；新用户必须由管理接口显式分配角色。
INSERT INTO user_role(user_id,role_id) SELECT id,'00000000-0000-0000-0000-000000000002'::uuid FROM local_user;
