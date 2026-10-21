# C7 页面补齐的真实浏览器验证

2026-10-21，主执行者使用真实浏览器 `onto-p1`、Vite 15184 与本工程 P3 最终 API 18182。没有 mock API，也没有修改邻工程。P4 新制品尚未启动，因此概览中的正式版本接口当前真实返回404；本轮验证其未知/重试展示，正式版本成功状态需随后补验。

## 已通过

| 项目 | 实际结果 |
| --- | --- |
| 四维筛选 | 名称“映射执行”、编码`data-mvgwzoo3`、领域“算法任务”、状态ACTIVE组合仅命中一个空间；改为ARCHIVED返回零个。 |
| 概览入口 | 空间根路由进入概览，对象/关系/规则、冲突与映射校验来自当前后端；版本接口失败显示“读取失败/未知，请重试”，没有补造零版本或发布状态。 |
| 资源真实拖拽 | 浏览器鼠标按下、连续移动、抬起实际产生dragstart/drop/dragend，从HTTP `/tasks`拖到“图形映射验收对象”，预填真实源/快照/资源/目标类；人工填写`graph:${task_id}`后保存。 |
| 字段与键盘 | 已保存对象映射`bfc3d97e-e69d-431b-9f3f-d13b1926ffc3`回读成功；点选`task_id`源字段，聚焦目标后按Enter，属性表单正确预填；POST使用`expectedDraftRev=81`，返回200及r82。 |
| 已保存连线 | 读取实际对象/属性映射，共四条SVG实线；缩放后路径重新计算且无NaN，点击已保存映射能定位原ID。 |
| 390px | 映射页、概览页、空间筛选页的document.scrollWidth均为390；映射画布内部宽330、滚动内容510，可独立横向滚动。 |

验收写入独立测试空间`7249675c-670b-4b9f-8856-169b8bd8e309`，新增对象`393834cc-370b-469e-bf48-aaf7965ae684`和属性`7ae1edcc-1ee7-4de6-bb9f-521e31b0361f`。原候选及原摘要不改；随后持久化检查继续使用原证据验证冻结隔离。

## 日志与截图

事实日志：`c7-browser-overview.log`、`c7-browser-graph-setup.log`、`c7-browser-mouse-drag.log`、`c7-browser-graph-form.log`、`c7-browser-graph-property-save.log`、`c7-browser-graph-visual.log`、`c7-browser-mobile.log`。其中鼠标日志保留实际事件链，属性日志保留安全的真实请求体与r82响应。

已查看截图：`c7-graph-desktop.png`、`c7-graph-mobile.png`；另存可放大的`c7-graph-mobile-detail.png`。筛选与概览截图为`c7-space-filter-desktop.png`、`c7-space-filter-mobile.png`、`c7-overview-p3-runtime.png`和`c7-overview-mobile-p3-runtime.png`。

## 自动化过程限制与修正

初次脚本把资源名误写为`tasks`（实际为`/tasks`）、把表单标题写为“新建”（实际为“新增”），另一次把关闭按钮写为“取消”。这些等待超时的日志保留，不是产品失败，也不计通过。

`locator.dragTo`快捷动作没有产生实际dragstart/drop事件，因此没有把其完成返回计为拖拽通过。调整为可见画布内的真实鼠标连续移动后捕获到完整事件链及正确表单。另一次保存成功后页面重新加载、重置了源/快照选择，后续脚本等待旧字段超时；重新选择同一真实快照后回读对象映射并完成属性保存，没有重复创建对象映射。

正式P4发布、权限/消费、版本恢复、事件/追溯与新版本概览仍由`c6-integration-report.md`跟踪，本轮不提前判为通过。

## 2026-10-21 02:13 正式发布后的补验

新 P4 后端就绪后，root 已实际完成 READY v1 概览：真实对象/属性/操作/关系/规则数、正式版本、可消费状态与草稿冲突/映射问题均由服务返回；390px文档宽度为390px。此前因接口404保留的“成功状态待验”现已关闭。证据为 `c6-browser-overview-events-audit-retry.log`、`c6-overview-ready-desktop.png`、`c6-overview-ready-mobile.png`。

P4 页面其他流程及发布目录状态同步补验见 [真实浏览器最终验收](./c6-browser-final.md)。新增状态修复通过作者7项、独立9项及真实逐阶段按钮操作，合并前端230项通过，类型/格式/生产构建通过。OM-C7-UI 的本地页面出口已满足，后端整期最终结论另见 C6。
