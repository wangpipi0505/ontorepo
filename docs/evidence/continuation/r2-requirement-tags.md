# R2 IA-S6 技术要求角标（增量）

日期：2026-10-21
任务：OM-R-B2（S6 收口）
目标：验收方在任意空间页面能直接看到该页对应的技术要求编号。

## 实现

- 新增 `composables/requirementTags.ts`：页面段 → 技术要求编号集中映射（建模、映射、操作、发布、消费、事件、审计等 28 个路径）。
- 新增 `components/RequirementTags.vue`：只读 pill 列表，`aria-label="技术要求角标"`。
- `WorkbenchLayout` 顶栏在空间名后渲染角标（仅 `/spaces/` 路径）。
- 角标不改变权限、路由或业务行为；未登记页面不显示。

## 浏览器验证

- 建模工作台顶栏显示 `2.1① 2.1② 2.1③ 2.1④`。
- 其他页面按映射显示；无控制台错误。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- 8 区 14 页的页面合并与旧路由 redirect 尚未完成（`codebooks/relations/constraints/rules/conflicts/strategies/assistant/guards/grants/suggestions/imports/test-cases/candidates/supply/events` 仍为独立页）。
- 角标按"当前页"映射，合并后需按新容器页签更新映射。
