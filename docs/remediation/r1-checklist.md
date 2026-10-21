# R1 执行清单（验收解锁）

> 2026-10-21。逐项按本清单执行；每项完成后在本文件勾选并在 `docs/evidence/continuation/r1-<id>.md` 留证据。
> 通用 DoD：真实 PG/MinIO 测试通过、页面证据、验收对照表状态更新、台账更新。

## OM-R-A1

- [x] **目标**：DEC-1=A 落地——无助手声明的纯模型空间可发布；发布版本无端点；部分声明仍阻断。
- [x] 后端
  - [x] `onto-trace/.../SupplyService.java`：`manifests()` 在 `MODEL_ONLY` 返回空列表（不再抛 409）。
  - [x] `onto-release/.../PublicationService.java`：`contract()` 对 MODEL_ONLY 返回 `PASSED/MODEL_ONLY_NO_CONTRACT/{manifestCount:0}`；提交时不生成 `service_endpoint`；`publication()` 返回 `contractMode`。
  - [x] `onto-trace/.../CandidateCheckService.java`：MODEL_ONLY 也写 CONTRACT=PASSED（0 manifest）。
  - [x] `onto-release/.../ReleaseGatePolicy.java`：增加 `operationCount`；0 操作时跳过 `NO_ENABLED_TEST_CASES`，有操作无用例仍阻断。
  - [x] `PublicationService` 统计冻结 payload 的 operations 数量并传入。
- [x] 前端
  - [x] `features/p4/PublicationView.vue`：MODEL_ONLY 候选显示"本候选不含服务契约，发布后无服务端点"。
  - [x] `features/p2/CandidateSupplyPanel.vue`：保留 preview/不可供给提示。
- [x] 测试（新增集成测试类 `P4ModelOnlyReleaseTest`）
  - [x] T1 0 操作 → validate/prepare/commit 成功；`service_endpoint` 0 条；RELEASED 1 条。
  - [x] T2 1 操作无助手 + 1 用例 → 发布成功、0 端点；SERVICE 仍拒绝。
  - [x] T3 1 操作有帮助声明但缺 param_examples → `MISSING_PARAM_EXAMPLES` 阻断。
  - [x] T4 1 操作无启用用例 → `NO_ENABLED_TEST_CASES` 阻断。
  - [x] T5 CONTRACT 路径回归（端点生成、manifest、meta）。
- [x] 文档：详设 v2.10 §8.4 已补；验收对照表 2.2③/2.1⑧ 状态待 D2 回填。

## OM-R-A2

- [ ] **目标**：端点级停用/启用，停用后零源访问。
- [ ] 迁移 `V15__remediation_audit_endpoint.sql`：`service_endpoint` 补 `updated_at`、`status_changed_by`；索引 `(release_id,status)`。
- [ ] 后端
  - [ ] `ReleaseGatewayService.updateStatus(...)`：锁内校验空间角色（OWNER/EDITOR）、写审计 `ENDPOINT_STATUS`。
  - [ ] 执行链加载端点后校验 `status='ACTIVE'`，否则 `ENDPOINT_DISABLED`（403），在访问数据源前拒绝。
  - [ ] `ReleaseController`：`PUT /onto/endpoints/{id}/status`，权限 `grant.write`。
- [ ] 前端：`ConsumptionView.vue` 端点旁加状态徽标与停用/启用按钮（确认弹窗）。
- [ ] 测试（`P4EndpointStatusTest`）
  - [ ] T1 停用 → 有效凭据调用 403 `ENDPOINT_DISABLED`，源请求 0；审计 1 条。
  - [ ] T2 启用 → 恢复；历史执行可查。
  - [ ] T3 VIEWER/非成员调用状态接口 403；不存在 404。
  - [ ] T4 release 非 READY 优先拒绝。
  - [ ] T5 并发停用/启用串行一致。

## OM-R-A3

