# C6 / P4 门禁与事件资格领域核独立审查

审查时间：2026-10-20 22:43（Asia/Shanghai）。

## 整批结论

- **Spec：PASS（仅本批纯领域规则基础）。** 五门禁、受限超 EL 豁免、候选摘要/修订、零用例阻断、历史事件资格、同版本 PENDING 顺序、DEAD 解阻、原事件身份重放和有限重试符合本批契约及正式详设。
- **Quality：PASS（仅本批 5 个文件）。** 未发现可复现、需要修改的 P0/P1/P2/P3 缺陷。独立复跑 14 条 JUnit 测试通过；补充探针的 1,851 个断言通过。
- **这不是 C6 / P4 整期验收通过。** 模块尚未接入运行期，本次没有证明发布事务、数据库原子领取、真实目的地投递、进程重启或消费者拉取拒绝已完成。未将缺少这些后续实现列为本纯核的代码缺陷。

## 范围与依据

已读取工程 `AGENTS.md`、`docs/continuation-plan.md`、`docs/evidence/continuation/c6-policy-report.md`、`docs/evidence/continuation/c6-policy-review-package.txt`、`contracts/p4-release.md`，并逐项核对实际文件：

| 文件 | 行数 | SHA-256 |
|---|---:|---|
| `onto-release/src/main/java/cn/ontorepo/release/domain/ReleaseGatePolicy.java` | 94 | `F812D5EAA6F62925A18CB87F257B757B8ABE5B019A68C0EEE16857A4DF3AD8BA` |
| `onto-release/src/main/java/cn/ontorepo/release/domain/OutboxPolicy.java` | 111 | `7ED80590AE3F7FD43D50822655BA97C94C9D342443F678F4A3A0E1AC89495C49` |
| `onto-release/src/test/java/cn/ontorepo/release/domain/ReleaseGatePolicyTest.java` | 122 | `B0B9FFB7A8C3D1D439C66E6E3B3B715F664F32A13B883BE18DF61E70A5692425` |
| `onto-release/src/test/java/cn/ontorepo/release/domain/OutboxPolicyTest.java` | 186 | `473D6E7AF94F6DE858C5FEEB07F69B2588189B929D74583EF81B8B8C0C6EC2A2` |
| `onto-release/pom.xml` | 9 | `E375B711A72F95303CE918D1E7BFAFB19BEB8E49123CBEF9F8C89B634B03AE1C` |

仓库尚无初始提交，5 个文件均为未跟踪新增文件，因此依据审查包、当前完整源码和文件摘要审查；不能用空 `git diff` 表示无变更。

权威依据：

- [正式详设 §7.1](G:/207/项目/算法重组工具/文档/设计/详细设计/领域本体构建/详细设计70-领域本体构建工具（模块2）.md:407)：第 407～424 行的资格、顺序、DEAD、重放、重启及五场景。
- [正式详设 §8.4](G:/207/项目/算法重组工具/文档/设计/详细设计/领域本体构建/详细设计70-领域本体构建工具（模块2）.md:541)：第 546～551 行的摘要、五门禁、不可豁免错误及 OWNER 超范围豁免。
- [开发计划 W13/W15/P4 出口](G:/207/项目/算法重组工具/文档/设计/详细设计/领域本体构建/领域本体构建工具开发计划.md:199)：第 199～213 行，尤其第 203 行事件规则及第 209 行五场景/重启要求。
- 编号核对：对本体开发计划执行 `rg -n 'T03'` 没有字面命中；正式详设中的 **OM-T03** 是 [§8.7 的空间授权范围及 A/B 入口样例](G:/207/项目/算法重组工具/文档/设计/详细设计/领域本体构建/详细设计70-领域本体构建工具（模块2）.md:592)，事件资格规则编号为 **OM-T04**。T03 授权场景仍属于运行期后续验收，本批不据此宣称完成。

## Spec 核对

