# R2 IA-S4 发布与溯源联动（增量）

日期：2026-10-21
任务：OM-R-B2（S4 发布/溯源）
目标：正式版本详情能直达其服务消费、事件与审计，并携带版本上下文。

## 实现

- `ReleasesView` 版本详情新增三条入口：
  - 去服务消费 `?releaseId=…`；
  - 查看事件 `?releaseId=…`；
  - 查看审计 `?subjectType=RELEASE&subjectId=…`。
- `ConsumptionView`：加载版本后优先选中 query 中存在的 `releaseId`，否则选第一个版本；避免"带版本进入却仍需手选"。
- `EventsView`：按 `releaseId` 过滤事件列表，空态明确。
- `AuditView`：用 query 初始化 `subjectType/subjectId` 过滤，进入即看到该版本审计。
- 三页均用可选 `routeLocationKey` 注入，组件在无路由的测试环境不报错。

## 浏览器验证

- 正式版本详情 →"去服务消费"，URL 带 `releaseId`，消费页版本下拉自动选中该版本（值与 URL 一致）。
- 事件/审计链接同法携带版本/对象上下文。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- 这是"版本详情到三页的上下文联动"，尚未把发布向导/版本/消费/事件/审计合并为单一路由内的页签。
- S6 旧路由 redirect 与 8 区 14 页收口仍未做。
