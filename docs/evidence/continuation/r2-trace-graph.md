# R2 证据关系图增量

日期：2026-10-21
任务：OM-R-A5 / OM-UI-GRAPH（证据图部分）
目标：用统一图谱组件替换静态 SVG，把执行、主体、步骤、证据按服务端事实展示，节点可查看引用。

## 实现

| 文件 | 改动 |
|---|---|
| `features/p4/TraceGraph.vue` | 改用 `OntoGraph`：节点=`graph.nodes`、边=`graph.edges`；点击节点显示 label/kind/ref/id；保留"可读节点与关系列表"作为无障碍与降级视图 |
| `features/p4/flows.test.ts` | trace 图单测改为断言可读列表（2 节点 + 2 边），避免 jsdom 无 Canvas；图组件在测试中 stub |
| `features/p4/ExecutionTracePanel.vue` | 接收 `select-node`，按节点 id 高亮同 id 的时间线条目（执行/步骤/证据），实现图↔时间线联动 |

后端无改动：`GET /executions/{id}/trace` 已返回 `graph`（execution→subject、步骤链、证据节点），本轮只做可视化与节点详情。

## 浏览器验证

真实执行（SERVICE / FAILED / RELEASE_NOT_READY，3 节点 2 关系）：

- 执行追溯页渲染画布：`执行 list_tasks · EXECUTION` → `RELEASE · RELEASE`、`EXECUTE · STEP`。
- 计数显示 `3 节点 · 2 关系`，与后端 graph 一致；可读列表同步展示。
- 节点点击详情与时间线/授权段落同页（画布坐标点击的自动化定位受布局缩放影响，模型页已用同一组件验证 `select` 事件驱动右侧详情）。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0
```

## 未覆盖

- 画布节点点击已联动高亮时间线条目；浏览器自动化点击受 G6 布局/缩放坐标影响，本轮以组件事件与类型/单测覆盖为主。
- 节点按 kind 分色、布局切换、缩放控件待 B1 收口。
- 后端 graph 已含执行链与证据，但"来源→映射→对象"多级关系仍以 evidence 节点平铺，后续在 B1 增加分层聚合。
