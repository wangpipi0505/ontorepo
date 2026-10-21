# 整改决策记录（DEC-1~DEC-6）

> 2026-10-21 用户确认。变更需追加记录并同步台账与验收对照表。

| 编号 | 决策 | 已确认结论 | 影响 |
|---|---|---|---|
| DEC-1 | MODEL_ONLY 候选能否发布 | **A：允许纯模型发布**。候选全部操作均无助手声明时：CONTRACT 门禁以 0 个 manifest 判 PASSED（原因 `MODEL_ONLY_NO_CONTRACT`）；发布版本不生成 `service_endpoint`，发布页标注"不含服务契约"；候选 0 操作时 TEST 门禁空真通过（`NO_OPERATION_NO_CASE`），有操作但无用例仍阻断；任一操作存在助手声明（即使不完整）仍按原规则校验并阻断 | 解除 P1-2；改动 `SupplyService`/`PublicationService`/`CandidateCheckService`/`ReleaseGatePolicy`；详设 v2.10 §8.4 |
| DEC-2 | 画布编辑范围 | **方案 B，直接实现可视化画布编辑**：画布内建对象、拉线建关系、编辑与删除，节点联动属性/关系面板与冲突高亮；不设只读中间态 | P1-1；`packages/onto-graph`、`ModelView`、`TraceView`、`GET /executions/{id}/graph` |
| DEC-3 | 取消 `data-query` 页 | **取消**，并入操作详情试执行；权限码随迁；实施前确认覆盖自由查询与取样 | IA S3；路由与 `pagePermissions` |
| DEC-4 | 会话保持 | **维持现状**：内存会话 + 刷新提示；本轮不做持久会话 | 无代码任务；README/登录页提示保留 |
| DEC-5 | JDBC 方言优先级 | **MySQL + DM 优先**；Hive/Doris 转外部实例验收 | R3 C2；`JdbcSourceAdapter`/`AdapterRegistry`/能力页 |
| DEC-6 | 外部验收边界 | **保持外部**：五类数据源（OM-P3-EXT）、跨系统 J-01~J-05（OM-P4-EXT） | 不纳入 R1~R3 验收 |

## DEC-1 的实现约束（防止放行扩大化）

1. 仅当 `capability=MODEL_ONLY` 且**没有任何** assistantTool 时放行；只要有一个操作声明了 assistantTool，即使字段不齐，也进入 CONTRACT 校验并按 `MISSING_*`/`GUARD_ATTR_UNRESOLVED` 阻断。
2. 纯模型发布的版本**没有服务端点**；消费方调用应得到明确"无端点"错误，不能回退到候选执行。
3. 事件仍按 `bound_status=READY` 投递 `RELEASED`；载荷中的 manifest 摘要为 0 manifest 的摘要，不得伪造正式契约。
4. 零操作空真只适用于"操作数=0"；有操作但无启用用例仍必须阻断。
5. 旧 `MODEL_ONLY` 候选（升级前）按同一规则重新走门禁；不修改历史候选字节。

## DEC-2 的实现约束

1. 画布编辑复用 `ont_class`/`ont_relation` CRUD 与 `useDraftRevision`，不新增后端写接口。
2. 失败就地标错，不静默回退；与表单编辑共用修订锁，冲突提示刷新。
3. 画布与冲突侧栏双向定位；节点点击联动属性/关系面板。
4. 390px 使用缩放/平移，不做整页横向溢出；图形库按路由懒加载。
