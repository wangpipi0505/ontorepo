# C4 / P2 后端独立 Spec + Quality 审查

> **最新复审结论（2026-10-20 23:27）：R1～R4 均已关闭；本批 P2 后端修复 Spec 通过，Quality 通过。** 依据当前18文件、独立真实 PostgreSQL 探针以及 Schema/OpenAPI 边界复核。以下初审内容保留为历史证据，当前状态以文末“修复后独立复审”一节为准；此结论不代替后续 P3/P4 或项目总体验收。

日期：2026-10-20。审查者：独立 reviewer。审查对象为 `c4-backend-review-package.txt` 明确列出的当前源码及相关集成源码；工程尚无提交，本报告不将源码包称为 Git 差异。

**Spec：暂不通过。Quality：请求修改。** 发现 3 项中优先级问题及 1 项低优先级不一致。以下优先级 `[P2]` / `[P3]` 是审查问题等级，不是实施期次。未发现本轮已检查路径能够越过守卫访问执行器；不因已有 190 条测试通过而批准未覆盖的边界。

依据：`AGENTS.md`、`contracts/p2-supply.md`、`c4-backend-brief.md`、`c4-backend-report.md`，以及详设70 v2.6 的 §8.6～§8.8。已直接补读包中未列出的 `FrozenPolicy.java`、`ApiDtos.java`、`P1Dtos.java`、`onto-common/pom.xml`。

## 整批发现

### R1 / [P2] 过滤归一化后为空的知识名称，避免 NL 无命中用例假通过

- **位置**：`onto-trace/src/main/java/cn/ontorepo/trace/application/TermMatcher.java:42`，尤其第43～46行；相关保存入口为 `onto-knowledge/src/main/java/cn/ontorepo/knowledge/application/KnowledgeService.java:29`。
- **触发**：有一个正常声明的操作“列出对象”，NL 用例问题为“完全陌生的问题”，用例 `operationId`（期望命中）指向该操作，`input={}`，`expected={"rows":[]}`，默认 `EXACT`。通过正常知识服务保存 `term="!!!"`、`aliases=[]`、非空定义和来源，重新冻结。
- **实际**：`normalize("!!!")` 得到空字符串。`q.contains("")` 与 `declaration.contains("")` 恒为 true，该无关词条给每个声明操作加10分。在仅有一个操作的常见配置下，无关问题被选为唯一命中；执行确实发生，完整 EXACT 用例随后返回 `passed=true`。
- **影响**：无命中输入被记录成成功的 TERM_MATCH；默认操作命中和结果精确匹配都不能阻止这类假通过，违反确定性 NL 的“未命中明确失败”要求。多操作情况下也会把原本的未命中污染成歧义。
- **独立证据**：`c4-review-probe.log` 中，同一用例在添加该知识前 `nlTestWithoutPunctuationKnowledge.passed=false`，添加后 `nlTestWithPunctuationKnowledge.passed=true`；`nlWithPunctuationKnowledge` 等于真实操作 id。知识由真实应用服务保存，未直接伪造冻结知识内容。
- **修复方向**：对术语及别名先统一归一化、去重并排除空串，再参与匹配。即使保存入口禁止纯标点词条，匹配器也应防御已有冻结候选；不要仅修新建表单。
- **复验**：纯标点 term、纯标点 alias、混合正常/空 token、多个操作均不得把无关问题提升为命中；原有正常别名命中和歧义用例继续成立。

### R2 / [P2] 契约允许非对象输入，但内部调用只能接收 Map

