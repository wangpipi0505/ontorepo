# C6 / P4 全量发布与消费后端

## 2026-10-21 最终验收结果

**本次完整根 reactor `verify`：362 项全部通过，0 failure / 0 error / 0 skipped，14 个模块全部 SUCCESS，实际 exit 0。** 起于北京时间 02:41:00，结束于 02:55:13，Maven 总时长 14:12。唯一最终日志为 `.runtime/c6-final-pool-verify.log`；机器汇总 [c6-final-verify-summary.json](c6-final-verify-summary.json) 只统计本次运行，模块总数与本次逐类结果之和独立核对一致，不累加下文任何历史红绿日志。

| 实际测试所属模块 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| onto-contract | 91 | 0 | 0 | 0 |
| onto-artifact | 6 | 0 | 0 | 0 |
| onto-data | 42 | 0 | 0 | 0 |
| onto-release | 50 | 0 | 0 | 0 |
| onto-designer-api | 173 | 0 | 0 | 0 |
| 总计 | 362 | 0 | 0 | 0 |

最终 JAR：`onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`，194866147 bytes，SHA-256 **`66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC`**。与 root 已运行、重启及真实 HTTP 验收的运行副本完全一致；最后一次修正仅在 BoundaryTest 收敛测试连接池和新增资源预算断言，生产源码、生产配置、数据库上限及 JAR 字节均未因此改变。

最终资源修正有真实 RED→GREEN：旧默认池为 maximum=10/minimumIdle=10，多缓存上下文耗尽 PG 的 `max_connections=100`；现共同测试入口 maximum=4/minimumIdle=0，既覆盖 P4 三连接锁时序，也覆盖旧 Workflow 四 worker。最终全量实际启动 10 个 Hikari 测试上下文池；后段快照为测试库 active2/idle16（含诊断连接1）、独立运行库 idle10，上限仍100，见 `.runtime/c6-final-pool-pg-budget.log`。新增资源断言、真并发及原两项连接失败定向6项与最终全量均通过。原361项连接耗尽失败日志完整保留。

本批54个新增/改动Java的AOSP dry-run，以及最终唯一测试增量的再次dry-run均 exit0；已批准两份policy原核SHA不变。独立 Spec/Quality APPROVE 依次见 [发布主链](c7-publication-review.md)、[历史恢复](c7-history-review.md)、[OM-D6及取消隔离](c7-reasoning-fallback-review.md)、[测试连接池](c7-test-pool-review.md)；这些独立探针不冒充真实PG/MinIO全量测试，二者分别保留证据。

root 独立完成最终同SHA运行副本的原P1/P2/P3/P4/正式数据重启重放、六模型HTTP、T02三入口零源访问，以及前端/浏览器和停投积压跨进程交付；本实现者未冒称亲自运行这些项目。root 真实停止MinIO后的3项不可达检查及恢复后的3项检查均通过，原日志 `.runtime/c7-storage-outage-final.log` 已只读核对，结果见 [不可达](p4-storage-unavailable.json)、[恢复](p4-storage-recovered.json)、[正式数据持久化](p4-data-persistence-smoke.json)、[停投重启](p4-deferred-persistence-smoke.json)、[新冻结守卫](p4-guards-http-smoke.json)。

02:55 验证结束后已明确归还唯一 Maven 和 MinIO 窗口，无活动测试/构建。五类外部数据源验收仍依用户确认延后；默认 PG inbox 只证明可靠本地收件，不能称为外部智能体平台上架。

## 2026-10-21 完整共享窗口实施细节与历史证据（以下早期限制不覆盖上述最终结果）

已将 artifact/release 接入 reactor，追加 V10 发布运行期及 V11 内容写屏障迁移；专用 `onto_test_p4` 已实际迁移到 V11，V10已应用字节从未改写。V11保留原candidate_no_update触发器名，禁止显式UPDATE全部10个不可变内容列（含payload=payload），发布状态/制品进度字段正常推进。新候选保留原 P3 全域冻结内容和管理文件证明，预留 release/版本流水后以真实 MinIO 归档复核；新候选和正式版本均使用同一 FrozenBundleLoader，绝不在对象损坏时回退 PG。历史候选字节及两个已批准 policy SHA 保持不变。

实现边界：PublicationService 管理五门禁、正数完成 test_run、OWNER 复核、精确制品计划、网络锁外上传与二次 CAS 原子提交；四制品为已复核 bundle、正式 manifest 集合、context pack 和真实 Lucene 物理文件 ZIP。ReleaseHistoryService/DraftBundleRestorer/HistorySafety 管理全域投影、版本 diff、完整 P1/P2/P3 恢复和安全展示。ReleaseGatewayService 实现 hash-only 一次明文 API key、过期/撤销和默认拒绝端点授予；CandidateService 的正式 RELEASE 主体复用现有 FrozenExecutor/FrozenDataExecutor、Schema 与守卫执行链，持久六阶段授权和真实证据。ReleaseLifecycleService 负责弃用及四制品完整性隔离，ReleaseEventService 负责空间隔离目录和原 id 有限重放；ReleaseConfiguration/ReleaseScheduler 已接默认真实 PG inbox 和有界调度，可显式配置受控 HTTP 目的地。

