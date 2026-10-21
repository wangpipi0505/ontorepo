-- C8：端点角色授予；与用户授予独立，执行时用户授予与 ACTIVE 角色授予的范围求交。
CREATE TABLE endpoint_role_grant(
  endpoint_id uuid NOT NULL REFERENCES service_endpoint(id),
  role_code varchar(80) NOT NULL REFERENCES local_role(code),
  data_scope jsonb,
  expires_at timestamptz,
  status varchar(10) NOT NULL CHECK(status IN('ACTIVE','REVOKED')),
  PRIMARY KEY(endpoint_id,role_code)
);
