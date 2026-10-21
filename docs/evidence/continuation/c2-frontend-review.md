# C2 / P1 前端独立审查

日期：2026-10-20。21:37 复审结论：**通过本次 C2 代码复审；原 1 项 P1、1 项 P2 均已关闭，共享删除的同根因修复通过，无遗留代码审查发现。** C3 的真实后端、数据库和浏览器验收继续由主执行者完成，本结论不替代 C3 验收。

## 修复复审与关闭证据

已读取最新 `c2-frontend-report.md`、修复源码、新增正式回归，以及更新后的 `contracts/p1-extension.md`。此次未修改业务源码，未操作、停止正在运行的 C3 服务，未执行打包构建。

| 项目 | 复审结论及当前代码位置 |
| --- | --- |
| 原 P1 属性删除修订窗口 | **关闭。** `PropertyPanel.vue:52` 在首个 await 前捕获 `expectedDraftRev`，第 64 行只使用该常量。即使影响查询期间列表刷新到 r6，原删除仍携带 r5，交由后端检测冲突。原独立反例未作任何修改，已转 GREEN。 |
| 共享删除同根因 | **通过。** `useDraftCollection.ts:66` 在确认框等待前捕获基线，第 71 行使用固定值。覆盖码表、关系、约束、规则、策略和用例；正式回归在确认等待期间刷新列表，断言仍携带 r5，已通过。 |
| 原 P2 清空可选范围 | **关闭。** `PropertyFacetsFields.vue:18–19` 明确接受控件的 null 空值，第 55–56 行同时省略 null / undefined，并保留 0。原独立清空下界反例未修改，已转 GREEN；正式用例分别覆盖上下界清空及上下界为 0。 |
| 最新逆/对称关系决策 | **符合。** `RelationsView.vue:107` 展示服务端 `subsetExceeded` 标记，第 174–179 行说明可继续手工维护及 OWNER 对真实超范围检查留痕豁免。写入显式从表单构造，未回传 `subsetExceeded` 或 `inverseOf`；正式组件回归确认超范围关系仍可编辑保存。 |

复审独立执行结果（均 `NODE_OPTIONS=--max-old-space-size=1024`、单 worker）：

| 时间 | 命令 | 结果 |
| --- | --- | --- |
| 21:37:07 | `pnpm exec vitest run --config ../.runtime/c2-review/vitest.config.mts --maxWorkers=1` | 原独立反例 2 文件 / 2 条全部通过；退出 0 |
| 21:37:15 | `pnpm test apps/onto-admin/src/features/p1/review-regressions.test.ts apps/onto-admin/src/features/p1/useDraftCollection.test.ts --maxWorkers=1` | 正式定向回归 2 文件 / 9 条全部通过；退出 0 |

最新完整 50 条回归、typecheck、format 通过来自实施者 21:34–21:35 的既有验证报告；本次独立复审重跑范围仅为上表，不冒称重跑了完整套件。未发现修复引入新的功能问题。

## 原确认问题（均已关闭；保留初审反例与原因）

### [P1] 删除属性必须在读取影响前固定列表修订

位置：`web/apps/onto-admin/src/components/PropertyPanel.vue:48–62`，重点为第 62 行。

原 `remove()` 在开始时检查 `loadedRev.value`，但没有保存该修订；读取引用影响和确认之后，构造 DELETE URL 时再次读取这个可变引用。同时原第 89 行的“刷新”按钮在 `mutation.pending` 时仍可操作。

可复现顺序：

1. 属性列表读到 r5，用户对属性 P 点击“删除”，`GET /properties/P/impact` 尚未返回。
2. 另一用户更新 P，草稿成为 r6。
3. 用户在影响读取期间点击属性面板的“刷新”。读取完成后，`loadedRev.value` 成为 6。
4. 旧影响请求返回无引用，用户确认最初针对 r5 属性发起的删除。
5. 原代码发送 `DELETE ...expectedDraftRev=6`，后端会把它当作针对最新修订的删除，丢失本应由 r5 触发的并发冲突保护。

独立组件反例：`.runtime/c2-review/deletion-revision.test.ts`。使用实际 `PropertyPanel` 和 Element Plus；只替换 API 响应、延迟影响查询和确认框结果。断言预期 URL 为 `/properties/p?spaceId=s&expectedDraftRev=5`，实际捕获 `/properties/p?spaceId=s&expectedDraftRev=6`。

建议：在 `remove()` 入口、首次 `await` 前捕获列表修订，并在整个影响检查与确认流程中一直使用该值。禁用读取期间的刷新可改善交互，但不应替代固定写入基线。

### [P2] 清空数值范围会生成 null，导致无法移除已有上下界