恢复保留源密文、守卫、类/属性/关系/约束/码表、公理、规则、策略、操作/测试、知识/RDF、文件目录、源/元快照和三类映射；草稿替换与旧元快照完整字段归档在同事务，同 id 异内容冲突回滚。归档禁止 UPDATE/DELETE，旧 trace 按原 id+执行空间查活动或归档快照；恢复移除的后增源/快照不再混入当前目录，旧执行证据仍可查。

真实证据（Maven reactor 单窗口，JDK21、Maven/fork 512MiB、ASCII tmpdir，测试仅本工程专用 PG/MinIO）：

| 日志 | 实际结果与范围 |
|---|---|
| `.runtime/c6-publication-red.log` | 首批3个真实API失败：预分配缺失/API404；缺实现RED，不计通过 |
| `.runtime/c6-main-green2.log` | 发布主线3+旧于200条事件定向重放1通过 |
| `.runtime/c6-failure-red2.log` | 真并发重复 commit 存在后锁重查缺口；修后二次 COMMITTED 检查保留幂等 |
| `.runtime/c6-gateway-green-attempt.log` | 网关5+当时故障5通过；API key、默认 deny/空间与端点独立、正式 meta/header、数组/布尔/null JSON根 |
| `.runtime/c6-history-green4.log` | 原历史/恢复/trace9通过；实际 V1-V10 随机 PG schema+MinIO |
| `.runtime/c6-lock-red.log` | 2个确定性真PG锁等待反例均失败：弃用后仍执行旧READY、降权后仍落机器检查 |
| `.runtime/c6-lock-lifecycle-green.log` | 修后8通过：锁竞争2、详设五事件场景5、真实数据四层范围1 |
| `.runtime/c6-history-fix-red.log` | 3反例真实失败：候选diff选DEPRECATED、CORRUPTED阻断上一READY、时间精度反序 |
| `.runtime/c6-terminal-red.log` | 3反例真实失败：COMMITTED可复核回退、迟到prepare覆盖提交、冻结失败覆盖取消 |
| `.runtime/c6-terminal-history-green.log` 01:25:52 | 18通过：历史9+故障9。复核/prepare锁内终态、tests迟到失败再锁、冻结失败条件更新；commit仍返回原版本 |
| `.runtime/c6-version-evidence-red.log` | 3反例真实失败：缺SERVICE_VER、缺RULE_VER、新候选零用例返回true |
| `.runtime/c6-version-evidence-green.log` | 首轮9项仅1失败：规则证据展示ref错误选择所属release，进一步修复按kind定位规则/服务身份 |
| `.runtime/c6-version-evidence-green2.log` 01:30:15 | 9通过：正式真实冻结SERVICE_VER/RULE_VER+正确证据身份/摘要、正数TEST及零用例明确false，原网关场景全部保留 |
| `.runtime/c6-full-verify.log` 01:43:37 | 首次完整342项：contract91+artifact6+data42+release50+API153；仅3失败、0错误/跳过。2项P2旧拒绝事实用例需独立历史fixture，1项原不可变no-op断言需要追加V11；日志原样保留，不宣称首轮全量通过 |
| `.runtime/c6-compat-gate-atomicity-green.log` 01:44:48 | 7通过：原3失败修后、2项新FAILED/VOID零LOCAL_TEST对照、OM-D5真实逆/对称完整语义默认阻断→EDITOR拒→OWNER同SHA/rev豁免→prepare/commit、outbox最后写故障五写/投影/端点全回滚后同候选重试 |
| `.runtime/c6-whitelist-green.log` 01:47:32 | 1通过：同一真实合法候选含白名单内算法成功与越白名单任务FAILED固定断言，两用例真实通过五门禁发布；TOKEN/EXTERNAL正式拒绝403 SCOPE_EXCEEDED，无meta/header，source解密/连接零调用，真实RELEASE失败事实2条 |
| `.runtime/c6-preview-package.log` 01:48:29 | root已停止旧进程后，串行skipTests package成功；待验收JAR SHA256 `ED602263A7F92934E9269957085F449443C9D806799D2F9E3AE3C877FC41B21B`。该次仅打包，不冒称全量GREEN |
| `.runtime/c6-final-aosp-check.log` | 50个本批Java最终AOSP dry-run，exit0；未改policy核 |
| `.runtime/c6-final-verify.log` | root真实HTTP发现DTO caller遗漏及引擎FAILED结论映射问题后，明确停止自身Maven/测试进程；该轮中止，无最终总数，不宣称通过 |
| `.runtime/c6-http-contract-red.log` 01:52:51 | 2项MockMvc真实RED：SERVICE/EXTERNAL/LOCAL_TEST执行列表DTO丢caller；原始FAILED/ENGINE_ERROR被错误映射NOT_CHECKED。原始HTTP RED同时由root保存 |
| `.runtime/c6-http-contract-green.log` 01:54:22 | 8通过：Execution DTO返回实际caller，只有明确NOT_CHECKED才映射NOT_CHECKED，FAILED/ENGINE_ERROR仍FAILED且不允许SUBSET豁免 |
| `.runtime/c6-http-fix-package.log` 01:54:41 | 中间运行副本42F61B32…，仅caller/FAILED展示修复；不冒称包含后续HermiT |
| `.runtime/c6-fallback-red.log` 01:56:54 | 用户批准OM-D6后真实6项中5失败：3基础数据类型、原本体不一致与不可满足类均因ELK incomplete阻断；对照class-only与非EL规则通过 |
| `.runtime/c6-fallback-green-attempt.log` / `c6-fallback-green2.log` | 基础类型/不可满足已过；极端Thing⊑Nothing原图使固定HermiT抛空union异常，正确FAILED，未伪造INCONSISTENT/CONSISTENT；该原图保留成真实异常阻断反例，另加标准命名类个体矛盾真实不一致反例 |
| `.runtime/c6-fallback-publish-green.log` 02:03:06 | 12通过：纯真实两引擎/控制/故障边界9+实际PG/MinIO基础STRING/INTEGER/DECIMAL required属性3例完整发布；每例零豁免 |
| `.runtime/c6-fallback-package.log` 02:04:10 | 成功返回前再检查共享总预算后纯9项再次通过，package成功，运行副本SHA256 `5661CED3C2090E255A5075145E314F36FAC09DFA75832F70A4F94E18D92B8336` |
| `.runtime/c6-fallback-final-aosp-check.log` | 54个新增/改动Java dry-run exit0，实际已执行 |
| `.runtime/c6-final-fallback-verify.log` | 原全量中P1连续超时后恢复失败，真实定位ELK静态共享中断器污染后主动中止自身构建；保留原失败，不宣称全量通过 |
| `.runtime/c6-elk-cancel-red.log` 02:12:22 | 新20轮超时→错误→恢复反例第0轮真实失败，下一独立请求被污染为TIMEOUT |
| `.runtime/c6-elk-cancel-green.log` 02:15:55 | 隔离ELK取消后14通过：纯10含20轮恢复、P1原兼容恢复1、真实PG/MinIO数据属性发布3 |
| `.runtime/c6-cancel-aosp-check.log` / `c6-final-isolation-aosp-check.log` / `c6-errors-aosp-check.log` | 取消修复3文件两遍AOSP；最终本批54个Java（53+Errors）dry-run exit0 |
| `.runtime/c6-cancel-isolation-package.log` 02:16:38 | skipTests package成功，SHA256 `66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC`，尚不等于全量通过 |
| `.runtime/c6-final-isolation-verify.log` 02:38:44 | 完整361项，1失败/1错误/0跳过；全部业务断言此前通过，后段P4Scope源连接400与Whitelist建上下文53300由测试缓存池耗尽PG连接造成，日志保留。9个已启动Hikari默认10/10池+独立API10连接触及PG max_connections100；不冒称通过 |
| `.runtime/c6-pg-budget-diagnostic.log` / `c6-pool-budget-red.log` | 真实SHOW max_connections=100；新增测试池预算断言真实RED确认maximum=10/minimumIdle=10。只收敛BoundaryTest共享测试配置为maximum4/minimumIdle0；P4锁等待需3连接、既有Workflow四worker需4，统一上限4覆盖二者，生产源码/配置/DB上限未改 |
| `.runtime/c6-pool-budget-green.log` 02:40:46 | 6通过：实际池4/0预算2、真实并发锁2、上一全量Scope/Whitelist两失败修后通过；无业务断言删减 |
| `.runtime/c6-pool-aosp-check.log` | 唯一测试增量BoundaryTest已AOSP两遍后dry-run exit0，其余本批Java格式基线无变化 |

