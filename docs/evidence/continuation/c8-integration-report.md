# C8 独立审核整改与完整 IAM 联调记录

日期：2026-10-21。状态：**本轮本地实现、独立复核、最终构建/运行包、浏览器及测试账号清理完成**。范围为用户指定独立审核中的成立项与“增加可自定义角色及权限的完整管理体系”；外部边界见文末。

## 实现与复核索引

| 范围 | 实施与独立复核 |
| --- | --- |
| 守卫判据、FREEZING 恢复、阶段资格与发布错误 | [发布整改](c8-release-fixes.md)、[后端独立复核](c8-backend-review.md) |
| TRIAL 并发、撤权/归档协调与三次历史查询 | [执行整改](c8-execution-fixes.md)、[后端独立复核](c8-backend-review.md) |
| 节点时钟与等待跨租约的 Outbox 边界 | [时间整改](c8-outbox-fixes.md)、[后端独立复核](c8-backend-review.md) |
| 实际步骤、严格 Parquet 数值、抽取说明、文件/RDF/映射卫生 | [数据整改](c8-data-fixes.md)、[数据独立复核](c8-data-review.md) |
| 用户、多角色、35 项权限目录、默认拒绝、修订/最后管理员/认证/审计 | [IAM 独立复核](c8-iam-review.md)、[阶段 HTTP 8 项](c8-iam-http-stage.json) |
| 冲突定位、冻结规则执行、批量用例、系统管理与权限界面 | [前端整改](c8-ui-fixes.md)、[前端独立复核](c8-ui-review.md) |

初始 597 文件快照与摘要保留在 `.runtime/independent-audit-20261021-baseline`。新增 V12/V13/V14 迁移，不改旧候选字节、原正式版本与原验收基线标识；已有 Git 仓库无初始提交，本轮不擅自提交。所有操作限定本工程及对应正式文档。

数据复核后的测试兼容变更：`DataAuditRegressionTest` 仅将手工构造 `new SpaceService(null)` 同步为 `new SpaceService(null, null)`，以适配授权协调依赖；该测试当前 SHA 为 `5C59521969A8D5000C9A24B33C157E2561137BCDE3E632228C0E132553C789C1`。原复核报告的测试指纹保留为当时快照，业务断言与数据生产实现未随此构造同步改变；当前版本由最终全量实际复验。

## 已执行的分轮证据

- IAM 安全专项 14 项通过：`.runtime/c8-iam-security-green-r2.log`。保留矩阵参数路径绕过的有效 RED 与修复，使用实际 MVC HandlerMethod 执行权限策略；保留一次重复测试角色残留引起的夹具失败，不当作生产缺陷或隐去。
- 数据/发布/IAM 合并专项 59 项通过：`.runtime/c8-combined-green.log`，其中分类有交叠，不能与下列重复运行累加成总覆盖数。
- 执行并发与既有流程回归 48 项通过：`.runtime/c8-execution-regression.log`，含执行专项 7、Workflow 14、P3Mapping 14、P3AuditGuard 2、P4Concurrency 2、P4Failure 9。
- Outbox 最终专项 27 项通过：`.runtime/c8-outbox-final-green.log`，两个行锁等待跨期反例转绿，原协议/relay/事件组件回归保持通过。
- 前端最终完整 **31 文件、259 项测试通过**，最后 CSS 修正后代码冻结重跑，日志 `.runtime/c8-web-final-frozen-full.log`，10:32:50 开始、57.60 秒；CSS 后 `pnpm typecheck`、`pnpm format:check`、`pnpm build` 通过，日志 `.runtime/c8-ui-style-{typecheck,format,build}.log`。此前真实后端导出 **109 个接口路径** 后已生成客户端类型，早期 10:07:46 的 259 项保留为过程结果，不跨运行累加。
- §8.8 四段 JSON 重新解析并与实际逐项断言夹具深比较一致：[本轮解析证据](c8-final/design-sample-alignment.json)。语义断言由最终 `ContractTest` 实际结果补足，不能仅凭 JSON.parse 关闭 T01。

### 实际浏览器追加发现与复验

用例页先运行新候选的冻结测试，原实现会在机器检查尚缺时推进 REVIEWING，而新 validate 前置拒绝该阶段，导致同候选无法继续发布校验。真实浏览器候选为 `5f692982-3ada-421f-a1bc-41481820e534`。已保存 `.runtime/c8-full-verify-interrupted.log` 与 [中断批汇总](c8-full-verify-interrupted-summary.json)：已完成 234 项、0 失败，但进程主动停止，明确不称完整通过。

