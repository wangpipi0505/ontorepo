# 前端小增量独立审查

日期：2026-10-21。依据工程 `AGENTS.md`、`contracts/p4-release.md`，使用 Vue、code-review、ponytail、verification-before-completion 技能。审查范围仅限本轮数据目录列宽/滚动提示、抽取候选复选框换行，以及 P4 的 FREEZING/FAILED 状态接入；不替代原 P3/P4 总审或期次出口验收。

## Spec 结论

**批准本轮三个前端增量，未发现新增阻断缺陷。**

| 增量 | 核对结果 |
| --- | --- |
| `DataCatalogView.vue` | 两个目录表各列设置可读最小宽度；窄屏显示左右滚动提示，保留完整源、快照、映射与操作标识。未修改数据请求、计数、关联来源和权限。 |
| `SuggestionReview.vue` | 仅为候选选择复选框增加局部 scoped 样式：容器允许收缩，Element Plus 标签允许换行及长标识断行。保留完整稳定标识；没有变更默认不勾选、人工决定、只读/过期/提交中禁用和批次提交语义。 |
| `release.ts` / `PublicationPanel.vue` | `PublicationStatus` 与固定契约一致，包含 FREEZING、FAILED，并保留 FROZEN。FREEZING 在统一 actionable 条件被排除；校验、测试、复核、豁免、取消、准备、提交均经此条件控制，action 函数本身同样提前返回。读取发布事实、差异仍可用。FAILED 的加入没有将失败状态视为门禁通过；准备仍要求全五门禁及 REVIEWING/READY_TO_COMMIT，提交还要求 READY_TO_COMMIT、四制品就绪及 bundle 摘要匹配。是否可重跑最终仍由实际服务端失败原因、制品及门禁决定。 |

P4 固定契约要求的失败持久化、失败序号作废不复用、真实制品核验、五门禁授权/事务、发布和正式消费，不能由本次 DTO 或按钮检查证明，均未在本审查中标为通过。

## Quality 结论

**批准本轮增量，无新增已确认的 P0/P1/P2 问题。**

样式集中在所属组件；目录复用 Element Plus 表格内部滚动，候选标签通过原生 CSS 换行；没有增加依赖或布局抽象。P4 复用统一操作守卫，没有为每个按钮复制独立的状态判断，现有候选 SHA/修订、权限、409 锁定和门禁检查仍保留。中文注释解释了列宽、稳定标识及候选身份不变量。

## 独立验证与证据

独立阅读上述五个生产文件、P4 交互测试、`publication.ts`，并补读 `PublicationView.vue`、`useResource.ts` 追踪状态读取与刷新路径；核对原红绿日志：

- `c6-freezing-ui-red.log`：FREEZING 反例失败，失败点为操作按钮没有 disabled。
- `c6-freezing-ui-green.log`：修复后原 P4 22 项组件测试通过。该历史红绿日志为根任务证据；本 reviewer 未回退共享生产源码重新制造 RED。

本次独立执行以下限定回归，读取完整输出和退出码：

```text
NODE_OPTIONS=--max-old-space-size=768
pnpm test apps/onto-admin/src/features/p4/flows.test.ts apps/onto-admin/src/features/p3/flows.test.ts --maxWorkers=1 --minWorkers=1
exit 0
Test Files 2 passed (2)
Tests 29 passed (29)：P4 22，P3 7
```

完整独立日志：`ui-increment-review-tests.log`。P4 挂载真实业务组件和 Element Plus，FREEZING 用例实际检查六个写操作按钮禁用；P3 回归覆盖真实抽取人工决定和既有异步/修订边界。jsdom 不执行真实布局，29 项通过不用于证明 390px 宽度。

本 reviewer 已实际打开并目视检查：

- `p3-catalog-mobile-readable.png`：移动端提示可见，列文本保持可读，宽表由容器裁切滚动展示。
- `p3-catalog-mobile-associations.png`：横向移到关联列后，完整类/操作标识可读。
- `p3-suggestion-mobile-readable.png`：复选框长标识在卡片内换行，人工审阅字段保持可见。

根任务在 `c5-integration-report.md` 记录的真实 DOM 结果为目录整页 390px、内部表格容器 332px/内容 955px；抽取由 401px 改为整页 390px、越界元素 0。本 reviewer 审阅了截图与记录，没有复用根任务浏览器会话或宣称自行重新测量 DOM。

按分工未重复全量 typecheck/格式/构建；已阅读根任务的 `c5-mobile-typecheck.log`、`c6-freezing-ui-typecheck.log`，它们作为根任务既有证据，不表述为本次独立重跑结果。

## 验收边界

未运行 Maven、启动/停止服务、修改生产/共享源码或提交 Git；只新增本报告与独立测试日志。未访问相邻工程或读取/输出凭据。

未独立执行 P4 真实 PostgreSQL/MinIO 发布、失败归档、正式消费、事件投递/收件箱、溯源持久事实及桌面/390px 浏览器联调。未实现或尚未验收的 P4 后端保持未验收；本地收件箱也不能视为外部智能体平台正式上架通过。
