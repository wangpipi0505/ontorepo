# C7 增量独立复核：发布目录状态同步

- 复核时间：2026-10-21 02:07（Asia/Shanghai）。
- 范围：`PublicationPanel.vue`、`PublicationView.vue`、`publication-sync.test.ts`；另核对其沿用的资源请求代次及空间上下文检查。
- 原问题：详情已显示 `COMMITTED`，左侧目录仍显示 `VALIDATING`。
- **Spec：APPROVE；Quality：APPROVE。未确认新的 P0 / P1 / P2 问题。**
- 文件版本摘要：`c7-publication-final-reviewed-files.json`。

## 结论依据

详情初读与后续动作均在实际收到、核验 `Publication` 后发出 `publication-updated`。候选 ID、摘要、草稿修订必须一致；初读同时检查组件请求代次、空间代次及 `useResource` 最新请求资格，动作响应同时检查 mutation 当前上下文及候选代次。提交动作以随后真实读取的详情作为目录状态依据，不从按钮点击或 `Release` 响应推测候选状态。

父页面只接受选中候选、同空间、同摘要和同修订的事件，并且只替换匹配目录项的 `status`。该更新不调用目录 / 草稿重新读取，不改变 `opened`，不改变面板 key；候选状态也不属于面板内部身份 watcher 的依赖。因此有效同步保留原复核理由、勾选、草稿修订和当前面板实例。

作者提供的 7 项父子集成、原 flows 22 项及类型 / 格式结果不计入下面的独立探针数量。本次没有重跑作者全套，也没有修改实现。

## 独立反例验证

探针和独立配置保存在 `.runtime/c7-publication-final-review/`：

```powershell
# 工作目录：代码/ontorepo/web
$env:NODE_OPTIONS='--max-old-space-size=512'
node node_modules/vitest/vitest.mjs run --config ../.runtime/c7-publication-final-review/vitest.config.mts
```

固定 `maxWorkers: 1`、`fileParallelism: false`、jsdom。最终结果：**1 文件、9 项通过、0 失败**，开始时间 02:07:24，用时 2.58 秒；日志 `c7-publication-final-review-probes.log`。

| 独立探针 | 结果 |
| --- | --- |
| 初次详情候选 ID 错配 | 目录保持原状态，详情给出身份错误，不发更新事件 |
| 初次详情 SHA 错配 | 同上 |
| 初次详情 draftRev 错配 | 同上 |
| 直接向父级发送非选中候选、错误 SHA、错误修订事件 | 父级均拒绝，不修改任一目录项 |
| 合法详情刷新为 COMMITTED，同时全局草稿已由 r7 推进 r12 | 仅目录状态改变；面板实际 Vue 实例 UID、理由与勾选保留；全局仍为 r12；只请求 publication，未重读草稿 |
| 取消动作发起后同空间重新进入，旧动作成功响应迟到 | 不把 CANCELLED 写回旧详情 / 目录，输入保留 |
| 候选 A → B → A 重新挂载，第一次 A 读取最后返回 | 新 A 的 READY_TO_COMMIT 保持，旧 TESTING 不覆盖 |
| 父目录项属于其他空间 | 即使子级直接发出匹配 ID / SHA / 修订的事件，也不能改目录状态 |
| 复用 Panel 时候选身份改变，旧详情读取迟到 | 只发出新候选 B 的事件，不发出 A 的 COMMITTED |

首轮独立探针使用 Vue Test Utils 的 setup proxy 引用比较，失败输出尝试展开组件循环对象导致 512 MiB 堆耗尽。已将测试改为比较真实 Vue 组件实例 UID；内存限制未提高，生产代码未改。原尝试日志保留在 `.runtime/c7-publication-final-review/probe.log`，最终通过日志独立保存。

## 边界

本记录只关闭本次目录 / 详情同步缺陷的独立代码复核。未运行 Maven、启停服务或使用共享浏览器；真实浏览器联调及合并后的前端全量回归仍以主任务最终验收记录为准。