| 核对项 | 实现位置 | 结果 |
|---|---|---|
| 五项必须齐全且不可重复 | `ReleaseGatePolicy.java:16`、`:56`、`:67` | 五种枚举均逐项检查；空列表、缺项、重复及缺 kind/conclusion 均阻断。 |
| 摘要及草稿修订 | `ReleaseGatePolicy.java:52`、`:54`、`:71` | 非法候选、当前修订变化、任一报告摘要不符均阻断；报告不能仅因结论为 PASSED 跳过摘要检查。 |
| 空测试不能通过 | `ReleaseGatePolicy.java:55` | `enabledCaseCount < 1` 阻断，包括零和负数；真实完成数量及 PASSED 报告来源由后续报告生产者保证。 |
| 豁免仅限一致性的超 EL 未检 | `ReleaseGatePolicy.java:81` | 仅 CONSISTENCY + NOT_CHECKED + SUBSET_EXCEEDED，加 OWNER、同摘要、候选/当前/审批修订一致及非空理由才覆盖。 |
| 其他失败不可豁免 | `ReleaseGatePolicy.java:73`、`:83` | FAILED(SUBSET_EXCEEDED)、TIMEOUT、ENGINE_ERROR、INCONSISTENT、守卫错误，以及其他四门禁的 NOT_CHECKED 均不能由该审批覆盖。 |
| 历史事件资格 | `OutboxPolicy.java:35`、`:102` | 类型只对照持久 `boundStatus`；没有 release 当前状态参数或读取路径。三种类型分别要求 READY、DEPRECATED、CORRUPTED。 |
| 同版本 PENDING 顺序 | `OutboxPolicy.java:62` | 先按 id 排序，再记录每 release 的首个 PENDING；首项未到期仍拦住后继，不先按 due time 过滤而造成越序。不同 release 各自选取。 |
| SENT/DEAD 解阻与非法资格隔离 | `OutboxPolicy.java:67`、`:68` | SENT/DEAD 不占排序屏障；非法资格返回 REJECT，本轮不放行该 release 后继，由持久层先记录 DEAD/告警。 |
| 有限重试与到期边界 | `OutboxPolicy.java:69`、`:76` | `nextRetryAt == now` 可投递；预算限定 1～10 次，失败计数达到预算转 DEAD，退避最大 300 秒。 |
| 重放原身份及本轮预算 | `OutboxPolicy.java:89` | 只接受资格有效的 DEAD；保留 id/releaseId/type/boundStatus，回 PENDING、计数归零、立即可领取。累计尝试审计和载荷字节保留由后续应用事务完成。 |

## Quality 核对及发现

**发现数：0（P0: 0，P1: 0，P2: 0，P3: 0）。**

两个类为无共享可变状态的确定性规则函数，没有数据库、网络、鉴权入口或外部解释执行依赖。`Result.violations` 及选择结果复制为不可变列表；`Event` 对身份、状态和重试上下文做基础校验。门禁职责与 outbox 职责分离，未发现需要引入额外抽象或删除代码的理由。POM 只增加测试依赖并继承工程 Java 21 配置。

已检查的质量边界包括空/缺失报告、重复门禁、错摘要、错误/空白审批、不可豁免结论、无效 bound_status、乱序输入、未来 due time、终态事件、不同比例的 PENDING/SENT/DEAD、单次到十次重试预算、退避上限及重放身份。本批没有需要建立删除/重构计划的项目。

## 独立验证

在工程根目录用通用 JDK 21 独立运行本模块，Maven 和测试 JVM 均限制 512 MiB：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -f onto-release/pom.xml '-DargLine=-Xmx512m' test
```

结果：2026-10-20 22:42:00 +08:00，BUILD SUCCESS；`ReleaseGatePolicyTest` 9 条、`OutboxPolicyTest` 5 条，合计 **14 条，0 failures / 0 errors / 0 skipped**。日志：`.runtime/c6-policy-review-test.log`；Surefire XML 位于 `onto-release/target/surefire-reports/`。

首轮命令使用机器默认 JDK 17，报 class file 65/61 不兼容且运行测试数为 0；该轮没有计入通过证据。明确设置 JDK 21 后的上述结果才作为复跑结论。

独立探针放在忽略的临时目录，不修改产品源代码或正式测试文件：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe' `
  -Xmx512m --class-path 'onto-release/target/classes' `
  '.runtime/c6-policy-review/PolicyReviewProbe.java'
```

输出：`PASS: 1851 independent policy assertions`。日志：`.runtime/c6-policy-review-probe.log`。范围为五门禁 × 两类非通过结论 × 六种原因 × 六种审批输入、各门禁摘要绑定、三类型/五种 bound_status、三个事件全部状态组合 × 到期组合 × 六种输入排列，以及 1～10 次预算的每次计数/终态/退避与重放身份。断言数量不等于数据库或端到端场景数量。

## 运行期接入与整期验收保留范围

以下属于既定 C6 后续工作，不在本次纯核 PASS 内：

1. 在空间锁及同一事务中验证真实身份、当前修订、同候选最新持久报告和审批；TEST 报告必须证明所有启用用例实际完成。禁止把客户端自报角色或数量直接映射为可信规则输入。
2. 正式 bundle/manifest/pack/index 就绪及摘要复核、候选状态机、CAS/幂等提交和发布五写；本次 `allowed` 只表示准备门禁通过。
3. 数据库领取须包含该 release 的所有前序 PENDING，不能只加载已到期行后调用 `next`；多 relay 原子领取、租约/崩溃恢复、发送与确认结果落库均待真实 PG 验证。
4. DEAD 告警、OWNER 人工重放权限和原因、累计尝试审计、原事件载荷不变、消费者按 eventId 去重，须由持久化实现与真实目的地接收证据证明。Event 规则对象没有载荷字段，本次仅验证身份字段保留。
5. §7.1 五场景与真实进程重启，尤其停机期间发布后弃用的“RELEASED 先投、拉取拒绝、随后 DEPRECATED”和“弃用重试至 DEAD 后 CORRUPTED_ALERT 可达”；OM-T03 三入口范围与 P4 其余出口仍待联调。

本次只新增本报告及忽略目录中的审查探针/日志，没有修改父 POM、5 个受审文件或其他产品代码；没有启停服务、访问数据库或操作相邻工程。
