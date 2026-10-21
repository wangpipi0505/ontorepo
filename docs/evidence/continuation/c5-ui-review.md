# P3 前端独立审查

最新结论（2026-10-20 23:21 最终复审）：R1–R6 全部关闭，所审前端范围的 Spec 与 Quality 均为 **APPROVE**。审查者自行重跑原扩展探针 **19/19 GREEN**。这不代表 P3 真实后端联调完成；下文保留历轮记录，最终结论及边界见末尾。

审查时间：2026-10-20 22:51。审查对象：`c5-ui-review-package.txt` 的 24 个文件及对应当前工作树源码；仓库没有提交基线，按完整源码审查。只产出本报告，未修改生产源码、既有测试或其他报告，未启停服务、提交 Git。

依据：`AGENTS.md`、`contracts/p3-data.md`（含展示结构、impact kind 和关系查询两端属性补充）、`onto-data/README.md`、`c5-ui-brief.md`、`c5-ui-report.md`；补充核对详设 §7.2 的“任务使用算法”出口。使用 vue-skilld、code-review-expert。P2 已审查内容仅作为共享上下文核对，不重复宣称独立完成 P2 验收。

## 两项结论

| 审查 | 结论 | 依据 |
| --- | --- | --- |
| Spec | **REQUEST_CHANGES** | R1 阻断关系查询必需示例；R2 无法通过界面配置已承诺的 HTTP POST 模板和分页边界。 |
| Quality | **REQUEST_CHANGES** | R3 元数据旧响应能覆盖同步新结果；R4 凭据输入的 JSON 语法错误会明文回显输入。 |

共 4 项：P1 × 1、P2 × 3；无 P0。以下按整批修正项列出，修复后应分别补回归再复审。

## R1 · P1 · 关系查询不能选择终点对象的输出或过滤属性

位置：[DataQueryForm.vue:25](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/DataQueryForm.vue:25)，以及同文件保存校验第 56–70 行、输出和过滤选择器第 117–136 行。

触发：已有任务对象映射 `task`、算法对象映射 `algorithm`，关系映射 `uses` 的 from 为 task、to 为 algorithm；算法标准码属性只映射到 algorithm。选择 RELATION、task、uses 后需要返回算法标准码，或加载已有 `propertyIds: ['algorithm-code']` 的配置。

问题：`available` 无论模式都仅按 `item.objectMappingId === state.objectMappingId` 取属性，终点属性既不出现在选择器中，也会在保存时被判为无效。过滤属性使用同一集合，同样受限。独立探针传入上述合法配置后触发表单提交，`save` 事件为 `undefined`。

影响：详设 §7.2 的 `SELECT a.std_code ... task JOIN algorithm ...` 示例无法从界面配置；已存在的合法终点投影配置也无法重新保存。契约操作查询段已明确 RELATION 支持两端已映射属性，主对象必须为 from。

建议：按选中关系关联 from/to 两个对象的属性映射，输出与过滤共用该可定位集合；选项展示所属对象/字段。若同一本体属性 ID 同时绑定两端，按契约拒绝歧义并提示拆分操作，不能用 Set 去重后任选一端。补充终点投影、终点过滤、起点约束及两端同 ID 歧义回归。

## R2 · P2 · HTTP 源缺少受支持的请求模板与分页配置

位置：[sourceFields.ts:39](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/sourceFields.ts:39)、[SourceForm.vue:94](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/SourceForm.vue:94)，模板第 173–185 行。

触发：OWNER 登记仅支持固定 JSON POST 的 HTTP 端点；或登记需要 `pageSize=200/maxPages=10` 的分页端点；或显式替换此类已登记源的连接。

问题：HTTP 通用字段仅提供 baseUrl/pageParam，专用字段只补 endpoints/headers，没有 method、body、pageSize、maxPages，提交体也不含这些字段。契约引用的 `onto-data/README.md` 第 21、50 行已支持 GET/固定 JSON POST 与分页声明；`HttpSourceAdapter.java` 第 80–85、187–188 行实际读取这些设置，默认值为 GET、100、1。

影响：POST 端点不能登记为可执行配置；满页后仍有下一页的读取可能命中 `HTTP_PAGINATION_LIMIT`，每页超过默认 100 条则命中 `HTTP_PAGE_SIZE_EXCEEDED`。用户明确整体替换连接时也无法重建原本合法的 POST/分页设置。独立组件探针确认 HTTP 类别下不存在请求方法选择器；源码同时确认另外三项未实现。

