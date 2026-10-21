-- R1-A2：端点级启停。只追加列/约束/索引，不改历史制品或候选字节。
ALTER TABLE service_endpoint ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE service_endpoint ADD COLUMN status_changed_by uuid REFERENCES local_user(id);
ALTER TABLE service_endpoint ADD CONSTRAINT service_endpoint_status_check CHECK (status IN ('ACTIVE','DISABLED'));
CREATE INDEX service_endpoint_release_status ON service_endpoint(release_id,status);
