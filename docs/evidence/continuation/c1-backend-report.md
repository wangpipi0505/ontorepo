# C1 / P1 后端实现与验证报告

最新结果：R2 重导入引用保护追加修复后，2026-10-20 22:10:25 以正式 JUnit Platform 精确运行 **28 条指定回归全部通过**；独立编译证明及原 probe 复核见末节。此前 21:53:41 的 **55 条完整 verify** 为对应版本历史证据，本次未冒称重跑全套。

日期：2026-10-20。范围：`contracts/p1-extension.md`、详设 §8.1/8.2/8.9/8.11。实施只修改 `onto-*`、Maven 依赖和本报告；未修改前端、部署脚本、契约或原设计，未提交 Git，未删除业务数据。

## 实现结果

- 属性：真实标量类型、默认值/枚举/数值范围、单位/值码表引用、必填主键和单类主键唯一；引用影响查询及约束/规则引用保护；追加全空间属性读取入口，原按类接口仍要求 classId。
- 码表、正反关系、四类约束：独立服务维护，同空间引用、写锁、expectedDraftRev、审计同事务；正反关系端点/基数同步更新、成对删除。用户已确认保留手工逆/对称关系：生成只读 subsetExceeded 标记并冻结，完整 OWL 语义进入真实 profile 检查，默认阻断，仅 OWNER 可豁免。
- 规则：递归下降白名单 AST，字面量、UUID 属性、比较/逻辑/算术和全部五种函数；类型及引用校验；运行解析与求值共用 50ms 时限；评分验证权重、分段、禁算、缺值和稳定次序。区间/仿射条件进行真正求交与蕴含，错误与重叠分别报告四类冲突。
- 策略及四类操作：绑定规则和策略，同空间校验；运行从冻结包获取规则、模型及策略，对资源上限取最小值；支持规则检查和评分输入，实际失败保留 FAILED 执行、步骤和输入/输出摘要。
- TTL/OWL：Jena 真解析、预览/确认、语法行号/冲突/超范围报告、过期预览拒绝；完整原文与规范 RDF 同时冻结；不跟随外部 owl:imports。可编辑投影更新导出已支持的声明，删除受原图引用保护；纯导入导出通过图同构断言。约束导出与真实 SHACL 检查共用映射。
- 候选：完整冻结 classes/properties/operations/codebooks/relations/constraints/rules/strategies/testCases/rdfDocuments；兼容缺少新增键的旧候选，不改旧字节和摘要。测试只运行候选中的启用用例、入口 LOCAL_TEST；EXACT 默认要求 rows 完整结果，CONTAINS 显式选择且必须有断言。
- 检查：初期 CONFLICT/CONSISTENCY/TEST；报告持久化。真实 OWL API profile + ELK 0.6.0，真实 Jena SHACL 5.6.0；一致、不一致、超范围未检查、超时和引擎错误分开记录。ELK 自报推理不完整时阻断 ENGINE_ERROR，不能把部分推理包装成一致。

## RED / GREEN 与执行命令

环境仅由本工程 `deploy/Environment.ps1` 导入；未在输出或证据写入密码。所有业务集成测试连接独立真实 PostgreSQL，未使用 H2 或内存替身。

```powershell
. deploy/Environment.ps1
$null = Import-OntoEnvironment
Set-OntoJava
$env:MAVEN_OPTS = '-Xmx512m'
mvn -B '-DargLine=-Xmx512m' verify
```

针对性测试使用 `-Dtest=P1ExtensionTest#方法名` 与 `-Dsurefire.failIfNoSpecifiedTests=false`（PowerShell 中整个 -D 参数加引号）。本地原始日志保留 `.runtime/c1-*.log`；以下只摘要实际结果。

