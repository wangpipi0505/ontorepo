# C8 后端独立 Spec / Quality 复核

2026-10-21。复核范围：C01、C02、C04、C05、C06、SHACL/prepare 错误分类和 Outbox 时间权威。IAM、前端和数据实现由其他独立复核承担。本记录只覆盖下列源码快照及证据，不代表整个 C8 已结项。

## 当前结论

**本次复核范围 PASS，无剩余阻断发现。** Outbox 独立发现的 BR-01（P2）和后续浏览器联调发现的用例先行阶段衔接 BR-02 均已修复复核关闭。BR-02 补充纠正了本报告初次 C06 复核未覆盖的正常操作顺序；初次通过结论不能用于该缺陷尚未修复的版本。10:27 定向回归结束后，已通知实现者可按 root 授权重新全量 verify；本报告不以定向通过替代最终集成出口。

复核遵守仓库 AGENTS.md，使用 code-review-expert 工作流；未修改生产代码，未自行运行 Maven、PostgreSQL 写入或 HTTP 写入。独立新增 `OutboxLeaseWaitAuditTest`，交唯一 Maven/PG 执行者运行；复核者读取实际日志和源码断言，不以作者口头结论替代证据。

## 确认缺陷 BR-01（P2，已修复复核关闭）

**行锁等待跨过租约期限，过期的 ack/fail 仍可落库。** 缺陷快照文件：

`G:/207/项目/算法重组工具/代码/ontorepo/onto-release/src/main/java/cn/ontorepo/release/infra/JdbcOutboxStore.java`，ack 条件第 248 行、时间参数第 252 行；fail 条件第 272 行、时间参数第 276 行。该快照 SHA-256 为 `B517A759FACB7ADA4E75C12920D665C952302B9C3B2A2E38CA5A7E3E5A59D0A0`。

可达时序：正常 claim 建立租约 → 另一事务持该事件行锁（例如人工 replay 锁同 release 整组）→ ack/fail 在租约仍有效时读取 PG 时间并等待行锁 → 等待期间真实租约到期 → 阻塞事务放锁 → 写回仍使用旧时间满足 `lease_until>?`。ack 错误地置 SENT；fail 错误地更新失败/重试及累计尝试结论，绕开了既有到期恢复边界。

约束依据：本类第 240 行明确“只接受当前未过期租约”；既有 `docs/evidence/continuation/c6-outbox-review.md:39`、`:79` 明确恰好到期 ack/fail 均拒绝。本项没有声称旧 token 能覆盖已被新 token 领取的事件，既有 token CAS 仍有效。

独立反例 `onto-release/src/test/java/cn/ontorepo/release/infra/OutboxLeaseWaitAuditTest.java`：使用生产数据库时间构造器及合法最短 5 秒租约；用 `pg_blocking_pids` 证明写回已经等待目标行锁，另查 DB 时钟证明此时租约仍有效；自然等待原期限到期再放锁，绝不截短租约或改系统时间。ack/fail 两分支均期望 false。

实际 RED：`.runtime/c8-outbox-lease-wait-red.log:258` 和 `:354`，2 项、2 失败、0 错误、0 跳过；两个分支实际均返回 true。`:372` BUILD FAILURE，`:375` 结束时间 10:13:34。测试有效进入业务边界，非环境失败。

修复建议：先锁定当前 PENDING/token 行，在持锁后读取本连接的权威时间，检查 `lease_until` 后才修改事件与 attempt；保留可控 Clock 构造供原协议测试。仅将原 WHERE 的参数替换为 `clock_timestamp()` 是否足够须由反例证明，不能假设 PostgreSQL 在仅等待行锁、元组未变化时一定重新计算过滤条件。

**修复复核（10:15 后）**：root 修改同文件 ack 第 249—258 行，先 `SELECT ... FOR UPDATE` 匹配 PENDING/token，再读取 `now(connection)` 比较期限；fail 第 287—299 行同样先取锁，使用锁内 `checkedAt` 判定期限和计算失败退避；null 期限拒绝。两条路径保留 token CAS、原事务与 attempt 原子性。修复 SHA-256：`146416A9A3085CCC247665718EC364D274D35EFF537677CCB747D2108C85309A`。

