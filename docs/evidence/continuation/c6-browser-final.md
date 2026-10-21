# P4 真实浏览器最终验收

2026-10-21 01:58～02:13，root 使用现有 Chromium `onto-p1`、真实登录会话、真实 PostgreSQL/MinIO 应用。页面全部使用真实路由与按钮，不拦截 API、不注入假结果。中间后端包为 `42F61B32…`，数据属性及最终页面补验使用 `5661CED3…`；后端最终全量与后续取消逻辑修复以 C6 联调记录为准。

| 操作 | 实际结果与原始证据 |
| --- | --- |
| 发布全流程 | 冻结→三项机器检查→实际用例→OWNER人工复核→四制品→提交成功；原 `c6-browser-publication-commit.log` |
| 授权和消费 | OWNER 缺端点记录仍明确拒绝，页面不显示成功结果；保存已有主体的有效授权后返回“任务”及实际 releaseId/SHA/preview=false；`c6-browser-consumption-retry.log` |
| 概览 | 正式 v1、已发布可消费、真实对象/操作/冲突/映射指标；未映射类如实显示问题，没有以假数据补齐；`c6-browser-overview-events-audit-retry.log` |
| 事件与审计 | relay 关闭时实际 RELEASED/PENDING、retry=0、空收件箱；审计显示 COMMIT/REVIEW/GRANT/EXECUTE；执行列表实际区分 SERVICE 与 LOCAL_TEST |
| 数据版本差异 | 原 v1/v2 仅一条 PROPERTY 变更，页面返回新增0/移除0/变更1 |
| 历史恢复 | 实际 POST 返回200，修订 r22→r23，恢复面板关闭，旧版本仍READY；未自动发布 |
| 数据追溯 | 原执行 `78c9b44f-c400-41f0-8ab2-1f6c5007360c` 有6项授权事实、11节点/10边、SOURCE_RECORD（原CSV第5行）、META_SNAPSHOT、MAPPING_SNAPSHOT、SERVICE_VER、ONTOLOGY_VERSION；恢复后仍能读取原证据 |
| 状态同步修复 | 新候选目录逐阶段 TESTING→REVIEWING→READY_TO_COMMIT→COMMITTED，理由始终保留；`c6-browser-publication-sync-final.log` |
| 响应布局 | 消费、概览、追溯、最终发布页均实测390px视口/390px文档宽度，截图已检查；图表允许组件内滚动 |

原数据空间为 `ee9d5319-28f3-44e9-a118-2f12b68d7b33`，两个正式版本身份及摘要保存在 `p4-data-http-smoke.json`。版本diff、恢复和trace最终成功日志为 `c6-browser-data-trace-diff-restore-final.log`。实际恢复两次的原因是第一次脚本等待会被父组件卸载的完成提示；第一次恢复本身已成功，后一次明确以真实响应及修订推进断言，不把定位失败称为产品失败。

最终状态同步空间为 `8b979fad-6b4d-45f7-9aed-eaf6cebc312d`，候选 `48f0d136-dfcc-4149-9c43-ac7e8bc9eaf1`，正式版本 `1ee83f80-78a4-4a5d-b9c8-2f6b2dbf4483`，bundle SHA `011d7cac4fe71c26cf6ed0189b9247b11a55c272988009d6d6e33ff521f174a1`。未用既有 COMMITTED 页面刷新冒充动作同步验证。

截图：`c6-publication-final-desktop/mobile.png`、`c6-consumption-desktop/mobile.png`、`c6-overview-ready-desktop/mobile.png`、`c6-data-trace-desktop/mobile.png`、`c6-data-restore.png`。最初定位脚本的 strict-match、隐藏 option、折叠导航和中文状态定位错误均保留原日志，最终成功日志单独保存。

本地收件箱不等于外部智能体平台上架验收。本页也不替代 MinIO 真实停机、应用重启持久化或后端全量回归。
