# C7 / P4 发布与消费后端独立审查

日期：2026-10-21。审查者 `/root/c7_frontend_review`，保持独立 reviewer 身份；只读生产实现、正式测试、迁移和合同。工程未提交，本报告以文件路径、观测时间和自有探针为依据，不虚构 commit SHA。

## 结论

- **Spec：APPROVE（本次 P4 后端源码审查范围）。** P4-R1 已修复并经独立行为探针复核，最终证据增量已核对，没有未关闭发现。
- **Quality：APPROVE（同范围）。** 本轮独立发布探针 10/10 PASS，历史原探针 5/5 PASS；原 RED 与发现过程保留。
- 历史恢复切片的 H1/H2 单独记录在 `c7-history-review.md`；此报告不复制其发现。root 另行提出的“等待空间锁后仍使用旧 release 状态”和“机器门禁锁前检查编辑权限”两项已交实施者，不计为本审查新增发现。
- 下文明确区分 reviewer 独立无 IO 探针、实施者真实集成日志，以及仍由 root 负责的完整运行出口。本结论不声称 reviewer 已独立运行整个真实 P4 环境。

## 范围和依据

依据 `AGENTS.md`、`contracts/p4-release.md`、详设 v2.6 §7.1/§8.3/§8.4/§8.7/§8.8，以及开发计划 v1.6 的完整 P4 出口。采用 requesting-code-review、java-spring-boot 和 verification-before-completion 技能。

本轮完整阅读主要实现：

- `onto-release/.../application/PublicationService.java`
- `ReleaseGatewayService.java`、`ReleaseLifecycleService.java`、`ReleaseEventService.java`
- `onto-release/.../domain/ReleaseGatePolicy.java`、`OutboxPolicy.java`
- `OutboxRelay.java`、`JdbcOutboxStore.java`、`PgInboxDestination.java`、`HttpOutboxDestination.java`、调度与配置
- `onto-trace/.../application/CandidateService.java` 正式执行入口及冻结、`FrozenBundleLoader.java`
- `SupplyService`、`CandidateTestService`、正式契约与知识制品生成所依赖的相关路径
- `ReleaseController`、`WebBoundary`、`Transport`、`Errors` 及候选测试 HTTP 边界
- V9/V10 迁移与有关前序表约束

正式测试只读覆盖 `P4PublicationTest/P4GatewayTest/P4ScopeTest/P4ConcurrencyTest/P4FailureTest/P4LifecycleTest`，以及 `ReleaseGatePolicyTest/JdbcOutboxStoreTest/OutboxRelayTest/ReleaseEventServiceTest`。历史四类、恢复、投影和追溯测试见单独历史报告。

## P4-R1 · P2 · 已提交或取消的候选可被迟到路径重新改写

首次独立探针 RED：2026-10-21 01:22:07，本地时间。文件定位以下列方法和语句为准；实施者随后正在增加状态屏障，因此行号对应原反例附近，最终修复位置另列复核记录。

| 原问题路径 | 定位 | 触发与原观察 |
| --- | --- | --- |
| 复核已提交候选 | `PublicationService.review`，约第 298–310 行；共用 `locked` | 候选 COMMITTED，草稿尚未改变，五门禁仍有效。`locked` 当时只拦 CANCELLED，`review` 将候选写回 REVIEWING。 |
| 复核后再取消 | `review` 与 `cancel`，约第 556–565 行 | 上一步之后 `cancel` 看到 REVIEWING，允许写 CANCELLED/VOID；已经存在正式 release 的候选被表示为取消，后续 commit 失去原幂等路径。 |
| 迟到准备完成 | `PublicationService.prepare` 最后一个事务，约第 422–426 行 | 请求 A 在锁外上传；请求 B 已完成准备并提交。同一 A 返回后共用锁函数未拒绝 COMMITTED，写回 READY_TO_COMMIT。 |
| 迟到契约失败 | `PublicationService.tests` 契约失败分支，约第 213–220 行 | 保存 CONTRACT 报告的事务结束后，用户取消；随后另一个无状态复核的事务将 CANCELLED 写成 FAILED。 |
| 迟到冻结失败 | `CandidateService.freeze` catch，约第 86–93 行 | FREEZING 已可从候选目录看到，用户在对象存储上传期间取消；上传异常随后无条件写 FAILED/VOID，覆盖 CANCELLED。成功上传分支原本已有 FREEZING 条件，失败分支没有。 |

