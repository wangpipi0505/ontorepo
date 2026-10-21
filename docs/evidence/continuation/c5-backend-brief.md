# C5 / P3 管理与执行集成

先读 `contracts/p3-data.md`（固定 API）及详设 §7.2/§7.5/§8.5/§8.7/§8.9/§8.10，开发计划 P3。本任务在 C4 后端通过审查后接入，不覆盖 P2 的冻结/守卫/NL 能力。

## 所有权与结构

可新增 `onto-mapping/`、`onto-ingest/`，编辑现有后端所需集成和 parent pom；不编辑 `onto-data/`（独立适配器已验收）、`onto-artifact/`、web/deploy/正式设计。唯一纯核例外：可按 `p3-data.md` 多表守卫扩展 `onto-contract/GuardValidator` 及相应定向测试，保留原89条，不能改其他Schema/包装语义。使用 `onto-data/README.md` 固定 API；有不兼容之处先报 root 协调，不能复制独立范围实现。

数据源归属空间，加密配置 AES-GCM，key 从 `ONTO_DATASOURCE_KEY` 读取；文件受管根从 `ONTO_FILES_ROOT` 读取。root 已通过 `Initialize-Data.ps1` 初始化。缺配置不允许明文回退。P2 已占用 V6/V7/V8，使用 V9 或实际下一可用迁移，只追加。管理文件与 MinIO 发布制品分开，文件 UUID 目录不可由客户端自定义；空间归属持续检查。

## 实施及边界

1. 数据源 CRUD/三段探测/真实元数据快照+diff+映射影响，全部九类走 AdapterRegistry。API 不返回明文凭据；授权范围不可由探测可达性自动扩大。删除引用拒绝的安全 message 提供当前空间内影响映射/操作的定位 ID；现有统一客户端只展示 code/message/status，不依赖未约定的错误 data/meta。
2. 三层映射、四项完整性、覆盖度、影响/目录；只允许受限标识模板和类型/码表转换，不接 raw SQL/Cypher/CQL。
3. 操作 data-query 独立管理，冻结源/元数据/三层映射和数据查询模板。现有 CandidateService/FrozenExecutor 接真实适配器；所有输入经过冻结 Schema，实际输出按冻结 Schema校验。不会把纯模型 SPARQL 当实例查询。
4. 统一授权四层交集下推（LabelScope），缺标签默认拒绝；缺守卫绑定/跨源白名单在访问源前拒绝。A/B/null 数据和端点未来收窄由同一函数接入，保留 P4 endpointScope 参数扩展点。所有范围均来自服务端可信上下文。按固定契约新增OWNER维护的多表filter_bindings，适配器已支持 `attr@resource`；每个 JOIN 参与表必须明确绑定，不准按同名列猜测授权。纯模型操作仍拒绝源列绑定。
   九类资源标识不等于SQL表名：GuardValidator结构层支持UUID.csv、/items、prefix/object.parquet等，非空≤512且无控制字符；业务层与冻结metadata资源精确匹配并按协议accessScope规则核验覆盖（如S3批准前缀）。SQL标识符规则仍在QueryPlanner，不能放宽。单资源非关系适配器传attr→单列名，不附SQL资源限定。
5. 任务使用算法关系全例：主键身份/关联字段生成真实 JOIN，结果带对象身份、关系与源记录主键；追溯 evidence_ref 关联真实 mapping/meta/candidate 摘要。行值保留 JSON 类型。
6. 三来源抽取，TABLE 从快照；FILE 真 CSV/JSON/XLSX；DOCUMENT 真 TXT/PDF/DOCX，选择官方解析库/受限解压。不能用上传纯文本冒充二进制文档支持。中文规则词典/句式 + 可定位来源，人工批量确认/拒绝，不能自动写模型；跨来源引用/同名冲突给出定位。
7. 追加迁移/中文注释/职责拆分，所有业务权威 PG，所有写入 revision+lock+audit；旧候选字节不改。

## 验证

TDD + 真实PG/MinIO。适配器模块已由其他代理验证，不复刻协议测试；重点真实API到源查询的链路、模型映射/类型转换/范围/证据/冻结隔离，以及三来源各样例与人工确认。旧 P1/P2 回归保留，禁止测试后门。不会要求五类外部实例，用户已同意待外部验收。

root 负责本工程运行配置和服务启停；运行前先通知。按 Environment.ps1 导入变量而不打印；JDK21/512m测试堆及ASCII `-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`。root已另建 `onto_sources` 数据库，`onto_sample.tasks/algorithms` 含 A/B/null 与跨标签关联样例（`docs/fixtures/p3/source.sql`），不要清空或改写这些人工验收数据。自动测试专属库 `onto_test_p3` 已就绪，Import之后覆盖 `ONTO_TEST_DB_URL=jdbc:postgresql://127.0.0.1:15472/onto_test_p3`，用户名/密码沿本工程配置；别用onto_test_p2。报告 `c5-backend-report.md`，含 API 对齐、RED/GREEN、真实场景和剩余边界，不提交 Git。