作者追加 3 项有效 RED 后修复：最终写回锁内读取同摘要的最新机器门禁，真实失败优先 FAILED/MACHINE_GATE_BLOCKED，缺机器检查保持 VALIDATING，齐全通过才 REVIEWING；SUBSET_EXCEEDED 保留等待 OWNER 豁免的唯一例外，review/prepare 原要求不放宽。`.runtime/c8-publication-order-green.log`，10:27:15 结束、01:38，契约 13 + API 17 = **30 项通过**，0 失败/错误/跳过，包含第④段全字段导入断言、3 项阶段反例及原发布/并发/豁免回归。独立复核追加到 [后端复核](c8-backend-review.md)。

另一实屏发现是权限选择弹窗继承 checkbox-group 的零字号/行高；局部样式恢复权限分组内的文字排版。主执行者已核对改动与 390px 实际截图，前端完整测试及检查通过。桌面/390px 的 C12、角色 CRUD/安全审计、只读功能、真实角色停用与密码重置会话失效均已通过，见 [浏览器记录](c8-browser.md)。最终新包复验同一 r7 候选再次 TEST 通过后进入 FAILED，validate 实际可执行，冲突/一致性通过、CONTRACT_NOT_AVAILABLE 继续阻断 prepare/commit，原阶段卡住关闭；最终 IAM 页面只读按钮仍为 0。

最终测试用户 `c8_ui_reader_20261021` 已停用、角色清空（revision=2），临时只读角色在无成员后删除，ROLE_DELETE 审计时间 10:52:35；旧登录下一导航返回登录页并只提示一次过期。临时凭据脚本已删除并检查不存在，初始管理员未改动。保留原业务空间、候选、执行和审计证据，最终包/API/Vite及已有 PG/MinIO 服务继续运行。

## 运行包与最终验收

早期阶段联调包 SHA-256 为 `50CF88E132B5C1D4D69C030A402858BE95975AE54A99567C34EE54890A027ECE`，未包含 C04/C05 和最后 Outbox/用例阶段修复。它只用于前端/IAM 早期联调，不作为最终交付制品。

最终单轮 `mvn verify` 于 **10:48:27** 完成，耗时 **20:32**：**14 模块 SUCCESS，407 项通过，0 失败、0 错误、0 跳过**。按本轮启动时间筛选实际 Surefire XML，未混入早期定向残留报告：契约 92、制品 6、数据 43、发布组件 53、API 集成 213，合计 407。命令、环境、逐测试类结果和制品摘要见 [完整构建汇总](c8-full-verify-summary.json)，实际日志 `.runtime/c8-full-verify.log`。

最终 JAR 为 `onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`，**194922278 字节**，SHA-256 **`63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`**。主执行者重新计算摘要一致，再安全停止仅本工程 API/Vite，以 SHA 命名的不可变副本重新启动；PG、MinIO、原始数据和样例源保留。relay 已开启。

最终包重新导出 109 个接口路径，与已生成类型所用阶段契约作全对象比较，内容及文件摘要均一致（`41b70bcc21f923d5c740e263b6234dc18ab392783df2f935d5fba8022abbe589`），见 [接口对齐](c8-final/openapi-alignment.json)。前端最后 CSS 修复后的 259 项测试及类型/格式/构建结果仍对应当前源码，不因未改变的接口重复生成或重复运行。

最终重跑通过 `ONTO_EVIDENCE_DIR=docs/evidence/continuation/c8-final/` 单独保存输出；持久性脚本仍从原历史证据读取候选与版本标识，不重建旧对象来替代兼容验证。新发布冒烟另建独立空间，不覆盖此前验收 JSON。

### 最终运行包的公开 HTTP 与原基线检查

主执行者实际顺序运行 10 条脚本，全部退出 0，绑定上述最终运行 SHA；逐命令起止时间及运行副本记录见 [HTTP 汇总](c8-final/http-suite-summary.json)，日志 `.runtime/c8-final-http-suite.log`。下列数字是各验收块/源组，不跨类别拼成新的单元测试总数。

