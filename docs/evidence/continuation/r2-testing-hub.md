# R2 IA-S6 验证与候选容器（增量）

日期：2026-10-21
任务：OM-R-B2（S6 页面合并）
目标：把"用例管理"与"候选与试执行"合并为一个空间入口，旧路径保持可达。

## 实现

- 新增 `/spaces/:spaceId/testing` 容器 `TestingView`：
  - `?tab=cases`（默认）→ 原 `TestCasesView`；
  - `?tab=candidates` → 原 `CandidatesView`；
  - `?tab=supply` → 原 `SupplyView`（候选供给/内部调用）；
  - 页签切换用 `router.replace` 更新 query，不新增历史记录。
- 旧路由：
  - `/test-cases` → `/testing?tab=cases`；
  - `/candidates` → `/testing?tab=candidates`。
- 导航"操作与测试"组改为"验证与候选"一项；`pagePermissions` 新增 `testing`（test.read/write），旧键保留兼容。
- 技术要求角标：`testing` → `2.4① / 2.2③`。

## 浏览器验证

- 导航进入 `/testing`：页签"用例与试执行 / 候选与供给"，默认用例页。
- 切"候选与试执行"：URL 变 `?tab=candidates`，页面标题"候选与试执行"。
- 切"候选供给"：URL 变 `?tab=supply`，页面标题"候选供给"。
- 旧路径 redirect 仅做路由配置与 typecheck/build 验证；因会话为内存态，整页深链会回登录，未做浏览器级 redirect 断言。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- `supply` 以独立页签并入，尚未与某条候选详情深链（`candidates/{id}#supply`）合并。
- 建模 6 页、操作声明/策略、空间设置等其余合并与 redirect 未做。
