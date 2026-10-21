# C6 / P4 独立 outbox 运行期组件审查

审查时间：2026-10-21 00:43（Asia/Shanghai）。

## 结论

- **Spec：PASS，仅限本批独立 outbox 持久化、relay 与真实目的地组件。** 历史创建资格、同 release 的 PENDING 屏障、SENT/DEAD 解阻、租约 CAS、有限重试、持久 DEAD 告警、原事件重放及实际收件箱确认符合当前组件契约。
- **Quality：PASS，仅限下列 6 个新增生产类、3 个新增测试文件和模块 POM。** 没有发现有证据、需要修改的 P0/P1/P2/P3 缺陷。独立重新编译后，JUnit Platform 复跑 **37/37 成功，0 失败、0 中止、0 跳过**；额外独立探针 **39 个断言通过**。
- **C6 / P4 整期：未验收。** 当前根 reactor 尚未包含 onto-release，未实施应用 Flyway 迁移、Spring 调度/默认目的地接线、发布事务或管理 API；本地 PG 收件箱通过也不代表智能体平台正式上架通过。

## 依据与范围

已读取工程 `AGENTS.md`、`contracts/p4-release.md`、`docs/evidence/continuation/c6-backend-report.md` 本轮“独立 outbox 运行期实施与验证”章节、`docs/evidence/continuation/c6-policy-review.md`。既有 `ReleaseGatePolicy` / `OutboxPolicy` 与已批准审查结论作为权威，不重新设计规则。

本轮静态审查及独立编译覆盖：

| 文件 | SHA-256 |
|---|---|
| `onto-release/src/main/java/cn/ontorepo/release/application/OutboxRelay.java` | `F6675A4EBCA33208EE0719E202A714096E474E33B6F9A4C0587345E9229B772A` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/JdbcOutboxStore.java` | `AF745AC90E1D1E11C1F21228950B2CBB0615DEF920F65A84301250DE162AA926` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/PgInboxDestination.java` | `DE5BFD6AFE43D982A95002FE146CB454E9073D8F03351044DD8BA1EB0FB1D03B` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/HttpOutboxDestination.java` | `DA361DC92D18B20014CF3AB287BA8E554D8F9DAB3B27F0C3294C975E13D929B9` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/OutboxDestination.java` | `BABD91E04A9A0FEF67757EA606F918D9B4F876A62F73CBF2345E602312B2979E` |
| `onto-release/src/main/java/cn/ontorepo/release/infra/OutboxProblem.java` | `1D8FE98B40B5BE797BD9B7649F3453FB20AE3BD1439242BC2D594F77D6EE5CAB` |
| `onto-release/src/test/java/cn/ontorepo/release/infra/OutboxDatabase.java` | `7B344E61DF3FF0A17A3BF30F29D9962DD0EF80EE7529A8D3069EBA53B044661F` |
| `onto-release/src/test/java/cn/ontorepo/release/infra/JdbcOutboxStoreTest.java` | `F1022162E0A0ED0E8200DA8EA5391FFB9937C28E40B68A69599B64705FE218E3` |
| `onto-release/src/test/java/cn/ontorepo/release/infra/OutboxRelayTest.java` | `D2502E7AEAC52DB4B53EA4CC600E99863206B627CA23E74902A1D8995D309B8F` |
| `onto-release/pom.xml` | `5DD3D0B2201666AFBBC6847C6F3CA74C9DF55176EFAF138BEAB7D80CB6418D33` |

两个既有生产 policy 摘要仍为批准值：`ReleaseGatePolicy=F812D5EAA6F62925A18CB87F257B757B8ABE5B019A68C0EEE16857A4DF3AD8BA`、`OutboxPolicy=7ED80590AE3F7FD43D50822655BA97C94C9D342443F678F4A3A0E1AC89495C49`。两个原测试文件摘要也分别保持 `B0B9FFB7A8C3D1D439C66E6E3B3B715F664F32A13B883BE18DF61E70A5692425`、`473D6E7AF94F6DE858C5FEEB07F69B2588189B929D74583EF81B8B8C0C6EC2A2`；原 9+5 条全部实际复跑。

## Spec 核对

