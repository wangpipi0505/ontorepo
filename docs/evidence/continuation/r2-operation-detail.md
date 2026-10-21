# R2 IA-S3 操作详情合并（data-query 增量）

日期：2026-10-21
任务：OM-R-B2（S3 操作详情）
决策：DEC-3 取消 data-query 页，并入操作详情，权限码随迁。

## 实现

- 新增 `OperationDataQueryPanel`：以单个操作为上下文读取 `/operations/{id}/data-query`、映射、属性、助手输入参数，复用 `DataQueryForm` 保存；不再在页面内选操作。
- `OperationsView` 操作行新增"数据查询"入口（仅查询类操作），在"操作详情 · 数据查询"对话框中打开；保存后刷新。
- `/spaces/:spaceId/data-query` 改为 redirect 到 `/operations`；导航移除"操作数据查询"页签；`data-query` 权限码保留为兼容映射（p3 权限测试仍覆盖）。
- 删除不再路由的 `DataQueryView.vue`。

## 浏览器验证

- 操作页"任务查询（list_tasks）"→"数据查询"→对话框显示"操作详情 · 数据查询"、查询模式与"保存查询配置"，无控制台错误。
- 操作列表与原有编辑/删除/助手声明入口不受影响。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- S3 还要求"试执行/引用"同页；当前操作详情只合并了 data-query，冻结试执行仍在候选页、规则引用仍在规则页。
- `/data-query` 的 redirect 只做了路由级验证（typecheck/build），未单独写路由单测。
