# C4 / P2 后端实现与验收报告

日期：2026-10-20。需求依据：`AGENTS.md`、`contracts/p2-supply.md` 与 `c4-backend-brief.md`。本报告覆盖 P2 后端，不代表 P3 九类源或 P4 正式发布已经验收。

## 最终结果

- `c4-final-verify.log`：2026-10-20 22:34:33 **BUILD SUCCESS**，完整 reactor `verify`（含最终 JAR 打包）通过。唯一测试合计 **190 条，0 失败、0 错误、0 跳过**：onto-contract 89、onto-data 当时版本 23、onto-designer-api 78。
- API 原有 57 条回归全部保留；新增 P2SupplyTest 19 条、KnowledgeIndexTest 2 条，共 21 条。末尾的 spy 定向重跑属于同一批已有用例，不额外累计测试数量。
- Lucene 十条固定问题 top-1 实测 **10/10**；门槛为至少 8/10。完整分母、问题、期望、实际及命中标记保存在 `c4-lucene-retrieval.json`。
- 真实 MockMvc `/v3/api-docs` 导出为 `c4-openapi.json`，并原样同步 `contracts/openapi.json`；包含 12 个 P2 路径、16 个管理/消费方法及显式响应 DTO。
- 本工程服务在构建前已由主执行者停止；仅测试独占的 PostgreSQL `onto_test_p2`。未启动 Docker、未结束其他 Java 进程、未提交 Git。

## 实现说明

### 独立助手声明与冻结契约

新增 `AssistantService` 和独立 GET/PUT `/operations/{id}/assistant`。管理请求外层 camelCase；`assistantTool` 内部 snake_case 原样保存。允许未配齐的声明草稿，提交的 Schema 由已复审的 `JsonSchemaAssertions` 做标准校验；完整编译时再统一验证守卫、例子和消费字段。

`SupplyService` 将冻结操作转换为 onto-contract 输入，三段定义、guardCfg、知识与格式能力标记都进入候选规范载荷。`FrozenPolicy` 为预览与执行共同计算策略资源交集，避免契约声称的限制与实际执行不一致。一个操作有问题时整个契约预览失败，不跳过问题操作。

候选预览四元组 spaceCode/releaseId/version/contentDigest 全部为 null，preview=true；包装层 candidateSha256 是实际持久候选摘要。`POST /contracts/validate` 只校验正式 manifest，不创建发布记录。错误信封保留 code/field/reason。

`/checks` 对 CONTRACT 候选增加独立 CONTRACT 项；不完整声明不能 eligible。新增 supplyEligible 明确表达供给资格：MODEL_ONLY 即使本地模型检查通过，也始终 supplyEligible=false。原 P1 的 eligible 继续表示其本地模型检查语义，不能作为正式发布资格。

### OWNER 守卫和主体授权

`GuardService` 提供守卫读取/保存、授权目录、授权列表和授权修改。所有写入复用空间行锁、expectedDraftRev、事务审计；守卫与授权仅 OWNER 可修改，不能移除最后一个 OWNER。目录只来自现有本地身份，不创建用户，不返回密码或令牌字段。

缺绑定的守卫草稿可以保存；显式提供的绑定必须符合类型、表列标识符和受支持操作符结构。授权属性来自 space_grant 数据库行，按守卫定义的 JSON 标量类型校验。dataScope=null 只有在授权行存在时表示不限；缺行继续拒绝。

主体授权不进入冻结载荷，每次执行在空间锁内重新读取。修改当前授权不改变历史冻结定义，也不能令客户端通过请求参数冒充主体属性。空间/策略范围复用 `LabelScope` 做交集，未分类值与空交集默认拒绝。

### 三入口统一执行与事实提交

TRIAL、LOCAL_TEST、SERVICE 共用 `CandidateService.run`、`SupplyService.authorize` 与 `FrozenExecutor`。入口按服务端身份、角色、冻结守卫完整性、可信当前属性、空间/策略范围和绑定适用性检查，执行器在这些检查之后才可访问。

