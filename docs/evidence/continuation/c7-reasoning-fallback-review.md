# C7 OM-D6 推理回退独立审查

审查时间：2026-10-21 02:08；取消残留增量复核于 02:18 完成（UTC+8）。审查者：`c7_frontend_review`，独立于本次实现者。生产代码、正式测试及合同均只读；仅在 `.runtime/c7-reasoning-fallback-review/` 编译当前源码、执行自有内存探针并保存证据。

**当前结论：Spec APPROVE；Quality APPROVE。** 作者全量回归发现的 ELK 取消残留曾于 02:12 导致本报告撤回批准，现已以独立 RED→GREEN 关闭，详见末节。当前范围没有未关闭问题；原 15 项加新增 5 组取消/恢复验证均通过。本结论不替代整个发布系统的最终全量验收。

## 需求及审查版本

依据用户明确决策、详设 v2.7 §8.11、开发计划 v1.7 B-04/OM-D6 增量验收和 `contracts/p4-release.md:20`、`:71`：先执行 ELK；只有完整模型通过 OWL 2 EL profile 且 ELK 明确报告不完整，才允许 HermiT 检查同一公理集的一致性和不可满足命名类。两阶段共享总时限；不一致、异常和超时阻断。非 EL 保留原 OWNER 显式豁免边界。

主要审查文件：

- `onto-trace/.../ConsistencyChecker.java`、`ReasoningBudget.java`、`onto-trace/pom.xml`。
- `ConsistencyFallbackTest.java`、`P4DatatypeReleaseTest.java`。
- `PublicationService.java:170` 的原始一致性结论归一化、`ApiDtos.java:145` 的 caller 传输增量。
- 关联读取 `RdfCodec`、`CandidateCheckService`、`ReleaseGatePolicy`、`CandidateService`、`Transport`、`CandidateController` 及 P4Gate/P4Gateway 回归。

本次工程尚未提交，使用当前文件 SHA-256 及修改时间固定审查对象，见 `.runtime/c7-reasoning-fallback-review/reviewed-files.json`（12 个文件）。其中 ConsistencyChecker 最后修改于 02:03:56，ReasoningBudget 于 01:58:40；结束前重新检查指纹均未变化。

## 独立验证

自有 `ReviewFallbackProbe.java` 使用 Java 21、`-Xmx512m`，将当前 ConsistencyChecker、ReasoningBudget、RdfCodec、ReleaseGatePolicy 源码编译到独立目录，优先于既有 target/classes 加载。依赖取自作者最新 Surefire classpath；没有运行 Maven、Spring、数据库、对象存储或 HTTP 服务。

**15 项 PASS，0 FAIL**，证据为 `compile.log`、`probe-utf8.log`。具体覆盖：

1. 纯类模型由 ELK 完成，不触发 HermiT；STRING/INTEGER/DECIMAL 均由真实 ELK 不完整信号进入真实 HermiT，结论及两引擎信息正确。
2. 在传入 HermiT 的 OWLOntology 中实际检查数据断言、domain、range、disjoint 和 annotation 公理仍存在。通过数据属性 domain 推导出的实例冲突被拒绝；原字符串值与 integer 范围冲突被拒绝。
3. 未实例化、经父类不相交关系间接产生的不可满足命名类被列出并阻断，不能仅凭整体一致而通过。
4. 非 EL 默认阻断，只有绑定同摘要/修订的 OWNER 豁免可通过实际 ReleaseGatePolicy。imports 在加载前阻断；解析异常、完整引擎普通异常、零预算均失败，OWNER 不能覆盖。
5. 注入真实 HermiT 已创建但 factory 尚未返回的晚到情形：总时限内返回 FAILED/TIMEOUT，释放门闩后恰好 dispose 一次，且不再执行一致性/分类查询。
6. 注入真实引擎 dispose 延迟：即使两项推理已完成，耗尽总预算仍返回 FAILED/TIMEOUT，不返回迟到 CONSISTENT。
7. 实际 classloader 只发现一份 `OWLOntology.class`，来自 OWLAPI 5.1.20；实际 ELK 为 0.6.0、HermiT 为 1.4.5.519。POM 排除旧 owlapi-distribution 后，本探针未发现重复 OWLAPI 类或链接错误。

