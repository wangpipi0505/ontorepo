-- C6：操作业务说明/执行条件、属性显示名称；旧数据为空，不伪造业务语义。
ALTER TABLE ont_operation ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE ont_operation ADD COLUMN precondition text NOT NULL DEFAULT '';
ALTER TABLE ont_property ADD COLUMN display_name varchar(200) NOT NULL DEFAULT '';
