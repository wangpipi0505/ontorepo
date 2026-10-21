# C7 / P1–P4 前端独立审查

日期：2026-10-21。审查者为新建、未继承实施过程的独立 reviewer；遵循本工程 `AGENTS.md`、requesting-code-review 和 vue-skilld。先阅读原需求、契约与当前源码并运行自有反例，之后才对照 C1/C2/C4/C5/C6 既有审查结论。工程暂无提交，按完整工作树审查，不虚构 commit SHA。

## 当前状态（主执行者汇总各独立复核）

- **R1、R2：CLOSED。** 原始反例文件保持不变，2/2 转为 GREEN；另增 7 项确认/迟到边界探针，合计 2 文件/9 项通过。详见文末复核证据。
- **R3、R4：CLOSED（代码与需求切片）。** 新的独立审查者完成13个实现文件复核及九项自有探针，Spec / Quality APPROVE，见 `c7-ui-final-review.md`；未把作者116项测试当作独立证据。
- **Spec / Quality：已发现的四项均经独立复核关闭。** R1/R2原审查者与R3/R4新审查者的范围分别保留，不能说某一人重新审查了全量实现。主执行者已通过四维筛选、真实鼠标拖拽/键盘预填并保存、四条SVG连线/缩放/定位及390px布局；P4真实版本显示仍待新后端制品，见 `c7-browser-completion.md`。

## 初审结论

- **Spec：REQUEST_CHANGES。** 初审发现对象编辑的修订来源缺陷，以及正式详设/开发计划中尚未覆盖的映射交互、空间总览与查询能力。
- **Quality：REQUEST_CHANGES。** 初审发现 P1 成功响应未遵守共享 mutation 上下文的缺口。当前是 **P1 × 1、P2 × 3，无 P0**。
- 主执行者已收到四项问题并开始修复；本文件中的初审发现尚不能视为已关闭。后续独立复核结果另附，不把全量旧测试通过当作这四项的修复证据。

这里的 P1/P2 是缺陷优先级，不是开发期次。P4 后端尚在实现，本审查按固定 `contracts/p4-release.md` 检查前端，不把未能访问真实 P4 HTTP 服务列为 UI 缺陷。

## R1 · P2 · 离开后返回同一空间，旧 P1 写响应会倒退当前修订

初审定位：`web/apps/onto-admin/src/features/p1/useDraftCollection.ts:52`，相关删除分支在第 69 行；全局接收点 `stores/workspace.ts` 的 `accept()` 只比较空间 ID。共享 `useMutation.run()` 提供了 `isCurrent()`，但这些 action 在 `await` 后没有使用它。

可复现顺序：

1. A 空间 r5 的码表页发起保存，保持 POST 响应未完成。
2. 卸载旧页，进入 B，再返回已读取至 r9 的 A。
3. 旧保存响应返回 A/r6。action 内直接调用 `workspace.accept(next)`。
4. 当前工作区从 r9 变回 r6。`run()` 最终返回 false 只能拦截外部通知/刷新，不能撤销 action 内已经执行的全局写回。

独立探针 `old P1 collection success must not roll back reentered workspace revision` 实际收到 **6**，正确断言为 **9**。影响是当前工作区修订/权限等 Space 状态被旧页面覆盖，后续编辑发生错误的冲突或权限展示；本项没有越权修改 B 的后端数据。

同型无条件接收还存在于初审的 `ClassDialog`、`PropertyDialog`、`OperationDialog`、`PropertyPanel.remove`、`OperationsView.remove`、`ImportsView.apply`。这些应按同一个不变量处理：发起上下文失效后，不能再接收全局空间快照、刷新当前页面或继续旧确认产生的写入。不要只在 `useMutation` 返回之后判断。

## R2 · P1 · 类编辑把旧对象内容与属性保存后的新修订组合提交

初审定位：`web/apps/onto-admin/src/components/ClassDialog.vue:31`，`draft.start(workspace.space?.draftRev || 0)`；相关 `views/ModelView.vue:24` 的类列表读取未保存独立列表修订。该页删除、拖动也直接取全局 `space.draftRev`。

独立探针挂载真实 `ModelView`、`PropertyPanel`、`PropertyDialog`、`ClassDialog` 和 Element Plus，通过真实点击与表单提交复现：