- **位置**：`onto-designer-api/src/main/java/cn/ontorepo/api/SupplyController.java:72`；相连的生成入口为 `onto-trace/src/main/java/cn/ontorepo/trace/application/SupplyService.java:33`，声明保存仅在 `AssistantService.java:45`～47 校验 JSON Schema 合法性。
- **触发**：通过助手声明接口保存 `inputSchema={"type":"array","items":{"type":"string"}}`、`param_examples=[["x"]]`，其余字段和守卫完整，冻结后读取候选契约。
- **实际**：契约生成成功，并对外声明上述数组输入；`["x"]` 通过实际冻结 input_schema。SERVICE 控制器的请求体固定为 `Map<String,Object>`，同一输入在 Spring Jackson HTTP converter 进入业务执行之前即抛 `HttpMessageNotReadableException`，由 `Errors.invalid` 映射为 HTTP400/INVALID_ARGUMENT。TRIAL、LOCAL_TEST 的输入传输类型也仅支持 Map。
- **影响**：候选可以生成一份无法按声明调用的供给契约。Schema 门禁与真实服务能力不一致；合法示例不是实际可调用输入。
- **独立证据**：`c4-review-probe.log` 的 `arrayInputManifestSchema` 为数组 Schema；`arrayInputSchemaValidation="RETURNED"`；实际 `MappingJackson2HttpMessageConverter.read(Map.class, ...)` 返回 `HttpMessageNotReadableException`。本项没有启动 HTTP 监听器；使用的是控制器绑定所依赖的真实 Spring HTTP converter，不将其描述为网络请求结果。
- **修复方向**：P2 若限定对象输入，应在保存/编译的相应边界给出明确且可定位的“不支持输入根类型”错误，保证不能生成不实供给声明；若支持任意 JSON 输入，则同步调整传输层、事实摘要和执行器边界。应避免只改一个控制器留下其他两入口差异。
- **复验**：数组、字符串、数字及包含非对象分支的 Schema，要么在编译时明确拒绝，要么合法实例可以经过三入口执行；正常对象输入及冻结后声明隔离仍通过。

### R3 / [P2] SERVICE 在统一执行记录之外复核候选摘要，损坏候选的失败事实丢失

- **位置**：`onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateService.java:306`～307。这里在 `run` 之前调用 `payload(c)`；实际记录路径位于第178～299行。
- **触发**：已授权 OWNER 调用其自身空间中一个载荷与摘要不一致的候选。该场景也是既有 P1 的损坏候选防御边界。
- **实际**：SERVICE 在操作编码解析前抛出 `409 CANDIDATE_CORRUPT`，没有进入 `run`，`op_execution` 增量为0、没有步骤或执行审计。相同候选经 TRIAL 调用时，摘要失败在统一 try/catch 内被捕获，保存 `FAILED/CANDIDATE_CORRUPT` 事实，增量为1。
- **影响**：新 SERVICE 入口与本地入口在相同完整性失败上产生不同的持久化结果；客户端已收到明确执行拒绝，但溯源页无法发现这一失败。给 service 增加 `noRollbackFor` 不能补救从未建立的事实。
- **独立证据**：`c4-review-probe.log` 中 `corruptService="409:CANDIDATE_CORRUPT"`、`corruptServiceFactDelta=0`，紧接着 `corruptTrial.status="FAILED"`、`corruptTrial.errorCode="CANDIDATE_CORRUPT"`、`corruptTrialFactDelta=1`。腐败候选仅在随机测试 schema 通过 INSERT 建立，未关闭不可变触发器，未改真实业务候选。
- **依据**：固定契约要求“拒绝或运行失败的事实与步骤保持可追踪”；brief 第4项要求失败事实持久化后再抛 HTTP 错误。详设 §8.6 将三类入口统一登记执行事实。
- **修复方向**：把 SERVICE 的候选完整性检查/操作选择纳入能够持久记录失败的共同执行边界，保留实际请求的 opCode、caller、principal 与候选标识；不要为了记录事实绕过摘要检查，也不要在失败响应注入成功 meta。
- **复验**：同一损坏候选在 TRIAL、LOCAL_TEST、SERVICE 均零执行器访问并可找到对应失败事实；候选不存在、无空间授权的入口拒绝应另外明确审计口径。

补充边界：探针也验证了有效本地主体缺 `space_grant` 时 SERVICE 返回403、事实增量0（`missingGrantService` / `missingGrantFactDelta`）。鉴于入口授权拒绝是否必须登记为 `op_execution`，还是应写独立安全拒绝审计，当前 P2 文档未细分，本报告**不将此单独列为阻断问题**。R3 的定案只依赖已授权 OWNER 和存在的损坏候选，避免混淆入口鉴权与执行对象完整性失败。

