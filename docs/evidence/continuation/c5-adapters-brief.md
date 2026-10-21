# C5 独立数据适配与查询计划任务

本任务是 P3 的适配内核，独占 `onto-data/`，不编辑其他模块、父 pom、前端、部署脚本或原设计。原有 CredentialCipher/LabelScope/DataProblem 保留并复用；主执行者会在 P2 后接入管理和执行链。

先读详设 §7.2/§8.5/§8.7/§8.10 和计划 P3。工程上两级 `文档/设计/详细设计/领域本体构建/`。用户已定：未分类数据默认拒绝。不得用客户端过滤后再 LIMIT 的方式泄漏/遗漏授权记录，不允许把适配器数量当九类别真实验收。

## 固定外部 API

包 `cn.ontorepo.data` 下按 domain/infra 分层。以下是共享接口的命名和语义；record 的合理内部细节可在本模块 API 文档中明确，主执行者按文档接入：

- `SourceSpec(id:String,kind:String,connection:JsonNode,accessScope:Set<String>)`：connection 是调用前解密的配置，仅适配器内使用。kind 枚举 RELATIONAL/WAREHOUSE/LAKE/FILE/HTTP/DOCUMENT/KEY_VALUE/WIDE_COLUMN/GRAPH。
- `AdapterRegistry` 根据 kind 和明确 connection.dialect 选择适配器。`SourceAdapter.probe(SourceSpec)` 返回三个独立阶段 CONNECTIVITY/AUTHENTICATION/SCOPE 的 PASSED/FAILED/NOT_RUN 与安全错误码；前段失败后后段 NOT_RUN，绝不伪造通过。
- `SourceAdapter.metadata(SourceSpec)` 返回 typed 元数据（资源、字段/类型/可空、主键和可获得的关系/索引）。只包含 accessScope 中实际存在且可访问资源，查询元数据也要受范围约束。
- `SourceAdapter.query(SourceSpec,QuerySpec)` 返回 columns/typed rows/source record evidence，不能将所有值强转字符串。QuerySpec 为结构化只读计划：resource、projection、filters（EQ/IN/比较）、joins（仅实现明确支持的关系）、limit、timeoutMs、协议特定白名单 options。禁止接收任意原始 SQL/CQL/Cypher/脚本；不支持时明确拒绝，不回退无范围全量扫描。
- `QueryPlanner` 将明确的结构化表列/关系映射转换参数化 SQL，校验全部表在 accessScope 中、标识符严格、JOIN 两端列存在、scope.guard 绑定完整。标签范围值只作为参数；必需标签必须非空（D3）。有效范围使用 LabelScope 的交集结果，空集合产生恒假 WHERE，不能省略条件。默认行数上限500、timeout≤10s，执行端再次强制；不允许调用输入扩大。

连接字段需明确记录：JDBC url/user/password/dialect；S3 endpoint/accessKey/secretKey/bucket；文件受管理 root/path；HTTP baseUrl/headers/分页声明；Mongo uri/database；Redis host/port/password/database；Cassandra contactPoints/port/datacenter/keyspace/user/password；Neo4j uri/user/password/database。凭据禁止写日志或放异常消息。原始 URI 的用户信息/参数敏感字段不能出报告。

## 九类实际实现

1. 关系库 JDBC：本地真实 PostgreSQL 代表；元数据与参数化 SELECT/JOIN/WHERE/LIMIT，查询只读事务、超时/结果上限。
2. 数仓 JDBC：至少明确 ClickHouse 方言，元数据及只读查询路径；缺真实服务时如实记录环境待验收，不能用PG改kind冒充数仓。
3. 数据湖：S3/MinIO 桶/前缀白名单，Parquet schema 和投影/谓词读取；本地真实MinIO可用，不允许忽略前缀或用同名JSON冒充Parquet。
4. 文件：CSV/JSON/Excel 读取及元数据；真实路径规范化后必须位于批准root，拒绝 ../、绝对越界、符号链接逃逸；大小/行数上限，Excel不执行宏或公式。
5. HTTP：仅声明base/端点白名单、受限GET/POST请求模板、参数绑定、分页上限；禁止重定向跨出授权端点，限制响应大小/超时；使用真正本地HTTP服务测试，勿把其宣称为外部平台验收。
6. 文档库：Mongo原生驱动，库/集合白名单、元数据、受限过滤与投影；不能注入任意聚合代码。
7. 键值：Redis原生客户端，只键查询/受限前缀扫描，不支持关系JOIN；限制SCAN总步数与结果上限。
8. 宽列：Cassandra CQL驱动，keyspace/table白名单，必须满足分区键查询边界，禁止ALLOW FILTERING不受限扫描。
9. 图库：Neo4j驱动，库/标签白名单、元数据、参数化只读节点/关系查询；标签标识符也须安全校验，不接受用户Cypher。

使用必要的官方稳定驱动版本，先查官方文档，不复刻网络协议。统一能力声明可读，不支持的查询显示具体限制。

## 验证及环境

用户已明确选择：先完成代码与现有环境验证，ClickHouse、MongoDB、Redis、Cassandra、Neo4j 五类保留待外部实例验收。无需再请求启动或安装这些服务。

禁止启动 Docker（已验证本机 Docker 服务会耗尽内存），禁止使用相邻工程数据库/服务/凭据。独立 PG 和 MinIO 已运行；配置只从本工程 `.runtime/local-env.json` 导入，不打印。PG地址变量 ONTO_DB_* 是业务库，只可在唯一测试schema内建测试表，不能清空业务表；数据源测试可由主执行者另建独立数据库。MinIO变量 ONTO_STORAGE_*。JDK21、Maven512m；不要同时启动多组重型测试。

先RED→GREEN，重点实际PG关系查询与scope A/B/null、SQL注入/白名单、文件逃逸、真实S3 Parquet、HTTP分页/边界、凭据不外泄。Mongo/Redis/Cassandra/Neo4j/ClickHouse缺环境必须写明确未验清单；协议实现可静态与领域测试，不能谎称九类连接通过。需要新服务先通知主执行者协调。

报告 `docs/evidence/continuation/c5-adapters-report.md`，另写 `onto-data/README.md` 固定API/connection/能力边界和调用样例。报告应含实际测试命令、每类状态、环境限制。不得提交Git。完成后主执行者独立审查并接入数据源/映射管理。
