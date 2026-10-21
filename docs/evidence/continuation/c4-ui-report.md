# C4 / P2 前端实施报告

日期：2026-10-20。依据：`c4-ui-brief.md`、`contracts/p2-supply.md`、工程 `AGENTS.md`。本任务仅编辑 `web/` 与本报告；未修改后端、部署配置或正式设计，未提交 Git，未启动或停止服务。

## 实现与边界

- 新增五个独立工作台路由：`/spaces/:spaceId/assistant`、`guards`、`grants`、`knowledge`、`supply`。保持现有深蓝导航、白卡和灰色画布；表单双列在窄屏折为单列，JSON 预览换行且限制在卡片宽度内。操作列表增加直达该操作助手声明的入口。
- 助手声明拆为页面、声明表单和执行/守卫字段组件。输入/输出 Schema、参数示例、必需守卫、说明属性、风险、四项执行属性、错误语义和返回约定均可编辑。内部字段保留协议 snake_case；示例、默认值、布尔值和数值保持 JSON 类型。允许保存未配齐的草稿，完整生成资格交由真实候选契约接口判断；本地 JSON 错误与可识别的服务端 Schema 字段错误定位到对应字段。
- 守卫定义拆为页面与绑定组件，仅 OWNER 可保存；明确选择“主体属性检查”“数据源列过滤”或“未绑定草稿”。源列过滤结构固定为表/列/IN，不提供任意脚本编辑入口。未绑定草稿显示后续生成/执行阻断说明；源列过滤显示模型执行阶段可能被服务端拒绝的边界。
- 授权页只对 OWNER 读取/开放；本地用户只能选自后端 `grant-users` 目录。维护角色、数据范围对象/null、可信主体属性对象；执行请求不拼接身份或属性。最后一个 OWNER 保护、类型及授权校验均保留服务端权威。
- 知识列表与当前空间概念在同一一致性读取中加载，支持新增、编辑、删除。别名按行去重，概念选择只保留当前空间实体。知识表单状态归页面所有，避免弹窗内部重建丢失 409 后输入；删除捕获点击列表时的修订，等待确认期间不切换基线。
- 单文档草稿读取复用 `readDraftSnapshot`；写入复用 `useDraftRevision` 和 `useMutation`。所有新草稿写带固定 `expectedDraftRev`；409 保留表单、暂停写入，工作区刷新也不能解锁旧表单。
- 候选供给页面只读取冻结候选的契约、知识包与操作；响应核对候选标识和摘要。manifest_preview 额外校验 `ontology_meta.preview=true` 且发布四元组全部为 null。真实候选内部调用保留完整成功信封，业务 data 与 meta 分开展示，校验 `contentDigest=candidate.sha256`、preview=true、releaseId/version=null。
- 知识搜索调用真实 `/knowledge-search`，展示服务端分数、别名、条目标识和同摘要知识包中的来源；没有客户端 contains 搜索，也没有草稿结果兜底。上下文包的概念卡、词典、关系摘要、示例问答均以完整冻结 JSON 展示。
- 保留已有试执行和 LOCAL_TEST 页面；新增内部候选调用表单，明确展示服务端错误码与错误原文。旧格式候选边界在供给页说明，后台拒绝不转换成成功或假正式版本。
- 用例页新增 DIRECT/NL，旧用例缺省 DIRECT；期望操作仍作为断言提交，默认 EXACT，CONTAINS 仍需逐用例选择。界面说明 NL 歧义或实际命中不符应失败。

## RED → GREEN 证据

所有 Node 命令设置 `NODE_OPTIONS=--max-old-space-size=1024`，Vitest 使用 `--maxWorkers=2 --minWorkers=1`。

| 时点 | 命令（web 目录） | 结果 |
| --- | --- | --- |
| 21:41 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2/supply.test.ts` | RED：新增独立页面不存在；初次用例测试字段定位辅助器发现标签元素差异并修正。 |
| 21:42 | 同命令加 `-t '用例允许'` | 有效 RED：缺少“执行模式”字段。 |
| 21:47–21:48 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2/supply.test.ts` | 知识 409 输入保留测试暴露弹窗子组件重建；提升表单状态后首批 10 项 GREEN。 |
| 21:50 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2` | 有效 RED：requestEnvelope 不存在、manifest 发布元数据未经验证、内部调用页面不存在。 |
| 21:50 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2/transport.test.ts` | 有效 RED：消费协议 code/data/meta 成功信封没有 message 时被旧客户端拒绝。 |
| 21:52 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2` | 有效 RED：新增页面缺工作台路由；其他 15 项通过。 |
| 21:53 | 同 supply 测试命令加 `-t '服务端Schema'` | 有效 RED：服务端路径未定位到输入 Schema 字段。实现后测试使用条件等待处理 Element Plus 错误显示延迟。 |
| 21:55 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2` | GREEN：17/17 新增测试通过。 |
| 21:55 | `pnpm test --maxWorkers=2 --minWorkers=1` | GREEN：13 文件、67/67 测试通过，原有 50 条全部保持通过。 |
| 21:55 | `pnpm typecheck` | 通过。 |
| 21:55 | `pnpm format:check` | 通过。 |
| 21:55 | `pnpm build` | 通过，Vite 生产构建生成全部新路由分块。 |