- 缺 guard_attrs、未定义守卫、无执行绑定均拒绝。
- P2 模型查询不能下推源列过滤，返回 GUARD_BINDING_NOT_APPLICABLE。即使同时提供 PRINCIPAL_ATTRIBUTE，也不会静默忽略另一个 filter_binding。
- 输入和实际业务输出使用冻结 Schema 标准校验；输出仍经过真实查询/规则/评分计算，不从用例期望构造结果。
- 内部候选入口按冻结 opCode 选择操作；成功 data 只有业务 columns/rows，执行 id 留在事实记录中。meta 来自真实冻结空间编码与候选 SHA；候选 releaseId/version 为 null、preview=true。失败不注入 meta。
- 执行拒绝/失败事实和步骤先持久化，再向 TRIAL/SERVICE 抛出 HTTP 错误；外部事务对已记录的 Problem/ContractProblem 配置 noRollbackFor。LOCAL_TEST 返回持久化的 FAILED 事实和 executionId，汇总结果不能被误判成功。
- V8 追加扩展执行事实约束允许 SERVICE，同时要求实际主体与输入/输出摘要；旧 LEGACY 记录保持原状。

结果行使用 `Map<String,Object>`，不把未来 P3 的 JSON 类型统一强转成字符串；本地 Jena 和既有规则行保持原有业务值表示。

### P1 兼容边界

V7 只追加候选能力元信息，不 UPDATE 既有 payload 或 sha256。新冻结格式为 formatVersion=2，显式记录 CONTRACT / MODEL_ONLY；旧格式默认仅有 MODEL_ONLY 能力，不会默认为 CONTRACT。

旧包内模型操作可继续本地 TRIAL/LOCAL_TEST，但内部服务入口拒绝。已声明助手的操作无论入口都经过守卫复核。新增外部 kind 未声明助手时不能落回 MODEL_ONLY。

P1 原三步骤事实格式保留；统一授权仍实际执行。增加了手工构造原格式候选的真实 PG 测试，试执行及修改草稿后逐字节比较原 payload 未变。正式 release 入口仍留给 P4，目前返回 404，没有假 READY 或假发布表。

### 知识、Lucene 与上下文包

新增独立 onto-knowledge 模块。知识 CRUD 校验非空名称/定义/来源、别名去重、同空间概念引用、角色和修订；所有词条进入候选冻结载荷。

上下文包从同一冻结候选生成：概念卡包含冻结属性，术语词典稳定提供 id、entryId、sourceRef；关系摘要来自冻结关系；示例问答来自冻结用例的期望断言，明确标为 EXPECTED_ASSERTION。助手参数例的返回约束标为 OUTPUT_SCHEMA，不能冒充实际执行答案。