独立反例仅 AOSP 格式化，没有删除或减弱断言；格式化后 SHA-256：`CEF9CA9F8C073467510C57998C5FC54E84630D1FF5E356AB9C90E4F0310277B1`。已读取 `.runtime/c8-outbox-final-green.log:263` 两个独立反例均通过；`:271` 全组 27 项、0 失败、0 错误、0 跳过；`:289` BUILD SUCCESS；`:292` 10:15:32 结束。该组另含 JdbcOutboxStoreTest 13、OutboxClockAuditTest 1、OutboxRelayTest 10、ReleaseEventServiceTest 1。BR-01 在此源码与定向测试边界内关闭。

## 补充缺陷 BR-02（用例先行阶段衔接，已修复复核关闭）

本项来源为后续真实浏览器联调。原 C06 检查覆盖 READY 不回退、迟到任务不覆盖后续阶段，却遗漏了用户从用例页先运行 tests 的正常路径：初始 VALIDATING 候选在 TEST 通过后无条件成为 REVIEWING，尚未运行 CONFLICT/CONSISTENCY，此时 validate 的来源限制又会拒绝，导致正常发布流程无法继续。已有真实机器失败及 MODEL_ONLY 的 CONTRACT_NOT_AVAILABLE 也会被通过的 TEST 清除候选失败状态。原缺陷位置是 `G:/207/项目/算法重组工具/代码/ontorepo/onto-release/src/main/java/cn/ontorepo/release/application/PublicationService.java:297`，修复前 SHA-256 `8A955C5A8CF8F17954F1A83EA4D46C1094ADA96DD343B5BBE15CEAF007799A53`。

已阅读作者新增的三项反例及真实日志：`.runtime/c8-publication-order-red.log:1942`、`:2052` 为 3 项、3 失败、0 错误、0 跳过；三项实际状态均为 REVIEWING，而分别应保留 VALIDATING 或 FAILED；`:2072` BUILD FAILURE，`:2075` 10:24:30 结束。反例分别覆盖有助手契约的先 tests 后 validate、无助手本地测试通过但正式契约失败、已有 SHACL 机器失败不能被 TEST 成功清除。

修复复核依据如下：

- `PublicationService.java:274` 先取得最后写回阶段的空间/候选锁并获得 current 行；`:298` 才调用 `stageAfterPassedTests(current)`，没有使用锁外旧状态推导阶段。前置与最终阶段检查仍拒绝 READY/COMMITTED/CANCELLED/VOID，长测试不会越过既有终态屏障。
- `checks()` 第 114—118 行按 `DISTINCT ON(kind)`、`id DESC` 选择每种门禁的最新报告；helper 第 315—334 行只接纳同候选当前 SHA。读取与阶段写入处于同一空间锁事务，其他 validate/tests 报告写入也使用该锁，因此不会在本次判据和状态写入之间插入新报告。
- helper 先记录缺失而继续检查，遇任一真实机器失败即 FAILED/MACHINE_GATE_BLOCKED；因此即使 CONFLICT/CONSISTENCY 尚缺，MODEL_ONLY 的 CONTRACT_NOT_AVAILABLE 仍优先阻断。测试事实与 test_run 中的 passed=true 保留为实际本地测试结果，没有伪造正式契约通过。
- 三门禁均 PASSED 才 REVIEWING；缺机器报告时 VALIDATING，后续 validate 可继续。唯一非 PASSED 例外严格限定 `kind=CONSISTENCY` 且 `conclusion=NOT_CHECKED` 且 `reason=SUBSET_EXCEEDED`，仅进入人工资格阶段；第 726 行 `requireGates`、ReleaseGatePolicy 的同摘要/当前修订/OWNER 豁免判据没有放宽。P4GateTest 仍实际验证无豁免 review/prepare 拒绝、合法 OWNER 豁免后发布、引擎错误不可冒充超范围。
- TEST 失败和零用例仍保持 FAILED/TEST_FAILED 或 NO_ENABLED_TEST_CASES；旧无预分配候选仍走既有本地兼容分支。没有改写冻结载荷、预分配标识、机器报告结论或新增豁免类型。
- 已核对正式详设 v2.8 §8.4 第 555 行“用例先行与发布阶段（OM-C06 追加）”，与上述实现一致。

