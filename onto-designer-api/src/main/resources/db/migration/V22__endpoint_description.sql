-- C8 收口：服务端点说明随发布冻结，供消费方识别业务用途。
ALTER TABLE service_endpoint ADD COLUMN description text NOT NULL DEFAULT '';