- [ ] **目标**：NL 链路补"结果解释"与 EXPLAIN 步骤。
- [ ] 后端
  - [ ] `CandidateService`：执行成功后按 `return_spec.modelSummary` 模板渲染解释；无模板用"操作 {opCode} 返回 {n} 行"；落 `step("EXPLAIN", ...)`。
  - [ ] `CandidateTestService`：NL 用例支持 `expected.explanationKeywords` 包含匹配；失败使用例失败。
  - [ ] `TestCaseService`：`expected` 白名单增 `explanationKeywords`（数组、≤20 项、每项≤200）。
- [ ] 前端：`TestCasesView.vue` 增加解释关键词；`CandidateTestsPanel.vue` 展示解释与断言结果。
- [ ] 测试（`P2NlExplanationTest`）
  - [ ] T1 命中 + 关键词匹配 → PASSED，追溯含 TERM_MATCH/QUERY_EXEC/EXPLAIN。
  - [ ] T2 关键词不匹配 → FAILED。
  - [ ] T3 无 return_spec → 默认解释，不报错。
  - [ ] T4 DIRECT 用例不变。
  - [ ] T5 解释不含凭据/守卫敏感值。

## OM-R-A4

- [ ] **目标**：审计按对象/动作/时间检索；执行记录筛选分页。
- [ ] 迁移 V15：`audit_log` 补 `subject_type`、`version_refs`、`input_digest`、`output_digest`、`result`；索引 `(space_id,subject_type,subject_id,id)`、`(space_id,action,id)`、`(space_id,created_at,id)`。
- [ ] 后端
  - [ ] `SpaceService.audit` 新增 `AuditDetail` 重载；数据接入/同步/映射/规则执行/发布/弃用/恢复补对象、版本、摘要、结果。
  - [ ] `TraceAuditService`：`/audit` 支持 `action/subjectType/subjectId/from/to/limit/beforeId`；join 用户返回 `actorName`。
  - [ ] `ExecutionFacts.recent`：支持 `caller/status/opCode/from/to/limit/beforeId`；Controller 暴露参数。
- [ ] 前端
  - [ ] `AuditView.vue`：筛选条（动作/对象类型/对象 ID/时间）；动作中文字典；操作者姓名。
  - [ ] `ExecutionsView.vue`：入口/结果/操作/时间筛选 + "加载更多"。
- [ ] 测试（`P4AuditQueryTest`、`ExecutionQueryTest`）
  - [ ] T1 三类审计含对象/版本；按对象 ID 查得。
  - [ ] T2 action+时间过滤；分页不重不漏。
  - [ ] T3 执行记录 caller/status 过滤 + 分页。
  - [ ] T4 旧行兼容，显示"历史未记录"。
  - [ ] T5 权限：VIEWER 可读；无 trace.read 拒绝。

## OM-R-D1

- [ ] `.gitignore` 增加 `output/`；删除根目录 `hs_err_pid*.log`、`replay_pid*.log`。
- [ ] 首次提交 + tag `v0.9-audit-baseline`；配置远端 `origin` 并推送。

## OM-R-D4

- [ ] `deploy/Test-Local.ps1` 在 `Import-OntoEnvironment` 后把 `ONTO_TEST_DB_URL` 改写为 `onto_test_p4`（或 `Initialize-Local.ps1` 创建并写入）。
- [ ] README 同步命令与说明。
- [ ] 验证：照 README 执行 `Test-Local.ps1 -BackendOnly` 得到 407 项通过。

## 执行顺序

1. D1（封板）→ A1（决策落地）→ D4（可复现）→ D2/D3（文档线并行）。
2. A2/A3/A4 后端可并行，但同一 Maven 窗口串行；A4 的 V15 与 A2 合并为一条迁移。
3. 每完成一项，更新 `文档/设计/详细设计/领域本体构建/技术要求模块2验收对照表.md` 状态列与台账。
