# P3 数据管理前端交付报告

日期：2026-10-20。范围：`web/`，以及本报告。依据 `AGENTS.md`、`c5-ui-brief.md`、`contracts/p3-data.md`（包含展示结构补充）与 `onto-data/README.md`。

## 实现状态

前端实现完成，编译与组件回归通过；P3 管理后端的真实浏览器联调、截图和真实实例验收由 root 接续，本文不把契约桩测试写成后端已接通。

新增工作台路由：

| 路由后缀 | 页面与能力 |
| --- | --- |
| `datasources` | 同空间源目录、OWNER 新增/编辑/删除、三阶段连接探测、元数据同步、历史快照、结构 diff 与受影响映射/操作、受管文件上传和文件目录、协议能力及验收状态 |
| `mappings` | 对象/属性/关系三层映射、同空间元数据与本体选择器、变更前影响、完整性四项检查、覆盖分母与定位问题 |
| `data-query` | MODEL / OBJECT / RELATION 模式、对象及关系映射、输出属性、参数化过滤、助手输入参数选择、切回 MODEL |
| `suggestions` | TABLE / FILE / DOCUMENT 三来源预览、批次筛选、规则评分说明、真实来源定位/摘录、候选名称与关联编辑、显式批量接受/拒绝 |
| `data-catalog` | 源与最新快照、映射计数、资源到本体类与操作的绑定读取视图 |

全部路由均位于 `/spaces/:spaceId/`，侧栏沿用深蓝导航、灰底、白卡及既有 Element Plus 风格。

## 组件和类型边界

- `features/p3/` 独立维护五个页面；`SourceForm`、`ManagedUpload`、`MetadataPanel`、`MappingForm`、`MappingList`、`MappingCoverage`、`DataQueryForm`、`ExtractionForm`、`SuggestionReview`、`SuggestionPayloadFields`、`SourceEvidence` 各承担具体业务职责。
- `useMetadataSelection` 负责切源/切快照请求隔离与可恢复读取；未再造全局草稿管理器。
- `packages/api-client/src/data.ts` 显式维护 P3 安全 DTO、元数据变更、完整性问题、目录和候选类型；没有使用 `any`、浏览器存储、运行时演示条目或缺接口时的假成功。
- 共享 API 客户端仅新增 FormData 直传分支，multipart 的 Content-Type / boundary 由浏览器生成；JSON、会话身份、401 迟到令牌保护仍走既有实现。
- 根任务维护的 `generated.ts` 未由本任务手改；没有更改后端、部署、正式设计或其他代理报告，没有 Git 操作，也没有启动/停止服务。

## 权限、凭据与并发

- OWNER 才显示源配置、删除、探测、同步和上传入口；EDITOR 可维护映射与抽取；VIEWER 读取目录/快照/候选，写按钮禁用或隐藏。服务端仍是最终权限权威。
- 安全源概要不读取连接配置。编辑默认 `connection=null` 保留旧密文，只有明确勾选整体替换时才提交新配置；所有凭据使用密码输入，不回显旧凭据，密码首尾空格原样保留。
- FILE 配置只提交 `dialect + fileId`，文件来自当前空间受管目录；选文件自动设置真实资源名和方言，提交前验证 accessScope 精确等于该资源。没有接受客户端绝对路径或 root。
- 上传使用字段 `file` 的 multipart；提前检查 8 MiB 与允许扩展名，实际内容、压缩限制和持久化均由后端裁决。
- 所有草稿写入复用 `readDraftSnapshot / useDraftDocument / useDraftRevision / useMutation`。列表读取时建立基线，打开表单后不借最新 store 修订覆盖；409 保留用户输入并暂停旧表单。
- 删除确认等待期间固定点击时修订与工作区代次；成功响应只由有效 mutation 上下文回写空间。切空间、卸载或同空间重新进入后的旧同步成功不能污染当前修订。
- 切源、切快照先撤销旧 resource 请求；迟到成功/失败均不覆盖当前选择。权限错误显示可重试状态，不回退假数据。

## 业务展示约定

- 能力面板明确区分“已实现”“本地真实实例已验收”“真实实例待外部验收”；当前登记源是否可达以三阶段 probe 结果为准。五类外部实例待验状态按后端能力 DTO 展示，不写死为已连接。
- 覆盖度显示 mapped/pending/unmappable、总分母及 `coverageBasis`；零分母显示“无可统计项”，不显示 100% 或全部完成。
- 映射来源链显示源/资源/快照、对象映射及字段；完整定义可展开查看。JSON 仅用于可选复杂过滤与只读定义展示，基本建模字段均为可选择/编辑控件。
- 抽取预览只生成待确认批次，页面不自动调用决定接口。候选默认不勾选、不决定；批量按钮只影响显式勾选项。接受可编辑名称/类型/关联，拒绝不要求名称并省略 payload。
- 同批候选类与已有同空间类可作为关联目标；实体 ID 与候选 ID 互斥提交，规则评分明确说明不是模型概率。来源位置仅展示服务端提供的真实字段，不补造页码、摘要或置信度。
- 实例查询配置不接受 raw SQL；过滤绑定来自助手输入 Schema 字段，界面说明完整助手声明、策略、空间授权与必需守卫要求，服务端执行前校验仍保留。

