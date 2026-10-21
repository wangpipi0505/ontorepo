# 独立审核发布链整改验证（2026-10-21）

本记录覆盖独立审核 C01、C02、C06，以及 SHACL 顶层错误分类和 prepare 编译/制品失败分类。C04/C05 由后续独立阶段处理；C07 所谓 ELK 配置全局共享经实际依赖核验不成立，本阶段不增加全局同步。

## 实现边界

- C01：新增 `FrozenGuardBindings`，冻结契约预览和实例执行共用逐资源、逐字段过滤绑定核验。实例操作仅有主体授权规则时不能编译通过；JOIN 两侧必须各有明确过滤绑定，绑定列必须属于冻结元数据。纯模型的 `PRINCIPAL_ATTRIBUTE` 规则仍保持合法。实例执行的当前主体授权、范围交集及默认拒绝未分类数据仍逐次复核。
- C02：V12 追加运行进度字段 `freeze_deadline`，历史 FREEZING 按创建时刻加 15 分钟迁移；新冻结默认 15 分钟（可配置 1 秒至 1 小时）。每 30 秒最多扫描 20 条，使用数据库时钟、空间优先的锁顺序和 `SKIP LOCKED`；恢复只把过期 FREEZING 原子改为 `FAILED/FREEZE_INTERRUPTED/VOID` 并追加系统审计。人工 REVIEWING 和上传续传计划不回收。迟到成功在写回时再次检查期限，即使调度器未运行也不得复活。已分配版本号不回用。
- C06：validate 允许 VALIDATING/TESTING/可重试 FAILED；tests 保留本地兼容需要的 VALIDATING/TESTING/REVIEWING/可重试 FAILED；review 仅允许 REVIEWING；prepare 允许 REVIEWING 与 READY_TO_COMMIT 的制品复核重试，状态不倒退。终态、VOID、OWNER 和当前修订约束继续有效。锁外检查、编译和上传后再次锁内检查阶段，旧结果不能覆盖后续阶段。
- SHACL：真实 SHACL 引擎异常在初期报告及正式发布报告顶层均分类为 `SHACL_ENGINE_ERROR`，不再被 `MODEL_CONFLICT` 遮蔽，不增加豁免资格。
- prepare：纯编译边界未知运行异常转为安全的 `ARTIFACT_COMPILE_FAILED`；已有明确业务异常保留原分类。对象存储不可达仍是 503，摘要/内容等制品异常为 409，均不向客户端返回内部异常文本。

## 测试与命令

运行环境使用本工程 `deploy/Environment.ps1` 的 `Import-OntoEnvironment`，JDK 21，Maven 与测试 JVM 均 512 MiB，Java 临时目录使用工程既有 ASCII 映射。测试使用独立 PostgreSQL `onto_test` 及本工程 MinIO，未重启现有 API/前端，未覆盖历史 HTTP 验收证据。

首轮 RED：

```powershell
mvn.cmd -B -pl onto-designer-api -am '-Dtest=P4AuditReleaseTest,P3AuditGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" test
```

09:32 完成：6 项，6 预期失败，0 错误，0 跳过。实际复现：实例无/缺资源绑定仍冻结 VALIDATING、没有崩溃恢复入口、READY 被 validate 回退 TESTING、编译失败落入 INTERNAL_ERROR、SHACL 异常顶层误分类。日志 `.runtime/c8-release-red.log`。之后补充真实已提交预留后的突发中断恢复、迟到成功超期作废、长校验写回期间被后续阶段推进的竞争测试。

GREEN 与相关扩大回归：

```powershell
mvn.cmd -B -pl onto-designer-api -am '-Dtest=P4AuditReleaseTest,P3AuditGuardTest,P4FailureTest,P4ConcurrencyTest,P4GateTest,P4PublicationTest,P4CompilationTest,P3MappingTest,DataAuditRegressionTest,ParquetIntegrationTest,FileIntegrityAuditTest,IamBoundaryTest,IamManagementTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" test
```

09:46:54 完成，耗时 05:06，14 模块 SUCCESS，59 项全部通过，失败/错误/跳过均为 0（onto-data 2、API 57）。日志 `.runtime/c8-combined-green.log`。其中发布/守卫新增回归 9 项、既有真实映射 14 项、既有发布故障/并发/门禁/编译/发布 18 项；同批还验证数据专项 9 项与当时 IAM 专项 9 项。V12/V13 在真实测试库从 V11 实际迁移成功。

