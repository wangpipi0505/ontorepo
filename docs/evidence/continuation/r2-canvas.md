# R2 画布增量：建模图谱

日期：2026-10-21
任务：OM-UI-GRAPH / OM-R-B1（第一增量）
范围：建模工作台画布——对象为节点、关系为有向边；支持画布内建关系、选中、双击编辑与冲突预留样式。

## 实现

| 文件 | 改动 |
|---|---|
| `web/apps/onto-admin/package.json`、`pnpm-lock.yaml` | 引入 `@antv/g6@5.1.1` |
| `components/OntoGraph.vue` | G6 画布组件：dagre 布局、拖拽/缩放、点击两对象建关系、双击编辑、冲突描边、选中高亮；按需异步加载 |
| `views/ModelView.vue` | 图谱/层级切换；并行加载关系；画布内选对象驱动右侧属性面板；新建关系对话框（名称/方向/基数）经既有 `POST /relations` 保存并刷新 |

## 浏览器验证（真实后端 + 真实空间）

- 登录 → 首个空间 → 对象与属性 → 图谱：渲染 2 个对象节点、0 条边（截图 `graph-model.png`）。
- 依次点击两个对象 → 弹出"新建关系"（任务 → 草稿新增对象），填写名称保存 → 画布出现有向边，计数变为 `2 对象 · 1 关系`（`graph-relation.png`）。
- 选中节点后右侧属性面板显示该对象；层级切换仍可用。
- 控制台无业务错误；G6 仅在切到图谱时加载（独立 chunk）。

## 自动化

```powershell
pnpm typecheck   # exit 0
pnpm test        # 31 files / 259 tests passed
pnpm build       # exit 0；OntoGraph 独立 chunk（gzip ~410 KB）
```

## 未覆盖 / 下一步

- 当前建关系为"依次点击两个对象"，不是按住拖线；G6 `create-edge` 的 drag 触发与 `drag-element` 冲突，后续在 B1 收口时补拖线或明确交互口径。
- 视图默认已改为"图谱"（画布主视图）；层级保留为切换项。B2-S2 建模工作台落地后补冲突高亮/布局切换/缩放控件。
- 证据关系图（溯源）复用同一组件，待后端 `/executions/{id}/graph` 聚合接口完成后接入。