实际 GREEN：`.runtime/c8-publication-order-green.log:53` ContractTest 13 项；`:443` P4AuditReleaseTest 10 项（含新增 3 分支）；`:486` P4ConcurrencyTest 2 项；`:521` P4GateTest 2 项；`:572` P4PublicationTest 3 项；合计 30 项，全部 0 失败/错误/跳过。`:596` BUILD SUCCESS，`:599` 10:27:15 结束。复核者实际读取日志后才通知作者重新 full verify。未另行运行 Maven/PG，也未把本次静态并发分析当作新增加的并发故障注入测试。

修复后 `PublicationService.java` SHA-256 为 `C9E2BBFBAD863C1E548389E23187D0B3496B6058D5A9831E0685E2F0DDF4EFC2`；`P4AuditReleaseTest.java` SHA-256 为 `F6C3559C219ECEE95ECFA01B80F56A0144F5E25A4BB7C31F3B0E26E9232EF5ED`。

## 逐项复核

| 项目 | 代码依据 | 复核结果与实际限制 |
| --- | --- | --- |
| C01 共用守卫绑定 | `FrozenGuardBindings.java:18`、`SupplyService.java:63` / `:142`、`FrozenDataExecutor.java:87` / `:137` | 冻结预览和实例执行均调用同一证明逻辑；dataQuery 的每个实际资源和字段须存在于冻结元数据。RELATION 同时检查 from/to。纯模型没有 dataQuery 时保留主体属性规则。`SupplyService` 仍逐次读取实时授权与范围、同时满足主体规则；未把仅主体规则当实例列过滤。 |
| C02 冻结恢复 | `CandidateService.java:241` / `:245` / `:263` / `:289`、`ReleaseScheduler.java:40`、V12 | 预分配版本与 deadline 同 reserve 事务，版本只递增。恢复只更新到期 FREEZING 为 FAILED/FREEZE_INTERRUPTED/VOID 并写系统审计；空间锁先于候选修改，锁忙跳过。成功写回带状态和 DB 截止条件，失败补偿不覆盖取消或过期结果；REVIEWING 不回收。调度默认 30 秒、20 条，期限默认 15 分钟。 |
| C06 阶段与长任务回写 | `PublicationService.java:150` / `:208` / `:315` / `:338` / `:369` / `:643` | validate 仅 VALIDATING/TESTING/非 VOID FAILED；tests 允许 VALIDATING/TESTING/REVIEWING/非 VOID FAILED；review 仅 REVIEWING；prepare 仅 REVIEWING/READY_TO_COMMIT。validate/tests/prepare 的锁外工作均在写报告/制品/状态前重读阶段、终态和权限。READY 不再被 prepare 降回 REVIEWING。BR-02 已补齐 TEST 通过后的最新机器证据阶段推导。review/prepare/commit 的摘要与修订检查保留。V11 拒绝不可变候选内容及身份列更新。 |
| SHACL 分类 | `CandidateCheckService.java:81`、`PublicationService.java:172` | SHACL 真实运行异常在两种报告顶层均为 SHACL_ENGINE_ERROR，内容使用安全文案；没有扩张 SUBSET_EXCEEDED 豁免资格。 |
| prepare 编译与存储错误 | `PublicationService.java:456` / `:665` | 仅纯编译函数捕获未知 RuntimeException 并转换 ARTIFACT_COMPILE_FAILED；明确 Problem/ContractProblem 保留。存储不可达 503，其他制品错误 409，响应不回显原内部异常。计划与预分配身份仍保留，可按原字节续传。 |
| C04 授权协调与短事务 | `SpaceAuthorizationGate.java:29` / `:38`、`GuardService.java:105`、`SpaceService.java:136`、`CandidateService.java:328` / `:393` / `:673` | 直接 trial 的共享 session gate 与授权修改/归档排他事务 gate 使用同一命名空间键；修改端先 gate 后空间行锁。trial gate 保留一条池连接，内部两个短事务复用它，真实执行无长业务事务；普通草稿编辑可并发。gate 内重验空间 ACTIVE/EDITOR，撤权/归档提交等待旧授权调用完成，新调用取 gate 后见新授权。LOCAL_TEST/正式服务继续原事务语义。 |
| C04 清理和原子性 | `SpaceAuthorizationGate.java:56` / `:70`、`CandidateService.java:673` | 嵌套 finally 保证解绑异常仍走 unlock，失败事务先回滚；SQL 解锁失败尝试 abort 连接。执行、步骤、证据、审计在同一事实事务，审计失败整组回滚，不返回伪成功。已读 fatal Error 和审计失败两项测试；未注入 JDBC unlock 与 abort 同时失败，不能宣称该极端驱动/连接池故障已运行验证。 |
| C05 三次批量读取 | `ExecutionFacts.java:27` / `:46`、`CandidateService.java:822`、V14 | 先按空间授权，最近 50 条按 created_at/id 稳定排序；非空列表固定三次读取，子记录只含本批 UUID、参数化 IN、步骤按 step_order。空列表只需一次查询。五个新索引与实际查询前缀匹配；已有 evidence_execution(execution_id,id) 被复用，没有重复添加。没有对生产数据量执行 EXPLAIN/容量基准。 |
| Outbox 节点时钟 | `JdbcOutboxStore.java:47` / `:71`、`ReleaseConfiguration.java:17` | 生产配置选 DB 时间构造器；append/claim/ack/fail/replay/alert 的时间都取自当前事务连接的 clock_timestamp，不另借连接，不截短已有租约。可控 Clock 路径仅由协议测试显式选择。BR-01 的锁等待时间边界已经锁内复核修复。 |

