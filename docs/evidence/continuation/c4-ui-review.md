# C4 / P2 前端独立审查

日期：2026-10-20。依据：`c4-ui-review-brief.md`、`c4-ui-brief.md`、`contracts/p2-supply.md`、`c4-ui-report.md`、`c4-ui-review-package.txt` 与工程 `AGENTS.md`。

## 结论

- **Spec：本次前端代码审查通过。** 五个独立页面、助手协议字段与 JSON 类型、OWNER 管理边界、冻结候选预览/搜索、内部调用与 NL/EXACT 表单在本次审查范围内符合约定；原切空间迟到响应隔离缺陷经定向复审已关闭。真实服务与 390px 浏览器验收仍由主执行者完成，本报告不把它们认定为已通过。
- **Quality：通过，当前无未关闭阻断项。** 按页面、业务组件、组合式逻辑、客户端、共享样式拆分合理，没有为本批引入额外依赖；共享 mutation 现已绑定空间 ID、上下文代次和作用域生命周期，P2 成功快照接收也核对该上下文。

## 定向复审与关闭（2026-10-20 22:24）

只复审上述发现及关联成功响应边界，没有扩大到其他 P1 业务。核对 `workspace.ts`、`useMutation.ts`、`useDraftDocument.ts`、`KnowledgeView.vue` 与新增测试后确认：

- 请求发起时捕获空间 ID 与上下文代次；空间 ID 同步变化、工作区重新 load、clear 会使旧上下文失效，A→B→A 不能仅凭相同 ID 重新获得资格。
- mutation 绑定组件作用域；失效的迟到错误不会写错误文本或全局 stale，失效的成功返回 false，不会触发页面后续刷新/通知。
- useDraftDocument 和知识新增/修改/删除在 `workspace.accept(next)` 前显式调用 `isCurrent()`，旧请求的 r6 不再覆盖重新载入的 r9。
- 当前有效 409 仍暂停当前空间写入、保留真实输入，并保持表单旧修订锁；共享 `workspace.refreshed` 不解锁旧表单。

独立复跑原真实 GuardsView probe，仅将末尾断言改为正确状态。2026-10-20 22:24:36 结果 1/1 通过，输出：

```text
LATE_CONFLICT {"currentSpace":"B","stale":false,"canWrite":true}
```

随后以 Node 1024m、最多 2 worker 定向运行：

```powershell
pnpm test --maxWorkers=2 --minWorkers=1 apps/onto-admin/src/stores/workspace.test.ts apps/onto-admin/src/features/p2/mutation-context.test.ts
```

2026-10-20 22:24:43 结果：**2 文件、11/11 通过**（已有 1 项正常冲突 + 新增 10 项）。包含 A→B、A→B→A、同空间重载、作用域结束、真实守卫/知识迟到 409、旧成功快照隔离，以及当前 409 的输入和修订锁保持。

**关闭结论：原 P2 发现已修复，本次 Spec 与 Quality 代码审查通过，没有新增阻断项。** 接受实施报告的全量 77/77、typecheck、format:check 通过记录，未重复全套；原生产构建证据不等同本轮新构建。本轮只更新本审查报告，不改业务、不运行 Maven、不启停服务、不提交 Git。真实浏览器与后端联合验收边界保持不变。

下文“发现”中的实际结果和旧断言保留为首次审查的历史证据；仓库外原 probe 当前已更新为本段的正确状态断言，可使用原命令重跑。

## 发现

### [P2，已关闭] 旧空间保存的迟到 409 会暂停新空间写入

**定位：** `web/apps/onto-admin/src/features/p2/useDraftDocument.ts:29–30`；真实入口为 `web/apps/onto-admin/src/features/p2/GuardsView.vue:68–69`；实际全局副作用发生于 `web/apps/onto-admin/src/composables/useMutation.ts:19–20`。

**触发步骤：**

1. 在 ACTIVE、OWNER 的空间 A 打开空间守卫，建立修订 5 的编辑基线。
2. 提交保存，保持 `PUT /spaces/A/guards` 请求未完成。
3. 使用仍可用的导航离开该页，进入另一个 ACTIVE、OWNER 的空间 B；此时 A 页面已经销毁，B 可正常写入。
4. A 的请求迟到返回 `ApiError('DRAFT_REV_CONFLICT', ..., 409)`。

**实际结果与后果：** `useMutation.run` 在异步 catch 中重新取全局工作区并无条件 `markStale()`；当前空间已经是 B，所以 B 被错误标记为过期，`workspace.canWrite` 变成 false，B 的编辑/内部试调用入口随之禁用，并出现不属于 B 的草稿冲突提示。刷新 B 才能恢复。A 的请求本身没有写入 B；本项是跨空间 UI 状态污染，不是越权写入或数据丢失。