### R4 / [P3] 守卫名与 dataScope 属性名使用不同的合法范围

- **位置**：`onto-space/src/main/java/cn/ontorepo/space/application/GuardService.java:54` 和第160行。前者复用 `GuardValidator.identifier`，后者接入 `LabelScope.parse`。
- **触发**：定义 `_region`，或长度81的全字母守卫名，类型 STRING、PRINCIPAL_ATTRIBUTE 绑定结构合法。守卫配置可接受这些名字；随后 OWNER 尝试设置 `dataScope={"attr":"_region","in":["A"]}` 或对应的81字符名字。
- **实际**：守卫校验使用 `[A-Za-z_][A-Za-z0-9_]{0,127}`，数据范围使用 `[A-Za-z][A-Za-z0-9_]{0,79}`，后者返回 `INVALID_DATA_SCOPE`。正常 `region` 两处都通过。
- **影响**：对已允许保存并冻结的部分守卫，OWNER 无法通过约定的同名 dataScope 收窄授权范围。失败是安全拒绝，不是权限泄漏，但产生迟至授权操作才暴露的配置不可用。
- **独立证据**：`c4-review-guard-names.log`；探针分别调用真实 `GuardValidator.identifier` 和当前源码重新编译的 `LabelScope.parse`，输出 `_region` 与81字符名 `acceptedGuard=true scope=INVALID_DATA_SCOPE`。
- **修复方向**：统一这两层共同使用的守卫属性名规则，并明确历史已冻结名字的兼容策略。不要扩大 SQL 表列标识符规则来顺带解决授权属性名问题。

## 已排除疑点与实际覆盖

1. **预览 manifest 的响应 Schema 与候选 meta null 不兼容：不列缺陷。** 已实际验证候选成功响应整体不通过预览中的 output_schema，原因是 releaseId/version 要求 string。然而详设 §8.8 第605行明确“发布四元组为 null……其余字段与正式 manifest 完全一致”，该预览是正式契约模板；固定 P2 契约又明确候选成功 meta 的 releaseId/version 为 null。两种既定口径应在使用文档中区分，不能据此放宽正式版本的 Schema，也不能伪造候选发布标识。本次确认业务 data 仍由冻结业务 outputSchema 校验。
2. **并发 EDITOR 降为 VIEWER 后运行 checks：未复现越权。** 探针先持有同一空间行锁并在未提交事务中降权，另一线程在旧角色下开始 checks 并确实阻塞于锁；提交降权后，嵌套 `tests.tests` 的角色复核拒绝检查，`candidate_check` 增量为0。不能仅凭 checks 中锁前的初次 role 读取判为越权漏洞。
3. **授权与事务**：已检查 SpaceService.write/lock 的先锁后授权、修订 CAS、同事务修订/审计；GuardService 的 OWNER 约束、最后 OWNER、已有主体校验、可信属性类型与授权行存在性；执行器前的冻结守卫校验和 LabelScope 交集。执行时再次读取授权，冻结定义未回读草稿。未发现 scope 缺行被 null 范围替代的路径。
4. **冻结与旧候选**：新冻结载荷包含声明、guardCfg、知识、策略、用例和能力标记；旧候选缺能力标记默认 MODEL_ONLY。SERVICE 明确拒绝 MODEL_ONLY；已声明守卫不会走无声明兼容分支；任何 filter_binding 在 P2 模型执行中均拒绝，包含同时声明主体属性绑定的情况。
5. **知识与检索**：源码实际使用 Lucene SmartChineseAnalyzer、ByteBuffersDirectory、IndexSearcher；stable entryId/sourceRef、alias 文档、摘要缓存、16项/64MiB索引文件权重及单候选数量/文本预估上限均存在。检查了新索引实例重建测试与十条固定查询完整期望表。该10/10表只证明这些固定问题，不能推广为自然语言检索总体准确率。没有进行极限内存/吞吐压测。
6. **上下文包**：概念卡携带冻结属性；知识 termDictionary 保留稳定 id/entryId/sourceRef；示例问答分别标记 EXPECTED_ASSERTION 和 OUTPUT_SCHEMA，并保留来源。未把测试期望描述成执行所得答案。
7. **NL 与事实**：TermMatcher 不接收期望 operationId；CandidateTestService 另外核对实际操作命中和默认 SUCCEEDED；EXACT 比较完整 rows，CONTAINS 必须显式选用。正常命中、歧义、未命中持久化路径已读，R1 是这些路径之外的空 token 缺口。
8. **API 与工程质量**：三个 P2 控制器主要负责身份/DTO转换并委托服务；错误信封对 SQL/内部异常使用通用文本，ContractProblem 保留 code/field/reason。查阅新增迁移 V6/V7/V8，未见改写旧 payload 的 SQL。中文注释解释了锁、冻结、授权、缓存及兼容边界。