建议：增加 GET/POST、POST 固定 JSON 对象及合法分页数值控件并序列化到 connection，沿用服务端限制。编辑仍须默认 `connection=null`，仅明确整体替换时要求输入。POST body 如可能含敏感值，错误处理一并遵循 R4。补充 POST 提交、分页边界及保留旧连接的测试。

## R3 · P2 · 同步结果会被未完成的历史快照请求覆盖

位置：[SourcesView.vue:145](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/SourcesView.vue:145)，相关旧读取位于第 59–66 行、busy 定义第 45 行。

触发：选择历史快照 old，GET 尚未完成时点击“同步元数据”；同步先返回快照 new 与新 diff，随后 old 的 GET 成功或失败。

问题：历史元数据读取不计入 busy，因此该操作序列可由正常界面触发。同步只直接执行 `metadata.data.value = result.snapshot`，没有撤销旧 `useResource.load` 的序号，也不清除其 loading/error；旧请求仍有权写回。`snapshotId` 也仍指向先前选择。

证据：独立探针延迟 old GET，先完成同步并返回 new，再释放 old GET，最终传给 `MetadataPanel` 的 snapshot.id 为 **old**，预期 new。新 diff 仍保留，因而界面会组合新影响信息与旧结构；如果旧读取失败，其错误状态还会遮住同步结果。

建议：把同步结果纳入元数据资源的请求代次，在写入新快照前使旧读取失效，并同步当前快照选择、loading/error。回归覆盖旧成功、旧失败和所选快照 ID；无需改变共享 P2 生命周期实现。

## R4 · P2 · 请求头 JSON 解析错误明文暴露凭据输入

位置：[sourceFields.ts:87](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/sourceFields.ts:87)、[SourceForm.vue:108](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p3/SourceForm.vue:108)，错误渲染第 188 行。

触发：OWNER 在密码类型的“可选请求头（JSON 对象，可能含凭据）”输入框粘贴 token 或带凭据但语法有误的 JSON 后保存。

问题：`objectJson` 的 `JSON.parse` 异常直接传出，SourceForm 将原始 `cause.message` 渲染为普通错误段。V8 的语法错误可以携带输入片段，密码控件的遮挡因此失效。

证据：独立探针仅使用合成值 `SENTINEL_SECRET`，实际普通 alert 文本为 `Unexpected token 'S', "SENTINEL_SECRET" is not valid JSON`。未使用真实凭据，也未向后端发送此值。

建议：在 JSON 解析边界捕获 SyntaxError，只返回固定中文字段错误（如“请求头必须是有效 JSON 对象”），不要渲染原始解析消息或输入片段。补充错误段不包含合成凭据的回归。

## 已核对且未发现额外阻断的问题

- 五路由、侧栏和各页面职责拆分符合现有 Vue 3/Element Plus 工作台结构；没有新增浏览器业务持久化、假数据回退或 `any` 掩盖类型。
- P3 DTO、目录与 capabilities 展示形状对齐固定契约；impact 请求使用 `objects|properties|relations`，实体类别展示使用 `OBJECT|PROPERTY|RELATION`。元数据字段、关系、索引与当前适配内核定义相符。
- OWNER 源配置、探测、同步、上传入口与 EDITOR/VIEWER 写边界存在；服务端仍须执行真实授权。安全概要不回读 connection；编辑默认 null，凭据输入不 trim 密码；FILE 新配置使用当前空间 fileId 和服务端 resource，不接受客户端 root/path。
- 三层映射使用元数据与本体选择器；关系两端候选按同源同快照筛选；覆盖度显示实际计数和 coverageBasis，零分母不伪造 100%。引用与基数最终校验仍归后端。
- 三来源抽取仅生成预览，人工决定默认空，拒绝省略 payload；候选关联在已有类与同批候选之间互斥提交；来源位置按响应原样展示，未补造页码或概率。
- P3 复用读取修订基线与 mutation 工作区代次；正常切源/切快照 reset 及页面卸载隔离存在，409 保留输入。R3 是同步直接赋值绕过资源代次的局部缺口。
- API 客户端 FormData 分支保留原对象、不设 multipart Content-Type；普通对象仍 JSON.stringify 并设置 application/json，原有令牌和迟到 401 保护路径保留。
- 源删除引用详情按 root 固定方案由安全 message 提供映射/操作 ID；本次不要求扩展 ApiError data。五类外部实例继续按用户批准的待验状态展示。