## TDD 与验证证据

初始关键反例共 5 项均 RED：FormData 被旧客户端序列化为 `{}`、五路由不存在、源表单/候选审阅组件缺失。实现后转 GREEN。后续反例真实暴露并修复：关系模式接受不属于当前对象的关系映射、密码被 trim 改写、FILE 范围可错配服务端资源名。

新增 P3 19 项测试（`p3.test.ts` 12 项 + `flows.test.ts` 7 项）覆盖：multipart、源连接保留与必填、默认不确认、批量拒绝、候选陈旧输入、零分母、关系映射引用、文件大小/扩展名、精确凭据、FILE 范围、源 409、权限失败重试、VIEWER、旧同步成功、切源迟到快照、先预览后人工确认、映射缺快照拒绝。

2026-10-20 22:43–22:44 最终运行，Node 上限 1024 MiB，Vitest 至多 2 workers：

| 命令 | 结果 |
| --- | --- |
| `pnpm typecheck` | 退出 0，严格 TypeScript 检查通过 |
| `pnpm test --maxWorkers=2 --minWorkers=1` | 16 test files，96 tests 全通过；既有 77 项未删改 |
| `pnpm format:check` | 退出 0，全部匹配文件符合 Prettier |
| `pnpm build` | 退出 0，Vite 1774 modules，生产构建通过（6.70s） |

过程中生产构建发现 Prettier 把多条内联事件赋值改为换行，导致 Vue 模板解析失败；已将三处事件改为命名脚本函数，并重跑最终全部检查。jsdom 只替换下拉浮层，业务表单和生命周期使用真实组件/组合式逻辑；无未处理测试错误。

## 待 root 联调与实际限制

- 本任务未启动服务、未作浏览器真实 API 联调。P3 后端接口尚待接续实现时，页面会显示真实错误与重试，不会伪装已接通。
- root 需要验证源注册/探测/同步、受管上传、三层映射、查询配置、三来源样例抽取与原子确认，并保存浏览器截图；前端契约测试不能替代 PostgreSQL 和真实源的集成证据。
- ClickHouse / MongoDB / Redis / Cassandra / Neo4j 仍遵循用户批准的待外部实例验收边界。本报告不改变其状态。
- 源删除引用拒绝目前展示统一安全错误消息；既有 ApiError 仅保留 code/message/status。已提示 root：若要在错误信封 data/meta 返回结构化影响对象，需要固定该错误详情形状后补展示，或先在安全 message 提供影响对象定位。元数据 diff 与映射 impact 的结构化影响已实现展示。

## 2026-10-20 23:08 · 独立审查四项修复

依据 `c5-ui-review.md` 整批落实 R1–R4，未修改原审查报告。22:55 重跑原独立临时探针，四项全部 RED；修复后 22:58 原四项全部 GREEN。仓内新增 `features/p3/review-regressions.test.ts` 15 项永久回归；首次 12 项失败、3 项既有约束通过，修复后 15 项全部通过。

| 项目 | 修复与验证 |
| --- | --- |
| R1 关系终点属性 | RELATION 从所选关系 from/to 两端构建属性绑定集合，输出和过滤共用；选项显示起点/终点、资源与源字段。主对象必须为 from；两端相同本体属性 ID 不再去重任选，而是从可选项排除，并在选中歧义 ID 时拒绝保存、提示拆分操作。回归覆盖终点标准码投影和过滤、from 约束、歧义拒绝与 OBJECT 仍限当前对象。 |
| R2 HTTP 选项 | OWNER 新建或明确替换连接时，可选 GET/POST、输入固定 POST JSON 对象、配置 pageSize/maxPages；分别校验整数 1–1000 / 1–100，序列化为真实数值。POST 载荷可保留布尔、数字、数组等对象内值；编辑未勾选替换仍提交 null，不回读旧模板或凭据。覆盖 POST 提交、五种分页边界错误和旧连接保留。 |
| R3 同步代次 | 同步开始立即 `metadata.reset()` 使历史 GET 失效；成功再建立新代次，并同步 snapshotId、快照数据、loading/error 与新 diff。更新选择器采用同步 watcher 和明确的同步响应标志，避免重复 GET 覆盖刚返回的完整快照。旧历史请求随后成功或失败，均不能覆盖或遮蔽新快照；切历史选择时清理旧 diff。 |
| R4 JSON 秘密错误 | `objectJson` 在 JSON.parse 边界替换原始异常，语法错误与非对象统一返回固定中文字段消息，不包含输入片段。请求头与新增 POST 模板均用密码控件；两个敏感值错误回归断言普通 alert 不含合成 sentinel。 |

