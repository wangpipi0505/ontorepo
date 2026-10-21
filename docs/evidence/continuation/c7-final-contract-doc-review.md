# C7 正式文档与最终契约增量复核

时间：2026-10-21 02:22（UTC+8）。独立审查者：`c7_frontend_review`。**Spec：APPROVE；Quality：APPROVE。** 本次未发现 D6 或冻结前置拒绝增量削弱 T01/T02/T03、事件资格或 D5 OWNER 边界的问题。此结论限于文档、契约及证据口径复核。

只读核对详设 v2.7 §7.1、§8.4、§8.7、§8.8、§8.11，开发计划 v1.7 的 B-04/P2/P4 出口、事项台账、P2/P4 固定合同及当前守卫脚本。15 个审查对象指纹见 `.runtime/c7-final-contract-doc-review/reviewed-files.json`。没有修改正式设计、计划、生产代码或测试。

复核结束后，root 同步了计划末尾 OM-D3/OM-D5 的验证完成状态及台账两项关闭状态；本审查已只读确认这两行，规范结论不因状态登记改变。原全文指纹保留，最终事件证据与状态同步后的计划/台账指纹另存 `final-evidence-and-status-fingerprints.json`，不把旧指纹标成后续文件当前值。

| 关注项 | 核对结论 |
| --- | --- |
| T01：§8.8 四段样例 | 独立运行 `node --max-old-space-size=512 deploy/Check-Design-Samples.mjs`，四段 JSON 全部解析通过，并与真实合同测试夹具逐段深比较一致。信封根 required 保留 code/data/meta，版本四元组及摘要完整；参数示例、SYNC/HTTP、完整 URL/POST、毫秒超时、守卫完整集合和导入 Schema 对象保持一致。 |
| T01：逐项语义 | 只读查看 `ContractTest.fourSamplesParseAndEveryContractStageIsValidated` 及紧邻负例：参数/input Schema、原响应/output Schema、业务 data、meta 四元组、导入 outputSchema/guardAttrs/returnSpec、枚举/超时、生成预览→正式 manifest 全对象相等均有断言；删除 meta、未知守卫、无执行绑定、非法枚举/缺字段/错误类型、缺示例、preview 版本证据均拒绝。上述 Node 检查只证明解析和夹具对齐；语义 91 项通过来自作者最终 verify 的 onto-contract 阶段，不冒充本审查独立运行。 |
| T02：缺 tenant_label 不得通过 | 详设 §8.8 仍明确必需守卫无执行绑定即阻断、没有“仅声明”豁免，info_attrs 不进入守卫集合；§8.7 保留三入口访问源前的运行期兜底。新增 §8.4 与计划 P4 出口仅区分失败发生的阶段：新候选冻结契约失败即 FAILED/VOID，LOCAL_TEST 前置拒绝；历史候选继续在执行链拒绝。没有为补执行记录而推进失败候选，也不伪造 test_run/CONTRACT 报告。两阶段共同要求不得通过、零源访问、无 READY/RELEASED。 |
| T03：范围预期 | §8.7 的 A/B 表及六步链保持：服务端点仅 A 时 B 被过滤；已存在且范围 NULL 的端点授予表示不限；缺授予记录直接拒绝。内部 TRIAL/LOCAL_TEST 要求至少 EDITOR，其端点等价范围与空间范围均来自同一 space_grant.data_scope。该规则不因 D6 或 LOCAL_TEST 前置拒绝而扩大。 |
| §8.4/§7.1：门禁及事件 | 五门禁、摘要/修订绑定、READY_TO_COMMIT 全制品就绪、短事务提交与 RELEASED 产生条件保持。事件资格仍绑定创建时的 bound_status：RELEASED←READY、DEPRECATED←DEPRECATED、CORRUPTED_ALERT←CORRUPTED；不以当前 release 状态撤销历史事件资格。同版本按 id 序、DEAD 不阻塞后继、人工 REPLAY、重启规则及五场景推演均保留，消费仍只允许 READY。 |
| D5/D6 | D5 仅 OWNER 对同候选摘要/当前修订的 NOT_CHECKED/SUBSET_EXCEEDED 显式豁免；不适用于守卫失败、引擎异常或超时。D6 仅在完整模型通过 OWL 2 EL profile 且 ELK 明确不完整时调用 HermiT；保留原公理、共享总预算、检查不可满足命名类，非 EL 不自动放行。 |

## 最终守卫脚本与现有证据

`Smoke-Data-Guards.mjs:115` 按 `preReleaseId` 明确区分新发布候选与旧候选：新候选必须 HTTP 409、publication=FAILED、prepare 409、没有 PASSED TEST、没有 LOCAL_TEST 执行、没有 release；旧候选必须 HTTP 200 但 `passed=false` 且唯一用例失败。两者都要求真实源计数为零，不把成功返回测试报告等同于业务通过。最终 `:131` 已显式增加 `/events` 中 RELEASED 为零断言；脚本语法检查通过。

已读取 root 的旧 P3 和新 P4 独立 HTTP 记录，两份证据分开保存：

- P3 记录：TRIAL/SERVICE 为 400 GUARD_ATTR_UNRESOLVED，LOCAL_TEST 报告 HTTP 200、业务失败，源访问 0。
- P4 最终记录（`checkedAt=2026-10-20T18:21:28.588Z`）：空间 `2accc368-44aa-458c-8d1a-7be60f5ae8b8`，候选 `8b214371-9e04-4ba1-b361-ad49f0af73b0`。TRIAL/SERVICE 为 400 GUARD_ATTR_UNRESOLVED，LOCAL_TEST 为 409 CANDIDATE_TERMINAL、`blockedBeforeTests=true`，源访问 0。

这些 HTTP 事实属于 root 执行证据。root 已在最终 `66F0E72…` 包实际运行含“无 RELEASED”断言的脚本，退出 0；本审查只读核对了 `.runtime/c7-final-p4-guards-events.log` 的成功终点、上述最终记录的时间/身份，以及脚本 SHA-256 与已审版本一致。脚本先完成 `/events` 零 RELEASED 断言，再写报告并输出成功消息，因此最后的事件断言已有 root 实际 HTTP 通过证据，不再标为待跑；这不是本 reviewer 独立执行 HTTP。`P4CompilationTest` 另有真实数据库断言覆盖新候选 FAILED/VOID、零 test_run/LOCAL_TEST/release；其执行仍归作者证据。

## 验证范围与保留事项

独立 Node 日志及本次样例对齐副本保存在 `.runtime/c7-final-contract-doc-review/`。设计样例脚本按其既定行为更新了 `docs/evidence/continuation/design-sample-alignment.json`；原索引已另存 `design-sample-alignment-before.json`。本次没有运行 Maven、数据库、HTTP 服务或浏览器，没有访问相邻平台工程。

**ClickHouse、MongoDB、Redis、Cassandra、Neo4j 五类真实外部实例继续待外部验收；跨系统 J-01～J-05 也未在本次验收。** 本地 PostgreSQL 通知收件箱只能证明本工程事件接收闭环，不能替代外部智能体平台导入、上架或联合场景通过。生产命名空间归属与外部可复用内核同样保留外部核实边界。