## 实际证据核对

- `.runtime/c8-combined-green.log:402`：P3AuditGuardTest 2 项；`:490`：P4AuditReleaseTest 7 项。已阅读测试源码，包括规则-only 实例冻结失败、缺 JOIN 资源/列、READY 前置操作拒绝且报告制品不变、冻结预留提交后 Error 恢复、迟到冻结期限、并发 validate 不能覆盖 READY、两种 SHACL 报告和编译错误重试。全批 API 57 + onto-data 2 = 59 项，09:46:54 BUILD SUCCESS。该批早于 C04/C05，不把它解释为当前全仓通过。
- `.runtime/c8-outbox-clock-green.log:271` / `:273` / `:277`：原仓储 13 项及新生产节点时钟 1 项，共 14 项通过；09:51:58 BUILD SUCCESS。新测试确实用 ReleaseConfiguration 构造两个生产 store，再把其中节点 Clock 分别偏移正/负一天，检查不可提前抢占和新事件可及时领取。该批不覆盖 BR-01。
- `.runtime/c8-execution-green-r2.log:363` 只有 6 项，不能证明后加的审计回滚用例。复核发现此证据差异并向作者核对后，读取后续 `.runtime/c8-execution-regression.log:342`：ExecutionAuditTest 7 项全过，42.81 秒，包含审计失败整组回滚及之后连接可复用。
- 同一扩大回归 `.runtime/c8-execution-regression.log:663`：48 项全部通过，`:683` BUILD SUCCESS，`:686` 10:12:32 结束；包括 ExecutionAuditTest 7、P3AuditGuardTest 2、P3MappingTest 14、P4ConcurrencyTest 2、P4FailureTest 9、WorkflowTest 14。已核对实际日志，不以测试类文件中的新增断言自动当作通过。
- BR-01 的独立 RED 与修复后的 27 项 GREEN 见上节。复核者在通知通过前，实际读取日志、当前生产实现和格式化后的反例断言。
- BR-02 的 3 项 RED 与修复后 30 项 GREEN 见补充章节；该批结果覆盖更新后的 PublicationService/P4AuditReleaseTest，不能反向证明修复前版本通过该路径。

## 基线与摘要

Git 尚无初次提交，使用 `.runtime/independent-audit-20261021-baseline/manifest.json` 及 repo 文件作 before、当前源码作 after。对 CandidateService、PublicationService、JdbcOutboxStore 的基线文件实际 SHA 均与 manifest 匹配；审阅其 no-index diff，不依赖空 Git diff。

以下是本轮最终审阅快照 SHA-256（已纳入 BR-01、BR-02 修复及测试）；失败快照另保留于对应章节。