| 不变量 | 实现证据 | 审查结果 |
|---|---|---|
| 创建状态绑定，而非当前 release 状态 | `JdbcOutboxStore.java:58` / `:140`，`OutboxPolicy.next` | append 与 claim 使用持久 bound_status；领取没有当前 release 状态查询。先 RELEASED 再弃用仍先领取 RELEASED 的真实 PG 测试通过。创建事务中真实生命周期状态/空间匹配仍由后续应用保证。 |
| 每 release 最早 PENDING 屏障 | `JdbcOutboxStore.java:128` / `:325` | NOT EXISTS 查询全部前序 PENDING，没有按前序到期时间或租约过滤；未到期和有效租约前项均阻塞后项。不同 release 仍可推进。锁后第二语句复查前项，配合 replay 锁组避免旧快照越序。 |
| SENT / DEAD 解阻 | `JdbcOutboxStore.java:130` | 屏障只包含 PENDING；确认后后继可达；弃用耗尽重试后 CORRUPTED_ALERT 可达。非法 bound_status 隔离为 DEAD 并持久告警，未向目的地发出。 |
| 原子领取与旧租约 CAS | `JdbcOutboxStore.java:133` / `:218` / `:238` | FOR UPDATE SKIP LOCKED、防同事件双领取；ack/fail 均匹配原 token、PENDING 和未到期时间。独立补测恰好到期的 ack/fail 均拒绝，旧 token 不能确认重放租约。 |
| 崩溃也消耗有限预算 | `JdbcOutboxStore.java:141` / `:150` | 租约超时先完成旧 attempt，按既有 policy.failure 记失败；按原到期时刻算退避，重建 store 不会无限续领。独立补测 maxAttempts=1 在到期回收时即 DEAD，并同事务产生告警。 |
| 网络在领取事务之外、避免提前租用整批 | `OutboxRelay.java:25` / `:29` / `:41` | 逐项 claim(1) 已提交才调用目的地，再独立 CAS；真实 HTTP 回调可 NOWAIT 锁同 outbox 行。第二项不会在第一项网络等待期间消耗租约。 |
| 真实目的地确认才 SENT | `PgInboxDestination.java:29` / `:43` / `:54`，`HttpOutboxDestination.java:76` | PG inbox 提交后返回；重复 eventId 核对 release/type/payload 摘要。HTTP 要求 2xx、accepted=true、可转 long 的整数 eventId 精确匹配，HTTP 200 本身不算确认。确认丢失重试后同 id 仅一条 PG 收件事实。 |
| HTTP 总 deadline 与响应体预算 | `HttpOutboxDestination.java:63` / `:66` / `:88` | 完整 body future 有总等待时限，超时取消；累计超过 4096 字节时取消订阅并失败。独立本机 HTTP 探针验证大响应拒绝、头到达后停滞仍按 deadline 返回、302 不跟随目标。 |
| 安全错误与 receipt | `OutboxRelay.java:37`，`JdbcOutboxStore.java:238`，`PgInboxDestination.java:29` / `:60` | 原始异常统一分类；last_error 只落安全码。实际 receipt 仅 accepted/payloadSha256，不复制拉取地址。独立补测 PG 缺表故障返回 INBOX_STORAGE_UNAVAILABLE；既有敏感网络异常脱敏测试也通过。 |
| DEAD、重放与历史 | `JdbcOutboxStore.java:272` / `:385` / `:399` | DEAD 与 alert 同事务；replay 保留原 id/payload/attempt_count，只重置本轮 retry_count，解决原告警；再次 DEAD 重新打开同 eventId 告警。独立补测在途后继时拒绝重放，后继完成后重放成功且 attemptNo 连续。 |
| 告警载荷与追加幂等 | `JdbcOutboxStore.java:82` / `:98` / `:421` | 相同 release/type 的重复追加只接受相同持久载荷，不覆写异载荷；CORRUPTED_ALERT 中嵌套 HTTP/S3 拉取地址拒绝。append/replay 要求调用者事务，回滚不遗留事件。 |

## Quality 与缺陷

**可报告缺陷：0（P0=0，P1=0，P2=0，P3=0）。**

模块 POM 只新增外部 Jackson 和测试范围 PostgreSQL/JUnit，没有引入相邻工程依赖。规则仍委托既有 policy；持久存储、真实目的地与 relay 边界清楚，没有内存业务替身或打印日志充当成功接收。已核对异常脱敏、连接/事务关闭、有限批次、HTTP 取消、幂等冲突及跨次累计尝试。当前证据没有支持要求额外重构或增加抽象。

没有把未集成范围列成该组件批次的实现缺陷，也没有用此范围豁免已实现路径的错误。

## 独立验证与可复跑证据