契约明确已 COMMITTED 候选重复 commit 返回原 release，已提交候选不能取消；取消写 CANCELLED/VOID，失败/取消的预分配序号不回收。详设 §7.1 将提交视为正式状态迁移，并不允许旧异步阶段反向改写已结束状态。前两项不依赖并发，直接连续 HTTP 动作即可触发相应生产路径。

影响包括：管理状态与真实 release 不一致、已发布候选被显示为可取消/可继续发布、正式提交重试不再走幂等、用户明确取消被迟到结果覆盖。原 release 内容不因此删除，本报告没有将其夸大为制品丢失。

建议在每次持锁状态变更时拒绝 COMMITTED/CANCELLED/VOID；仅 commit 的幂等读取允许已提交状态。锁外工作完成后必须重新取得该判断；冻结失败用限定 FREEZING 的条件更新，并仅在实际更新成功时记录失败审计。不能只在 HTTP 初始状态或页面按钮上禁止。

### 独立原始证据

`.runtime/c7-publication-review/PublicationReviewProbe.java` 编译并运行当前真实 `PublicationService` 与 `CandidateService` 方法，隔离数据库、事务和对象存储边界；存储回调与事务提交回调注入明确交错。使用真实状态判断及门禁策略，非手写一份生产状态机。

原日志 `.runtime/c7-publication-review/probe-original-red.log`：

```text
FAIL review cannot reopen a committed candidate: state after review=REVIEWING
FAIL review followed by cancel cannot cancel an existing release candidate: state after review/cancel=CANCELLED
FAIL late prepare cannot demote a concurrently committed candidate: state after late prepare=READY_TO_COMMIT
FAIL late contract-failure publication cannot replace cancellation: state after late test failure=FAILED
FAIL late freeze failure cannot replace cancellation: state after late freeze failure=FAILED
PASS committed retry still returns the original release
Summary: 1 passed, 5 failed
```

此探针不执行真实 SQL、不证明真实锁竞争或 MinIO 网络行为。特别是原 `MemoryDb.update` 不解释 SQL 的 WHERE 条件；若修复采用条件 UPDATE，复核替身必须相应模拟条件，而不能把旧替身无条件写入的行为当成修复失败。原文件和原 RED 保留，任何复核替身改动单独说明。

## 已核对且未发现新增具体缺陷的边界