测试覆盖真实 Element Plus 表单提交与 Vue 组件行为；下拉浮层使用原生 select 隔离 jsdom 的定位限制，网络边界使用 API mock。新增检查覆盖助手字段及 JSON 类型、固定修订、409 保留、VIEWER/EDITOR/OWNER 边界、本地目录选人、知识请求、NL、候选摘要与 preview、真实搜索路径/分数/来源、内部拒绝及信封元数据。这些测试不代表真实服务接入已经通过。

## 接口对齐与差异

1. 原契约缺少未授权本地用户目录，已通知主执行者并由其补入 `GET /spaces/{id}/grant-users`（仅 OWNER；返回 userId/username/displayName）；前端据此实现。
2. 消费协议的成功信封示例允许省略 message；新增 `ApiClient.requestEnvelope` 保留 meta，普通 request/get/post 仍返回 data。成功信封兼容缺省 message；带 message 时仍校验其字符串类型。
3. 主执行者已确认：manifest_preview 的 spaceCode/releaseId/version/contentDigest 均为 null，外层 candidateSha256 提供摘要；内部候选执行的 contentDigest 则必须是实际候选摘要。前端按此区分，测试保留该约定。
4. knowledge-search 的既定 hits 不带 sourceRef，前端按同一 context-pack.termDictionary 的 `id` 或 `entryId` 关联 `sourceRef`。已通知主执行者核对后端词典形态；未提供来源时明确显示“冻结知识包未提供来源”，不构造来源。

## 未覆盖与后续验收

- 未执行 P2 真实浏览器、390px 截图、真实 PostgreSQL/服务集成；按任务分工交由主执行者统一验收。CSS 与 jsdom 不作为真实窄屏证据。
- 服务端角色校验、守卫三类拒绝、三个候选入口执行器零访问、同空间限制、最后 OWNER、Schema 全量合法性、Lucene 中文分析器和十查询命中率、NL 错误命中、冻结隔离均需后端及联合验收证明。
- `/contracts/validate` 的正式 manifest 仅校验接口没有新增独立编辑页面；本批 UI 的合同要求是候选完整预览及真实生成错误展示。
- P4 正式 READY、正式发布消费和外部数据源执行不在本批前端范围；没有伪造正式版本或数据执行成功。

## 独立审查修复：迟到写响应的上下文隔离

2026-10-20 22:23，依据 `c4-ui-review.md` 的阻断项完成修复。本轮仅修改前端与本报告；没有启停服务或提交 Git，没有重复生产构建。

确认问题不只影响错误文本：真实 `GuardsView` 在 A 保存后切 B，迟到 409 会设置 B 的全局 stale；A→B→A 时，迟到成功还会把新加载的 r9 回写成旧请求的 r6。知识页直接调用 `mutation.run` 也存在同样边界。

修复内容：

- 工作区新增空间 ID + 上下文代次。进入/重载与 clear 失效旧代次；同步观察 ID 改变，确保同一事件循环的 A→B→A 也不会被当作原上下文。正常 accept/refreshed 不借机解除表单自身的修订锁。
- `useMutation` 在发起请求时捕获上下文，并绑定组件作用域生命周期。迟到失败仅在原上下文仍有效时显示错误及设置全局 stale；页面卸载、空间切换或重新加载后返回 false，不触发成功后的刷新/通知。
- P2 `useDraftDocument` 及知识新增/修改/删除在接收成功空间快照前检查同一上下文，阻止旧成功响应覆盖重新加载的同空间修订。
- 真实当前上下文的 409 行为保持：全局停止写入、用户输入保留、表单仍锁住旧修订；即使共享工作区 refreshed，也不能重试旧表单。

RED/GREEN：

| 时间 | 命令（web 目录，Node 1024m、最多 2 worker） | 结果 |
| --- | --- | --- |
| 22:20 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/stores/workspace.test.ts apps/onto-admin/src/features/p2/mutation-context.test.ts` | 有效 RED：8 项失败、2 项原冲突行为通过。复现不同空间、A→B→A、同空间重新加载、已结束作用域、真实守卫与知识页面迟到 409，以及守卫成功响应将 r9 改成 r6。测试先隔离 jsdom 下拉浮层后重跑，无未处理错误。 |
| 22:21 | `pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/features/p2/mutation-context.test.ts -t '迟到SUCCESS'` | 有效 RED：知识成功响应同样将 r9 改成 r6。 |
| 22:22 | 上述两个测试文件加 `supply.test.ts`、`useDraftRevision.test.ts` | GREEN：27/27 通过；包含当前冲突保留、旧修订不可解锁的原回归。 |
| 22:22 | `pnpm typecheck` | 通过。 |
| 22:22 | `pnpm test --maxWorkers=2 --minWorkers=1` | 共享 mutation 影响全部写入口，因此运行一次全量回归：14 文件、77/77 通过（原 67 + 本轮 10）。 |
| 22:23 | `pnpm format:check` | 通过。 |

新增实际组件回归使用真实 GuardsView/KnowledgeView、Element Plus 表单、原修订组合逻辑和工作区；只用受控网络 Promise 驱动迟到时序。没有把这些 jsdom 检查当作真实浏览器/后端联调证据。
