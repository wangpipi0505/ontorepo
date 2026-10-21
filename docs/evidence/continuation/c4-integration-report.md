# P2 实际服务与浏览器联调

2026-10-20 22:36～22:48；使用22:34完整 verify 打包的应用、本工程独立 PostgreSQL，后端18182、前端15184。后端独立审查进行中，本文只记录已完成的真实运行证据，不据此关闭该审查或P2整期。

## HTTP 与持久化

- `deploy/Smoke-Supply.mjs` 六项通过，见 `p2-http-smoke.json`：预览四元组全null与外层实际摘要、内部调用业务结果/真实meta、冻结知识及Lucene来源关联、Schema失败无meta且保留事实、草稿修改不污染旧供给、tenant_label无绑定三入口拒绝且零查询步骤。
- 验收空间 `1b5ac566-1697-4c3a-b70d-36eaba3df02a`；合法候选 `14b5ad88-c039-40e6-a72a-f6e510fd26ba`，摘要 `51eef41160083d349b2daa792f60dcdf1df534b4173eccc8dd07b00f4c528789`。只创建/操作本轮验收数据。
- 实际重启本工程应用后，`deploy/Check-Supply-Persistence.mjs` 继续读取相同候选，五项通过，见 `p2-persistence-smoke.json`：契约/守卫/摘要不变、原知识与来源保留、草稿隔离、Lucene重建后命中、原候选内部执行成功。未重启PG，不夸大为数据库故障恢复演练。
- 实际控制器导出 OpenAPI 49个路径，`contracts/openapi.json` 与生成客户端已更新。

## 页面操作

Playwright CLI 的独立 `onto-p1` 会话，凭据由忽略配置导入且屏蔽回显。完成：

1. 助手声明读取全部字段，将本轮反例草稿守卫恢复为region并保存，空间r9→r10，未改变原候选。
2. 空间守卫加载真实region主体属性绑定；主体授权编辑读取实际华东范围和属性，不从执行输入冒充。
3. 知识新增“任务调度”及别名“任务排程”、定义和来源，实际持久化并列出，空间r10→r11。
4. 切换原r6候选，读取完整冻结契约/context-pack；“业务任务”经真实Lucene命中“任务”，显示词条ID、正分数、匹配别名和原来源。
5. 选择冻结操作并实际调用，显示业务rows与preview元数据；提交有效JSON但不允许的unexpected字段后显示SCHEMA_VALIDATION_FAILED，旧成功结果/meta清除。
6. 1440px及390px均无整页横向溢出，实际查看截图，表单与搜索/调用控件可操作。

截图：`p2-assistant-desktop.png`、`p2-supply-desktop.png`、`p2-supply-mobile.png`；窄屏可读局部为 `p2-supply-mobile-top.png`、`p2-supply-mobile-search.png`。供给JSON可以折叠；截图保留展开态以证明真实字段。

测试工具曾因共享HMR导致内存会话清空、Element Plus原生input被可见placeholder遮挡、PowerShell原生命令引号传递造成无效JSON而重试；分别重新登录、操作可见选择器、使用JSON.stringify后完成，没有修改产品绕过验证。

预览manifest的output_schema按详设§8.8保持正式契约模板，内部候选响应releaseId/version为null；不能拿该正式模板对preview成功信封做整体断言。正式发布后仍必须校验完整正式信封，后续P4负责。
# 23:17 修复制品的补充验证

2026-10-20 23:17 打包成功后，由root使用本工程Stop/Start脚本重启API/Vite，保留PG和MinIO。下列结果均来自新JAR实际HTTP监听器或真实浏览器，不将MockMvc替代为网络联调。

- `node deploy/Check-Supply-Edges.mjs`：14个检查全部通过，报告 `p2-http-edges.json`。数组、字符串、0、false、true、null经TRIAL与SERVICE分别成功，LOCAL_TEST的6个冻结精确断言全部成功；`_region`守卫与同名dataScope可正常授权；纯标点知识不能令陌生NL问题误命中，实际失败为NL_OPERATION_NOT_FOUND且无查询步骤。
- 在原供给脚本创建新空间之前，`node deploy/Check-Supply-Persistence.mjs` 对上一轮旧候选运行5项重启持久性检查，全部通过；随后原 `Smoke-Supply.mjs` 6项全部通过，包含tenant_label无绑定三入口零查询拒绝，最新资源标识见对应JSON报告。
- 真实 `/v3/api-docs` 再次导出49路径，`pnpm --dir web api:generate` 成功；生成声明包含合法JSON根类型。后续P3/P4新增接口仍须重新同步。
- 浏览器在空间 `540d7b83-4aac-4069-aede-63da6036e504` 的助手声明中读取并保存 `[["x"],"value",0,false,true,null]`，实际PUT成功、修订r13→r14，回读类型原样保留。桌面1440px截图 `p2-json-examples-desktop.png` 已目视检查。
- 同一浏览器通过“内部候选调用”分别提交false、null、数组；实际请求体与JSON.stringify逐字匹配，3次HTTP200均返回“任务”业务行及真实冻结SHA/preview=true。390px没有全页横向溢出，`p2-json-call-mobile.png` 已目视检查输入、结果和预览信息。

浏览器最初在前端仍格式化时因Vite HMR刷新丢失内存会话，停止写文件后重新登录完成验证；一次选择器名称不匹配按实际快照修正。这两次自动化中断不作为产品功能通过证据。原候选被实际保存的声明草稿修改不影响冻结SHA。后端完整回归的冷启动超时与定向复核结果以 `c4-backend-report.md` 如实记录，不称为单轮全量通过。