## 独立验证及边界

未复跑交付者已报告的 96 项全量回归/typecheck/format/build；阅读了 P3 两份测试，现有用例未覆盖以上四项。另在共享工程之外建立临时 Vitest 探针，加载真实 Vue 组件、Element Plus、现有组合式逻辑，仅替换 jsdom 下拉浮层及 API 响应，不影响正在运行的页面或服务。

命令（工作目录为本工程 web）：

```text
node --max-old-space-size=1024 node_modules/vitest/vitest.mjs run --config C:/Users/Administrator/AppData/Local/Temp/ontorepo-p3-ui-review-20261020/probe.config.mjs --maxWorkers=2 --minWorkers=1
```

22:51:26 运行结果：1 test file，4 个预期行为断言全部 RED，持续 2.05 秒，分别对应 R3/R1/R2/R4；这些失败是缺陷复现，不是交付回归通过证据。探针保留于上述临时目录，未纳入生产源码或共享测试。

本次没有真实 P3 后端/浏览器联调，没有验证数据库持久化、真实网络探测、查询授权下推或抽取原子落库；这些仍须在后端接通后验证。组件/API 桩测试不作为 P3 真实联调或九类实例验收结论。

## 2026-10-20 23:13 · 独立复审与扩展审查

本轮以当前源码和交付报告 23:08 增量为准，未使用已过时的原 review-package 推断修复状态。范围为 R1–R4 修改文件，以及 P2 GuardsView/GuardBindingFields/InternalCallPanel/fields、P1 inputs/TestCasesView、CandidatesView 业务输入、API types/supply 和三份新增测试。为验证任意 JSON 根的完整配置链，另追踪 AssistantForm 与 ContractGenerator 的参数示例边界。CandidatesView 的真实状态及 preview 说明仅核对这两处呈现；其他 P4 工作不在本报告结论内。

| 审查 | 当前结论 | 说明 |
| --- | --- | --- |
| Spec | **REQUEST_CHANGES** | 原 R1–R4 均关闭；新增 R5 阻断非对象根助手声明，R6 阻断 FILE/HTTP/Parquet 等资源守卫配置。 |
| Quality | **REQUEST_CHANGES** | 已确认原异步覆盖与敏感错误缺陷修复；仍存在输入类型与资源类型在前端校验边界被过度收窄的两项真实缺陷。 |

当前未关闭：P1 × 2（R5、R6）。原 P1 × 1、P2 × 3 全部关闭，不重复计入未关闭问题。

### 原 R1–R4 复核

| 项目 | 当前源码及独立证据 | 结论 |
| --- | --- | --- |
| R1 关系两端属性 | DataQueryForm 按选中关系构建起点/终点绑定，输出和过滤共用；歧义 ID 排除并阻止提交，仍要求主对象为 from。原终点投影探针通过。 | CLOSED |
| R2 HTTP 配置 | SourceForm 增加 GET/POST、固定 POST 对象及 pageSize/maxPages 整数范围，提交真实数值；未明确替换仍为 null。原配置控件探针通过，另检查仓内 POST 载荷与边界断言。 | CLOSED |
| R3 同步旧响应 | 同步开始 reset 撤销旧读取，成功再 reset 并同步 snapshotId；同步 watcher 配合 applyingSyncedSnapshot 避免新快照重复读取。原延迟历史成功覆盖探针通过；仓内也覆盖迟到失败。 | CLOSED |
| R4 敏感 JSON 错误 | objectJson 在 JSON.parse 边界捕获并替换固定中文错误，POST body 同样走该边界。原合成凭据错误探针通过。 | CLOSED |

23:12:04 独立重跑原四项临时探针：**4/4 GREEN**，用时 2.30 秒。此结论来自审查者自行运行，而非转述交付者结果。

### R5 · P1 · 非对象根输入仍无法维护匹配 Schema 的助手参数示例

