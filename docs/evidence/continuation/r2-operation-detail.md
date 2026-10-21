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

## 冻结试执行与规则引用

- 操作行新增"冻结试执行"：共享 `FrozenCandidatePicker` + `FrozenOperationTrialPanel`，试执行器新增 `operationId` 约束，只允许当前操作。
- 操作"查看定义"展示规则引用名称（加载 `/rules?spaceId=` 映射 id→name），不再只显示 UUID。
- 浏览器实测：试执行对话框出现"选择冻结候选"，操作详情可看规则名称。

## 未完成

- data-query、冻结试执行、规则引用已合并到操作列对话框；尚无独立"操作详情"整页。
- `/data-query` 的 redirect 只做了路由级验证（typecheck/build），未单独写路由单测。