作者日志 `.runtime/c6-outbox-formatted-green.log` 显示 37 条成功；本次没有以作者日志代替独立执行。

独立脚本：`.runtime/c6-outbox-review/run.ps1`。从本工程 `deploy/Environment.ps1` 静默导入环境并明确覆盖测试 URL 到 `jdbc:postgresql://127.0.0.1:15472/onto_test_p4`；使用通用 JDK 21、JVM 512 MiB、ASCII Unix-domain temp。脚本直接 javac 当前生产/测试源码到 **`.runtime/c6-outbox-review/classes`**，没有调用 Maven，也没有读写任何模块 target。

仅对夹具副本进行一项隔离替换：schema 前缀 `p4_outbox_t_` 改为 **`c6r_` + 随机 UUID**；DDL、测试断言及产品源文件原样编译。JUnit Platform 1.12.2 / Jupiter 5.12.2 实际执行参数化测试和生命周期，不以手工调用普通方法代替框架。

```powershell
& ./.runtime/c6-outbox-review/run.ps1 *> .runtime/c6-outbox-review/independent.log
```

最终日志 `.runtime/c6-outbox-review/independent.log`：

```text
Test run finished after 69026 ms
37 tests found / 37 started / 37 successful
0 tests skipped / 0 aborted / 0 failed
PROBE PASS assertions=39; own PG schema cleaned; loopback HTTP stopped
```

分组为既有领域 14、PG store 13、relay 10。独立探针 `.runtime/c6-outbox-review/ExtraProbe.java` 的 39 个断言覆盖：

- 恰好到期 ack/fail 拒绝、最终崩溃预算耗尽与持久告警、DEAD 解阻；在途后继时重放拒绝，后继完成后原 id/载荷/累计 attempt 保留，旧 token 不得确认新租约。
- 实际 PG inbox 重复接收只有一条 receipt，安全摘要存在；注入缺表时只传播安全错误码。
- HTTP accepted 精确布尔及 eventId 整数/范围/原身份核验：拒绝小数、字符串、错 id、false、long 越界和大响应；每次均核对 Idempotency-Key 与嵌套原 payload；302 不跟随；响应头后 body 停滞仍受总 deadline 控制。

审查 harness 首次编译因未列入 junit-jupiter-params 依赖而失败，补齐独立 classpath 后才取得上述结果；该轮不是产品测试失败，也未计作成功证据。

资源复核 `.runtime/c6-outbox-review/cleanup.log`：`CLEANUP remaining_c6r_schemas=0`。所有自有 HTTP server 已停止，额外 HTTP client 均关闭。仅访问专属测试库和 127.0.0.1 随机端口，未访问开发业务数据、相邻工程或外部平台；没有输出秘密、启停产品服务、Git 提交或 Maven 构建。临时探针和日志保留在忽略的 `.runtime` 中供复查。

## 明确保留的 C6 / P4 接入与验收范围

1. **真实迁移与启动接线未验。** 当前测试自行创建组件边界 DDL，不能替代应用 Flyway、正式约束/索引、根 reactor、默认 PG 目的地与 Spring 调度/HTTP client 销毁接线验收。
2. **生命周期和管理安全未接入。** append/replay 接受应用事务连接，不自行证明 OWNER/空间授权、重放原因及审计，也不证明 release 状态与 bound_status 的业务写入原子性。当前回滚测试只证明 outbox 参加调用者事务；正式发布五写、弃用/完整性状态与 outbox/审计原子性仍须应用集成测试。
3. **管理 API 尚未验。** GET events / inbox 的空间隔离、安全字段、receipt 展示、人工处理状态，以及 replay 的真实身份/原因与统一错误信封，均不能从本组件测试推定通过。
4. **§7.1 全闭环与真实进程重启未验。** 当前有真实 PG 持久恢复、仓储对象重建和租约回收证据；未执行产品进程停机/重启，也没有证明“停机期间发布后弃用 → 先收 RELEASED → 消费者拉取被拒 → 再收 DEPRECATED”的应用端到端链路。
5. **P4 其他出口仍未在本次覆盖。** 冻结/五门禁/制品准备与提交、READY 消费拒绝、凭据/端点授权、完整性扫描、历史恢复及 trace 等需要各自集成验收；本批结论不覆盖它们。外部智能体平台联调也未运行。

本轮只新增本审查报告与忽略目录中的独立 harness/探针/日志，没有修改受审生产或正式测试文件、根 POM、迁移和其他业务模块。