位置：[AssistantForm.vue:56](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/AssistantForm.vue:56)、[fields.ts:28](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/fields.ts:28)、[supply.ts:8](G:/207/项目/算法重组工具/代码/ontorepo/web/packages/api-client/src/supply.ts:8)。

触发：声明 `inputSchema: {type:"boolean"}`，参数示例为 `[false]`；或读取已有数组/null/数值/字符串根声明后保存。业务输入现已能通过三个执行入口，但要建立或维护该操作仍须经过助手声明表单。

问题：AssistantForm 的参数示例依然调用 `objectArrayField`；该函数逐项拒绝非对象、数组和 null，API 类型也仍为 `param_examples?: JsonObject[]`。这与错误语义映射应为对象数组的约束混用了。ContractGenerator 第 23–26 行要求示例数组非空，并将**每项本身**按 inputSchema 校验；给 boolean/array 根改填 `{}` 不可能成为合法替代。

独立证据：分别以 boolean=false、null=null、number=0、string="hello"、array=[1,false,null] 五种 Schema 匹配的初始声明挂载真实 AssistantForm，提交后全部没有 `save` 事件。五项断言均 RED。该缺陷既阻断新增，也使已存合法声明无法通过界面原样保存。

修正要求：参数示例允许“任意 JSON 值组成的数组”，只校验外层为数组并保留每项原类型；API 类型同步表达该边界。错误语义映射继续使用对象数组校验，Schema、返回约定和授权范围继续保持对象边界。补充上述五类及 true 的声明保存/读取往返，不以只测执行入口替代声明链。

### R6 · P1 · SQL 表名正则阻断非关系源的必需守卫绑定

位置：[GuardsView.vue:84](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/GuardsView.vue:84)，相关用户提示位于 [GuardBindingFields.vue:127](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/GuardBindingFields.vue:127)。

触发：OWNER 为 FILE 的受管 UUID.csv、HTTP `/items` 或 Parquet `prefix/object.parquet` 配置 region 源过滤绑定。`filter_bindings` 单项数组或旧 `filter_binding` 均走相同表名校验。

问题：当前通用结构校验要求 table 匹配 `[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?`，因此 UUID 文件的连字符/数字开头、HTTP 端点斜线及 Parquet 对象键均被拒绝，PUT 不会发出。三种源需要真实行标签过滤，不能用只有主体属性检查的守卫替代。

独立证据：三个探针分别加载 table=`7fa12d3a-4e5b-4b21-a5aa-2357aa2e9090.csv`、`/items`、`prefix/object.parquet`，column=`region`、operator=`IN`。提交后 `api.put` 调用次数均为 0，三项断言 RED。

修正要求：按当前 `contracts/p3-data.md` 多表守卫补充，历史字段 table 表示协议原始资源标识，通用层仅校验非空、长度不超过 512、无控制字符，继续检查数组 1–16 项及资源重复；不要统一套 SQL 名正则。实际源/资源/字段必须由 P3 服务端对冻结 accessScope/元数据逐项精确匹配，SQL QueryPlanner 仍独立限制标识符，原始资源字段不能直接拼成 SQL。界面文字应说明资源含义并保留可恢复错误。后端尚未接通不属于此次 UI 缺陷证据，后端对应支持由 P3 后续任务负责。

### 扩展核对与独立验证

- 多表守卫当前对主体规则与单项/数组组合保留完整；旧单项 IN/EQ 未静默迁移，形状冲突默认拒绝，1–16 项、重复、列名和操作符校验存在，409 沿用固定修订并保留输入。独立双表 + 主体规则往返探针通过；R6 仅定位协议资源识别范围。
- InternalCallPanel 使用 jsonField，CandidatesView 和 TestCasesView 使用 parseJson；编辑已有用例以对象是否存在决定缺省，不再把 false/0/null 替换为 `{}`。字段标签和固定语法错误相符。
- 独立使用**真实 ApiClient 序列化分支**和模拟 fetch，分别发送 false、null、0、string、true、array 六个根值：请求 body 均精确等于 JSON.stringify 原值，Content-Type 均为 application/json，六项通过。身份没有进入业务载荷。
- 读取并检查新增 48 项仓内回归（15 项原审查修复、13 项守卫、20 项 JSON 输入），其现有范围未覆盖 R5 声明保存与 R6 非 SQL 资源。未重复运行已报告的 163 项全量/typecheck/format/build。