| 阶段 | 证据日志 | 实际结果 |
| --- | --- | --- |
| 新入口 RED | c1-red.log | 17 条中新增 3 条失败；当时缺码表、导入及用例入口，原 14 条继承回归通过 |
| 首批 GREEN | c1-green2.log | 新入口和原回归 17 条 + 规则领域 7 条，共 24 条通过 |
| 扩展针对性 | c1-new-red.log | 文件名沿用阶段标签，实际是 6 条全部通过；不冒称 RED |
| 导入编辑/外部导入标记 RED | c1-import-red.log | 2 条失败：旧名称仍被导出、imports 未在预览标记 |
| 引用及运行失败 RED | c1-boundary-red.log | 2 条失败：删除 MUTEX 集合引用类成功、除零返回 400 丢执行事实 |
| 上述修复 GREEN | c1-boundary-green.log | 4 条全部通过 |
| 用户关系决策/SHACL 导出 RED | c1-decision-red.log | 2 条失败：缺关系超范围标记、缺 SHACL 导出 |
| 决策修复及 RDF 回归 GREEN | c1-decision-green.log | 4 条全部通过（逆/对称审批、SHACL 导出、OWL XML 与旧候选、实际 SHACL 实例失败） |
| 十条竞争性问题 | c1-cq-green2.log | 1 个场景中 10 次冻结试执行全部答对；新草稿改名后仍返回旧候选答案 |
| 最终完整验证 | c1-acceptance-verify.log | 2026-10-20 21:33:54 BUILD SUCCESS；47 条，0 失败、0 错误、0 跳过；完成 JAR 重打包（59,735,707 字节） |

较早一次完整 verify 为 56 条通过，但包含继承带来的 14 条重复回归。之后抽出公共 WorkflowFixture 消除重复；正式最终为 Boundary 1 + 原 Workflow 14 + QueryPolicy 7 + P1Extension 18 + RuleExpression 7 = 47 条，不合计阶段次数。

环境失败：曾有 PowerShell Maven 参数未加引号、未发布迁移中 SQL 保留字 symmetric 未引用，以及宿主机并行工作导致 JVM native allocation OOM。前两项已修复；OOM 按环境失败记录，主执行者恢复内存后改为 Maven/fork 512m 串行验证，ELK 工作线程限制为 2。未把环境失败记成业务成功。

## 关键出口样例

| 要求 | 自动化证据 |
| --- | --- |
| 十条竞争性问题 | tenCompetencyQuestionsAreAnsweredFromFrozenModel：对象清单、根类型、任务直接子类、非根类型、任务属性、整数属性、算法评分字段、任务到算法关系、任务出边、同时有名称及优先级的对象；10 个真实候选操作均比较实际返回名称集合 |
| 正常 EL 一致 | elkChecksConsistentUnsatisfiableAndActualTimeout：真实 ELK 返回 CONSISTENT、eligible=true |
| 不相交/继承矛盾 | 同测试 A/B 继承叠加 MUTEX，真实 ELK 不可满足类，INCONSISTENT 且阻断 |
| 导入全称量词及豁免 | rdfPreviewPreservesUnsupportedGraphAndRequiresConfirmation：TTL 图同构，NOT_CHECKED(SUBSET_EXCEEDED) 默认拒绝、OWNER 审批后允许初期检查、草稿变更后自动失效，不能重新审批旧候选 |
| 手工逆/对称及角色 | manualInverseAndSymmetricRelationsAreMarkedAndOnlyOwnerCanWaive：实际 OWL 公理/profile、只读标记、EDITOR 403、OWNER 通过 |
| 超时/引擎失败与重跑 | owlXmlRoundTripAndLegacyCandidateRemainCompatible：真实本地推理任务 1ms 超时；缺损输入触发 ENGINE_ERROR；同正常包足够时限重跑 CONSISTENT。另 0ms 截止测试验证时限前置拒绝 |
| 四类冲突 | conditionsAreTypedAndAllFourConflictKindsAreDetected：区间求交 LOGIC、蕴含互斥 CONSTRAINT；仅测试维护身份注入损坏 TYPE/REFERENCE（普通保存会预先阻止这两类坏输入） |
| 引用与冻结 | constraintsRejectCrossSpaceAndPropertyReferencesBlockDestructiveChanges、frozenRulesScoringAndPolicyLimitsDoNotReadNewDraft、frozenTestSetIgnoresDraftChangesAndExactMismatchFails |
| OWL XML 与旧候选 | owlXmlRoundTripAndLegacyCandidateRemainCompatible：RDF/XML→TTL 图同构；构造旧四键载荷并计算真实摘要，试执行成功 |
| 真实 SHACL | shaclValidatesActualCandidateInstancesAndExternalImportsAreFlagged：真实实例 20.0 违反 0..10 范围，实际报告 MaxInclusiveConstraintComponent；authoredConstraintsAreExportedAsRealShaclShapes 检查实际 shape 三元组 |