## 独立验证与复验材料

- 没有重新运行全量 Maven，也没有重跑会 TRUNCATE 的 WorkflowFixture。实现者的 `c4-final-verify.log`（190条）和 root 的真实 HTTP/浏览器结果分别属于它们自己的证据范围。
- root 明确批准使用独立 `onto_test_p2`。本次每次探针均新建随机 `c4review_*` schema，通过本工程 Flyway 迁移建立结构；应用以 `WebApplicationType.NONE` 启动，无网络监听端口。日志确认连接数据库是 `onto_test_p2`。每次 finally 仅删除该次新建 schema，未清理公共夹具。
- JAR 只从已知精确路径读取一次并解压到独立临时目录，作为依赖类路径。`SupplyService`、`CandidateService`、`CandidateCheckService`、`CandidateTestService`、`CandidateSupplyService`、`TermMatcher`、`FrozenExecutor`、`GuardService`、`SpaceService`、`FrozenPolicy`、`AssistantService`、`KnowledgeService`、`TestCaseService` 在临时 classes 下用**当前源码**重新编译覆盖；GuardNameProbe 另重新编译当前 LabelScope。未写共享 target。
- 最终综合探针退出码0；最后随机 schema `c4review_af7c3ec02ef74057833148bf19afb979` 已删除。一次早期探针尝试 UPDATE 损坏夹具被不可变触发器正常拒绝，该次诊断不计为产品缺陷；最终改为 INSERT 独立腐败 fixture，未禁用触发器。
- 保存材料：
  - `c4-review-probe.java.txt`：综合独立探针源文件；
  - `c4-review-probe.log`：最终成功运行及实际观察；
  - `c4-review-guard-names.java.txt`、`c4-review-guard-names.log`：属性名兼容探针及结果。
- 复验时将 `.java.txt` 复制为对应类名 `.java` 到自有临时目录，使用 JDK21、当前模块类及同版依赖编译；导入本工程 Environment.ps1 后把 `ONTO_TEST_DB_URL` 设为指定测试库。综合探针对库名有固定检查，使用环境变量获取凭据且不会打印凭据。

本轮未修改产品代码、正式契约、共享 target 或业务运行数据，未启动/停止本工程服务，未访问相邻工程，未提交 Git。修复后应逐项复验 R1～R4，并由实施者补充对应回归；当前报告不宣称 P2 出口已经关闭。

## 修复后独立复审

复审时间：2026-10-20 23:27（UTC+8）。**Spec：通过。Quality：通过。R1～R4 均关闭，本批修复范围没有新增阻断发现。** 上文原“暂不通过/请求修改”仅指初审时源码。

### 对象与方法

- 以 `c4-backend-report.md` 最后追加的18文件清单和实际文件为准，参考 `c4-fixes-review-package.txt`；不以过时的初审代码包判定修复。
- 阅读全部相关生产变更及 `P2ReviewRegressionTest`、`QueryPlannerTest` 新增回归，检查输入类型从 HTTP/DTO、用例存储、冻结载荷、统一执行、摘要直至执行器的连续性。没有用实施者测试计数替代源码审查。
- 将15个当前生产 Java 源文件在自有临时目录重新编译，依赖来自23:17构建的新 JAR；包括 GuardAttributeName/GuardValidator、LabelScope/QueryPlanner、GuardService、TestCaseService、TermMatcher、FrozenExecutor、SupplyService、CandidateService/CandidateTestService、ApiDtos/P1Dtos、CandidateController/SupplyController。没有写共享 target，也没有运行 Maven。
- 综合探针通过本工程应用服务、真实 Spring Jackson HTTP converter、DI 控制器和真实 PostgreSQL 建立/读取事实。应用为 `WebApplicationType.NONE`，没有开放端口；控制器调用中的身份来自受控请求桩，因此本轮不把它称为网络 HTTP 或完整会话鉴权验证。root 的真实 HTTP 结果属于另外的证据。

