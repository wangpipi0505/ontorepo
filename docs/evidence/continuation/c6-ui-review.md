# C6 / P4 前端独立审查

日期：2026-10-20。范围依据 `AGENTS.md`、`contracts/p4-release.md`、`c6-ui-brief.md`；阅读 `c6-ui-report.md` 的既有验证证据。使用 code-review、vue-skilld、ponytail，并按 systematic-debugging 对具体疑点进行独立复现。

## 结论

- **Spec：暂不批准。** P4 功能入口、契约数据形状及安全边界已覆盖，但恢复草稿的 409 后恢复流程存在一项 P2 缺陷，未满足“失败显示可恢复错误”的约束。
- **Quality：请求修改。** 发现 1 项 P2，未发现 P0/P1。页面、业务组件、参数/门禁辅助、API DTO、公共样式已拆分；中文注释解释了固定修订、迟到响应、凭据生命周期及权限边界，没有发现需要额外拆分或新增抽象的阻断项。

本报告仅审查 P4 及指定共享接入，P1–P3 作为调用协议和共享状态依赖阅读，未作全仓重新审查。

## 问题（一次汇总）

### [P2] 恢复冲突后，“刷新版本”不重读草稿，恢复入口持续消失

- **定位：** `web/apps/onto-admin/src/features/p4/ReleasesView.vue:26–33`，重点第 29 行；相关第 24 行和第 193 行。依赖链为 `RestoreDraftPanel.vue:43–45` → `composables/useMutation.ts:33–35` → `workspace.stale`。
- **触发条件：** OWNER 在空间草稿 r5 打开某个 READY 历史版本的恢复面板；另一个编辑把服务端草稿更新到 r6；提交恢复时后端返回 `409 / DRAFT_REV_CONFLICT`。
- **复现步骤：** 输入恢复理由并勾选覆盖确认 → 提交并收到上述冲突 → 按错误提示关闭恢复面板 → 点击“刷新版本” → 尝试重新打开恢复。
- **实际结果：** `useMutation` 正确标记 `workspace.stale=true`；但版本页刷新只 GET `/releases?spaceId=s`、版本详情与实体投影，不 GET `/spaces/s`，也不调用 `workspace.refreshed`。因此本地草稿仍为 r5，`workspace.canWrite=false`；`owner` 将 `canWrite` 一并作为条件，导致包含“恢复到草稿”的管理操作区消失。重复刷新不能解除。原面板虽然保留理由，但页面建议的“关闭并刷新后重新打开”不能完成。
- **影响：** 一次正常的并发冲突会中断此页的恢复、弃用及完整性管理；用户必须另行使工作台重新加载空间，才能继续。没有发现自动借用新修订覆盖草稿或破坏历史版本的风险。
- **修复建议：** 将版本页显式刷新作为完整恢复入口，同时读取当前空间与版本数据，并在发起上下文仍有效且所需数据读取成功后调用 `workspace.refreshed(space)`。保留已有恢复面板固定 rev/409 锁定规则；只能关闭旧面板后，以重新读取的修订创建新面板并重新确认，不能自动更新旧确认。可复用 `PublicationView.load` 现有的上下文检查模式。
- **独立证据：** 临时 Vue Test Utils + 真实 Element Plus 探针挂载完整 `ReleasesView`，设置当前空间 r5，空间读取桩可返回 r6，版本及实体桩返回真实 DTO 形状，恢复 POST 抛出上述冲突。实际点击完整步骤后，断言 GET 调用没有 `/spaces/s`、草稿仍 r5、`stale=true` 且“恢复到草稿”按钮不存在，全部成立。该探针验证缺陷存在，不是声称功能通过。

## 完整覆盖核对

审查包的 **28 个完整源码文件全部阅读**；另补读当前 `views/CandidatesView.vue`。审查结束前逐文件核对审查包与磁盘源码，28 个文件内容一致。补读共享 `useResource`、`useMutation`、`workspace`、`session` 与 `AsyncState` 以追踪实际生命周期。

| 范围 | 核对结果 |
| --- | --- |
| PublicationView / PublicationPanel / publication 辅助 | 五门禁按候选 SHA 匹配；状态字符串不足以解锁；四类制品就绪且 BUNDLE SHA 匹配才提交；prepare 与 commit 分开；冻结/提交固定 rev/SHA；409 保留理由并锁旧表单；旧候选提示重新冻结；仅真实一致性超子集允许 OWNER 豁免；零用例结果阻断；人工复核需差异、勾选与理由。未发现其他可复现问题。 |
| ReleasesView / RestoreDraftPanel / ReleaseEntities / ReleaseDiffPanel / ProjectionDiff / ArtifactList | 版本状态、制品、端点、投影、差异、正式 manifest/context pack、弃用与完整性入口均接真实 API；版本 diff 校验 from/to；损坏版本禁止恢复；覆盖确认固定目标和打开时修订；恢复不自动发布。409 后重新进入流程见唯一 P2。 |
| ConsumptionView / EndpointGrantsPanel / CredentialsPanel | 端点授权与空间授权分别读取和展示；缺记录拒绝，已有 null 才不限；选择已有本地主体；凭据目录无明文 key；创建后仅内存展示，关闭、卸载、切空间和失去权限均清理；迟到响应受上下文保护；撤销使用真实 API。未发现其他可复现问题。 |
| ServiceCallPanel / ParameterFields / parameters / ApiClient | 正式调用体直接为业务值，未注入身份；字段模式与 JSON 根值模式并存；格式错误不回显原始 JSON 内容；成功必须同时匹配 preview=false、releaseId、vN、spaceCode 和 bundle 摘要；data/meta 分区。共享客户端用 `body === undefined` 区分无请求体，合法 false/null 不会改成对象。 |
| EventsView | 显示原事件 id、绑定状态、版本当前状态、投递/重试/错误；仅 OWNER 的 DEAD 行可带理由重放，校验返回原 id；本地收件箱与外部上架边界清晰。未发现可复现问题。 |
| AuditView | 请求采用服务端 nextBeforeId，后页追加并对已展示 id 去重；失败保留已展示页与游标；刷新/翻页分别记录重试模式；切空间以代次隔离。未发现可复现问题。 |
| TraceView / ExecutionTracePanel / TraceGraph | 校验执行标识；授权、步骤、时间线、证据及摘要来自 API；缺失历史事实显示未记录；图仅画真实 nodes 与可解析 edges，缺端点的边保留可读列表；没有补造节点。未发现可复现问题。 |
| router / WorkbenchLayout / ExecutionsView / ExecutionResult / CandidatesView | 所有 P4 路由已接入；发布与消费使用折叠组；原导航保留；执行表展示 subjectType/subjectId/caller 并链接 trace；结果组件适配共同冻结摘要；候选页面显示真实状态和 preview 提示。未发现可复现接入问题。 |
| p4.css / 组件结构与注释 | 页面布局使用最小宽度保护、窄屏单列和局部图/表滚动；长 JSON 可换行/折叠。组件按业务职责拆分，中文不变量注释明确。390px 实际布局仍属于下述浏览器边界。 |

