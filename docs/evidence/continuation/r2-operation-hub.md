# R2 IA-S3 操作容器（增量）

日期：2026-10-21
任务：OM-R-B2（S3 操作详情化）
目标：操作定义、助手声明、执行策略共用一个入口，旧路径保持可达。

## 实现

- `/spaces/:spaceId/operations` 改为 `OperationsHubView`：
  - `?tab=definitions`（默认）→ `OperationsView`；
  - `?tab=assistant` → `AssistantView`；
  - `?tab=strategies` → `StrategiesView`；
  - 页签切换 `router.replace` 更新 query。
- 旧路由 redirect：
  - `/assistant` → `/operations?tab=assistant`；
  - `/strategies` → `/operations?tab=strategies`。
- 导航"操作与测试"组收口：模型操作 / 验证与候选 / 执行记录。
- 操作页角标追加 `2.1⑧`（策略）。

## 浏览器验证

- `/operations` 页签：操作定义 / 助手声明 / 执行策略。
- 切"助手声明"：URL `?tab=assistant`，标题"助手声明"。
- 切"执行策略"：URL `?tab=strategies`，标题"执行策略"。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未完成

- 操作详情内的"声明/守卫/策略同屏"仍是独立页签，不是单条操作的双栏详情。
- 发布向导/服务消费尚未容器化。