### 原问题逐项结论

| 原问题 | 当前实现与独立结果 | 状态 |
| --- | --- | --- |
| R1 空归一化污染 NL 匹配 | `TermMatcher.java:44` 在归一化/去重后移除空名称，覆盖旧冻结词条和别名。独立探针保存 `!!!`/`???` 与正常“对象/实体”混合知识，陌生问题返回 `NL_OPERATION_NOT_FOUND`，完整默认 EXACT 用例失败；正常别名仍命中，真实双操作仍歧义。 | 关闭 |
| R2 契约与非对象传输不一致 | SERVICE 的 JsonNode、TRIAL 的 JsonNode→Object、LOCAL_TEST 的 Object 保存/执行贯通；输入事实 TreeMap 容纳 null。对 object、array、string、number、boolean、null 及 object/array 联合 Schema，逐一经 converter、SERVICE 控制器、TRIAL 控制器、LOCAL_TEST 成功，并核对实际 TRIAL input_digest 与原始 JSON 根的规范摘要一致。预览 input_schema 与模型查询原声明相同。 | 关闭 |
| R3 SERVICE 完整性失败无事实 | `CandidateService.service` 直接进入共同 run，候选摘要复核和按请求编码选择操作均处在记录失败的边界内；不再在此前解包。独立 INSERT 的损坏候选在 SERVICE/TRIAL/LOCAL_TEST 分别留下 `FAILED/CANDIDATE_CORRUPT`，真实 principal、caller、摘要齐全，SERVICE 保留请求 `listClasses`。共同边界仍先验证载荷再进入执行器；实际不存在 MODEL_SELECT/RULE_EXECUTION 步骤。 | 关闭 |
| R4 守卫与范围名称不一致 | GuardAttributeName 统一 `[A-Za-z_][A-Za-z0-9_]{0,127}`，应用守卫、契约属性规则、LabelScope、QueryPlanner guardAttribute 共用。实际 `_region`、81字符及128字符名称可配置、冻结、允许调用，随后将范围 A 改为 B 后明确 `DATA_SCOPE_DENIED`；129字符被拒绝。`attr@records` 属性部分支持相同范围，含 `;drop` 的资源部分仍 `INVALID_IDENTIFIER`。 | 关闭 |

R1 的双操作歧义探针使用冻结载荷的独立副本构造第二操作，没有改动持久候选；无命中及完整 EXACT 失败使用真实保存/冻结用例。R3 仅对本次自有 schema INSERT 损坏夹具，未 UPDATE 冻结载荷、禁用不可变触发器或读取业务候选。零执行器访问的结论由源码控制流和无执行步骤支持；实施者新增回归还包含执行器 spy 的零调用断言，本探针不冒称使用了该 spy。

### null、规则根类型与 OpenAPI 连带边界