## 验证方式与边界

- 已阅读交付报告中的 20 文件 / 163 tests（P4 19）、typecheck、Prettier 与 build 证据；按任务限制没有重复执行同样全量检查，也不将原报告结果写成独立重跑结果。
- 本次仅执行独立临时探针：`pnpm exec vitest run c6-ui-review-probe.test.ts --config apps/onto-admin/vitest.config.ts --maxWorkers=2 --minWorkers=1`，环境 `NODE_OPTIONS=--max-old-space-size=1024`；最终 **1 文件、6 tests 通过**。其中 1 项为上述缺陷的特征断言，另 5 项通过真实 `ApiClient` 及 fetch 桩检查根值 `false`、`null`、`0`、空字符串、数组的请求体精确等于对应 `JSON.stringify`，并携带 JSON Content-Type。临时文件已清除，未修改共享测试。
- 初次临时探针置于 web 根目录时，pnpm 子包依赖解析不到 pinia；改为引用本工程管理端已有依赖路径后执行成功。该次测试装载错误不是产品缺陷。
- **未覆盖真实后端：** 未运行 PostgreSQL/MinIO 发布、恢复、端点授权、API key 消费、正式版本头、事件投递/收件箱、trace 持久事实及敏感信息脱敏链路。源码与 mock 交互审查不能替代这些集成验收。
- **未覆盖真实浏览器：** 未独立验证桌面/390px 视觉、真实剪贴板、SVG 可读性与完整导航交互；未启动或停止服务。根任务应继续统一浏览器联调。
- 未运行 Maven、修改生产源码/共享测试/target/其他报告、执行 Git 提交或访问相邻工程。仅交付本审查报告；本地收件箱不代表外部智能体平台上架通过。

## P2 修复复审结论（2026-10-20，最新结论）

- **Spec：批准本次 P4 前端审查范围。** 原唯一 P2 已关闭，没有剩余已确认缺陷；上文初审结论与问题描述保留为历史证据。
- **Quality：批准。** 修复复用了既有资源及工作区上下文机制，没有引入新的共享抽象；原恢复面板的固定修订、409 锁定和显式覆盖确认没有放宽。

复审阅读 `ReleasesView.vue`、新增两项完整页面测试、未改动的 `RestoreDraftPanel.vue`，并复核 `useResource` 和 `workspace` 的资源失效与上下文规则，以及交付报告新增验证记录。

`ReleasesView.vue:27–45` 现将当前空间与版本列表放入同一次 `Promise.all` 读取，只有两者成功且 `useResource.load` 返回当前有效结果、工作区上下文仍匹配时，才调用 `workspace.refreshed(currentSpace)`。因此原冲突后可通过关闭旧面板、刷新至 r6、重新打开并重新填写理由和确认完成恢复。任一读取失败不会解锁；卸载/资源重置使 `load` 不返回当前结果，切空间使上下文检查失败，迟到读取不能恢复其他上下文的写权限。刷新入口在旧恢复面板仍打开时保持禁用，不会自动更新旧确认或自动重试恢复。

独立执行本问题相关 3 项回归：

```text
NODE_OPTIONS=--max-old-space-size=1024
pnpm exec vitest run apps/onto-admin/src/features/p4/flows.test.ts --config apps/onto-admin/vitest.config.ts --maxWorkers=2 --minWorkers=1 -t '版本恢复409|版本刷新中的空间读取失败|恢复面板409后'
Test Files 1 passed; Tests 3 passed | 18 skipped (21)
```

三项分别验证完整 409→关闭→刷新→以 r6 重新确认提交、空间读取失败保留 stale、旧面板在外部空间刷新后仍不可复用旧确认。已阅读实现者报告的 P4 21 tests、typecheck 和格式检查证据，本次未重复全量检查或构建。没有新增临时探针文件，也未修改生产源码或共享测试。

上述批准为前端源码与定向组件测试审查结论；真实后端、浏览器、剪贴板和跨系统联调边界继续按初审“验证方式与边界”执行，并未由本次复审补验。