| 检查 | 实际结果与证据 |
| --- | --- |
| 完整 IAM | 8 项通过，[最终 IAM HTTP](c8-iam-http-final.json)；多角色、默认拒绝、功能权限与空间权限取交、矩阵/编码路径、停用/重置密码、修订及安全审计 |
| T02 缺绑定 | TRIAL/SERVICE 400 GUARD_ATTR_UNRESOLVED/NO_FILTER_BINDING；LOCAL_TEST 409 前置拒绝；源请求 0，[三入口结果](c8-final/p4-guards-http-smoke.json) |
| 新空间正式发布 | 11 项通过，含先 tests 仍 VALIDATING、同候选继续 validate/tests/review/prepare、幂等提交、默认端点拒绝、实际 API key、正式 meta、冻结隔离、通知及弃用，[发布 HTTP](c8-final/p4-http-smoke.json) |
| 原 P1 候选 | 5 项重启检查通过；草稿已删除操作仍从原候选执行，原摘要与模型内容保留，[结果](c8-final/persistence-smoke.json) |
| 原 P2 冻结供给 | 5 项通过；旧词条、来源、契约和摘要不受草稿更新影响，Lucene 从持久数据重建，[结果](c8-final/p2-persistence-smoke.json) |
| 原 P3 候选 | 6 组真实源候选 TRIAL/SERVICE 与来源证据通过；仍使用原候选/源快照和原预期，不用新的 kind 冒充外部实例，[结果](c8-final/p3-persistence-smoke.json) |
| 原 P4 弃用版本 | 5 项通过；历史可审计、消费拒绝、幂等不复活、事件和撤销凭据保持，[结果](c8-final/p4-persistence-smoke.json) |
| 原积压事件基线 | 5 项通过；原 RELEASED/DEPRECATED 标识、顺序和收件去重保持，[结果](c8-final/p4-deferred-persistence-smoke.json) |
| 原正式映射版本 | 5 项通过；两个原正式版本/摘要、原查询结果、原历史执行完整来源证据和 6 步授权快照完全一致，[结果](c8-final/p4-data-persistence-smoke.json) |

### T01/T02/T03 本轮结果

四段原文 JSON 在最终包验收时再次解析并与实际夹具深比较一致，[解析证据](c8-final/design-sample-alignment.json)。本轮 ContractTest 13 项及整个契约模块 92 项均在最终单轮通过，新增④完整导入断言已实际执行；逐项复核依据见 [契约/文档独立复核](c8-contract-doc-review.md)。

| 样例 | 已实际通过的主要逐项语义断言 |
| --- | --- |
| ①声明 | guard 定义/类型/绑定、参数示例通过 input_schema，执行标志和风险语义进入契约生成，生成不改原声明 |
| ②manifest | 由①生成预览、正式化后的整个对象与②相等；完整 URL/POST、SYNC/HTTP、输入/信封输出 Schema、guard 集合、执行/风险/错误/返回字段及四元组一致；正式导入断言通过 |
| ③原始响应 | 整个响应通过②的信封 Schema，data 通过操作业务输出 Schema，meta 四元组与②全对象相等；删除整个 meta 的负例拒绝 |
| ④导入结果 | 从②显式构造 17 个映射字段，以及 skillId/versionNo/DRAFT、authRef=null、超时覆盖/并发10/响应1048576及 defaultsApplied；与④整个对象相等，并用其输入/输出 Schema 复验①参数和③响应 |

④的结果证明本工程契约示例映射一致，不替代外部技能中心真实导入。T02 同时保留“新候选前置拒绝”和历史 format2 候选的运行期拒绝：最终 P2Supply/Compilation/AuditGuard 测试覆盖后者及三入口失败事实，新最终包 HTTP 覆盖前者，不伪造未执行的 LOCAL_TEST/TEST 报告、READY 或 RELEASED。

T03 的权限前提、四层交集、§8.4 五门禁/就绪屏障和 §7.1 bound_status 事件资格经独立复核无新增矛盾；最终 P4Scope、Lifecycle、Gate 等测试通过。设计 A/B 表为范围语义；实际 P4Scope 的内部收窄实例使用 B，LOCAL_TEST 显式 CONTAINS，未夸大为每一表格单元均有完整精确端到端断言。

## 范围说明

OM-C07 的共享 ELK 配置指控不成立；OM-C03 的 E1 主链缺失、OM-C12 无 Git、Waiver 死类型和缺 401 跳转等原判断已按代码纠正。C11 按明确修订边界实现固定规则先验和可配置词表；表抽取仅使用已授权元数据并标注 METADATA_ONLY，没有实现实例样例抽取，也不宣称用户单独批准过此项降级。

ClickHouse、MongoDB、Redis、Cassandra、Neo4j 五类真实实例验收仍遵照用户决定待外部环境；跨系统 J-01～J-05、OM-D1/D2 继续按台账跟踪。本地收件箱、服务消费和 trace 不替代外部智能体实际运行证据。
