# 优化台账执行报告：R12（失效墓碑审计）

> 执行日期：2026-10-25。对应《优化台账-本体侧-ontorepo》R12。
> 环境：JDK 21.0.12、工程专用 PG 16.15（127.0.0.1:15472，O: 盘别名）、MinIO（127.0.0.1:19100，按 local-env.json 凭据）、`ONTO_TEST_DB_*`/`ONTO_STORAGE_*` 来自 `.runtime/local-env.json`。

## 设计与实现

**语义**：对象弃用（deprecated false→true）与恢复（true→false）是治理动作，从泛化的 `CLASS_UPDATE` 中区分出来，落独立审计动作 `CLASS_DEPRECATE` / `CLASS_RESTORE`——回答"谁、何时、对哪个实体、为何、从什么状态到什么状态"。

**变更点**
- `onto-model/.../ModelService.saveClass`：
  - 写锁前读取当前 deprecated 值判定迁移（expectedDraftRev 乐观并发保证锁前读与写锁内读等价——并发改动会使本次写入因修订冲突回滚）；
  - 弃用迁移必填 `deprecateReason`（≤500 字，`Input.optionalText` + 空白校验 → `DEPRECATE_REASON_REQUIRED` 400）；恢复无需理由；
  - 审计明细（AuditDetail）：subjectType=CLASS、subjectId=实体 id、versionRefs={name,from,to,reason}、result=SUCCEEDED；
  - **重载消歧修复**：`spaces.write` 的 `AuditDetail` 与 `Supplier<AuditDetail>` 两个重载在三元表达式下无法静态分派——原写法 transition=false 时静默选中 AuditDetail 重载、Supplier 内 detail.get() NPE（集成测试暴露）。改为显式 Supplier 局部变量：transition=false 时 `() -> null`（沿用"缺省字段保持 null，不伪造历史事实"约定）。
- `onto-designer-api/.../ApiDtos.ClassWrite`：新增 `deprecateReason` 字段。
- `web/apps/onto-admin/.../ClassDialog.vue`：勾选弃用（编辑既有对象且当前未弃用）时显示"弃用理由（必填，记入审计）"文本域并前端校验；恢复路径不显示。

## 测试（真实 PG + MinIO 集成）

`onto-designer-api/src/test/.../R12TombstoneAuditTest` 2/2 绿：

1. `deprecateWritesTombstoneAuditWithReasonInSameTransaction`——缺理由 400 且零审计行；带理由弃用成功，审计行断言 actor_id=owner、subject_type=CLASS、version_refs.reason/from/to、**draft_rev 与业务变更后的空间修订一致（同事务证据）**；恢复落 CLASS_RESTORE 且无需理由。
2. `plainUpdateStaysClassUpdateWithoutTombstoneRow`——deprecated 不变的普通改名仍是 CLASS_UPDATE，不产生墓碑行。

**回归**：C1ReviewRegressionTest 10/10、C6FieldsTest 1/1、DataAuditRegressionTest 7/7 全绿（覆盖 ModelService 主路径与审计读取）。前端 `vite build` 通过。

## 排障记录（供后续参考）

- 测试上下文初始化需要 MinIO 运行（ArtifactStore bean 创建即连接）与 `ONTO_STORAGE_*` 环境变量（`@Value` 无默认值）；本地 MinIO 须按 local-env.json 的凭据以 `MINIO_ROOT_USER/PASSWORD` 启动，否则 bean 创建失败。
- POST /classes 的响应体是 **Space 汇总**而非类实体——类 id 需从 `GET /onto/classes?spaceId=` 类树回查（与基线夹具 `createClass` 同口径）。
- `audit_log.subject_id` 为 text 列，JDBC 参数传 String 而非 UUID。
- `Input.number` 对**缺失键**同样抛"必须为整数"——saveClass 的创建分支也必须携带 expectedDraftRev。

## 台账影响

R12 待启动 → **待验收**。UIF-O1 依赖的 R10（影响分级）未动；UIF-O 系列不受影响。