1. 对象树读取 r5，类 C 为“旧名称/旧说明”。
2. 另一个编辑者修改 C，服务端进入 r6；当前页的对象树仍是 r5。
3. 用户只点击右侧属性面板“刷新”，读到 r6 的属性；编辑并保存属性，写入使用 r6，返回空间 r7。属性面板不会重新读取对象树。
4. 点击仍显示旧类内容的“编辑对象”，直接保存。
5. 实际 PUT `/classes/class` 为 `{name:"旧名称",description:"旧说明",expectedDraftRev:7,...}`，而正确基线应仍是类列表读取时的 **5**，或必须重新读取最新类再允许编辑。

此处后端看到合法 r7 会接受旧内容，静默覆盖他人在 r6 对类的修改，CAS 保护被前端错误基线绕过。独立断言 `expectedDraftRev:5` 收到 **7**，并非只检查私有函数。修复应保存类列表的读取修订，并让编辑、删除、拖动都使用所见列表的基线；属性面板已有独立 `loadedRev` 的设计可作为参考。

## R3 · P2 · 映射工作台缺少详设要求的双树连线交互

初审定位：`web/apps/onto-admin/src/features/p3/MappingsView.vue:128`、`:162`，当前工作台只有 `MappingList` 和 `MappingForm`；`MappingList.vue` 输出分层按钮与表格，`MappingForm.vue` 使用选择器填写源/快照/字段/类/属性/关系。整个映射入口没有源字段树、本体概念树或源目标连线交互。

明确需求：

- 详设 v2.6 §2.3（第 117 行）：“左侧源字段树 ↔ 右侧本体概念树，拖拽拉线，批量推荐（置信度排序+证据链），人工确认”。
- 同文 §5.1（第 219 行）再次要求“源字段树↔本体概念树，拖拽拉线”。
- 同文 §5.2（第 238 行）规定“左右双栏树 + 连线”“自研拖拽映射交互”。

复现：登记源并同步元数据、建立类/属性后进入 `/spaces/{id}/mappings`，只能逐项打开表单选择；无法从源字段拖到本体属性、看到已有绑定连线或从双树定位关联。因此三层 DTO/CRUD 的完成不能代替这一明确前端能力。本项是正式需求遗漏，不是对布局或图表库的主观偏好；保留表单作为键盘入口是合理的。

可以复用的现有 API：

| 用途 | 现有接口 |
| --- | --- |
| 源及快照树 | `GET /datasources?spaceId`、`GET /datasources/{id}/snapshots?spaceId`、`GET /datasources/{id}/snapshots/{snapshotId}?spaceId` |
| 本体概念树 | `GET /classes?spaceId`、`GET /spaces/{id}/properties`、`GET /relations?spaceId` |
| 已有连线 | `GET /mappings?spaceId`，对象层、属性层、关系层分别由已有稳定 ID 关联 |
| 拖拽后的明确保存 | 复用当前 `MappingForm` 校验及 `POST/PUT /mappings/{objects|properties|relations}`，固定同一读取修订；拖拽不应绕过所需对象标识/转换/连接字段 |
| 影响与覆盖 | `GET /mappings/impact?spaceId&kind&id`、`GET /mappings/validate?spaceId` |
| 推荐/人工确认入口 | 当前 `/suggestions/extract`、`GET /suggestions?spaceId`、`/suggestions/decide` 已提供三来源证据与批量人工确认，可从工作台明确连接 |

实现时需核对同源/同快照、本体类归属、VIEWER、迟到快照、409 固定修订、键盘等价操作和窄屏访问；图只是编辑手势，不能成为绕过现有表单/合同的第二套保存语义。

## R4 · P2 · 空间页缺少全量计划中的总览指标与查询

初审定位：`web/apps/onto-admin/src/views/SpacesView.vue:16` 仅请求 `/spaces`；`:67`、`:71`、`:75` 仅展示对象/属性/操作计数；`router.ts:13` 将空间根路由直接跳到 model。当前没有名称/编码/领域/状态查询控件，也没有满足全部总览字段的页面。

明确需求：

- 开发计划 v1.6 §3 包 D，D-01（第 82 行）：“对象数/关系数/规则数/当前版本/发布状态/校验问题”。
- D-02（第 83 行）：“按名称/编码/领域/状态查询；新建/编辑/进入设计/归档”。
- 详设 §2.1（第 92 行）及 §5.1（第 208 行）均把关系/规则计数、版本、发布状态、校验问题列为空间卡片内容；空间概览另有入口要求。

复现：创建多个领域空间并分别建立关系、规则、冻结/发布/检查后返回空间列表；卡片仍只有三项首批计数，无法区分待发布、已有正式版本或存在校验问题，也不能按约定四字段查找空间。进入空间根路由仍直接到对象树。

可以复用的现有 API：