1. **显式 null 与省略分开。** 实际 Jackson 反序列化中，省略 `Trial.input` 得到 Java null，显式 `input:null` 得到非空 NullNode；CandidateController 仅把省略转换成 `{}`。声明 `type:null` 的实际三入口成功，且独立输入摘要证明 parameters 保留 null。只有同时无 inputSchema、无助手声明的旧操作才在共同执行边界转成 `{}`；独立旧 MODEL_ONLY 查询探针验证其执行成功且摘要按 `{}` 计算。旧 RULE_CHECK 的同一兼容路径及新增精确回归已读，不将 MODEL_ONLY 查询的运行证据扩大为独立运行了该规则用例。
2. **一般 JSON 根契约与规则/评分的对象能力一致。** 纯契约可描述一般 JSON 根，模型查询现在实际支持这些根；RULE_CHECK/SCORING 读取属性映射，只适用对象。SupplyService 在原 Schema 根追加 `allOf` 对象约束，同时用于预览和运行校验；FrozenExecutor 仍对非 Map 防御拒绝。本次另行独立执行 RULE_CHECK 和 SCORING 的 Schema 探针：保留已有 allOf、根 `$ref` 和 `$defs`，接受合法对象，拒绝违反原 required/属性类型的对象，也拒绝原 union 中的数组；确认原冻结 Schema 未被修改。没有把规则能力限制错误地推广成所有接口仅对象，也没有删除原 Schema 限制来迁就执行器。
3. **OpenAPI 声明与传输一致。** 阅读实际注解和当前 `contracts/openapi.json`，独立解析确认版本为3.1，Trial.input、TestCaseWrite.input、SERVICE requestBody 均有六种 type，且 Trial.input 没有 JsonNode `$ref`。ApiDtos 使用 `SchemaResolution.INLINE`。本轮解析文件的 SHA-256 为 `F23BCAF75BC851E198F54D3334F40B1AFD8FD039036688818D8186E1D2E03699`；不依赖手写假文档，也未自行重生成导出。
4. **授权和错误边界保持清楚。** 已授权主体对存在但损坏候选的失败进入共同事实边界；不存在候选或入口没有空间授权仍在此前拒绝。阅读未知操作码回归确认其与入口拒绝分开。R3 不要求为了日志伪造不可访问候选或冒用执行身份；失败响应不补成功 meta。LOCAL_TEST 对损坏载荷返回关联真实执行事实的完整性失败，不读取不可信用例集合。
5. **属性名与 SQL 资源名仍分离。** QueryPlanner 仅 guard 属性部分换为公共规则，`@resource`、表/列标识符继续走原 identifier；新增计划回归确认参数绑定、IS NOT NULL 和 LIMIT 仍存在。P3 的 multi-resource/filter_bindings 接入不属于本次 R4，不以当前未接入判回归，也没有要求放宽 SQL 规则。

初审排除的 preview 正式 output_schema 模板问题维持原结论：候选 meta 的 null 语义与正式模板应区分，不能据此修改正式发布 Schema。

### 独立结果及证据限度

- `c4-rereview-probe.log`：综合探针退出码0，`ALL_ASSERTIONS="PASS"`。使用库 `onto_test_p2`，独立 schema 为 `c4rereview_bf3546302edf4bdea98b9bf497290130`，finally 删除成功。未触碰 onto_dev、公共测试夹具或其他数据库。
- `c4-rereview-schema-boundary.log`：规则/评分有效 Schema 与实际 OpenAPI 解析探针退出码0，全部断言通过；该探针不连接数据库。首次仅环境引导未导入项目 JDK 配置时被 Set-OntoJava 拒绝，随后按项目环境顺序执行成功；无产品变更。
- 对应可复验源码为 `c4-rereview-probe.java.txt` 和 `c4-rereview-schema-boundary.java.txt`。综合探针固定检查目标测试库，通过环境变量取凭据而不打印；使用随机 schema，不依赖清库夹具。将保存文件复制为相应 public class 名的 `.java`，使用项目 JDK21、当前源码和同版依赖编译即可复验。
- 已阅读实施者结果：contract89/data28通过；完整 API95 有94通过和1项原 C1 冷启动1000ms超时，原参数定向复核通过。新增19项中18项在组合运行通过，OpenAPI 最后1项在精确修复后单独通过。**本结论不将这些独立轮次拼成“一次全量全绿”，也不隐去该次超时。** 本轮独立探针未出现超时，不能据此断言冷启动时限问题不存在。
- root 提供的新 JAR 真实 HTTP、原 P2 HTTP、重启旧候选供给与 OpenAPI/TS 同步结果，与本复审证据互补。当前结论批准本批 P2 后端修复继续集成；不代表 P3/P4 或整体出口验收已经完成。

复审仅修改本报告及独立探针证据；未修改产品源码、正式契约、运行服务或共享 target，未提交 Git。