## 独立只读审查的八项修复

1. 导入实体修改/删除：支持声明覆盖原图，删除同步处理原图并拦截未解除的 RDF 引用。
2. 重导入破坏属性：预览报告引用冲突，确认仍调用统一属性保护；失败回滚。
3. 删除类漏 MUTEX.classIds：集合引用也阻断删除。
4. 空用例断言：保存拒绝空断言，EXACT 要求 rows，执行默认要求成功。
5. 运行异常丢事实：仅“操作不在候选”属于执行前拒绝；运行中的规则错误/超时/缺值保存 FAILED 事实。
6. 逆/对称 RDF 丢失：完整投影 owl:inverseOf 和 owl:SymmetricProperty，真实 profile 识别超范围。
7. SHACL 漏类型：五种属性类型均映射 sh:datatype。
8. 已绑定规则改 kind：更新阻断破坏操作绑定，候选冲突检查也复核操作/规则兼容性。

## 接口增补与真实边界

- 与主执行者约定新增 `GET /spaces/{id}/properties`；原 `GET /properties` 的 classId 必填不变。关系只读响应增加 subsetExceeded，已同步主执行者；写请求仍只用 inverseName 管理逆边。
- EXACT 用例必须给 rows；不支持空断言冒充测试成功。初期 eligible 只表示当前三项检查，不是正式发布资格。
- 多属性非线性及部分字符串次序条件的求交/蕴含不作无依据结论：返回 RULE_ANALYSIS_UNSUPPORTED 并阻断冲突门禁，需调用者拆分条件；未宣称一般算术逻辑求解器。
- ELK 0.6.0 对某些 OWL 数据属性/断言会报告不完整；该情况返回 ENGINE_ERROR 并阻断，不允许 OWNER 以 SUBSET_EXCEEDED 豁免。真实报告保留这一实现边界。
- 非空 readScope 当前模型查询无法证明范围，执行显式 READ_SCOPE_UNSUPPORTED；真实外部实例关系、读取范围翻译属于 P3，未用本地元模型查询冒充。
- 不支持构造的预览行号按原文中构造名第一次出现定位；同一构造多次出现可能共享行号，未声称逐 token 精确源映射。原文及完整 RDF 均被冻结以便人工复核。
- 本次未执行前端浏览器、服务重启与部署；由主执行者统一 C3。未执行正式发布、MinIO 归档或 P2～P4 业务；本报告不代表这些出口通过。

## 外部官方资料

