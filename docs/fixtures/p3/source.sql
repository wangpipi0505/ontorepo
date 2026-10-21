-- 只用于本工程独立 onto_sources 样例数据库；业务库不运行本文件。
-- 固定前缀和无覆盖 INSERT 使重复初始化保留人工编辑，不清理任何业务记录。
CREATE SCHEMA IF NOT EXISTS onto_sample;
CREATE TABLE IF NOT EXISTS onto_sample.algorithms(
    std_code varchar(40) PRIMARY KEY,
    algorithm_name varchar(100) NOT NULL,
    region varchar(20),
    enabled boolean NOT NULL
);
CREATE TABLE IF NOT EXISTS onto_sample.tasks(
    task_id varchar(40) PRIMARY KEY,
    task_name varchar(100) NOT NULL,
    algo_code varchar(40) REFERENCES onto_sample.algorithms(std_code),
    region varchar(20),
    priority integer NOT NULL
);
COMMENT ON TABLE onto_sample.tasks IS '任务';
COMMENT ON TABLE onto_sample.algorithms IS '算法';
COMMENT ON COLUMN onto_sample.tasks.task_id IS '任务编号';
COMMENT ON COLUMN onto_sample.tasks.task_name IS '任务名称';
COMMENT ON COLUMN onto_sample.tasks.algo_code IS '使用算法';
COMMENT ON COLUMN onto_sample.tasks.region IS '区域';
COMMENT ON COLUMN onto_sample.algorithms.std_code IS '算法标准码';
INSERT INTO onto_sample.algorithms VALUES
    ('ALG-A','图像分类算法','A',true),
    ('ALG-B','目标检测算法','B',true),
    ('ALG-NULL','未分类算法',null,false)
ON CONFLICT DO NOTHING;
INSERT INTO onto_sample.tasks VALUES
    ('T-A','华东分类任务','ALG-A','A',1),
    ('T-B','华北检测任务','ALG-B','B',2),
    ('T-NULL','未分类任务','ALG-A',null,3),
    ('T-CROSS','跨标签关联任务','ALG-B','A',4)
ON CONFLICT DO NOTHING;
