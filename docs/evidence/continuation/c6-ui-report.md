# C6 / P4 发布、消费与审计前端交付

日期：2026-10-20。依据 `c6-ui-brief.md` 与 `contracts/p4-release.md` 的固定展示 DTO 实现。代码只在本工程；未执行 Git 提交、服务启动/停止或外部部署。

## 实现与入口

| 入口                                             | 实现范围                                                                                                                          |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| `/spaces/:spaceId/publication`                   | 冻结发布候选、五门禁、冻结用例、候选 diff、OWNER 理由复核/超子集豁免/取消、独立 prepare/commit、制品摘要/就绪、旧候选重新冻结提示 |
| `/spaces/:spaceId/releases`                      | 正式版本管理目录、详情、实体投影、版本投影差异、正式 manifest/context pack 读取、弃用、真实完整性检查、明确确认后恢复草稿         |
| `/spaces/:spaceId/consumption`                   | 正式端点、端点授权与空间授权分区、现有本地身份选择、API key 创建/一次展示/复制/关闭/撤销、正式服务调用及 data/meta 分区           |
| `/spaces/:spaceId/events`                        | 原事件 id、绑定状态/版本当前状态、投递状态/重试/错误、DEAD 原 id 重放、本地 PG 收件箱接收证据                                     |
| `/spaces/:spaceId/audit`                         | 安全审计明细、服务端倒序游标、追加前页保留、失败保留上下文并重试原读取方式                                                        |
| `/spaces/:spaceId/executions/:executionId/trace` | 持久执行身份、授权、步骤、证据、事实时间线、真实 nodes/edges SVG 图与可读列表                                                     |

导航新增“发布与消费”折叠组，保留既有导航；执行追溯从原执行记录入口进入，不另增顶层导航。执行记录增加 `subjectType/subjectId/caller`；原执行详情保留，摘要文案适配正式执行。

## 关键约束

- 所有发布状态和门禁均取真实 API；按钮不会自行生成 READY。五项门禁的摘要必须属于当前候选，所有四类制品必须已就绪，BUNDLE SHA 必须等于冻结摘要才能启用提交。
- 提交固定候选的 `draftRev/sha256`；409 保留理由并锁定当前表单，即使空间后来刷新也不能借新修订重试覆盖。恢复面板独立捕获打开时修订，明确勾选覆盖与输入理由；恢复不自动发布。
- 仅真实 `CONSISTENCY + NOT_CHECKED + SUBSET_EXCEEDED` 显示 OWNER 豁免；守卫、契约、超时、测试失败不显示豁免入口。真实零验收用例阻断后续准备。
- API key 不写浏览器持久存储或控制台；仅创建响应留在组件内存，关闭/卸载/切空间清除。旧请求的迟到 key 不进入新空间。
- 端点缺少记录明确显示拒绝，已存在记录的 `null` 才显示不限；空间授权独立读取展示。端点 ACTIVE 不等于调用者获准。
- 正式调用默认逐字段业务输入，另提供 JSON 值模式支持 schema 合法数组、标量及 null。请求体直接为业务值；不提交身份/可信属性字段。JSON 格式失败不回显输入内容。
- 正式成功证据只用响应 meta，核对 `preview:false`、真实 releaseId、vN、spaceCode、bundle 内容摘要；失败和 preview 候选结果不显示正式成功结果。
- 图只使用真实节点与关系，无法解析端点的边保留可读列表而不补造节点。缺失历史时间、授权与证据明确显示未记录。折叠长内容、局部图/表滚动和窄屏单列布局已实现。

## 验证证据

所有 Node 运行设置 `NODE_OPTIONS=--max-old-space-size=1024`；Vitest `--maxWorkers=2 --minWorkers=1`。

- 关键交互先 RED：首批 7 个缺失组件行为失败，第二批事件/审计/调用 3 项失败；随后逐项实现转 GREEN。
- 补充 RED 捕获并修复：零验收用例误放行、bundle 摘要错配仍可提交、审计刷新失败误按翻页重试、任意 JSON 根入口缺失。
- 最终全量回归：20 个测试文件、163 tests 全部通过；其中 P4 `flows.test.ts` 19 项。P1–P3 原回归保留。
- 生产构建：`pnpm build` 成功，1816 modules；P4 五页及 trace 均进入独立构建 chunk。
- 本次所有新增/修改文件 Prettier 检查通过。
- `pnpm typecheck` 最终通过。共享接入期间曾遇并行 P3 测试的临时泛型错误，已由负责代理修复后复跑确认，无剩余类型错误。

本次没有独立启停服务，也没有将 mock 测试写成真实后端验收。浏览器 390px/桌面视觉、真实 PostgreSQL/MinIO 发布与消费链路、外部平台上架均由主任务统一联调；当前报告只确认前端实现、自动化交互与生产编译。本地收件箱不等同外部智能体平台上架通过。

## 交付与 review 包

新增：

- `web/packages/api-client/src/release.ts`：固定 DTO。
- `web/apps/onto-admin/src/features/p4/`：22 个文件，包括 6 个路由页面、独立业务组件、门禁/参数辅助、样式和 `flows.test.ts`。

经主任务批准修改共享集成：

- `web/packages/api-client/src/index.ts`
- `web/apps/onto-admin/src/router.ts`
- `web/apps/onto-admin/src/layouts/WorkbenchLayout.vue`
- `web/apps/onto-admin/src/views/ExecutionsView.vue`
- `web/apps/onto-admin/src/components/ExecutionResult.vue`
- `web/apps/onto-admin/src/views/CandidatesView.vue`：与 P3 交接后仅修正真实状态和 preview 文案，保留其任意 JSON 输入改动。

优先 review：`PublicationPanel.vue` 的 rev/SHA 与五门禁限制；`RestoreDraftPanel.vue` 的固定修订；`CredentialsPanel.vue` 的 key 生命周期；`ServiceCallPanel.vue` 的响应证据；`AuditView.vue` 的游标与失败重试；`TraceGraph.vue` 对真实图事实的使用。

## 独立审查 P2 修复

`c6-ui-review.md` 指出的恢复冲突后刷新无法解除空间过期状态已修复。`ReleasesView.load` 现在同时读取版本列表与当前空间，且只在两者成功、组件请求仍有效、工作区上下文未变化时调用 `workspace.refreshed`。空间读取失败保持 `stale`，不会开放管理权限。

旧恢复面板的固定修订、409 锁定和输入保留均保持。用户必须先关闭旧面板，再显式刷新、重新打开恢复面板、重新输入理由并勾选确认，才可用新修订提交；刷新不会自动重试恢复。

验证：先新增两条完整版本页回归，RED 分别失败于未请求 `/spaces/s` 和未展示空间读取失败；修复后 P4 定向 `flows.test.ts` **21 tests 全部通过**，包括原有 19 项。`pnpm typecheck` 与本次修改文件格式检查通过。本次仅修改 `ReleasesView.vue`、P4 回归测试及本报告，未重复全量测试或构建，待原审查代理复核。

## 发布状态接入补验

2026-10-20 23:57：root对照详设§8.3补齐DTO中的FREEZING/FAILED（历史FROZEN保留），并禁止在FREEZING制品仍归档时提前运行门禁、取消、准备或提交。先新增真实组件交互反例，`c6-freezing-ui-red.log`确认按钮未被禁用；修复后`c6-freezing-ui-green.log`中22项定向用例全部通过，typecheck退出0，修改文件经Prettier处理。没有把这轮增量计入23:33那轮198项全量，也没有提前标为独立复审通过；后续发布联调与最终审查继续覆盖。