### 用户明确批准的OM-D6两引擎边界

root真实HTTP发现仅数据属性的EL图也会被ELK报告incomplete后，用户明确选择ELK优先、HermiT完整兜底（正式设计/计划由root同步）。实现只在**原完整ontology通过OWL2EL profile且ELK明确报告一致性或分类不完整**时启动HermiT，不删公理、不把异常当一致。非EL仍原SUBSET_EXCEEDED及仅OWNER同SHA/rev例外；ELK真异常、超时或真不一致不会启动第二引擎。报告保留`engine=ELK→HermiT`、原不完整阶段`fallbackReason`与`incomplete=true`，HermiT必须同时确认整体一致、无不可满足命名类才CONSISTENT。

ReasoningBudget在创建工作线程前开始计总时限，ELK与HermiT共享同一个Future/deadline；HermiT仅取得剩余时限，HermiT取消使用实例interrupt；ELK 0.6的interrupt使用静态共享DEFAULT_INTERRUPTER，因此仅以Future.cancel(true)中断本请求工作线程，不能污染并发/后续请求；迟到创建但尚未查询实例只dispose，工作线程finally释放引擎/ontology/model，返回成功前再次检查总预算。真实超时边界测试用实际创建HermiT+仅查询边界阻塞注入，确认超时返回FAILED/TIMEOUT并dispose；失败注入只在完整引擎工厂边界，业务规则不使用替身。