| 用途 | 现有接口及边界 |
| --- | --- |
| 查询/筛选 | `/spaces` 已有 `name/code/domain/status`，可先对服务端授权可见的完整列表做客户端筛选 |
| 对象/关系/规则计数 | `Space.classCount`、`GET /relations?spaceId`、`GET /rules?spaceId`；读取失败必须显示未知/重试，不能补零 |
| 版本与候选状态 | `GET /releases?spaceId`、`GET /spaces/{id}/candidates`；READY、DEPRECATED、CORRUPTED 与候选状态分别按服务端真实值展示；无版本有明确空态 |
| 当前问题 | `GET /spaces/{id}/conflicts`、`GET /mappings/validate?spaceId`；需要区分尚未检查、读取失败和实际零问题 |
| 空间详情及一致性 | `GET /spaces/{id}` 配合已有 `readDraftSnapshot` 或显式绑定读取代次；概览读取不能给旧表单换修订解锁 |

现有首批 `Space` 契约没有这些聚合字段，不是删除总量需求的授权；可以用已有读取接口组合，或经主执行者固定补充接口后实现。尚未上线的 P4 端点应展示真实可恢复错误，不能产生假版本。

## 已核对的实现边界

| 范围 | 独立核对结果 |
| --- | --- |
| P1 基础/扩展 | 空间/对象/属性/操作、码表、关系、约束、规则、策略、导入、用例和检查有独立页面/业务组件；逆/对称完整字段可编辑并展示超 EL 标记；自动逆关系不回传只读字段；导入须预览和确认；固定候选操作列表不回退草稿。修订缺口见 R1/R2。 |
| P2 | 助手输入/输出 Schema、snake_case 声明、任意 JSON 示例根、守卫单项/多项与主体规则、授权目录、知识来源、冻结包/检索/内部调用的候选标识和摘要均按固定契约处理。P2 保存路径显式核对 `isCurrent()`，未发现 R1 同型全局接收遗漏。 |
| P3 | 源配置仅输入凭据，更新默认 connection=null，秘密不 trim；JSON 错误消息不回显源片段；受管上传保留 FormData；九类能力区分实现与外部验收；映射三层、关系两端属性、歧义拒绝、覆盖分母、三来源确认与实际证据展示符合契约。已存在同步/旧快照代次防护。交互完整性见 R3。 |
| JSON 原值 | `ApiClient` 以 `body === undefined` 区分无 body，false/0/null/数组保留；`TestCasesView` 只给新用例默认 `{}`；`AssistantForm.param_examples` 使用任意 JSON 值数组；候选/正式内部调用根值直接传输。未发现再次强制对象的页面入口。 |
| P4 发布 | publication 响应同时核对 candidateId/SHA/rev；每项门禁按 SHA 判断；只有一致性 NOT_CHECKED/SUBSET_EXCEEDED 可豁免；零用例明确阻断；人工复核需 diff/理由/确认；四类制品就绪且 bundle 摘要匹配才提交；旧候选提示重新冻结。 |
| P4 恢复/消费 | 恢复固定打开时修订并在 409 后锁定，关闭后完整刷新方可重开；凭据仅创建时展示、关闭/卸载/切空间/失权清除；成功调用核对 preview=false、spaceCode、releaseId、vN、bundle 摘要；再次请求前清除旧 data/meta，错误不保留成功证据。 |
| P4 事件/追溯 | DEAD 事件带理由按原 eventId 重放，事件绑定状态与当前版本状态分开；审计游标追加不覆盖前页；trace 节点/边/时间线/证据来自服务端，缺历史字段不补造；错误状态都有读取重试。 |
| 组件/注释/类型 | 未发现生产 `any`、浏览器业务持久化、假数据回退、v-html 或 eval。最大业务 SFC 为 `MappingForm.vue` 409 行（初审），其三层字段同属一项业务；没有以行数或主观风格列阻断。公共并发/授权/恢复/凭据边界有中文注释。 |
| 桌面/窄屏 | 源码有栅格最小宽度、局部滚动、长标识折行、弹窗视口限制、700/650/450px 单列/导航规则；本轮没有启动浏览器，也没有把 CSS 静态存在当作 390px 实际通过。 |

后读的 `c2-frontend-review.md`、`c4-ui-review.md`、`c5-ui-review.md`、`c6-ui-review.md` 均有各自批准范围。C4 明确只修复 P2 成功上下文，未重新覆盖 P1，因而 R1 不与其关闭结论冲突；C2 修复的是属性列表使用类树新修订，R2 是反向的类列表借用属性新修订。这两项均由本轮独立反例发现，不是复述已关闭问题。C1 后端报告仅用于确认前期语义决定，不替代本轮前端核查。

