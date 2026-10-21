# R2 IA-S6 空间设置容器（增量）

日期：2026-10-21
任务：OM-R-B2（S6 页面合并）
目标：把"空间守卫"与"主体授权"合并为空间设置入口，旧路径保持可达。

## 实现

- 新增 `/spaces/:spaceId/settings` 容器 `SettingsView`：
  - `?tab=guards`（默认）→ `GuardsView`；
  - `?tab=members` → `GrantsView`；
  - 页签切换 `router.replace` 更新 query。
- 旧路由：
  - `/guards` → `/settings?tab=guards`；
  - `/grants` → `/settings?tab=members`。
- 导航"空间设置"组收口为一项（ownerOnly）；`pagePermissions` 新增 `settings`（grant.read/write），旧键保留兼容；角标 `2.5②`。

## 浏览器验证

- 空间设置页签"守卫属性 / 成员与授权"；默认 URL `/settings`。
- 切"成员与授权"：URL `?tab=members`，页面标题"主体授权"。
- 无控制台错误。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- 导入模板/命名空间规范配置尚未并入空间设置。
- 建模 6 页、操作声明/策略、发布向导/消费的容器合并仍未做。