**期望：** A 的迟到冲突只能影响 A 的编辑上下文；离开/销毁旧上下文后，不能改变 B 的过期标记。`useResource` 的读取序号保护和 `workspace.accept` 的同 ID 保护不能覆盖该 catch 副作用。建议在共享 mutation 或其调用契约中捕获发起请求的空间和上下文序号，并在处理全局冲突状态前核对；保留当前空间正常 409 的输入保留/暂停写入行为。知识页直接使用 `editor.mutation.run`，修复时须同时覆盖该调用形态。

**定向实证：** 仓库外临时组件 probe 挂载真实 `GuardsView` 和 Element Plus，mock 仅控制网络时序。确认实际发出 `PUT /spaces/A/guards`、`expectedDraftRev:5` 后，卸载组件并把工作区切至 B，再拒绝旧请求。输出：

```text
LATE_CONFLICT {"currentSpace":"B","stale":true,"canWrite":false}
Test Files 1 passed (1)
Tests      1 passed (1)
```

测试断言确认当前空间仍为 B、迟到响应到达前可写、到达后 `stale=true/canWrite=false`，故通过表示成功复现缺陷。运行命令（在 `web` 目录）：

```powershell
$env:NODE_OPTIONS='--max-old-space-size=1024'
pnpm exec vitest run --config G:/207/.ontorepo-c4-ui-review/vitest.config.mjs --maxWorkers=2 --minWorkers=1
```

证据源：`G:/207/.ontorepo-c4-ui-review/probe.test.ts`。关键时序如下，probe 保留完整依赖导入与挂载配置以便原样重跑：

```ts
await wrapper.get('form').trigger('submit')
expect(api.put).toHaveBeenCalledWith('/spaces/A/guards', {
  expectedDraftRev: 5, guardCfg: {},
})
wrapper.unmount()
workspace.space = b
expect(workspace.canWrite).toBe(true)
reject(new ApiError('DRAFT_REV_CONFLICT', 'A草稿已变化', 409))
await flushPromises()
expect(workspace.space?.id).toBe('B')
expect(workspace.stale).toBe(true)
expect(workspace.canWrite).toBe(false)
```

## 已检查范围及无新增阻断项的部分

| 范围 | 本次检查结果 |
| --- | --- |
| 助手声明 | Schema 和参数示例按 JSON 解析，数组内对象/布尔/数字/默认值保持类型；`snake_case` 字段保留；风险两类、错误语义五类与现有协议校验器一致；允许未配齐草稿，完整生成错误由真实接口返回并在可识别字段下展示。 |
| 修订与切换操作 | 一致性读取前后核对修订，保存使用表单固定基线；409 保留输入且表单自身 stale 不会被工作区刷新自动解锁；读取操作切换由 resource 序号隔离。迟到写错误存在上述问题。 |
| 权限与身份 | 守卫只有 OWNER 能保存；授权页对非 OWNER 不读取目录/授权；授权用户来自 `grant-users`；普通助手/知识写入口依实际角色与 ACTIVE 状态开放；内部调用 body 只有操作输入。 |
| 守卫结构 | 明确区分主体属性、源表列 IN 绑定、未绑定草稿；无任意脚本入口；未绑定和模型执行不可应用源过滤的限制有提示。 |
| 冻结供给 | contracts/context-pack/search 都核对 candidateId/candidateSha256；manifest 额外要求 preview=true、发布四元组全 null；内部调用按候选摘要要求 contentDigest 且 releaseId/version=null，元数据失败不展示业务结果。 |
| 知识与来源 | 管理引用限制为当前空间概念；搜索走真实接口并展示分数；来源按同摘要词典 id/entryId 关联。只读核对现有 FrozenKnowledge.context 的词典形态会保留 id/sourceRef 并补 entryId，与前端关联一致。 |
| 用例与客户端兼容 | DIRECT/NL 显式选择，旧用例默认 DIRECT，EXACT 为默认、CONTAINS 逐用例选择；期望 operationId 仍作为断言提交。requestEnvelope 保留 data/meta，普通 request/get/post 仍只返回 data，成功信封兼容无 message，错误信封仍走 ApiError。 |
| 组件与 CSS | 新功能有独立路由、页面与业务组件；JSON 区域设置换行/收缩，表单双列在 700px 下改单列，弹窗受视口宽度约束。仅确认代码存在对应规则，未据此声称真实 390px 截图通过。 |

## 验证边界

- 接受实施报告已有原 50 + 新增 17 = 67 tests、typecheck、format:check、build 通过的证据，未重复运行全套。
- 本次只运行上述一个具体时序的定向组件 probe（Node 内存 1024m、Vitest 最多 2 worker），不代表真实服务/数据库/浏览器联调。
- 未运行 Maven，未启停服务，未修改业务代码或既有测试，未提交 Git；仓库内仅新增本报告。P2 后端尚未联合验收的端点、守卫执行器零访问、Schema 全量校验、Lucene 命中率、NL 判分与真实角色拒绝均未误报为 UI 缺陷或宣称完成。