| 需求边界 | 源码核对结果 |
| --- | --- |
| 冻结权威与版本身份 | 空间锁内预分配 releaseId/版本号，全量序列化固定字节和 SHA；版本序列允许空洞。新候选经对象存储加载，缓存 SHA 也核验；存在对象引用时不在损坏后回退 PG payload。旧候选兼容不自动取得正式发布能力。 |
| 五门禁 | CONFLICT、CONSISTENCY、CONTRACT、TEST、REVIEW 全部绑定候选 SHA。CONSISTENT 才映射 PASSED；INCONSISTENT、超时和异常不冒充通过。仅一致性 NOT_CHECKED/SUBSET_EXCEEDED 接受同摘要、同当前修订的 OWNER 豁免。 |
| 真实测试 | 正数启用用例、实际完成数和通过结论共同建立 TEST；零用例及失败阻断，CONTRACT 失败的新正式候选不进入执行。旧本地兼容测试结果不能替代正式 TEST 证据。 |
| 就绪屏障 | prepare 在提交前生成 manifest/context pack/index，确切字节先存不可变计划，失败重试复用计划；并发编译以持久计划为准。三类制品引用齐备才 READY_TO_COMMIT。commit 在锁外核验 bundle 加三制品实际摘要，锁内复核同一引用、修订、SHA 和门禁后一次事务写全部业务事实。 |
| 幂等与发布事务 | 候选正式 id/版本固定，commit 同事务建立 release、space_version、context_pack、实体投影、端点、RELEASED 和 COMMITTED；已提交重试返回原 release。未观察到先 READY 后补制品或重复发送时重编译的路径。P4-R1 是此前其他入口破坏这项前提的问题。 |
| 权限与固定版本 | 正式网关只接受真实会话或哈希核验的 API key；路由空间、凭据空间必须匹配 release。显式有效 endpoint_grant 是前提，OWNER 不自动获得调用权。再核对冻结守卫、空间授予及四层范围交集，三入口都保留未分类标签默认拒绝。 |
| 冻结执行 | 正式入口以 release 的不可变 bundle、操作和策略执行，证据引用实际 release/SHA；不因草稿变更回查草稿定义。root 已单独发现的等锁期间弃用问题由实施者修正并提供真实锁等待反例。 |
| 成功/失败响应 | HTTP `JsonNode` 转 Object 保留数组、布尔、null 等合法根值，最终按冻结 inputSchema 校验。成功 meta 取实际 release 四元组并为 preview=false；版本头在网关成功返回后才赋值。错误处理不注入旧成功 meta 或版本头。 |
| 凭据和目录 | 凭据只存随机高熵 key 的不可逆摘要；明文仅创建返回一次。目录不选 key_hash，认证按 ACTIVE 和有效期核验，映射本地主体并继续空间/端点授权。端点授权和凭据写入在空间锁内 OWNER 复核并写审计。 |
| 生命周期和实际消费 | 弃用状态与事件同事务且重复请求幂等。消费仅 READY，读取实际对象后重新检查状态。完整性扫描检查四类制品，暂时不可达不误报为损坏；损坏隔离并产生不含拉取地址的 CORRUPTED_ALERT。 |
| 事件资格和顺序 | 创建资格使用 bound_status，投递不借当前 release 状态取消历史事件。每 release 前序 PENDING（含未到期/租约中）阻塞后继；SENT/DEAD 不阻塞。领取后重检前序，避免人工重放与领取交错绕序。 |
| 可靠投递与重放 | 网络发生在领取事务外；有效租约 token 才能确认，超时租约计失败并退避，累计失败最终 DEAD/告警。REPLAY 保留原 id/载荷/累计尝试，拒绝仍被后继活动租约占用的冲突重放。PG 收件箱持久确认并按 id/载荷摘要去重；HTTP 要求正确 eventId 的确认且响应体读取有时限/大小上限。 |
| 安全输出 | 事件目录不返回内部 payload；lastError 为安全分类，收件箱 receipt 仅接受结果和摘要。制品列表不返回存储凭据。trace/audit 的历史事实和敏感内容处理见历史切片报告。 |
| 数据库保护 | V10 追加正式表与候选状态元数据，候选冻结内容、release 内容和 outbox 身份/载荷受触发器限制；正式/候选执行主体检查包含 space_id。历史归档与恢复外键边界见历史报告。 |
| 职责与注释 | 发布编排、门禁策略、网关、生命周期、历史恢复和 outbox 存储分离；关键事务、网络边界、凭据和幂等计划有中文注释。部分新增类格式较密集，但未把主观排版或单纯文件长度列为阻断。 |

## 运行方法、证据边界和未运行范围

自有目录 `.runtime/c7-publication-review/`。JDK 使用机器现有 Java 21，`-Xmx512m`；仅把实际审查类和自有探针编译到该目录。依赖 classpath 只读既有 surefire XML 的 `java.class.path`，不输出凭据。Windows 使用参数文件避免超长 classpath 命令失败；运行参数将本工程路径转为相对路径，避免含中文路径的 JVM 参数文件解码问题。

