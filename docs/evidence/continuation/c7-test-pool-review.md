# C7 测试数据库连接池增量复核

时间：2026-10-21 02:44（UTC+8）。独立审查者：`c7_frontend_review`。**Spec：APPROVE；Quality：APPROVE。** 结论仅覆盖本次测试资源配置补丁，未发现阻断问题，不代表最终完整 verify 已通过。

本次只读审查 `BoundaryTest.java`、Spring 测试继承链、相关并发用例及作者已产生的日志；没有运行 Maven、PostgreSQL、MinIO、HTTP 或浏览器，没有修改生产代码或生产测试。审查指纹、类继承清单和生产文件比较结果保存在 `.runtime/c7-test-pool-review/static-evidence.json`。

| 检查项 | 独立核对结果 |
| --- | --- |
| 配置覆盖 | `BoundaryTest.java:22–29` 在测试专用 `@DynamicPropertySource` 中注册 `maximum-pool-size=4`、`minimum-idle=0`。`WorkflowFixture.java:38–41` 委托同一个方法；P3/P4 基类继续继承它。遍历现有测试源码确认 22 个具体 Spring 测试类（含 BoundaryTest）及 3 个共享基类均走此入口，没有子类覆盖连接池参数或其他独立 Spring 数据源上下文入口。Mock/Spy 形成不同缓存上下文仍使用该属性注册链。这里的类数量不等于缓存上下文实例数量。 |
| 并发资源 | `P4ConcurrencyTest.java:37、69、127` 同时需要持锁事务、等待请求、`pg_stat_activity` 诊断共 3 条连接，上限 4 留有一条余量。`WorkflowTest.java:293–329` 的四 worker 更新需要 4 条连接；主线程等待所有 Future 后才查询修订和审计，没有第五条同时占用的诊断连接。两种并发均被覆盖。`P4FailureTest` 的双提交及存储锁外迟到路径也未见额外连接等待环。 |
| 断言与边界 | 新增 `BoundaryTest.java:43–51` 解包实际 `HikariDataSource` 检查上限与最小空闲值，并非只检查配置字符串。旧匿名访问 401/UNAUTHORIZED 断言仍完整；真实 PG 环境变量缺失仍直接失败。并发用例的 403/409、唯一提交/审计、锁等待及原超时断言保留。本补丁没有提高 PostgreSQL 上限、增加超时或放宽业务成功条件。最终中文注释已同时说明 P4 三连接与 Workflow 四 worker 的区别。 |
| 作用范围 | 两项配置只在 `src/test` 注册，生产 `application.properties` 没有加入连接池覆盖。onto-release 的直接 `PGSimpleDataSource`、onto-data 的 `DriverManager` 测试不属于缓存 Spring/Hikari 上下文，本结论不把它们描述为继承该配置。 |

## 现有执行证据

- 原失败日志 `.runtime/c6-final-isolation-verify.log` 保留：9 个 Hikari 池先完成启动，第 10 个启动出现 PostgreSQL `53300 / too many clients already`；P4Scope 的源查询 400 与 P4Whitelist 上下文失败未被改写成通过。该轮总计 361 项、1 failure、1 error 为作者汇总，API 模块日志明确记录 172 项、1 failure、1 error。
- `.runtime/c6-pg-budget-diagnostic.log` 记录 `max_connections=100`、运行 API 的 `onto_dev idle 10`；`.runtime/c6-pool-budget-red.log` 于 02:39:40 记录新增资源断言真实失败，实际最大值/最小空闲值均为 10。
- 已直接读取 `.runtime/c6-pool-budget-green.log`：02:40:46 `BUILD SUCCESS`，Boundary 2、P4Concurrency 2、P4Scope 1、P4Whitelist 1，共 6 项，0 failure/error/skipped。这是作者的真实数据库执行证据，本审查没有独立重跑。完整 verify 重试仍须另行记录最终结果。

## 生产未变的证据边界

作者声明此次语义变更仅在 `BoundaryTest.java`。独立比较前次保存的 `reviewed-files-postcancel.json`：其中 8 个生产 Java 文件及 `onto-trace/pom.xml`，9/9 SHA-256 均未变化。当前 API JAR 的 SHA-256 仍为 `66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC`，与本补丁前已验包一致。本审查没有补丁前完整生产树快照，因此不会把局部源码指纹比较表述成全树逐文件证明。

最终 `BoundaryTest.java`（02:44:09 注释修正后）SHA-256：`56A3EF093D20707C64AF66F0C3195B6C50CBFC1B45AA88180489726F393DBF79`。