上述修复只修改 P3 业务文件；没有改共享 resource/mutation 生命周期实现。

## 2026-10-20 23:08 · JOIN 多表守卫增量

按 root 新增的 `p3-data.md` 多表守卫契约，例外扩展 `features/p2/GuardsView.vue`、`GuardBindingFields.vue` 与 `packages/api-client/src/supply.ts`。

- 旧 `filter_binding` 单项与新 `filter_bindings` 数组互斥；逐表支持 IN/EQ，保持旧单项形状、原运算符与旧值，不做无提示协议迁移。
- 页面显式支持“主体属性检查 + 数据源列过滤”，读取同时存在的 authorization_rule 与任一过滤形状时完整展示并往返，不再因单一绑定方式选择器丢弃其中一项。
- OWNER 可新增/删除逐表绑定；多表数组允许 1–16 项，同表不可重复。table 仅为 table/schema.table 资源标识，column 仅为单列标识，拒绝 SQL 表达式。
- 如果读取到互斥的旧单项和数组同时存在，默认阻止保存；只有用户明确选择形状后才提交一种。界面说明每张 JOIN 表须显式绑定同一授权属性，纯模型执行不能应用源过滤；不把当前后端未接通解释为可执行成功。
- 新增 `guard-bindings.test.ts` 13 项。首批 11 项全部 RED 后实现；最终覆盖两种协议往返、主体规则并存、新增/移除、旧单项显式迁移、重复/SQL 标识/空数组/17 项拒绝、16 项边界与 409 输入保留。既有 P2 守卫和 mutation 上下文测试保持通过。

## 2026-10-20 23:08 · P2-R2 任意 JSON 根输入配套

按 root 指示配套后端 Schema 的任意 JSON 根支持，扩展的仅是业务输入：

- `InternalCallPanel` 操作输入、`CandidatesView` 试执行输入、`TestCasesView` 用例输入改用公共安全 JSON 解析函数；允许对象、数组、字符串、数值、布尔和 null。非法 JSON 只给固定中文提示，不回显原始解析异常。
- `TestCase.input` 改为 `unknown`，由冻结 Schema 负责业务根类型校验，没有使用 any。编辑既有用例不再用 `input || {}`；false、0、null 均原样展示并重新保存，新用例仍默认空对象。
- Schema、声明、范围、预期结果继续使用对象校验；没有放开这些边界。三处业务标签改为“操作/试执行/用例输入（JSON）”。
- 新增 `json-inputs.test.ts` 20 项；首批 13 项中 12 项 RED，分别复现根值被拒绝和 false/null/0 被替换。最终覆盖 array/false/null/zero/string/true 跨三个入口保真、非法输入不回显、预期结果数组仍拒绝。
- 既有 `supply.test.ts` 只更新该内部调用用例的两处字段标签定位字符串，以匹配要求的新标签；保留原用例和所有业务断言，未删除原 96 项测试。

本轮上述增量所需文件已得到 root 所有权例外授权；未修改 P4 组件、P4 的共享 router/layout/index、ExecutionsView 或 release.ts，未全目录 format-write。

## 复审前最终检查与剩余边界

Node 最大堆 1024 MiB，Vitest 最多 2 workers，2026-10-20 23:07–23:08：

| 命令 | 最终结果 |
| --- | --- |
| 原独立 4 项临时探针命令（见审查报告） | 4/4 GREEN（原先 4/4 RED） |
| `pnpm test apps/onto-admin/src/features/p3 --maxWorkers=2 --minWorkers=1` | P3 目录 5 files / 67 tests 全通过（初交付 19 + 本轮新增 48） |
| `pnpm test --maxWorkers=2 --minWorkers=1` | 全工程 20 files / 163 tests 全通过（含并行 P4 19 项）；原 96 项保留 |
| `pnpm typecheck` | 最终退出 0；期间发现新增测试 generic mock 类型注解错误，已更正并重新完整运行通过 |
| `pnpm format:check` | 最终退出 0；仅定点格式化本人文件，随后全目录只读检查通过 |
| `pnpm build` | 退出 0；Vite 1816 modules，生产构建通过（7.17s） |