复现命令（仓库根目录，参数文件保留在自有目录）：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/javac.exe' -J-Xmx512m '@.runtime/c7-reasoning-fallback-review/javac.args'
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe' '@.runtime/c7-reasoning-fallback-review/java-utf8.args'
```

静态核对显示，ELK 的一致性和 bottom-class 两个不完整分支才进入 `complete()`；普通异常直接 FAILED，OWL profile 失败直接 NOT_CHECKED。共享预算覆盖图构建、两个引擎和返回前检查，取消会中断已挂载引擎，晚到引擎在 attach 时清理。两个引擎、ontology manager 和 Jena model 均有释放路径。

PublicationService 仅把 CONSISTENT 映射 PASSED，把 NOT_CHECKED 保持原值，INCONSISTENT/FAILED 均映射 FAILED，同时保留原始 report。CandidateCheckService 的实际重检和 ReleaseGatePolicy 仍共同限定 SUBSET_EXCEEDED 豁免。caller 来自持久化 op_execution 字段，经新增 DTO 字段返回；该增量没有以当前请求入口推测历史 caller。

## 作者证据与本次边界

只读检查了作者日志及对应测试，以下均为**作者执行证据**：

- `c6-fallback-publish-green.log`：02:03:06，12 项通过，其中纯推理 9 项、真实 PG/MinIO 的三种数据属性完整发布 3 项。
- `c6-fallback-package.log`：02:04:10，在增加返回前总预算检查后纯推理 9 项再次通过并成功打包。
- `c6-http-contract-green.log`：01:54:22，P4Gate 2 项及 P4Gateway 6 项通过，覆盖真实持久化的 FAILED 门禁不可豁免和执行列表 caller。

本审查没有独立执行 HTTP/浏览器、PG/MinIO、根 reactor verify，也未作大规模性能或任意 OWL 构造穷举。作者已知的 `owl:Thing ⊑ owl:Nothing` 加数据属性触发固定版本 HermiT 异常，当前准确返回 FAILED/ENGINE_ERROR，并有真实回归；没有删除公理或将其当作一致。正式源白名单的作者 PG/MinIO MockMvc 证据不记为本审查或 root 的独立 HTTP 通过。

## 02:18 取消残留增量复核

**D6-C1 / P1：ELK 取消污染其他请求，已关闭。** 作者全量回归发现旧实现 `ReasoningBudget.attach/cancel` 调用 ELK 0.6 的 `interrupt()`，设置 `ReasonerFactory.DEFAULT_INTERRUPTER` 静态共享标记；后续正常检查会错误返回 TIMEOUT。原 15 项只覆盖 HermiT 取消及直接超时，没有覆盖 ELK 取消后的新请求，初次批准未覆盖该真实缺陷。

本审查保存了旧源码和旧编译类，并在独立 JVM 中获得四项真实 RED：

- `cancel-late-red.log`：真实 ELK 迟到挂载后，下一检查误报 TIMEOUT。
- `cancel-active-red.log`：已挂载真实 ELK 取消后，下一检查误报 TIMEOUT。
- `cancel-sequence-original.log`：实际 1ms 检查→异常输入→正常恢复，第一轮恢复失败。
- `cancel-running-original.log`：真实 ELK 已进入推理阶段，超时并完成 shutdown 后，下一检查仍误报 TIMEOUT。

修复后的 `ConsistencyChecker.java:125/:206` 显式传递引擎中断资格；`ReasoningBudget.java:32/:49` 对迟到实例只释放，不调用尚未执行查询的引擎 interrupt，ELK 取消使用本任务 Future 的线程中断，HermiT 保持实例中断。没有修改 ELK 的全局标记或以清除全局标记影响其他请求。中文注释解释了此依赖行为。

重新编译当前源码后：

| 验证 | 结果及证据 |
| --- | --- |
| 原 15 项公理、门禁、超时和 HermiT 生命周期探针，源码及断言未改 | 15 PASS，`probe-recheck.log` |
| 迟到挂载、已挂载取消 | 2 PASS，`cancel-late-green.log`、`cancel-active-green.log` |
| 连续限时/异常/恢复 | 60 轮 PASS，`cancel-sequence-green.log` |
| 实际活动 ELK 超时返回、shutdown、后续恢复及资源退出 | PASS，`cancel-running-final-green.log`；本请求线程退出，共享池 active=0、queue=0 |
| 第一个真实 ELK 请求仍活动时启动第二个正常请求，随后首请求超时，再运行第三请求 | PASS，`cancel-parallel-green.log` |

`CancelIsolationProbe.java` 的既有行为断言保持；仅对 `attach` 的新签名增加 `false` 参数。资源断言有一次必要修正：初版额外要求所有 `elk-reasoner-thread` 消失，但实际字节码证实 `Reasoner.EXECUTOR_` 为静态共享核心线程池，合法空闲线程随进程保留。该误判日志 `cancel-running-green.log` 原样保存；最终断言检查本请求线程退出和共享池实际活动任务/队列清零，不把正常空闲缓存线程误报为泄漏。`elk-core-bytecode.txt`、`elk-shared-interrupter-bytecode.txt` 保存了依赖核对证据。

最终审查指纹见 `reviewed-files-postcancel.json`，旧指纹保留：

- ConsistencyChecker：`45519BB218867ED58A261508D86B1F18CEDCF86BB02D545C4EF93D856FD762DC`
- ReasoningBudget：`36857A710FB60F3E18549171DC702611BB3D9463F2481254260CF8FB93FFE139`
- ConsistencyFallbackTest：`5AC08B89467D6B9D8AD43E682C487170E273FEB4F086C2BE66F92555F73938CF`

独立复现使用 `javac-recheck.args`、`java-recheck.args` 和 `cancel-{late,active,sequence,running,parallel}-new.args`，其 Java 路径及内存上限与前节相同。另只读确认作者 `c6-elk-cancel-green.log` 于 02:15:55 的 14/14 GREEN（纯推理 10 项、P1 旧恢复 1 项、真实 PG/MinIO 发布 3 项）。这仍是作者证据；本次没有运行 Maven/PG/HTTP，最终根 reactor 验收仍由作者另行提供。
