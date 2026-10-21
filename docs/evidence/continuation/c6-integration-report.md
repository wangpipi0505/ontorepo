# C6 / P4 应用联调与最终复核

本文件记录主执行者的真实应用验证，独立于后端作者测试和切片审查。2026-10-21本地代码与现有环境验收完成；最终统一结果见文末，历史反例和阶段限制按发生时点保留。本地 PostgreSQL 收件箱只证明本工程事件闭环，外部智能体平台上架及跨系统联合验收另行跟踪。

## 已执行的前置反例

- `Smoke-Release-Data.mjs` 在最后 P3 制品上运行，实际停于缺少 `/candidates/{id}/validate` 的 HTTP 404，日志 `c6-data-http-red.log`。当时不是 P4 功能已实现后运行失败。
- 同一 CSV/映射/策略/精确结果夹具在既有冻结用例入口实际执行一条并通过，日志 `c6-data-fixture-check.log`。因此后续正式发布失败不能直接归因于未验证夹具。
- P4 发布脚本、正式数据脚本及两个持久化脚本已通过 Node 语法检查。此项不计入业务通过数。

## 应用验证清单

| 项目 | 实际动作及关键断言 | 状态 |
| --- | --- | --- |
| 旧功能兼容 | 新 P4 制品启动后先检查原 P1/P2/P3 候选、摘要、冻结源与结果 | 最终取消隔离包 `66F0E72…` 上原基线 5/5/6 组全部通过；`c7-final-p1-restart.log`、`c7-final-p2-restart.log`、`c7-final-p3-restart.log` |
| 正式发布 HTTP | 五门禁、正数实际用例、四制品、并发幂等、默认端点拒绝、TOKEN/API key、实际 meta/版本头、弃用 | `5661CED3…` 包 10 项通过，`c6-http-final.log`；其原版本在 `66F0E72…` 重启复验通过 |
| 正式数据 HTTP | 实际 CSV 转换、四层授权交集、空标签拒绝、过期/撤销、真实四层证据、二版本 diff 与完整恢复 | 02:06 新补验包 8 项通过，`c6-data-http-fallback.log` / `p4-data-http-smoke.json` |
| 暂停投递时发布后弃用 | 禁用 relay 启动应用，原 RELEASED/DEPRECATED 都是 PENDING、空收件箱、消费已拒绝 | 01:56 中间包 10 项通过，`c6-deferred-http-green.log` / `p4-deferred-http-smoke.json` |
| 应用重启恢复积压 | 恢复 relay，原 eventId/boundStatus 不变，按顺序 SENT/接收；历史状态不恢复为 READY | `42F61B32…` 停用投递→`5661CED3…` 实际重启，原版本及事件 5 项通过，`c6-deferred-restart.log` |
| 原正式版本重启 | 原 releaseId/SHA、历史实体、撤销凭据、事件与收件去重、弃用仍拒绝 | 最终取消隔离包 `66F0E72…` 5 项通过，`c7-final-p4-restart.log` |
| 原正式数据重启 | 原两版本仍可调用，恢复草稿/来源快照/映射及旧 trace 保留 | 最终取消隔离包 `66F0E72…` 5 项通过，`c7-final-p4-data-restart.log`；保留原报告身份、摘要和完整证据 |
| T02 新发布候选三入口 | 无 tenant_label 绑定时 TRIAL/SERVICE 拒绝；FAILED/VOID 候选 LOCAL_TEST 前置拒绝；零源请求、零正式版本、零 RELEASED | `66F0E72…` 实际 HTTP 通过，`c7-final-p4-guards-events.log` / `p4-guards-http-smoke.json`；历史候选运行期拒绝证据另存 |
| 对象存储真实停机/恢复 | 停止本工程MinIO后正式调用503且不注入成功meta；完整性检查UNAVAILABLE、保留READY、零损坏告警；恢复后原摘要与原结果通过 | 02:39最终 `66F0E72…` 包3+3全部通过，`c7-storage-outage-final.log` / `p4-storage-unavailable.json` / `p4-storage-recovered.json`；存储已恢复 |
| 浏览器 | 发布、授权、消费、版本/diff/恢复、事件、审计、真实 trace；桌面与390px | 全部通过，含原数据版本恢复及11节点/10边追溯图、最终新候选逐阶段状态同步；见 `c6-browser-final.md`，页面使用 `42F61B32…` / `5661CED3…` 应用包 |
| 最终构建及独立复审 | 全后端 verify、前端测试/类型/格式/构建，OpenAPI生成，对所有新发现复核关闭 | 全部通过：后端362项；前端230项及生成后7项定向、类型/格式/构建；所有已发现问题独立复核关闭，见文末及各切片报告 |