检索使用 **Lucene 9.12.3 SmartChineseAnalyzer、ByteBuffersDirectory、BM25**，没有使用字符串 contains 替代检索。每个别名独立索引，结果保留 entryId、matchedAlias 和来源。参考：[Lucene SmartChineseAnalyzer 官方 API](https://lucene.apache.org/core/9_12_3/analysis/smartcn/org/apache/lucene/analysis/cn/smart/SmartChineseAnalyzer.html)。

缓存按冻结摘要建立，有 16 候选及 64MiB 索引文件权重上限，淘汰时关闭 DirectoryReader/Directory。单候选在分配索引前限制 10000 词条、50000 文档及保守估算的 8MiB 重复索引文本；超限整包拒绝，不静默截断。64MiB 是索引文件权重，不宣称覆盖全部 JVM/reader 工作内存；构建另限 4MiB writer 缓冲，测试 JVM 最大堆为 512MiB。

索引不是业务权威，PG 候选可以确定性重建。已用全新索引实例验证相同冻结内容的查询结果一致；物理索引归档与制品接入属于 P4。

### 确定性 NL 用例

用例新增 mode=DIRECT/NL，缺省 DIRECT 兼容旧记录。NL 匹配器只接收冻结包和问题，不接收期望 operationId；根据冻结操作名称/说明、术语别名及参数例字符串确定性选择。普通参数例值也参与选择，不依赖非标准 question 字段。匹配不唯一或未命中明确失败。

TERM_MATCH 成功和失败均持久化。断言始终检查实际命中操作，默认要求 SUCCEEDED；EXACT 比较完整 rows，CONTAINS 必须逐例显式指定。规则与评分仍由同一个真实执行器执行，保留既有策略限制。没有使用 LLM 主观判分。

### 实际集成修正

- onto-data 的 Hadoop 传递日志 binding 与 Boot Logback 冲突，在 onto-space 依赖边界排除 slf4j-reload4j，不修改 onto-data。
- 类路径新增驱动后 Boot 曾尝试创建默认 localhost Mongo/Neo4j 客户端。已在 API 配置排除 Mongo/Neo4j/Cassandra/Redis 默认自动配置，数据源连接必须由空间配置驱动的显式适配器创建。
- Windows JDK NIO 的默认临时路径导致三项 HTTP 适配测试无法建立 Unix socket。IPv4 参数并非修复；最终使用已确认的 ASCII Unix socket 临时目录。

## RED / GREEN 证据

标准命令前置：

```powershell
. deploy/Environment.ps1
$null=Import-OntoEnvironment
Set-OntoJava
Set-OntoBuildLimits
$env:ONTO_TEST_DB_URL='jdbc:postgresql://127.0.0.1:15472/onto_test_p2'
```

未打印或写入密码。测试库覆盖仅在进程变量中，不修改本地配置。

| 证据 | 实际结果 |
|---|---|
| c4-red-isolated.log | 首组隔离 PG：6 失败、0 错误，缺少声明/守卫/知识/服务接口；随后开始业务实现 |
| c4-green-5.log → c4-green-6.log | 11 项从 5 失败到 11 全过；修复 SERVICE 事实约束与 LOCAL_TEST 契约异常处理 |
| c4-hardening-red.log → c4-hardening-green.log | 索引上限、CONTRACT 门禁与未实现路由反例复现；最终 16 全过 |
| c4-regression-ascii.log | 89+23 通过，API 75 中 2 失败：P1 步骤兼容、同时存在两类绑定时不能丢源列过滤 |
| c4-context-red-assert.log | 1 失败、0 错误，概念卡缺冻结属性；随后补齐概念卡和有来源的用例问答 |
| c4-nl-example-red.log | 1 失败、0 错误，普通参数例值没有参与 NL 选择；随后修复 |
| c4-final-verify.log | 完整 verify：89+23+78=190 全过并重新打包 |
| c4-zero-executor-three-entry.log | 在真实 PG 的三缺守卫×三个入口场景，spy 对 execute 断言 never；同一用例保留 9 条 FAILED 事实和零 MODEL_SELECT |

无效或基础环境诊断明确排除：c4-red.log 的共享测试库夹具冲突；初次语法/并行 P3 编译错误；首次 context 测试的空节点访问错误；hardening-red 中消费测试把 assistant 样例误当正式 manifest 的测试夹具错误，均不算功能 RED。消费端点改用实际候选预览正式化后的完整 manifest 验证成功/失败。

最终全量命令：

```powershell
mvn -B -o verify '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp'
```

日志保留测试负例的预期 SQL/审计/解析异常及 Jena URN、Mockito 动态 agent、Lucene Vector API 和重复 JSONObject 警告；不把这些日志行等同测试失败。失败 MockMvc 日志中的临时会话令牌已脱敏。

## 验收映射与限制

| 范围 | 后端证据 |
|---|---|
| 独立声明、三段 Schema、冻结隔离、预览 null 四元组 | independentDeclarationAndFrozenContractsUseFrozenValues；契约核 89 回归 |
| 三种缺守卫、三入口拒绝且执行器零调用 | filterBindingAndUndefinedAndMissingGuardsRefuseBeforeExecutorAcrossThreeEntries；zero-executor 定向 |
| 可信属性、无授权、范围拒绝、过滤不适用、最后 OWNER | serviceChecksTrustedAttributesSchemasAndReturnsBusinessOnly；ownerGrantManagementRejectsViewerStaleAndLastOwner；validFilterBindingsAreRefusedByModelExecutorAndScopeIsNotExpanded |
| VIEWER、跨空间、过期修订、知识来源/别名 | knowledgeReferencesRevisionAndPermissionsAreAtomic；既有 Workflow/P1 角色与修订回归 |
| 输入/输出 Schema、失败落库、成功/失败 meta | serviceChecksTrustedAttributesSchemasAndReturnsBusinessOnly；frozenOutputSchemaFailureCommitsAndNeverInjectsMeta |
| 原候选字节与本地兼容、服务拒绝 | originalLegacyCandidateBytesRemainUnchangedAndLocalOnly；legacyModelCandidateCannotEnterService；原 P1 legacy 测试 |
| 知识冻结、中文检索、来源关联、索引重建/上限 | knowledgeSearchAndContextAreFrozenAndLuceneRetrievesTenQuestions；KnowledgeIndexTest 两项 |
| 概念属性与真实冻结用例答案 | contextPackContainsConceptPropertiesAndFrozenExampleAnswers |
| NL 正确/错操作/歧义/未命中/普通参数例 | positiveNlUsesFrozenKnowledgeAndExactRowsWhileNoHitFails；nlSelectsWithoutExpectedOperationAndPersistsAmbiguity；nlUsesOrdinaryFrozenParameterExampleValues |
| 策略资源与真实规则/评分 | contractResourceLimitsMatchFrozenStrategyIntersection；原 P1 frozenRulesScoringAndPolicyLimitsDoNotReadNewDraft |
| OpenAPI 与消费验证 | openApiIncludesP2AndConsumptionValidationPreservesErrors；同步 JSON 静态核对 |

未覆盖/后续边界必须保留：

1. 本轮没有浏览器端到端联调；前端 67 条及其后修复由主执行者独立管理，不能与后端测试相加冒充浏览器验收。
2. 本轮没有实际网络 HTTP 调用运行中的应用；测试通过真实 Spring MVC 边界与真实 PG。主执行者随后报告另行完成 6 项 P2 HTTP 与 8 项 P1 通过，证据由主执行者归档，不计入上述 190 条。
3. 没有完整进程重启检索演练；已验证新索引实例重建，且 API 必先读取并校验持久候选摘要。
4. NL 是明确的术语/声明/参数例确定性匹配，不宣称通用语义理解。参数例字符串递归深度最多 32，短于两字符的参数值不作为匹配特征。
5. P2 不执行数据源列过滤或外部实例查询；P3 必须在现有统一授权之后接真实过滤下推。P4 接真实 release、物理知识制品及正式四元组。
6. 此次 onto-data 23 条是当时纯适配代码回归，不意味着九类真实外部实例均已连接，更不关闭主执行者随后独立审查发现的 HTTP/稀疏 Excel/JDBC 分类待修项。后续适配修复应重新构建整体 JAR。
7. 没有做生产吞吐/索引极限压测；空间锁内执行沿用 P1 同空间串行与单次上限边界。

## P2 文件清单及所有权

以下为 P2 实际新增/修改的生产源文件和配置，便于独立复审；所有路径以工程根为基准。

```text
pom.xml
onto-common/pom.xml
onto-space/pom.xml
onto-space/src/main/java/cn/ontorepo/space/application/GuardService.java
onto-operation/src/main/java/cn/ontorepo/operation/application/AssistantService.java
onto-operation/src/main/java/cn/ontorepo/operation/application/TestCaseService.java
onto-operation/src/main/java/cn/ontorepo/operation/domain/FrozenPolicy.java
onto-knowledge/pom.xml
onto-knowledge/src/main/java/cn/ontorepo/knowledge/application/KnowledgeService.java
onto-knowledge/src/main/java/cn/ontorepo/knowledge/application/FrozenKnowledge.java
onto-trace/pom.xml
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateTestService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateCheckService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateSupplyService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/SupplyService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/TermMatcher.java
onto-trace/src/main/java/cn/ontorepo/trace/application/FrozenExecutor.java
onto-designer-api/src/main/java/cn/ontorepo/api/ApiDtos.java
onto-designer-api/src/main/java/cn/ontorepo/api/P1Dtos.java
onto-designer-api/src/main/java/cn/ontorepo/api/P2Dtos.java
onto-designer-api/src/main/java/cn/ontorepo/api/Errors.java
onto-designer-api/src/main/java/cn/ontorepo/api/SupplyController.java
onto-designer-api/src/main/java/cn/ontorepo/api/GuardController.java
onto-designer-api/src/main/java/cn/ontorepo/api/KnowledgeController.java
onto-designer-api/src/main/resources/application.properties
onto-designer-api/src/main/resources/db/migration/V6__p2_supply.sql
onto-designer-api/src/main/resources/db/migration/V7__candidate_supply_capability.sql
onto-designer-api/src/main/resources/db/migration/V8__service_execution_facts.sql
onto-designer-api/src/test/java/cn/ontorepo/P2SupplyTest.java
onto-designer-api/src/test/java/cn/ontorepo/KnowledgeIndexTest.java
contracts/openapi.json
docs/evidence/continuation/c4-*.log
docs/evidence/continuation/c4-openapi.json
docs/evidence/continuation/c4-lucene-retrieval.json
docs/evidence/continuation/c4-backend-report.md
```

**C1 所有权文件，P2 未修改**，但已包含在最终回归/打包输入：

```text
onto-model/src/main/java/cn/ontorepo/model/application/ImportService.java
onto-designer-api/src/test/java/cn/ontorepo/C1ReviewRegressionTest.java
```

P2 未修改 onto-contract、onto-artifact、onto-data、web、deploy 或正式设计。SpaceService、AuthService、OperationService 和 WorkflowFixture 只读取/复用，未改动。所有已有迁移原样保留，仅追加 V6/V7/V8。

## 2026-10-20 独立复审 R1～R4 修复追加

以上文件所有权与190条构建结果是首轮交付历史。本轮按 `c4-backend-review.md` 落实全部四项，另修复任意JSON贯穿时发现的P1显式null兼容和OpenAPI声明问题；本节覆盖本轮实际边界。未提交Git。

### 修复行为

- **R1**：对冻结词条及别名先归一化、去重并移除空token，再参与问题/声明匹配。既有标点词条不能再以 `contains("")` 制造成功或歧义。保留正常别名命中与真实多操作歧义，不通过禁止保存历史值掩盖问题。
- **R2**：SERVICE、TRIAL、LOCAL_TEST保存与执行统一接受任意JSON根。真实Schema决定数组、字符串、数值、布尔、null及联合类型是否合法；输入事实摘要也保留实际类型。规则/评分实际只消费属性对象，因此在原Schema根追加 `allOf: [{type: object}]`，保留原组合约束和 `$defs`/本地 `$ref` 路径。模型查询支持非对象根。旧的无 `inputSchema` 且无助手声明的操作才将显式null恢复为空对象；声明nullable Schema的null仍是null。试执行DTO用JsonNode区分省略与显式null，OpenAPI内联声明六种根类型，SERVICE body同步声明。
- **R3**：SERVICE按操作码选取和候选完整性复核移入共同执行事实边界。损坏候选仍返回原409错误，同时记录FAILED、CANDIDATE_CORRUPT、真实principal/caller、请求操作码及摘要；TRIAL和LOCAL_TEST同样记录失败。LOCAL_TEST损坏载荷不读取不可信用例，而返回关联真实executionId的完整性失败。已授权但不存在的SERVICE操作码记录404失败事实。候选不存在、入口用户无授权等在执行边界之前拒绝，不创建伪执行记录。SERVICE成功meta来自该次已校验的冻结事实，不再为读取meta重复解包。
- **R4**：在纯onto-contract增加公共属性名策略 `[A-Za-z_][A-Za-z0-9_]{0,127}`，GuardValidator、GuardService、LabelScope和QueryPlanner的guard属性解析共用。保留既有首下划线与128长度合法名称；范围标量、交集、拒空分类语义不变。QueryPlanner的 `@resource`、表名、列名及SQL资源规则原样保持，不混入后续P3多表绑定扩展。onto-data仅经root批准改动下方四个文件，未改适配器。

### RED / GREEN 与真实限制

全部测试使用真实PG `onto_test_p2`，仅进程覆盖TEST URL；没有修改local-env。JDK使用ASCII NIO临时目录 `-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`，未放宽业务timeout。

| 证据 | 实际结果 |
| --- | --- |
| `c4-review-fixes-red.log` | 首批14条真实缺陷RED：14失败、0错误 |
| `c4-review-fixes-green-1.log` | 首批14/14通过 |
| `c4-review-r4-plan-red.log` | 新增qualified guard属性计划回归真实RED |
| `c4-review-r4-data-green.log` | 首次数据回归仍失败：新测试错误漏算LIMIT参数500；修正测试断言，不更改生产LIMIT |
| `c4-review-fixes-all-test.log` | 完整reactor test：contract89通过、data28通过、API95中94通过/1失败；不能宣称全量GREEN |
| `c4-review-compat-red.log` | 同一C1用例定向原参数通过；新增旧null和OpenAPI两条RED，因此该次整体BUILD FAILURE |
| `c4-review-fixes-final-green.log` | 文件名并不代表通过：复审19条中18通过，仅OpenAPI试执行JsonNode引用断言失败，整体BUILD FAILURE |
| `c4-review-openapi-green.log` | 精确修复SchemaResolution.INLINE后，最后OpenAPI用例1/1通过，BUILD SUCCESS |

完整测试唯一失败是原C1 `uppercaseStrategyBindingsAreCanonicalAndOldCandidateLimitsRemainEnforced` 在冷启动模型SELECT触发QUERY_TIMEOUT，事实elapsed=2982ms、冻结timeout=1000ms，断言期望1行而实际失败无行。该次失败真实保留；随后相同用例原源码、原1000ms限制独立运行通过（见compat-red日志中C1类1/1）。未改C1生产代码、测试或timeout，不能把后续复核描述为此前完整运行成功。

当前19条复审用例全部已有通过证据：18条在最后组合运行通过，剩余OpenAPI用例在其后的精确修复运行通过；没有再次运行完整19条或全量reactor。新增一条data回归后data总28条，含所有原27条，完整通过；contract89条全通过。最终打包另见下方记录。以上按日志实际状态报告，不能把不同轮次相加冒充单次全量通过。

覆盖包括纯标点词条/别名/混合历史值、正常别名/真实歧义；六种根及对象/数组联合在三入口贯穿；规则Schema本地引用/合取约束；旧无Schema显式null与声明null分开；损坏候选三入口事实及执行器零调用；不存在操作码与入口拒绝区别；`_region`、81、128长度属性的真实允许/拒绝范围，以及qualified属性计划和非法SQL资源拒绝。

运行应用的真实HTTP复验与运行OpenAPI/生成TS同步由root在新JAR启动后执行，本轮MockMvc/PG测试不冒充网络HTTP验收。原reviewer仍须按当前源码复核，本节不自行宣布独立复审通过。

### 本轮实际修改文件

```text
onto-contract/src/main/java/cn/ontorepo/contract/GuardAttributeName.java          # 新增公共属性名策略
onto-contract/src/main/java/cn/ontorepo/contract/GuardValidator.java
onto-data/pom.xml                                                            # 经批准增加contract依赖
onto-data/src/main/java/cn/ontorepo/data/domain/LabelScope.java                  # 批准例外
onto-data/src/main/java/cn/ontorepo/data/domain/QueryPlanner.java                # 批准例外，仅guard属性名
onto-data/src/test/java/cn/ontorepo/data/domain/QueryPlannerTest.java            # 新增一条共享命名回归
onto-space/src/main/java/cn/ontorepo/space/application/GuardService.java
onto-operation/src/main/java/cn/ontorepo/operation/application/TestCaseService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/TermMatcher.java
onto-trace/src/main/java/cn/ontorepo/trace/application/FrozenExecutor.java
onto-trace/src/main/java/cn/ontorepo/trace/application/SupplyService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateService.java
onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateTestService.java
onto-designer-api/src/main/java/cn/ontorepo/api/ApiDtos.java
onto-designer-api/src/main/java/cn/ontorepo/api/P1Dtos.java
onto-designer-api/src/main/java/cn/ontorepo/api/CandidateController.java
onto-designer-api/src/main/java/cn/ontorepo/api/SupplyController.java
onto-designer-api/src/test/java/cn/ontorepo/P2ReviewRegressionTest.java            # 新增19条参数展开回归
docs/evidence/continuation/c4-backend-report.md
docs/evidence/continuation/c4-review-*.log                                      # 本节测试/构建证据
```

本轮不改root pom、已有迁移、任何source adapter、C1两项所有权文件、前端或部署脚本；未改现有测试期望以回避冷启动失败。运行OpenAPI契约与前端生成文件由root同步。

最终构建：root先停止本工程API/Vite并保留PG/MinIO，随后执行 `mvn -B -o -DskipTests package`；`c4-review-package.log` 确认2026-10-20 23:17:01 BUILD SUCCESS。该命令跳过测试，仅作为新JAR构建证据；测试结果以本节各独立日志为准。产物为 `onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`。root随后已启动此新JAR进行HTTP验证。本代理已停止源码修改与Maven操作，交还独立复审。