位置：`web/apps/onto-admin/src/features/p1/PropertyFacetsFields.vue:54–55`；错误由 `web/apps/onto-admin/src/features/p1/inputs.ts:25–27` 抛出。

真实 Element Plus `el-input-number` 清空输入时向 `v-model` 发出 `null`。原 `read()` 仅排除 `undefined`，因此将清空后的字段序列化为 `minimum:null` 或 `maximum:null`，继而被 `parseFacets()` 拒绝为“数值范围仅用于数值类型”。

可复现：编辑一个 `INTEGER` 属性，其分面为 `{minimum:3}`；清空“最小数值”后保存。用户要求删除可选下界，但前端在调用 API 前报错，重新填写数值只能保留某个下界，不能移除它。用户新建属性时先输入数值再清空也会触发。

独立组件反例：`.runtime/c2-review/facet-clear.test.ts`。挂载真实 `PropertyFacetsFields` 和 Element Plus，读取现有 3，清空真实 `input[role="spinbutton"]` 并失焦，预期 `read()` 返回 `{primaryKey:false}`，实际抛出上述错误。

建议：模型类型明确接受 `number | null | undefined`，序列化时同时省略 `null` 和 `undefined`，仍保留数值 `0`；对上下界的清空分别覆盖回归。

## 需求与实现核对

已读取 `contracts/p1-extension.md`、`c2-frontend-report.md`、`c2-frontend-review-package.txt`、当前全部 P1 功能源码及相关原有页面，并对照真实 `P1Dtos`、`ApiDtos`、控制器、属性分面、关系、操作、策略、用例和候选检查服务。差异包只列出既有文件的修改，新建 `features/p1/` 文件也已直接审查。

| 范围 | 审查结果 |
| --- | --- |
| 真实提交 DTO | 码表、关系、四类约束、规则/评分、策略、用例、属性分面及四类操作请求字段与实际 Java DTO 相符；关系写入未发送 `inverseOf`。导入应用和候选检查/豁免的特殊请求形式与控制器一致。 |
| 列表与表单修订 | 新增列表使用前后夹读；编辑表单固定打开时修订；409 后旧表单保持锁定，不借新工作区修订重试。属性编辑使用属性列表修订。复审确认属性删除及共享删除均固定发起时修订。 |
| 候选与草稿 | 操作清单读取 `/candidates/{id}/operations`，没有草稿回退；试执行发送结构化输入；检查与用例仅调用候选端点，没有提交草稿规则/用例。候选面板以 ID 为 key，检查/测试/豁免响应校验摘要。 |
| 导入确认 | 预览不提交 apply；明确勾选后才应用；改变原文或格式撤销确认；错误、冲突或过期修订阻止应用。未发现无确认写入路径。 |
| 权限与豁免 | VIEWER 隐藏写入口，方法亦检查写权限；豁免限定 OWNER、同修订、CONSISTENCY/NOT_CHECKED/SUBSET_EXCEEDED，原因为必填。未发现对 INCONSISTENT/TIMEOUT/ENGINE_ERROR 的豁免入口。 |
| 表单结构与风格 | 各领域页面独立，共享组件未引入嵌套 form；详情通过转义文本显示。深蓝导航、白卡、浅灰背景沿用共享变量；两栏表单、对话框最大宽度和候选布局有窄屏规则。真实布局和键盘交互留待 C3。 |
| 验证边界 | 复杂评分、跨类依赖和完整断言有效性仍由服务端拒绝非法输入，这是明确的实现边界。现有 API spy 用例不能证明后端接受了请求，例如用例默认 `expected:{}` 实际会被后端拒绝；本次不把 spy 通过称为联调通过。 |

## 初审验证记录

遵循只读审查范围：未改业务源码、后端、契约或部署；未启动服务；未提交 Git。仅增加 `.runtime/c2-review/` 下的反例和配置，以及本报告。

在 `web/` 执行：

```powershell
$env:NODE_OPTIONS='--max-old-space-size=1024'
pnpm exec vitest run --config ../.runtime/c2-review/vitest.config.mts --maxWorkers=1
```

2026-10-20 21:29:35，Vitest 3.2.7，2 文件 / 2 条反例均失败，退出 1；失败分别为旧删除实际携带 r6，以及清空下界时抛出类型错误。这是故意断言正确行为而得到的 RED 证据。

C2 初版实施报告所列 43 条回归、类型检查、格式检查与构建属于实施者既有证据，初审没有重复执行或冒称独立重跑。两项反例现已转为正式回归；修复与复审 GREEN 证据见本报告开头。C3 继续真实联调及桌面/窄屏验收。