源删除引用错误定位已由 root 固定为安全 message 包含影响映射/操作 ID，不需要扩展 ApiError data，初交付报告中的该接口疑点已关闭。本轮没有启动/停止服务、提交 Git 或接入真实 P3 浏览器后端；仍需 root 在管理 API 可用后完成真实三来源/关系查询/多表标签联调与截图。本轮通过的 P4 用例仅报告全量回归事实，不替代 P4 独立审查与真实联调。

## 2026-10-20 23:20 · 扩展审查 R5 / R6 修复

依据独立审查追加部分与当前固定契约修复 R5/R6；原 R1–R4 已由独立复审关闭，本轮不重开其范围，也不修改独立审查报告。

### R5 参数示例允许任意 JSON 根

- `supply.ts` 增加递归 `JsonValue` 类型，`AssistantTool.param_examples` 改为 `JsonValue[]`；保留 JSON 的字符串、数值、布尔、null、数组和对象类型，没有使用 any。
- `fields.ts` 新增 `jsonArrayField`，只检查参数示例的外层为数组，每一项的 Schema 有效性仍由后端契约生成校验。语法错误转为固定中文字段错误，不回显原始输入片段。
- `AssistantForm` 参数示例使用新数组校验；错误语义继续走 `objectArrayField` 并逐项检查 match 与五类 semantic，输入/输出 Schema、返回约定与授权范围继续要求对象，没有共用放宽后的示例边界。
- 新回归覆盖 false、true、null、0、string、array 六种合法根声明的读取/原样保存和表单编辑保存；同时验证外层 false/null/object 或非法 JSON 拒绝、原文保留，以及错误语义的非对象项仍拒绝。

### R6 守卫资源采用协议原始标识

- `GuardsView` 的历史 table 字段不再套 SQL 表名正则，也不再 trim 资源原文。受管 UUID.csv、HTTP /items、Parquet prefix/object.parquet 及原始空格均按原文提交，以免改变后端冻结元数据匹配目标。
- 通用资源校验只限制非空、长度不超过 512、无 C0/DEL/C1 控制字符；数组 1–16 项、原始资源重复、单列标识和 IN/EQ 校验、主体规则并存、旧单项形状、OWNER 和固定修订保护保持。
- 页面将字段说明为“源资源”，明确它可为表、文件、端点或对象键，必须由后端与冻结元数据和协议 accessScope 核验，不能被当成 SQL 片段执行。没有修改 QueryPlanner、后端 SQL 安全规则或源执行能力。
- 新回归覆盖三类非 SQL 资源在旧单项和新数组中的往返、主体规则保留、原始空格不改写、512 字符边界、513 字符及控制字符拒绝。
- 旧 `guard-bindings.test.ts` 的“SQL 表表达式须被通用前端拒绝”用例，按更新契约改为“资源含控制字符须拒绝”；保留该用例数量及错误保留断言。SQL 列表达式拒绝用例未放宽。实际未登记资源仍由后端精确引用校验拒绝，前端不执行资源文本。

### 本轮验证与范围

23:16 独立扩展探针重新复现 **19 项中 11 GREEN、8 RED**；R5 五种既有根声明、R6 三种资源均按原报告失败。新增永久 `features/p3/type-boundaries.test.ts` **33 项**，首次 **28 RED、5 已有边界 GREEN**，修复后全部 GREEN。

23:19–23:20 定向检查，Node 最大堆 1024 MiB、Vitest 最多 2 workers：

| 检查 | 结果 |
| --- | --- |
| `pnpm test apps/onto-admin/src/features/p3/type-boundaries.test.ts apps/onto-admin/src/features/p3/guard-bindings.test.ts apps/onto-admin/src/features/p2/supply.test.ts --maxWorkers=2 --minWorkers=1` | 3 files / **61 tests 全通过**，包括新增 33、既有守卫 13 和 P2 供给 15 |
| 审查报告原临时独立扩展探针命令 | **19/19 GREEN**，包括本轮原先失败的 8 项；2.46 秒 |
| `pnpm typecheck` | 最终退出 0 |
| `pnpm format:check` | 最终退出 0；只对本轮本人修改文件作定点格式化 |

为避开后端冷查询验收 CPU 窗口，按 root 要求没有重跑全量 test/build；前文 163 项和生产构建是前一轮历史证据，不冒充本轮全量结论。本轮只改 AssistantForm、fields、GuardsView、GuardBindingFields、supply.ts 与必要测试及本报告，没有修改 P4、共享路由/布局或后台，没有启停服务或 Git 操作。交还原独立审查者复核；真实 P3 管理 API、关系多表授权与三来源落库联调仍待 root 接续。