- [ELK 官方仓库：OWL API / Maven 坐标与版本](https://github.com/liveontologies/elk-reasoner)
- [Apache Jena 官方 SHACL 文档](https://jena.apache.org/documentation/shacl/)
- [google-java-format 官方版本](https://github.com/google/google-java-format/releases/tag/v1.37.0)；仅本地格式化工具，不增加项目运行依赖。

## 独立复审 R1～R5 修复补充（2026-10-20）

复审依据：`c1-backend-review.md` 与主执行者的 `.runtime/C1ReviewProbe.java`。本轮未修改 root pom、onto-contract、onto-artifact、web、deploy 或契约；未启动服务、未提交 Git。

| 复审项 | 修复和新增实际验证 |
| --- | --- |
| R1 导入关系对称/逆边漏投影 | 导入分支同步开启/关闭 owl:SymmetricProperty，并完整维护手工逆边公理，保留其他未投影公理。HTTP 导入→编辑→导出→候选检查覆盖开启、关闭、建立逆边、原注释保留；另覆盖“原本导入的对称关系关闭后，不能被旧预览标记永久阻断”。旧候选仍按自己的冻结对称值阻断。 |
| R2 dateTime/自定义类型被 STRING 覆盖 | 只将完整 XSD IRI 对应的五种基础类型放入可编辑属性投影。未知类型合法导入并完整保留 RDF、明确列为 DATATYPE 待人工处理，不伪造 STRING 或类投影；旧候选的 STRING 占位也不会改写原始类型。dateTime 与自定义 datatype 均断言未编辑图同构。 |
| R3 大写 UUID 绕过策略 | 新策略 boundOps、操作 ruleIds、约束引用集合、属性码表引用、评分指标/禁算字段均在保存时规范化；表达式 UUID 和执行输入按同一身份处理。旧冻结包在运行时使用 UUID 等价匹配，执行结果证明大写旧绑定仍限 1 行，带非空范围仍明确阻断；未改旧字节/摘要。旧草稿大写集合的规则/操作/属性/码表删除保护也覆盖。 |
| R4 DATE 枚举 SHACL 误拒 | 枚举项按属性实际 RDF datatype 构造，ISO 日期生成 xsd:date。真实 Jena SHACL 验证枚举内日期通过、非枚举日期失败；未降低门禁或跳过校验。 |
| R5 草稿策略引用误报 | draft 快照载入 strategies，并使用正确复数键。真实同空间显式绑定无错误，实际缺失策略仍报告 REFERENCE。 |

门禁额外修正：超范围检测从“原导入预览标记”调整为“冻结原图叠加明确模型编辑后的实际候选 RDF”。预览证据及原文仍保留；实际图继续经过同一 OWL API profile 和真实 ELK。此变更同时防止合法删除超范围公理后仍误报，以及新手工公理没有预览标记时漏报。

本轮 RED/GREEN：

- `.runtime/c1-review-red.log`：新增五个复审用例，真实 PG **5 失败、0 错误**，分别稳定复现 R1～R5。
- `.runtime/c1-review-green3.log`：五项修复加关联 UUID 集合验证，共 **7 通过**。
- `.runtime/c1-stale-flag-red.log`：导入对称性关闭后，旧预览仍造成 NOT_CHECKED 的补充用例 **1 失败**。
- `.runtime/c1-review-all-test.log`：在不打包运行 JAR 的前提下执行 `mvn -B '-DargLine=-Xmx512m' test`，**54 通过、0 失败/错误/跳过**。
- 主执行者原反例原样重跑，输出在 `.runtime/c1-review-probe-green.log`：R1 开启 emitted=true、关闭 still present=false；R2 dateTime graph-isomorphic=true、range 原样 dateTime；R3 大写和规范绑定均 1 行；R4 DATE 枚举 conforms=true。
- 最终重打包验证：`.runtime/c1-review-final-verify.log`，2026-10-20 21:53:41 **BUILD SUCCESS，55 条、0 失败、0 错误、0 跳过**；完成当前 JAR 重新打包。

本轮新增回归源文件：`onto-designer-api/src/test/java/cn/ontorepo/C1ReviewRegressionTest.java`。截至最终验证编译，包含 8 个测试场景。没有使用数据库替身，没有把直接 Java 探针或阶段重复执行合计为独立用例。

## R2 重导入已有引用属性的追加修复（2026-10-20）

定向复审发现：新未知数据类型被跳过可编辑投影时，也跳过了旧同 IRI 属性的引用保护。已在 ImportService 抽出共享 `validatePropertyReplacement`：预览先验证旧属性变化再执行投影跳过；确认在空间写锁内、任何 UPSERT 前重新验证，不能相信旧版预览中空的 conflicts。

- 原有引用属性改为未知类型/不可投影结构：先报告 PROPERTY_IN_USE；旧预览即使没有冲突也不能应用。
- 已有基础投影没有引用时，也不能自动与未知新类型并存，明确报告 IMPORT_TYPE_CONFLICT，要求先人工处理原属性；全新 IRI 的未知数据类型继续合法导入并保持图同构。
- 新增两项实际 PG 回归：dateTime/自定义 datatype 在预览阶段报告冲突；人为恢复旧版“空 conflicts”预览后，apply 仍拒绝，并断言修订、导入文档数、IMPORT_APPLY 审计、applied 标志及原属性 INTEGER 与唯一 range 全部保持不变。
- `.runtime/c1-reimport-red.log`：2 条，2 失败、0 错误，准确复现漏冲突及错误确认。
- 第一次定向 `.runtime/c1-reimport-green.log` 中，C1ReviewRegressionTest 的 10 条全部通过；P1ExtensionTest 遭并发测试库夹具干扰，出现会话中途被清空及 owner 唯一键冲突，2 失败、2 错误。已通知主执行者协调独占，未将该轮记为成功。
- 指定业务类实际文件名为 `P1ExtensionTest`（本工程没有 P1ExtensionWorkflowTest）；本轮计划在独占测试库后仅重跑 `C1ReviewRegressionTest,P1ExtensionTest`，不重复全套。

### R2 追加修复的最终定向结果

最终采用主执行者确认的 **JUnit Platform 1.12.2 直接启动**，没有将其写成 Maven 成功记录：

```powershell
# cp 从既有 surefire XML 的 java.class.path 读取，并添加本机已有的
# junit-platform-launcher-1.12.2.jar；凭据仍只由本工程环境脚本导入。
$compiled = '.runtime/c1-final-classes'
javac -J-Xmx512m --release 21 -encoding UTF-8 -parameters `
  -classpath $cp -d $compiled `
  onto-model/src/main/java/cn/ontorepo/model/application/ImportService.java `
  onto-designer-api/src/test/java/cn/ontorepo/WorkflowFixture.java `
  onto-designer-api/src/test/java/cn/ontorepo/BoundaryTest.java `
  onto-designer-api/src/test/java/cn/ontorepo/C1ReviewRegressionTest.java `
  onto-designer-api/src/test/java/cn/ontorepo/P1ExtensionTest.java `
  .runtime/C1DirectedLauncher.java
java -Xmx512m -classpath "$compiled;$cp" C1DirectedLauncher
```

`C1DirectedLauncher` 用正式 LauncherDiscoveryRequestBuilder / LauncherFactory 精确选择 C1ReviewRegressionTest 与 P1ExtensionTest，使用 SummaryGeneratingListener，只有发现且成功完成全部 28 条、无失败时才成功退出。运行结果：

```text
Test run finished after 67864 ms
28 tests found / 28 tests started / 28 tests successful
0 tests failed / 0 tests skipped / 0 containers failed
C1_DIRECTED found=28 succeeded=28 failed=0 skipped=0
```

- 时间：2026-10-20 22:10:25 完成；退出码 0，Hikari 池自然关闭，未遗留测试进程。
- 对应当前源码的证明：上述 `javac` 退出 0；输出只写独立 `.runtime/c1-final-classes`，未覆盖共享 target；`javap -private` 确认包含 validatePropertyReplacement。启动日志的 IMPORT_SOURCE 和 REVIEW_TEST_SOURCE 都指向该独立目录。源文件和全部生成字节码 SHA-256 保存于 `.runtime/c1-final-compilation-hashes.json`；编译日志 `.runtime/c1-final-javac.log`；真实执行日志 `.runtime/c1-reimport-junit-final.log`。
- 原 `.runtime/C1ReimportReviewProbe.java` **源码原样**复核：使用独立 ImportService classes、复制到 `.runtime/c1-reimport-probe-snapshot` 的 C1 模块 JAR 及本机已有依赖，不改 target、不运行 Maven；实际输出 preview status=PREVIEW、conflicts 含 numeric 规则引用；apply=REJECTED、code=IMPORT_CONFLICT；Review transaction rolled back。日志 `.runtime/c1-review-reimport-green.log`，快照 SHA `.runtime/c1-reimport-probe-snapshot-hashes.json`。
- 协调中的非成功记录也保留：第二次 Maven 范围执行 `.runtime/c1-reimport-green-isolated.log` 仍被未结束旧进程造成的 owner 唯一键冲突干扰，28 条中 1 环境错误；随后 `.runtime/c1-reimport-final-test.log` 在进入 C1 测试前被正在开发的 onto-data RED 测试源码缺类阻断。均未作为业务成功，也未为绕过它们修改新模块、pom 或门禁。
- 本补丁最终验证仅为上述 28 条指定回归；未再次声称全套 verify，也未重新打包应用 JAR。前一阶段 55 条完整 verify 仍是其对应版本的历史证据，后续应用打包由主执行者的统一构建负责。