## 审查追加修正

- `c7-history-review.md`：H1 候选差异已选择上一 READY 版本；H2 时间线按真实时刻排序。原五项无IO独立探针全部通过，报告批准。
- 主执行者发现的正式执行旧状态、机器门禁旧角色两项锁竞态，作者用另一事务持锁和`pg_stat_activity`确认等待，真实PG反例先RED，修复后通过，日志`c6-lock-red.log` / `c6-lock-lifecycle-green.log`。
- `c7-publication-review.md`：完整 P4 发布与网关独立复核已批准。review/prepare/tests/freeze终态回退问题修复，历史/终态/零用例和冻结版本证据的最终独立探针合计15项通过。其运行范围为无IO探针，不能称为独立HTTP或实际PG验收。
- 前端 R1/R2 并发修复已通过33项定向回归和九项独立探针。最后一次仅修测试桩告警后，八项新增用例再次通过，见 `c7-ui-concurrency-final-green.log`。紧前一次漏传 Vue Vitest 配置导致装载失败，零用例执行，保留 `c7-ui-concurrency-final.log`；不是产品断言失败。
- 前端 R3/R4 双树映射及空间概览/筛选已补齐，九项独立探针通过，真实拖拽/键盘提交、SVG 与 390px 已验。01:27 的单次前端全量为 24 文件/223 项，类型/格式/构建通过；正式版本概览的成功状态仍待新 P4 制品，见 `c7-browser-completion.md`。
- 第一次后端完整回归发现两组旧 P2 用例与新发布门禁预期冲突：旧用例要求契约失败候选仍可执行本地测试，新候选按详设必须 FAILED/VOID 且不得 TESTING。采用明确的独立 format2 历史候选保留原三入口九次拒绝及 NL 误选断言，并增加新候选前置阻断对照；不改写旧载荷，不放松新门禁。原失败日志 `c6-full-verify.log` 保留，最终全量仍待完成。
- 回归过程中一次长等待经线程与 PG 活动核对为 `DataFileImmediateSync`，没有阻塞事务；未降低持久化配置。诊断见 `c6-full-thread-snapshot.log`、`c6-pg-activity.log`。

## 保留边界

ClickHouse、MongoDB、Redis、Cassandra、Neo4j 按用户确认继续待外部真实实例验收。跨系统 J-01～J-05、生产命名空间归属和外部可复用内核没有本次环境证据，不能用本模块的调用或通知收件箱替代。

## OM-D6 与实际应用增量