## 独立运行证据与限制

自有探针及配置位于 `.runtime/c7-frontend-review/`，未修改生产测试。于 **2026-10-21 01:01:10** 运行：

```powershell
# 工作目录：G:/207/项目/算法重组工具/代码/ontorepo/web
$env:NODE_OPTIONS='--max-old-space-size=1024'
node node_modules/vitest/vitest.mjs run --config ../.runtime/c7-frontend-review/vitest.config.mts
```

结果：**1 文件 / 2 项正确行为断言，2 项 RED**。第一项实际 r6、预期 r9；第二项实际提交 r7、预期基于所见类列表 r5。原日志保存在 `.runtime/c7-frontend-review/probe-original-red.log`。首次配置 glob 因 Windows 反斜线没有匹配到测试，已在自有配置修正；该装载问题不作为产品发现。

主执行者另有 `pnpm test` 21 文件/199 tests、typecheck、format:check、build 成功记录，本审查未重复全量，也未将其宣称为独立执行证据。

未运行 Maven、服务启停、真实 PostgreSQL/MinIO/P4 HTTP、实际桌面/390px/剪贴板或相邻工程。未改生产源码/合同/部署/已有报告，未提交 Git；仅写本审查报告和自有 `.runtime` 探针/日志。现有 P1/P2/P3 HTTP 和浏览器证据属于根任务，不由本次源码审查重新认证；P4 后端及外部实例未验范围继续由主执行者保留。

## R1/R2 独立复核（2026-10-21 01:08–01:10）

独立读取实施者修复后，确认：

- `useDraftCollection` 保存/删除，`ClassDialog`、`PropertyDialog`、`OperationDialog` 保存，`PropertyPanel`、`OperationsView` 删除以及 `ImportsView` 应用，均在成功异步返回后、接收全局 Space 之前核对 `isCurrent()`。
- 集合、类、操作删除在打开确认框前捕获上下文，确认后核对；属性删除把影响查询、确认与删除置于同一个 mutation 上下文，逐段核对。旧组件卸载后 `useMutation` 的 active 标记会阻止后续提交。
- `ModelView` 保存成功读取的类树 `loadedRev`；`ClassDialog` 打开时接收此修订，类删除在确认前捕获它，拖动也使用它。属性局部刷新/保存不改变这份类树的修订。
- 导入预览、本地文件读取和导出也在迟到结果写入页面状态前核对上下文。

原始 `.runtime/c7-frontend-review/independent.test.ts` **未修改**，于 01:08:22 原样重跑，**2/2 GREEN**，新日志 `.runtime/c7-frontend-review/probe-r1-r2-recheck.log`；原 `.runtime/c7-frontend-review/probe-original-red.log` 保留。

随后仅在审查者自有目录增加 `boundaries.test.ts`，通过真实组件事件及延迟 transport/确认 promise 补充七项：

1. 集合删除等待确认时 A→B→A，不发送 DELETE。
2. 集合删除等待确认时列表刷新至 r7，仍提交点击时 r5。
3. 类删除等待确认时工作区代次变化，不发送 DELETE。
4. 操作删除等待确认时工作区代次变化，不发送 DELETE。
5. 属性删除影响查询返回前卸载，不再打开确认框或发送 DELETE。
6. 属性删除确认等待期间卸载，不发送 DELETE。
7. 导入应用响应返回前卸载并重入 A/r9，旧 A/r6 成功不倒退工作区。

沿用前述 1024MiB Node 命令于 01:09:57 运行全部独立探针，**2 文件 / 9 项 GREEN，退出码 0**；日志 `.runtime/c7-frontend-review/probe-boundaries-green.log`。补充探针首次挂载实际 `ElSelect` 触发 jsdom 中的递归更新，未进入导入断言；在自有探针中采用与原探针一致的原生 select stub 后完成验证。首次环境错误日志保留为 `probe-boundaries-recheck.log`，不把它列为产品缺陷；其余 Element Plus 与业务组件、草稿修订和 mutation 逻辑均使用真实实现。

已阅读实施者新增的 `components/concurrency.test.ts`，其中包含集合迟到、三类对话框迟到及类 edit/remove/drag 修订回归；它及实施者的 33 项 GREEN 不替代以上独立运行。本复核没有重复全量、启动服务或占用浏览器。**R1/R2 据此关闭，R3/R4 保持未关闭。**
