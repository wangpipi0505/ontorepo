# 五类外部数据源验收

用户已确认：先完成代码和当前独立环境验证，ClickHouse、MongoDB、Redis、Cassandra、Neo4j 的真实实例留待外部验收。本文为可执行的补验清单，不是通过记录。当前状态以 `docs/evidence/continuation/acceptance-matrix.md` 和事项 OM-P3-EXT 为准。

## 输入与统一流程

每种实例提供独立测试库/集合/前缀及对应账号。由空间 OWNER 在“数据源管理”填写连接，原始凭据不进入验收报告；外部环境版本与驱动兼容情况须按实际返回记录。

1. 保存源，确认读取源概要不回显连接凭据；重新编辑不替换连接时保留原配置。
2. 运行连接、认证、授权范围三阶段探测，记录每段实际结果；无权限的范围不能因连接成功自动进入白名单。
3. 同步元数据，核对资源/字段/类型/主键或证据定位方式；修改一个测试字段后再次同步，核对新增/删除/类型变更及影响映射。
4. 建立本体对象、属性与映射；OWNER 明确配置必需标签绑定。为授权范围 A 的主体准备 A、B、未分类、普通数值与布尔样例；不允许分类标签的协议按下表使用对应反例。
5. 冻结候选，分别经试执行、LOCAL_TEST、内部候选调用核对范围和结果。未定义/未绑定 `tenant_label` 的反例必须在访问源前失败；完整性门禁不得将它记为通过。
6. 发布后授予仅 A 的端点范围，即使空间范围为 A/B、账号能访问更多资源，正式服务也只返回 A；成功记录实际版本摘要、源记录、元数据与映射证据。失败无成功 meta/版本头。
7. 修改草稿映射/源设置后，再调用原版本，确认使用其冻结定义。撤销当前授权后应立即拒绝，冻结不得使旧授权永久有效。
8. 核对超时、越白名单、空范围交集和未支持查询的明确拒绝。停止测试实例后记录真实不可达结果，不记录为“业务无结果”。

执行入口权限、候选/发布能力按实际实现状态核验；未完成的发布链不可用手工插入 READY 数据代替。

## 按协议补验

| 类型 | 连接与白名单 | 必需真实样例 | 实现边界 |
| --- | --- | --- | --- |
| ClickHouse | `dialect=clickhouse`，url/user/password，`database.table` | MergeTree 家族表；A/B/NULL/纯空白标签，数值/布尔；边界内筛选及投影；核验 `_part/_part_offset` 证据 | 排序键不承诺唯一；实际字段/函数/系统表权限按实例确认。当前只验证过生成谓词，未验证服务器语法与执行 |
| MongoDB | `dialect=mongodb`，uri/database，`database.collection` | 包含 `_id`、region及不同JSON类型的文档；缺失/null/纯空白标签；索引与原生find筛选/投影/limit | 元数据来自样本，不代表全集合Schema；聚合脚本不在边界内，需明确拒绝 |
| Redis | `dialect=redis`，host/port/password/database，批准键前缀 | STRING中的JSON对象，A/B/未分类键；`_key EQ`精确读取及SCAN范围；越前缀拒绝 | 不把HASH/LIST类型伪装成已支持；记录SCAN预算耗尽与完整结果的区别 |
| Cassandra | `dialect=cassandra`，contactPoints/port/datacenter/keyspace/user/password，`keyspace.table` | 完整分区键EQ查询，分类字段作为同一分区键；A/B及协议允许的纯空白标签反例；缺分区键拒绝 | 主键不可为NULL，记录该协议约束；不能伪造NULL主键样例。无ALLOW FILTERING或任意JOIN |
| Neo4j | `dialect=neo4j`，uri/user/password/database，批准节点标签 | A/B/缺失标签节点；单跳有向关系两端A/A通过、A/B拒绝；真实节点/关系elementId证据 | 单跳关系模板用固定关系类型和目标标签；不接受任意Cypher，不把当前不支持的关系附加过滤判为通过 |

所有连接字段和QuerySpec边界以 `onto-data/README.md` 为准。管理FILE的受管目录属于本地已有验证，不计入这五类。不能将 PostgreSQL 改写 kind 后计为 ClickHouse 验收。

转换属性过滤增量的验收须遵循实际能力：Redis的受限JSON查询可在授权后、LIMIT前应用白名单转换；其余四种外部协议的当前模板对此明确返回`TRANSFORM_FILTER_UNSUPPORTED`，不能把该拒绝当作成功查询，也不能把转换后的输入与原始源值直接比较。各协议原有IDENTITY过滤与转换属性投影继续分别核验，增量实际通过状态见`c5-transform-report.md`。

## 证据格式

每个协议保存一份脱敏报告 `docs/evidence/external/<kind>-<date>.json`，至少包括：

```json
{
  "kind": "WAREHOUSE",
  "dialect": "clickhouse",
  "checkedAt": "实际执行时间",
  "serverVersion": "实际实例版本",
  "datasourceId": "本工程返回的标识",
  "checks": [
    {
      "case": "A范围过滤",
      "status": "PASSED或FAILED或NOT_RUN",
      "expected": "只返回A且证据可定位",
      "actual": "实际结果摘要",
      "executionId": "实际执行标识"
    }
  ],
  "unverified": []
}
```

证据不得包含密码、API key、授权头或含凭据的连接串。五类逐行完成代码边界内的实际探测/元数据/查询/授权/证据后，才能更新 OM-P3-EXT；某一类失败或未运行时保留该行，不以总测试数代替。