- 首个应用包 `ED602263A7F92934E9269957085F449443C9D806799D2F9E3AE3C877FC41B21B` 暴露执行列表丢失 caller 和 FAILED 归一化缺陷，原 RED 保存在 `c6-deferred-http.log`；8项作者回归后中间包 `42F61B32EB5C4B346531CCE59DAE9C4C295A17FCD53906245B5605D56A59274A` 通过 relay 停用的10项实际HTTP。
- 原数据发布在一致性门禁阻断，`c6-data-http-first.log`；最小 CLASS/STRING/INTEGER/DECIMAL/BOOLEAN/DATE 六个实际 HTTP 探针表明 ELK 的数据属性覆盖不完整。用户确认 OM-D6 后，固定 HermiT 对同一完整 EL 公理集补验，共享总时限。详设/计划/契约已同步，非 EL 的 OWNER 豁免边界不扩大。
- 补验包 `5661CED3C2090E255A5075145E314F36FAC09DFA75832F70A4F94E18D92B8336` 上实际 CSV 正式发布8项通过。六个原最小探针重新执行：纯类仍 ELK，一致性完整的 STRING/INTEGER/DECIMAL 报 ELK→HermiT、保留公理数量和补验原因；BOOLEAN/DATE 仍 NOT_CHECKED/SUBSET_EXCEEDED。原 RED JSON 和新 `c6-fallback-profile-*.json` 分开保留。
- 正式数据原空间 `ee9d5319-28f3-44e9-a118-2f12b68d7b33` 的 v1=`d3ff3e9b-1333-495c-93c8-a419a57d014b`、v2=`849a8d10-3767-47b7-abf5-4722331943e8`。后续持久化检查必须使用此报告保存的原摘要、快照、事件身份和完整证据，不重新生成期望值。
- `c7-integration-review.md` 中 I1/I2 修复经独立8项隔离探针通过；它证明脚本确实比较原证据和制品摘要，不替代本页实际 HTTP。源白名单拒绝覆盖来自作者真实 PostgreSQL/MinIO 的 P4WhitelistTest，不能写成主执行者独立公网关 HTTP。
- 浏览器脚本中的重复定位及中文状态选择器超时属于自动化定位错误，保留尝试日志；修正后 `c6-browser-consumption-retry.log` 和 `c6-browser-overview-events-audit-retry.log` 实际通过。纯类模型没有对象映射，概览如实显示映射问题；这与只查询本体元模型的正式操作发布门禁不是同一条件。
- 最终取消隔离包 SHA `66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC` 于02:17实际启动。先重放原P1/P2/P3/P4及正式数据基线，共5/5/6/5/5组通过，再执行T02三入口和原六种模型实际HTTP探针；STRING/INTEGER/DECIMAL经ELK→HermiT通过，纯类仍ELK，BOOLEAN/DATE仍SUBSET_EXCEEDED。日志分别为 `c7-final-*-restart.log`、`c7-final-p4-guards-events.log`，探针为 `c7-final-profile-*.json`。
- 全量回归曾发现ELK静态共享interrupter被一个取消任务污染，保留作者20轮反例首次RED及独立四项原始RED。修复只取消该ELK任务自己的Future/线程；HermiT仍调用独立实例interrupt，晚创建未查询实例只dispose。作者14项定向与独立5组（含60轮恢复、真实活动任务超时及两请求并行/第三请求恢复）通过；原15项完整模型探针也保留。最终独立Spec/Quality批准见 `c7-reasoning-fallback-review.md`，完整统一回归另行记录，不把定向次数相加代替全量。
- 正式文档最终复核批准见 `c7-final-contract-doc-review.md`：T01四段可解析并逐段与契约夹具一致，语义91项来自作者合同模块测试；T02区分前置拒绝与运行期兜底；T03范围交集、五门禁和历史事件bound_status资格无新增矛盾。审查者已核对最终HTTP零RELEASED证据，未冒称其独立运行HTTP。
- 02:38完整回归在多个Spring测试上下文累计占用连接后触发PG `too many clients already`。该轮日志 `c6-final-isolation-verify.log` 原样保留：361项，1失败/1错误，不能标为全量通过。后端收敛测试池资源后须重跑完整verify；生产门禁、断言和PG连接上限不因此放宽，具体诊断及修正见后端报告。
- 02:39在后端明确释放存储窗口后，实际停止本工程MinIO，使用同一个原正式数据版本 `d3ff3e9b-1333-495c-93c8-a419a57d014b` 验证停机3项；finally恢复同一数据目录并完成恢复3项。未重新发布、重造基线或替换对象。已将存储窗口归还后端，全量回归期间保持服务运行。
- 测试连接池修正已独立批准，见 `c7-test-pool-review.md`：22个Spring测试类共用每池上限4/最小空闲0，保留三连接锁竞争及四worker并发；真实六项定向通过。只改测试配置及资源预算断言，核心生产指纹与JAR未变；最终全量日志另存 `c6-final-pool-verify.log`，不覆盖前一轮失败。

## 最终出口结论

02:55:13，作者单窗口根 `mvn verify` 返回0，14个reactor模块全部SUCCESS，**单轮362项、0失败/0错误/0跳过**：contract91、artifact6、data42、release50、API173。主执行者已读取最终日志尾和汇总，并再次核对实际JAR摘要。模块汇总与本轮逐类计数相等，机器可读证据为 `c6-final-verify-summary.json`；原361项连接耗尽失败日志完整保留，未将定向结果累加替代本轮全量。

最终JAR SHA-256为 `66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC`，与02:17启动、原数据重启和02:39停机恢复验证的运行副本完全一致，测试资源修正没有产生新的生产包。前端最终25文件/230项通过，实际101路径OpenAPI生成后只补测试夹具必需字段，7项定向及类型/格式/生产构建通过，见 `web-combined-verification.md`。这些分轮事实分别记录，不合并声称237项单轮前端全量。

实际页面、版本恢复、追溯和窄屏证据见 `c6-browser-final.md`；当前前端登录页200、匿名身份接口401和存储就绪200见 `c7-runtime-health.json`。C6、C7及OM-D6按本地范围关闭，正式台账与评审记录同步。OM-P3-EXT、OM-P4-EXT和OM-D1/D2继续保留外部验收/决策边界。