```powershell
# 工作目录：G:/207/项目/算法重组工具/代码/ontorepo
$reviewJdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin'
& "$reviewJdk/javac.exe" '@.runtime/c7-publication-review/javac.args'
& "$reviewJdk/java.exe" '@.runtime/c7-publication-review/java.args'
```

本 reviewer 独立执行：编译当前 `PublicationService`、`CandidateService` 成功；6 项无 IO 行为断言得到 5 RED、1 PASS。历史独立探针另有 5 项，详见对应报告。

本轮没有运行 Maven、真实 PG/JUnit、MinIO、HTTP 服务、浏览器或实际进程重启；不修改实施者生产测试。上述正式测试的源码阅读证明存在相应断言，不等同于本 reviewer 独立执行这些场景。真实 PG/MinIO/HTTP、五事件场景与重启闭环由 root 和 `p4_backend` 分别提供运行记录；本审查也不将本地 PG 收件箱等同于外部智能体平台上架验收。

## 修复复核与最终增量

### P4-R1 已关闭

实施者稳定版本通知后重新编译实际生产源码。01:27:23 首次复核 6/6 PASS；01:30:31 在最后的零用例/证据增量和 AOSP 格式化后再次编译并复核，仍 6/6 PASS，编译和运行退出均为 0。

最终修复路径：

- `PublicationService.java:620`：`lockEditor` 取得空间锁后重新检查编辑权限及候选终态/VOID；validate、tests 的成功和失败写入均通过该屏障。
- `PublicationService.java:641`：`locked(...,allowCommitted)` 在空间锁、修订及摘要核验后拒绝终态；只有 commit 允许读已提交结果。review、cancel 和 prepare 锁外工作前后均走该判断。
- `CandidateService.java:125`：失败回调仅更新 `status='FREEZING'` 的行，实际改变后才记录冻结失败审计；已取消状态和取消审计不再被覆盖。
- 幂等 commit 对照仍返回原 release；没有为了禁止重开而一并破坏重试。

自有复核文件 `PublicationRecheckProbe.java` 从原探针另存：只给 fake Db 补充 `AND status='FREEZING'` 的条件更新语义，并将原来允许任意 `Problem` 的捕获收紧为仅接受 `CANDIDATE_TERMINAL/CANDIDATE_CANCELLED`。六项状态/幂等断言不放宽，原 `PublicationReviewProbe.java` 和原 RED 均保留。

日志：`.runtime/c7-publication-review/compile-final.log`、`probe-final.log`，结果 **6 PASS / 0 FAIL**。

### 最后增量：证据身份和零用例结果

独立阅读 `CandidateService.recordDefinitionEvidence`、`TraceAuditService.publicReference/evidenceDetail` 及 `PublicationService.tests`：

- 实际成功执行后的 SERVICE_VER/RULE_VER 使用冻结操作/所选规则规范化字节计算摘要，携带真实 bundle/release 绑定；未用到的规则、查询绕过的规则和候选伪发布身份不生成对应证据。
- 对外 trace 的 RULE_VER 指向 ruleId，SERVICE_VER 指向 serviceId，所属 release 保留在 detail；不会用共同 releaseId 替代每个定义节点身份。该映射代码与实施者真实正式规则 trace 测试只读核对。
- 新正式候选测试响应显式返回 `passed=false` 和 `NO_ENABLED_TEST_CASES`，与持久 TEST 门禁一致；旧无 preReleaseId 的兼容候选保持原有行为。

自有 `AdditionalPublicationProbe.java` 4 项 PASS（01:30:29）：

1. 正式服务和选中规则的定义 SHA、release/SHA 绑定正确，未选择规则不混入。
2. dataQuery 执行路径不伪造 RULE_VER。
3. 候选规则证据保留 CANDIDATE 主体，不出现正式 releaseId 或 SERVICE_VER。
4. 底层兼容 runner 对空集合返回 true 时，新发布接口仍返回 false/明确原因。

其中定义证据探针直接调用当前真实辅助方法，零用例探针调用真实 `PublicationService.tests`，持久化/执行依赖为替身；不将其写成完整真实执行链测试。日志 `probe-additional.log`。初次自有探针构造器参数顺序错误导致编译失败，修正的只是自有探针，日志另存 `compile-additional-setup-failure.log`；不作为产品 RED。

