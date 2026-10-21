# R2 IA-S2 建模工作台容器（增量）

日期：2026-10-21
任务：OM-R-B2（S2 建模容器）
目标：把对象/码表/关系/约束/规则/冲突六个建模入口合并到一个工作台，旧路径保持可达。

## 实现

- 新增 `/spaces/:spaceId/model` 容器 `ModelHubView`：
  - `?tab=classes`（默认）→ `ModelView`（画布 + 属性/关系/约束/规则侧栏）；
  - `?tab=codebooks` → `CodebooksView`；
  - `?tab=relations` → `RelationsView`；
  - `?tab=constraints` → `ConstraintsView`；
  - `?tab=rules` → `RulesView`；
  - `?tab=conflicts` → `ConflictsView`；
  - 页签切换 `router.replace` 更新 query。
- 旧路由 redirect：`/codebooks`、`/relations`、`/constraints`、`/rules`、`/conflicts` → `model?tab=…`。
- 导航"概念建模"收口为"建模工作台"一项；操作/助手/策略归入"操作与测试"组。
- 角标 `model` 扩展为 `2.1①~⑤`。

## 浏览器验证

- `/model` 页签：对象与属性 / 码表 / 关系 / 约束 / 规则 / 冲突检查。
- 切"关系"：URL `?tab=relations`，页面标题"关系与逆关系"。
- 画布页（默认）仍正常渲染。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- 页签是"容器切换"，未把对象树/冲突常驻侧栏与各表格深度联动；侧栏链接仍跳独立页（经 redirect 回容器）。
- 操作/助手/策略容器、发布向导/消费容器未合并。
