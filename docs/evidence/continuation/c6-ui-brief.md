# P4 发布、消费与审计前端

先读 `contracts/p4-release.md`，独占 web/，保留现有P1-P3组件和交互。实现候选发布向导（冻结/五门禁/测试/复核/prepare/commit/取消）、版本列表/详情/投影diff/恢复草稿/弃用/完整性、端点授予/API key创建撤销、正式服务调用、事件队列/重放、本地收件箱、执行trace时间线+关系图、审计列表。

状态从真实API展示；不得用前端点击伪造门禁或READY，所有按钮按后端状态/权限开放且后端仍是权威。失败保留可重试上下文；submit传固定rev/SHA，409不得借新修订。prepare与commit独立按钮/阶段，清楚显示制品摘要及就绪。OWNER确认理由明确，超范围豁免遵从现有UI，不可给守卫错误显示豁免。

API key只生成时展示一次，可复制；关闭后不留浏览器存储或控制台。endpointgrant与spacegrant分别展示，空范围注明仅现有授权不限，缺记录不可消费。测试真实正式调用时输出业务数据/meta分区，不伪造版本证据。

版本恢复需展示目标版本/当前修订和覆盖草稿的明确确认，历史版本不变；不自动提交发布。事件表显示boundStatus/当前release状态、retry、DEAD和原eventId重放，帮助理解历史通知与可消费状态不同。

trace图从真实nodes/edges生成，组件化SVG或已有库，配合可读节点列表/时间线；长内容可折叠，390px无整页横向溢出。沿用现有蓝色工作台，不能将所有功能写进一个文件。

先写关键交互RED，保留旧测试；typecheck/format/build，Node1024m/Vitest2。不启动/停止服务/root统一浏览器。报告 `c6-ui-report.md`，不提交Git。