最终可复跑命令：

```powershell
$reviewJdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin'
& "$reviewJdk/javac.exe" '@.runtime/c7-publication-review/javac-final.args'
& "$reviewJdk/java.exe" '@.runtime/c7-publication-review/java-recheck.args'
& "$reviewJdk/java.exe" '@.runtime/c7-publication-review/java-additional.args'
```

最终主要源码指纹如下；其余已读稳定类的路径、文件时间和 SHA-256 保存在 `.runtime/c7-publication-review/reviewed-files.json`。

| 文件 | SHA-256 |
| --- | --- |
| `PublicationService.java` | `24BC930440D55F066CEF0ECC9FB5B64D139881D3A99B6520179FBC7E3F80187B` |
| `CandidateService.java` | `749280B4CCB63439F390F9F5D5E85B524BF7825ECA5D3BBC823DBA397B71C512` |
| `TraceAuditService.java` | `DA32531B8D4FC73C4BC5E8B23F342F25A1DCC16AD5B216C2A8BC08E66AE681CC` |

### 只读核对的实施者集成证据

下列日志均实际读取测试计数和 BUILD SUCCESS；不是本 reviewer 执行：

| 日志 | 实际结果与范围 |
| --- | --- |
| `.runtime/c6-terminal-history-green.log` | 18 项通过：历史恢复 6、trace 3、发布故障 9；包含真实已提交拒 review/cancel、迟到 prepare 与 commit、冻结锁外取消后故障。 |
| `.runtime/c6-version-evidence-green2.log` | 9 项通过：正式网关 6、发布 3；涵盖正式规则证据与零用例响应，以及 API key、JSON 根类型等既有回归。 |
| `.runtime/c6-lock-lifecycle-green.log` | 8 项通过：真实锁等待竞态 2、五事件场景 5、范围交集 1。root 提出的两项竞态已有等待锁证据和源码修复，不再作为待修事项。 |
| `.runtime/c6-outbox-final-green.log` | 37 项通过：领域门禁/资格 14、真实 PG outbox 13、relay/实际收件箱和受控 HTTP 10。 |

最终历史源码经再次编译后原 5 项也全部通过，日志 `.runtime/c7-history-review/probe-final.log`，详见历史报告追加段。根 reactor verify 在本报告完成时仍由实施者运行；本报告不提前宣称其最终通过。真实服务重启、完整浏览器发布闭环及外部系统联调边界继续由 root 验收记录负责，不被上述源码 APPROVE 替代。

## 后续纯格式化与 V11 追加记录

实施者第二遍 AOSP 格式化改变少数长 SQL 的续行缩进。再次重新编译当前生产类并运行已有 6+4 发布断言、5 项历史断言，仍 **15/15 PASS**；日志分别 `c7-publication-review/compile-postformat.log`、`probe-postformat.log`、`probe-additional-postformat.log` 和 `c7-history-review/compile-postformat.log`、`probe-postformat.log`，编译/运行均退出 0。

新指纹保存在 `.runtime/c7-publication-review/reviewed-files-postformat.json`，原指纹文件保留。主要变化：

- `PublicationService.java`：`D943581482BE63BC066D6AC009136BA63369523555F4572E81007583C3A44192`
- `CandidateService.java`：`E2D3855E0F779E0A6ED4B3DD0C366E151077F372A5641A35C80BF2D289290139`

root 随后追加 V11，不修改已应用 V10：冻结内容列改用 `BEFORE UPDATE OF` 的无条件拒绝触发器，从而也拒绝 `payload=payload`。已只读核对全部 10 个不可变列、实际 10 条候选 UPDATE 和只读加载器，静态兼容性 **APPROVE**；具体记录与 3 项无数据库静态探针见 `c7-integration-review.md`。此处不提前声明 V11 的真实 PG/全量 verify 结果。