沿用本报告临时目录和命令，23:12:40 扩展独立探针共 **19 项：11 GREEN、8 RED**，用时 2.57 秒。11 项通过为原 R1–R4、六种原始 JSON 序列化及主体规则/双表组合往返；8 项失败为 R5 五种根值、R6 三种资源。没有将这些真实失败忽略或降级。

本轮只修改本报告及自有临时探针，未修改产品源码、共享测试/target 或服务，未运行 Maven、未涉及相邻工程。**真实 P3 后端联调、三来源抽取/人工决定落库、关系查询和多表标签授权下推仍未完成**；本次复审与桩探针不替代该出口，也不扩展 P4 审查结论。

## 2026-10-20 23:21 · R5 / R6 最终独立复审

本轮只复核 R5/R6 修复及其直接边界，依据已冻结当前源码、`c5-ui-report.md` 23:20 补充、原独立探针和新增永久 `type-boundaries.test.ts`。未重新审查无关 P4 代码。

| 审查 | 最终结论 | 范围 |
| --- | --- | --- |
| Spec | **APPROVE** | 本报告前端审查范围内 R1–R6 全部关闭，未发现新的阻断项；API 真实实现与联调出口仍单独验收。 |
| Quality | **APPROVE** | 类型边界、敏感错误、异步代次及此次放宽所需的保留约束已核对；无未关闭审查问题。 |

### R5 · CLOSED

[AssistantForm.vue:56](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/AssistantForm.vue:56) 的参数示例现调用独立 `jsonArrayField`，外层仍必须为数组，内部任意 JSON 根及嵌套值保留；[supply.ts:4](G:/207/项目/算法重组工具/代码/ontorepo/web/packages/api-client/src/supply.ts:4) 使用递归 `JsonValue`，`param_examples` 为 `JsonValue[]`。错误语义仍调用 `objectArrayField` 并检查 match/semantic，输入/输出 Schema 和返回约定仍走对象校验。

原五项 boolean/null/number/string/array 声明探针全部转绿，能读取并原样提交。另阅读新增永久测试，覆盖 true、通过表单修改示例、外层非数组/非法 JSON 拒绝及安全错误、错误语义非对象项仍拒绝。修复未将全部 JSON 编辑字段共用放宽边界。

### R6 · CLOSED

[GuardsView.vue:75](G:/207/项目/算法重组工具/代码/ontorepo/web/apps/onto-admin/src/features/p2/GuardsView.vue:75) 保留 table 原文，不再 trim 或统一套用 SQL 表名正则；通用校验为非空、至多 512 字符、无 C0/DEL/C1 控制字符。旧单项/多项互斥、1–16 项、资源重复、单列标识、IN/EQ、主体规则并存、OWNER 权限和固定修订路径保留。界面已将字段说明为协议源资源，并明确后端冻结元数据/授权范围核验及禁止作为 SQL 片段执行。

原 UUID.csv、`/items`、`prefix/object.parquet` 三项探针全部转绿，PUT 载荷保留原资源。另阅读新增永久测试，覆盖旧单项/数组两形态、主体规则、资源空格保真、512/513 边界及控制字符；旧守卫测试的 SQL 列表达式拒绝、重复、数组上下界与 409 断言仍保留。

### 本轮独立验证及限制

审查者于 **23:21:28** 自行执行本报告原临时探针命令：**1 file / 19 tests 全部通过，2.91 秒，退出 0**。其中包含原 R1–R4，原 R5 五项、R6 三项，六种真实 ApiClient JSON 序列化及主体规则/双表组合往返。本轮没有修改探针以删除或放宽原断言。

阅读了新增永久 33 项和相关守卫测试的实际断言；没有重复运行交付者已报告的定向 61 项、typecheck/formatcheck，也没有把前轮全量 163 项/build 记录当作本轮新运行结果。

本轮只修改此报告，未改产品源码、共享测试、服务或 target，未运行 Maven。root 正在进行的真实浏览器声明/业务输入核验属于另一份验收证据；本报告未亲自验证其结果。**P3 管理后端、真实源探测/同步、三来源原子确认、关系查询及多表标签授权下推的真实联调仍未完成**，前端 APPROVE 不替代这些出口，也不代表 P4 已通过独立审查。