新断言包含：规则-only 实例契约冻结失败，TRIAL/SERVICE 都返回守卫缺绑定，LOCAL_TEST 拒绝且没有成功执行；READY 三种前置阶段接口都拒绝且报告/制品完全不变；过期恢复有界、幂等并保留人工 REVIEWING；真实预留事务后的未捕获中断可以恢复；存储迟到成功在调度器前也被作废；长校验结果不得覆盖已推进的 READY；SHACL 两套报告分类一致；纯编译失败安全分类且可重试成功。既有故障回归继续证明取消不被冻结补偿覆盖、prepare 迟到不能覆盖 commit、上传失败按原物理计划续传和版本不回用。

本结论只覆盖本文件列明修复和该批快照；后续 C04/C05、IAM 后续补充修复、全量验证及运行服务重启仍需另行记录，不将本批定向通过当作整个独立审核全部关闭。

## 范围与限制

- 中断测试在真实预留事务提交之后注入未捕获 `Error`，复现进程退出留下 FREEZING 的持久边界；没有强杀正在服务用户的 API 进程。
- 过期判断受数据库时钟影响；应用节点时钟漂移不参与期限计算。
- 定时恢复是有界后续扫描，不承诺在精确截止瞬间完成；活跃空间正在写入时跳过并留待下一批。
- 本轮不改变已冻结载荷/摘要，不迁移或重写已发布制品，不部署外部测试源。

## 浏览器联调发现的阶段衔接补充修复

首次完整 `verify` 在浏览器联调发现新问题后主动中止，原日志保留为 `.runtime/c8-full-verify-interrupted.log`，独立汇总 `c8-full-verify-interrupted-summary.json` 明确标记 `INTERRUPTED`；其中 234 项已完成且无失败，不是完整通过结论。停止范围仅为该轮 Maven/Surefire，不涉及 API、前端、PostgreSQL 或 MinIO。

新问题：允许在初始 VALIDATING 阶段执行本地测试，但原 `tests` 将所有通过结果无条件推进 REVIEWING；机器检查尚未执行时，随后 `validate` 的正确阶段保护会拒绝该候选。另有真实机器失败或无助手的契约不可用结果，会被 TEST 通过清除失败状态。

新增 3 个真实 PostgreSQL 回归分支，先观察 RED：合法助手契约先 tests 后仍应继续 validate；无助手本地测试可以通过但 CONTRACT_NOT_AVAILABLE 仍阻断发布；SHACL 已失败不能被通过的 TEST 掩盖。`.runtime/c8-publication-order-red.log` 于 10:24:30 完成，29.866 秒，3 项预期失败，0 错误/跳过，三项实际状态都错误变成 REVIEWING。

修复只调整 TEST 写回后的阶段判据，不放宽 validate/review/prepare 的来源限制。TEST 失败仍为 FAILED；TEST 通过后在最后的空间/候选锁内读取同摘要最新 CONFLICT、CONSISTENCY、CONTRACT：真实失败优先为 FAILED/MACHINE_GATE_BLOCKED，其次缺检查为 VALIDATING，三项齐全通过才进入 REVIEWING。CONSISTENCY 的唯一 `NOT_CHECKED/SUBSET_EXCEEDED` 可以进入待人工资格阶段，但报告仍为 NOT_CHECKED，review/prepare 原有 OWNER、当前修订、摘要绑定豁免门禁保持有效。新实现未增加任何发布豁免类型。

对应 GREEN：

```powershell
mvn.cmd -B -pl onto-designer-api -am '-Dtest=ContractTest,P4AuditReleaseTest,P4GateTest,P4ConcurrencyTest,P4PublicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" test
```

`.runtime/c8-publication-order-green.log` 于 10:27:15 完成，30 项全部通过，失败/错误/跳过均为 0：ContractTest 13（包含样例4全字段映射补断言）、P4AuditReleaseTest 10、P4GateTest 2、P4ConcurrencyTest 2、P4PublicationTest 3。独立复核已核对最终日志、锁内阶段判据、NON-EL 豁免和正式 §8.4，一致且无剩余阻断，见 `c8-backend-review.md` 的 BR-02。重新完整构建继续单独记录，不沿用中断批次的部分结果。

重新完整 `verify` 已于 10:48:27 成功，14 模块、407 项全部通过，最终 JAR SHA-256 为 `63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`。本文件全部修复均包含在该快照，详见 `c8-full-verify.md` / `c8-full-verify-summary.json`；运行联调继续由最终集成记录确认。
