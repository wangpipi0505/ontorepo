# C04/C05 执行并发与历史读取整改（2026-10-21）

## 设计约束

直接候选 TRIAL 将源查询移出草稿行锁事务，同时通过独立空间授权 gate 保持撤权和归档的顺序。查询持 PostgreSQL session 共享 advisory lock；授予变化/归档在既有事务起点持排他 xact advisory lock，再取得草稿行锁。普通草稿修改不参加授权 gate。LOCAL_TEST、候选 SERVICE、正式服务及已有事务内调用的 trial 继续原有完整事务边界。

共享 gate 期间绑定一条自动提交的 JDBC 连接，开始校验和结果/步骤/证据/审计落库的短事务均复用该连接，避免四并发调用持满四连接池后又申请额外连接的死锁。finally 负责解锁、解绑和归还；解锁失败应物理关闭连接，不能把持 session 锁的连接归还池。

执行历史提取到 `ExecutionFacts`，最近 50 条采用稳定的 `(created_at DESC,id DESC)` 顺序。事实、步骤、证据分别批量读取，只需 3 次查询；空列表只读事实一次。不改变响应结构和空间授权。V14 只追加实际缺失的空间/执行子表索引，不重复已有主键、唯一约束或证据索引。

## RED 证据

使用现有独立 `onto_test`、MinIO、JDK21 和 512 MiB 单 Maven 窗口：

```powershell
mvn.cmd -B -pl onto-designer-api -am '-Dtest=ExecutionAuditTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" test
```

首次共 6 项：3 项预期失败、3 项通过、0 错误/跳过。失败分别为：查询等待期间普通草稿修改被锁阻塞；四连接池的四个试执行不能同时进入源边界；50 条历史产生 151 次查询而非 3 次。撤权/归档顺序及致命异常清理在旧事务实现下通过，是新实现必须保留的行为。日志 `.runtime/c8-execution-red.log`，50.694 秒。

## 实现与首轮 GREEN

新增 `SpaceAuthorizationGate` 统一封装共享 session 锁与排他事务锁，后者只允许在真实事务内调用。共享 gate 绑定连接，短事务采用已有 `TransactionTemplate`；结束时先解绑，且无论解绑是否异常都尝试清理 session 锁，解锁失败执行 JDBC `abort`，不会正常归还仍持锁的会话。`GuardService.saveGrant` 和 `SpaceService.archive` 在取得草稿行锁之前调用排他 gate。

`CandidateService.trial` 的直接调用采用共享 gate，来源授权和活动状态在短事务内复核，源执行在自动提交状态下完成，事实、证据、步骤及审计在第二个短事务内原子写入。原有事务内调用保持旧逻辑，SERVICE/LOCAL_TEST 的事务语义未放宽。`ExecutionFacts` 接管单条及列表事实读取，避免 CandidateService 同时承担逐行组装历史查询。

首次 GREEN 构建发现三个手工构造测试夹具需同步新增依赖，已只调整构造注入。随后使用同一命令复跑：`.runtime/c8-execution-green-r2.log`，10:06:49 完成，耗时 01:08，14 模块 SUCCESS，6 项全部通过，失败/错误/跳过均为 0。V14 在真实 PostgreSQL 中从 V13 实际迁移成功。四并发用例在上限为 4 的真实连接池中完成，并在结束后再次同时借出 4 条连接，确认没有额外借用或连接遗留。

扩大回归另补“最终审计写入失败时事实/步骤/证据整组回滚，随后同线程可成功重试”的边界断言：

```powershell
mvn.cmd -B -pl onto-designer-api -am '-Dtest=ExecutionAuditTest,WorkflowTest,P3MappingTest,P3AuditGuardTest,P4ConcurrencyTest,P4FailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" test
```

10:12:32 完成，耗时 04:45，14 模块 SUCCESS，48 项全部通过，失败/错误/跳过均为 0：ExecutionAudit 7、Workflow 14、P3Mapping 14、P3AuditGuard 2、P4Concurrency 2、P4Failure 9。日志 `.runtime/c8-execution-regression.log`。已确认旧候选不可变重放、原三步骤形状、空间并发编辑、归档拒绝、审计回滚和发布重试/迟到回写边界继续通过。

## 适用范围

本轮只解除直接候选 TRIAL 的长草稿事务，LOCAL_TEST、候选 SERVICE 和正式服务仍保留既有事务安全语义。授权 gate 使用数据库会话锁，连接中断后由 PostgreSQL 释放；没有改用进程内互斥或缓存授权。最近 50 条读取查询数已经有真实数据库断言，但没有将该用例当作生产大规模吞吐基准。全量验证、独立复核和最终运行包由后续统一验收记录。

最终补录：独立复核见 `c8-backend-review.md`，本范围无剩余阻断。10:48:27 的完整 `verify` 14 模块、407 项全部通过（含 ExecutionAuditTest 7 项），详见 `c8-full-verify.md` / `c8-full-verify-summary.json`。最终 JAR SHA-256：`63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`；实际运行包联调由集成记录继续确认。