依赖固定为[HermiT 1.4.5.519官方Maven发布](https://central.sonatype.com/artifact/net.sourceforge.owlapi/org.semanticweb.hermit)，排除其旧fat OWLAPI-distribution 5.1.9，保留ELK 0.6.0固定的OWLAPI 5.1.20。实际Surefire classpath已检查7个OWLAPI模块全部5.1.20，无混合distribution；JDK21上真实正常/不一致/不可满足执行已过。固定版本在极端Thing⊑Nothing原图出现引擎异常时仍按ENGINE_ERROR阻断，未删原公理或设置ignoreUnsupportedDatatypes。

五事件场景实际调用发布/弃用/真实 MinIO 损坏检查/真实 PG relay/inbox：正常 READY 交付；已交付 RELEASED 后 DEPRECATED 失败重试；已交付后损坏隔离；relay停投积压 RELEASED→DEPRECATED 后按历史 boundStatus 顺序交付且实际弃用拒拉；DEPRECATED 重试屏障阻后续 CORRUPTED_ALERT，有限 DEAD 后才解阻。通知原 id、payload、boundStatus 持久保留，CORRUPTED_ALERT 无拉取地址。时间控制只改变重试到期，业务事实均在 PG；进程级应用重启由 root 独立真实 HTTP harness 验收，已完成结果见最终节，本实现者不冒称亲自执行。

已对当前54个本批新增/改动Java执行AOSP dry-run exit0。公共方法和事务/权限/失败边界有中文说明。已批准ReleaseGatePolicy/OutboxPolicy未格式化或改写，SHA与下方历史核记录一致。首轮全后端独立APPROVE及15探针见c7-publication-review/c7-history-review；后续caller/FAILED及用户批准HermiT初轮15探针APPROVE见c7-reasoning-fallback-review；ELK共享取消缺陷已新增真实红绿，reviewer追加5组迟到/活动取消、60轮恢复、请求线程退出/共享执行器空闲、重叠请求隔离均通过；同报告Spec/Quality APPROVE已恢复，原15及新增5组各自证据保留，不用旧探针代替。连接预算修复后定向6项及最终完整根reactor 362项均GREEN，日志为`.runtime/c6-final-pool-verify.log`；上一轮361项连接耗尽FAIL完整保留，不合并历史为通过；之前失败/中止日志全部保留。root从`.runtime/c6-running/<sha>.jar`独立副本启动512MiB/onto_dev应用，不锁target JAR；我方专用onto_test_p4与随机制品隔离，MinIO/HTTP保持运行直到完整测试结束。默认PG收件箱验收不能表述为外部智能体平台上架。

## 2026-10-21 独立outbox运行期实施与验证

**本批GREEN：37条，0失败/错误/跳过。** 仅证明独立outbox持久化/真实目的地组件及既有纯领域核；尚未加入应用reactor、Flyway迁移、Spring定时接线或正式发布API，不据此关闭C6/P4，更不等于智能体平台上架。

root明确允许受限并行单模块Maven后，先执行 `mvn -B -f onto-release/pom.xml '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test`：

| 证据 | 实际结果 |
|---|---|
| `.runtime/c6-outbox-red.log`，00:28:13 | testCompile因缺JdbcOutboxStore/OutboxRelay/目的地实现失败；这轮是缺实现编译RED，未计任何JUnit通过 |
| `.runtime/c6-outbox-green-attempt.log`，00:31:36 | 29条，1错误：原租约用例在31秒即取下一领取，但既有policy规定租约失败后5秒退避；用例补充31秒仍等待、35秒可领取断言，保留既有policy |
| `.runtime/c6-outbox-boundary-red.log`，00:32:27 | 新增2项均先真实失败：整批提前租用导致第二项确认时过期；HTTP响应头到达后body阻塞2284ms突破总deadline |
| `.runtime/c6-outbox-green.log`，00:33:37 | 修复即时逐项领取、全body总deadline/入内存上限后，32条全部通过 |
| `.runtime/c6-outbox-ledger-red.log`，00:34:28 | 新增2项均先真实失败：DEAD缺持久告警、receipt复制原payload而无安全摘要 |
| `.runtime/c6-outbox-final-green.log`，00:35:41 | 补齐告警/重放处理、receipt仅存摘要；37条全部通过（原领域14+PG store13+relay10） |
| `.runtime/c6-outbox-formatted-green.log`，00:37:54 | AOSP格式化后复跑37条全部通过；0失败/错误/跳过，BUILD SUCCESS |

运行环境由本工程Environment.ps1静默导入，使用JDK21、Maven/JUnit fork均512MiB、ASCII Unix-domain temp。测试数据库明确覆盖 `jdbc:postgresql://127.0.0.1:15472/onto_test_p4`，每例独立随机schema。HTTP只绑定本机回环随机端口；未访问任何外部平台或邻工程资源。只运行 `-f onto-release/pom.xml`，无`-am`、root构建、install、package或共享target改写。

新增生产组件：

- `JdbcOutboxStore`：短事务PG领取、全部前序PENDING屏障、bound_status委托既有policy、租约CAS、超时/进程崩溃计入有限重试、累计尝试历史、DEAD告警；调用方连接append/replay确保后续版本状态/审计可同事务接入。写入幂等但拒绝异载荷覆盖；坏资格隔离；损坏告警拒绝嵌套HTTP/S3拉取地址。
- `OutboxRelay`：有界批次即时单项领取，事务外发送，真实确认后独立CAS；旧租约确认不改新状态。异常只传安全分类码，原始网络/SQL错误不进入公开lastError。
- `PgInboxDestination`：真实PG唯一eventId持久接收及重复一致性核验，commit后确认；receipt仅`accepted/payloadSha256`，不泄露内部制品地址。确认丢失重试仍同eventId，收件箱仅一条。
- `HttpOutboxDestination`：显式构造配置才使用HTTP，不跟随重定向；原eventId/幂等头/既有payload保持，必须收到`accepted=true`且匹配整数eventId；body入内存上限4096字节，全请求总deadline覆盖“头到达、体停滞”分支，可取消请求并关闭客户端。实际应用默认PG接线尚待完整窗口。
- `OutboxDestination`为PG/HTTP两种真实目的地的公共边界；`OutboxProblem`仅携安全错误码。

六个新增类及三个新测试文件已用本工程既有google-java-format AOSP格式化，两个已批准policy类保持原SHA256：ReleaseGatePolicy=`F812D5EAA6F62925A18CB87F257B757B8ABE5B019A68C0EEE16857A4DF3AD8BA`，OutboxPolicy=`7ED80590AE3F7FD43D50822655BA97C94C9D342443F678F4A3A0E1AC89495C49`。

完整窗口接入时，追加真实迁移须包含`publish_outbox`、`publish_delivery_attempt`、`publish_delivery_alert`、`publish_inbox`的组件列/约束/索引（当前测试夹具DDL列明具体边界）；事件追加连接不得autocommit。应用服务仍须实现OWNER/空间授权、审计理由、生命周期状态/outbox原子、消费READY检查与定时调度。后续需真实应用迁移复跑、§7.1五场景端到端、进程级重启和独立review；本批仅用重建store/relay及租约模拟崩溃验证持久恢复，不宣称已完成整应用重启。

## 2026-10-21 首批测试先行（历史记录）

root 授权先写 `onto-release/` 新运行期文件及本报告，P3仍占共享后端。已新增真实 PostgreSQL 测试夹具 `OutboxDatabase`、9 项 `JdbcOutboxStoreTest` 和6项 `OutboxRelayTest`，覆盖调用方事务回滚、历史 bound_status、未来PENDING屏障、DEAD解阻、并发领取、过期租约/旧确认CAS、原id重放、非法资格隔离、多版本并行、PG持久收件箱确认与去重、HTTP失败/原载荷重试/错误确认、网络期间无行锁、lastError脱敏。所有业务状态由 PG 表承载，测试时钟仅控制到期边界。

测试夹具强制 `ONTO_TEST_DB_URL` 指向本工程 `127.0.0.1:15472/onto_test_p4`，每例使用随机 `p4_outbox_t_<UUID>` schema，清理仅该schema。夹具DDL仅检验独立outbox存储边界，不能替代后续真实应用迁移/五事件完整闭环。

`onto-release/pom.xml` 仅增加由既有 parent 管理版本的 Jackson（真实HTTP载荷与确认处理）和测试 PostgreSQL 驱动依赖，无新增框架；root POM及已批准两个领域规则未改。

**该历史时点执行状态：NOT RUN，尚无RED/GREEN证据。** root 短暂交出单模块 Maven 窗口后，P3提前就绪要求收回；本 agent 当时尚未启动命令，即时交还，未运行 Maven、未访问PG/MinIO。先保留测试，等下一窗口确认缺实现的真实RED再编写运行期实现，不能把“预计编译失败”写作已观察的失败。当时新增测试引用待实现 `JdbcOutboxStore`、`OutboxRelay`、`PgInboxDestination`、`HttpOutboxDestination`，为未完成的测试先行状态；不影响尚未把该模块加入reactor的P3构建。最新完成状态见本文顶部独立运行期实施记录。

正式 canonical route 已由 root 同步 contract 为 `/onto/r/{spaceCode}/releases/{releaseId}/ops/{opCode}`，保留 `/onto/s/{spaceCode}/ops/{opCode}?releaseId=...` 同网关别名。后文“契约差异”第1项已解决，无需改动已批准的 ContractGenerator。

## 首轮只读调查与证据边界（历史记录）

2026-10-20：只读集成调查草案，**尚未实施、尚未运行测试、尚未验收**。P3 正在收尾，须等 root 通过 `followup_task` 明确授予生产写入与 Maven 窗口，再开始 TDD。当前仅创建本报告，未修改源码、测试、POM、迁移、部署或正式设计，未运行 Maven、访问数据库、启动/停止服务或操作相邻工程。

依据已逐项读取：工程 `AGENTS.md`、`docs/continuation-plan.md`、`c6-backend-brief.md`、`contracts/p4-release.md` 全文，正式详设 §7.1/§8.3/§8.4/§8.6/§8.7/§8.8、开发计划 P4/W13～16，以及 `c6-artifact-report/review.md`、`c6-policy-report/review.md`。ArtifactStore 的 6 例和发布领域核的 14 例/1,851 个独立断言是既有基础证据，本报告不将其当作实际 P4 持久化调用链验收。

root 已明确：旧候选 LOCAL_TEST 的原结果兼容保留；发布 TEST 必须独立证明正数启用用例全部实际执行通过，不能依赖旧逻辑的空集合 `passed=true`。任意 JSON 根（含 null、数组、布尔）保持到冻结 Schema/执行器边界；正式执行成功 meta 精确取实际版本，失败不注入成功 meta 或版本头。

## 只读发现与集成方案

### 模块及共用执行链

- `onto-release` 当前只有已批准的 `ReleaseGatePolicy`、`OutboxPolicy` 与对应测试，尚未加入 reactor；`onto-artifact` 也未接入 reactor。原样复用两项领域规则和 `ArtifactStore.storeImmutable/loadVerified`，不重写简化规则或上传实现。
- `onto-trace` 当前承载候选冻结、执行、检查、测试、知识供给；`FrozenExecutor` 已共用 P1/P2/P3 冻结执行器，`SupplyService` 已有可信 endpointScope 参数及 LabelScope 交集。P4 正式 RELEASE 执行应接入同一条持久事实/Schema/守卫/执行路径，不能把 releaseId 伪装为 candidateId。
- 建议依赖方向：`onto-trace → onto-artifact`（共享可信 bundle 加载边界），`onto-release → onto-trace + onto-artifact`（发布编排与恢复/消费/事件），`onto-designer-api → onto-release`（薄控制器），避免 trace/release 互相依赖。共享 BundleLoader 放 trace：按 subjectType/id 查可信 PG 引用，新候选和 RELEASE 使用 `loadVerified`，只有明确旧格式候选保留原 BYTEA 兼容分支。对象损坏或不可达不得落回 BYTEA。
- 发布服务拆分为应用编排、制品编译/读取、PG 持久化与 relay 基础设施；控制器不持有发布业务规则。现有 CandidateService 的冻结组包提取/扩展在 P3 最终源码上进行，不覆盖 P3 增量。

### 冻结、门禁与提交

- 当前 `CandidateService.freeze` 在空间行锁内一次读取模型/规则/策略/操作/用例/RDF/知识/源/元快照/三层映射，formatVersion=2；尚无预分配标识、bundle_ref、正式状态机。新冻结增加版本能力标记，旧候选 payload/sha/formatVersion 不改。
- 冻结先在短事务锁空间、检查 expectedDraftRev、递增空间独立流水、分配候选和 preReleaseId/preVersionNo、保存不可变快照与 FREEZING。事务提交后编译/归档真实 bundle 与契约预览，再用短事务改 VALIDATING；失败另事务持久 FAILED 与原因，版本预分配记 VOID、永不回收。FREEZING/FAILED 可审计；进程中断不得伪造就绪。
- 当前 `candidate_no_update` 触发器禁止候选任意 UPDATE。追加迁移必须把限制精确收窄为 payload/sha/space/draftRev/创建事实/预分配身份不可变，允许状态和制品进度更新；禁止改动既有 V1～V9。
- 当前 `CandidateCheckService.checks` 是旧兼容复合检查：TEST 先于 CONTRACT，缺独立 REVIEW；不能直接用作 P4 validate。P4 validate 仅运行 CONFLICT/CONSISTENCY/CONTRACT，原始 CONSISTENT 映射 publication 的 PASSED，原报告保留；CONTRACT 失败持久 FAILED，不能触发测试。旧 `/checks` 字段形状保持。
- `CandidateTestService` 当前逐个真实执行冻结 enabled 集合但不持久 test_run；P4 增加同摘要 test_run 与逐项执行关联，零用例报告缺验收并阻断发布。旧候选本地结果兼容不等于新发布资格。
- OWNER REVIEW 单独保存候选摘要、当前修订、人工原因/身份/时间；仅既有 OWNER/SUBSET_EXCEEDED 同摘要/同当前修订豁免可覆盖 CONSISTENCY NOT_CHECKED，其他门禁失败不可覆盖。最终准备/提交真实调用 `ReleaseGatePolicy.evaluate`。
- prepare 读取同一可信 bundle，以持久 manifest_preview 正式化，填 preReleaseId、`vN`、spaceCode、bundleSha256；同时生成 context pack 和真实 Lucene 物理文件归档。四制品（含原 bundle）均 `loadVerified` 后短事务 CAS 为 READY_TO_COMMIT；上传/进程故障停 REVIEWING，重试不改变已归档字节。
- commit 先读取候选、授权并真实核验四对象，全部网络操作置于提交事务外；随后短事务锁空间/候选、二次核对状态/授权/修订/SHA/制品引用/五门禁，同候选幂等，原子五写 release/version/context_pack/outbox/candidate，并派生 release_entity/endpoints。重复并发仅一个 release 与 RELEASED。同候选已 COMMITTED 返回原 release，无补制品路径。

### 制品、版本与全域恢复

- `FrozenKnowledge` 当前使用真实 Lucene ByteBuffersDirectory 建索引但仅作可重建 LRU 缓存。应复用现有文档编译函数，导出 Lucene 已提交的文件作为有界归档并实际打开验证；不能用知识 JSON 冒充物理索引。其现有文档/8MiB编译上限保留，归档受 ArtifactStore 16MiB 上限约束，越限明确失败。
- bundle 投影只用于浏览/diff；执行永远读取 bundle。投影包含 contract 固定 8 类，其中属性码表、约束/RDF、源配置等仍保存在完整 bundle，不能因投影枚举而从恢复中丢弃。
- restore 先在事务外验证历史 bundle，再短事务 OWNER + 空间锁 + expectedDraftRev，全量重建 P1/P2/P3 草稿并增加新修订；恢复空间名称/域/guardCfg 及模型、码表、关系、约束、规则、策略、操作、用例、RDF、知识、源/映射/元快照。保留历史候选、版本、执行、证据、审计和导入/建议处理事实。
- 删除/插入顺序遵守现有非延迟 FK：用例先于操作、操作先于策略、知识/规则/关系/属性先于类、元快照先于数据源；类 parent 层次及 relation.inverse_of 循环需显式处理。meta_snapshot 有不可更新触发器，不能以 UPDATE 改历史事实，恢复策略须保持旧快照事实并安全处理同 id。
- `CandidateService.freeze` 当前未把 `data_file` 目录纳入 payload，P3 文件源 resource 依赖此信息；写入窗口重新核对最终 P3 冻结协议，决定将目录引用/不可变文件证明随 bundle 冻结，确保从历史版本恢复不丢失管理型文件关联。原始源数据不要求复制入 bundle。
- READY→DEPRECATED、READY/DEPRECATED→确认损坏 CORRUPTED，与各自 outbox/审计同一事务；重复操作幂等。存储临时不可达只记可重试故障并拒绝消费，不能误称摘要损坏。扫描定时、有界批次、防重复告警。

### 授权、HTTP 与追溯

- 现有 `SupplyService.authorize` 先 `guards.grant`；`GuardService.grant` 一律要求 editor，适用于内部试执行但不等于正式服务的空间读取权限。P4 要把入口角色/端点检查与空间读取拆开，严格按认证→入口授权→冻结守卫完整性→空间可读→四层交集/白名单→标签过滤的顺序，SERVICE/EXTERNAL 的 VIEWER 若具备明确端点授权应按空间读取规则处理；TRIAL/LOCAL_TEST 仍≥EDITOR。
- endpoint_grant 缺记录拒绝；存在 NULL 范围才不限，过期/撤销拒绝。API key 使用安全随机值，仅首次返回明文，PG 只存 hash、过期与撤销事实，映射已有本地主体，不取客户端伪造身份。
- 现有 WebBoundary 对 `/onto/**` 一律 TOKEN；仅固定正式消费路由应允许 TOKEN 或 X-API-Key，管理 API 仍 TOKEN。不会把全局拦截器简单改为任意 key 均可管理。
- 现有 `op_execution.subject_id` FK 只指 publish_candidate，caller 约束也尚不包含 EXTERNAL。追加迁移需改为可验证的 CANDIDATE/RELEASE 主体完整性，保留历史记录，不用候选外键套正式版本。请求/授权快照/步骤/真实 evidence 与 trace 同事务持久化，失败执行也保留实际身份和主体。
- 成功正式服务返回 `meta={spaceCode,releaseId,version:vN,contentDigest:bundleSha256,preview:false}` 及 `X-Ontology-Release`；失败不填两者。候选保持 preview=true。HTTP JsonNode/业务 Object 连续传递，不加 input 外壳，不把 null/数组/标量改为空对象。
- trace 从持久事实派生图/时间线；历史没有授权步骤或时间则 null/空集合，不补虚构节点。audit 按 id 倒序游标分页，最大200，detail 脱敏，不返回连接串、原始源配置、密码/API key。

### outbox 与目的地

- 新 publish_outbox 持久 bound_status、原始 payload、retry_count/累计尝试历史、due time、lease owner/token/到期和人工处理信息。领取事务读取每 release 最早 PENDING（包括尚未到期或已租用的前项），与 `OutboxPolicy.next` 一致，不先按 due time 过滤导致越序。
- 短事务领取、事务外发往目的地、短事务按 lease token CAS 确认；超时租约重启回收。旧租约响应不得覆盖后续领取结果。只有目的地真实确认才 SENT。
- 默认 PG inbox 用 eventId 唯一键幂等接收、保存安全 receipt；HTTP 可选且默认不外发，测试仅本地受控故障服务。PG 可靠收件箱不能写成智能体平台上架。
- 重试委托已批准 OutboxPolicy.failure，达到预算 DEAD + 告警/审计，不阻挡后继。OWNER replay 原事件 id/载荷，重置本轮预算，累计尝试与理由保留；非法资格隔离 DEAD，CORRUPTED_ALERT 载荷不含下载 URL。

## 契约差异与无需停工的处理

1. `ContractGenerator.formalize` 当前固定生成详设示例 `/onto/r/{spaceCode}/releases/{releaseId}/ops/{opCode}`；P4 contract 另要求 `/onto/s/{spaceCode}/ops/{opCode}?releaseId=...`。无需修改已批准 onto-contract：后端让两条正式固定版本路由进入同一网关，保留 formalize 的 `/r/` 输出，同时提供 contract 固定 `/s/` 路由，均严格校验路径空间与实际版本空间一致。
2. 详设示例 meta.version=`3`，contract 展示补充已固定 `v3`；按最新固定 contract 对外输出 `vN`，PG versionNo/preVersionNo 为正整数，不改正式设计。
3. 五门禁 publication 采用规范化结论；旧 `/checks` 保留 CONSISTENT/INCONSISTENT 原语义，避免前端与历史回归破坏。
4. 尚无必须向用户重新确认的业务契约问题。上述恢复文件目录/元快照处理属于实现核对点，实施时结合 P3 最终源码确定，不扩大外部数据源验收范围。

## 实际已运行与剩余验收清单

下列勾选只表示本实现者已运行对应范围；独立审查和root进程/浏览器证据分别列明，不相互代替。日志与具体数量见本报告首节。

- [x] 真PG/MinIO冻结预分配、上传失败FAILED/VOID、不复用序号、无半release；实际bundle损坏拒绝不回退PG；旧候选字节/SHA兼容。
- [x] 正数完成test_run与真实候选摘要绑定；零用例明确false；五门禁领域14项及独立1851断言核保持不变。新CONTRACT失败不推进TESTING。
- [x] prepare上传失败停REVIEWING、复用原Lucene确切计划字节重试；4制品均实对象复核、正式预分配四元组和固定路由。
- [x] 测试后改稿CAS拒绝；真并发commit单release/单事件；迟到prepare不得回退COMMITTED；候选COMMITTED/CANCELLED保护与重复commit幂等。
- [x] 全域投影/稳定key与diff；真实全迁移PG全域restore、新rev、归档原元快照字段、同id冲突/坏FK原子回滚、旧执行证据可查；实际损坏bundle拒restore。
- [x] 默认拒绝端点授予、NULL不收窄/端点A收窄、无空间授权即拒、显式VIEWER正式消费而内部试执行拒；API key只hash/一次明文、撤销、EXTERNAL实际RELEASE事实。
- [x] 真P3执行链A/B/NULL标签、空间/策略/端点/源范围求交、无NULL泄漏；root已独立补真实文件HTTP与到期/跨空间验收，见最终节。
- [x] 合法对象/数组/null/布尔经冻结Schema与正式网关；成功真实meta/header、拒绝无成功meta/header。
- [x] 详设五事件真实PG/MinIO/relay场景，历史boundStatus、顺序屏障、DEAD解阻、弃用拒拉、真实损坏告警。
- [x] 独立outbox37项：并发领取、租约回收/旧确认CAS、未来PENDING屏障、重放原id+原payload、inbox去重、本机HTTP故障/截止/有限DEAD与告警。
- [x] 四制品实检路径区分不可达与真实损坏，无重复告警；mock只注入网络边界，物理损坏用例真实改自身MinIO对象。root已独立完成真实停止存储/恢复，见最终节。
- [x] 持久trace六阶段授权、P3四证据与RULE_VER/SERVICE_VER真实定义摘要、空间拒绝、历史缺值不编造、审计游标/脱敏。
- [x] 全后端独立Spec/Quality APPROVE，原终态/历史及增量15探针全部GREEN，见c7-publication-review/c7-history-review；属于reviewer无IO运行。
- [x] 新增P4GateTest最终真PG：逆/对称完整公理、默认阻断、非OWNER拒绝、OWNER同SHA/当前rev豁免后prepare/commit；P4AtomicityTest事件追加失败五写全回滚，已实际7项GREEN。
- [x] 新增P4WhitelistTest正式TOKEN/EXTERNAL源access_scope负例，合法候选经标准门禁发布，超白名单403且零源连接，已实际1项GREEN。
- [x] 最终完整根verify 362项全GREEN，0失败/错误/跳过，14模块SUCCESS；最终JAR SHA66F0E72C…71291FC与root真实验收副本一致。原342及361失败日志完整保留；P2历史format2、P4严格FAILED/VOID和V11原不可写断言均保留。仅本次机器汇总见c6-final-verify-summary.json。
- [x] root独立完成统一真实HTTP/浏览器/跨进程停投重启/真实MinIO断连恢复及P1/P2/P3/P4/正式数据持久化，证据见最终节；本地收件箱通过不能标外部智能体上架通过。

## 实施窗口与运行约束

root 授予写入窗口前不操作生产/测试/构建文件；授予后仅 onto-release、必要现有后端/root POM/追加下一迁移。onto-data、onto-contract、onto-artifact、web、deploy 和正式设计不改。所有权变更先与 root 同步。

Maven 为全工程单窗口；package 前通知 root 安全停止本工程 API，避免运行 JAR 锁。环境用 `Import-OntoEnvironment`（不输出秘密）、`Set-OntoJava`、`Set-OntoBuildLimits`，测试 DB URL 覆盖到 `jdbc:postgresql://127.0.0.1:15472/onto_test_p4`；JDK21/JVM512MiB、Unix-domain temp=`C:/Windows/Temp`。不 Docker、不 Git 提交、不操作邻工程资源；五类外部数据源验收按用户确认延后。