| 文件（仓库相对路径） | SHA-256 |
| --- | --- |
| `onto-mapping/src/main/java/cn/ontorepo/mapping/application/FrozenGuardBindings.java` | `BB776FFC8EFC9A61C2D428AD4156F7E9BD469EA9D05741DFF27631E940F761FB` |
| `onto-mapping/src/main/java/cn/ontorepo/mapping/application/FrozenDataExecutor.java` | `8818F06C90C529BC5D6A09DDA7AB1B0C0985BB51CC97CA22C90ED002FD4B611B` |
| `onto-trace/src/main/java/cn/ontorepo/trace/application/SupplyService.java` | `26FC14A3BEF05AEED6B1BBE8C16557CC163FDE9A737DDC6BAD64456623AC1C61` |
| `onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateService.java` | `B1DD98960FF2A662731248C121283BB6CD37CEAE26D659610107B1602FF95E36` |
| `onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateCheckService.java` | `B5834ABB7DD0A942E1E97CBA7B929605C1C5086E1DF3FFEACBD4B8BA293AC83A` |
| `onto-release/src/main/java/cn/ontorepo/release/application/PublicationService.java` | `C9E2BBFBAD863C1E548389E23187D0B3496B6058D5A9831E0685E2F0DDF4EFC2` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/ReleaseScheduler.java` | `8EE23D1A95097A394D6E95C306B698DD3B401E1BED030515FA68FBF361FCB886` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/JdbcOutboxStore.java` | `146416A9A3085CCC247665718EC364D274D35EFF537677CCB747D2108C85309A` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/ReleaseConfiguration.java` | `EC2DA2CAC5B71803F1135B4630F285068D7DE9D051204DB57A6475BA8813007C` |
| `onto-space/src/main/java/cn/ontorepo/space/infra/SpaceAuthorizationGate.java` | `A0F483888FC8450D82448527B37DE7B636C8C90D4EAEDC2E93D56EB8580F103B` |
| `onto-space/src/main/java/cn/ontorepo/space/application/GuardService.java` | `62A14FDDB801CADDFDB2933FEBCDE49584F318CE9C7C51913C6CB0E33BF926DD` |
| `onto-space/src/main/java/cn/ontorepo/space/application/SpaceService.java` | `D18EDCEBD960E2C22F5D940092E7E79E09842D23BE34CAFFD759A94F579BC592` |
| `onto-trace/src/main/java/cn/ontorepo/trace/application/ExecutionFacts.java` | `0C54ACFA48A3B8DA0B322C0E30A6DC50FBD6293069476F2D8F21CB18D6D312B1` |
| `onto-designer-api/src/main/resources/db/migration/V12__candidate_freeze_recovery.sql` | `AD840998AF9B7AF2A7D9D25A6AB7441D746B193CA9E589AFA630F2C7AD92C40B` |
| `onto-designer-api/src/main/resources/db/migration/V14__execution_and_space_read_indexes.sql` | `3134CC5FE5850C565BEF28F429D42ABF7D84F368DB2C6F81559152B697F321B3` |
| `onto-designer-api/src/test/java/cn/ontorepo/P3AuditGuardTest.java` | `DD2EA5B4B1292C810B0057676AD1800AE3CC9EFB99FAD46492262D353BE6616C` |
| `onto-designer-api/src/test/java/cn/ontorepo/P4AuditReleaseTest.java` | `F6C3559C219ECEE95ECFA01B80F56A0144F5E25A4BB7C31F3B0E26E9232EF5ED` |
| `onto-designer-api/src/test/java/cn/ontorepo/ExecutionAuditTest.java` | `0A3E384F20025BE0ACF0BF2EC060BD50D751CE8D97EF1C5DFF5F166555E32074` |
| `onto-release/src/test/java/cn/ontorepo/release/infra/OutboxClockAuditTest.java` | `115B331FB127B1457A9170DBB0150500331E912D18BD95284002E5659D9D579C` |
| `onto-release/src/test/java/cn/ontorepo/release/infra/OutboxLeaseWaitAuditTest.java` | `CEF9CA9F8C073467510C57998C5FC54E84630D1FF5E356AB9C90E4F0310277B1` |

## 证据边界

本复核未执行最终新 JAR 浏览器/HTTP、进程强杀或重启验收；BR-02 来自其他执行者的实际浏览器联调。本报告完成时，覆盖 BR-02 修复后的完整 verify 尚待重新执行，最终集成结果以主执行记录为准。没有外部五类源或跨系统验收。freeze 中断证据来自预留事务已提交后注入未捕获 Error，不等同于强杀产品进程。授权 gate 的实际并发测试使用真实 PostgreSQL 与可控执行器阻塞，证明同空间查询并发、普通草稿可写、撤权/归档等待及四连接池归还；不替代外部数据源的故障和吞吐测试。数据库本身时钟调整不在节点时钟测试范围内。所有这些限制保留给最终集成验收，不通过当前子集通过结果推定关闭。
