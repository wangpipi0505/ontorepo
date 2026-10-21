# 后续验收矩阵

> 最新C8验收见文末追加；下列P1～P4/C7行及362/230等数字保留其原批次，不当作C8当前结果。

本表区分代码实现、独立审查与真实场景。只有证据对应的阶段可勾选；测试数不跨重复运行累加。运行主机为本工程独立 Windows/PG/MinIO，不操作相邻智能体工程。

| 出口 | 必需证据 | 当前状态/索引 |
| --- | --- | --- |
| P1 属性/关系/规则/策略/导入/检查/用例 | 真实PG、OWL/Jena/ELK/SHACL、四类冲突/四类门禁、十问题、导入保真 | 后端实现与复审关闭；`c1-backend-report.md` / `c1-backend-review.md` |
| P1 前端与原有功能 | 固定修订、权限、409/删除/数字范围、桌面/390px | 本期完成：50条基线及复审通过；最新制品HTTP8项与应用重启旧候选5项通过，见 `c3-integration-report.md` |
| P2 四段样例/契约核 | JSON可解析、逐项Schema、守卫/示例/信封、递归引用边界 | 最新91条通过；四段JSON再次独立解析并与逐项断言夹具深比较一致，`c4-contract-review.md` / `c7-final-contract-doc-review.md` |
| P2 内部执行/知识/NL | 真实PG、三入口缺绑定零执行、可信属性、Schema失败事实、冻结知识/Lucene≥8/10、NL实际选择 | 本期完成；R1～R4独立复审关闭、最新HTTP6+边界14/重启5通过、Lucene10/10；`c4-backend-review.md`。P2Review19/P2Supply19均已进入最终单轮362项全量通过 |
| P2 前端 | 五页面/JSON类型/冻结meta/搜索/修订与生命周期 | 原77条与独立复审通过；扩展参数示例/任意JSON根/资源守卫修复已独立复审关闭，真实1440px/390px声明保存及调用验证通过，见 `c4-integration-report.md` |
| P3 适配内核 | 九类边界内代码；PG/湖/文件/HTTP真实实例，范围/类型/证据/超时 | 三项修复后27条全过，原reviewer独立精确反例关闭；`c5-adapters-review.md` |
| P3 五类外部实例 | ClickHouse/MongoDB/Redis/Cassandra/Neo4j真实连接/权限/metadata/query | 用户已确认待外部验收，不以编译/单测/PG改kind替代 |
| P3 管理/映射/抽取 | 四完整性、任务使用算法全例、A/B/null/关联双方范围、快照diff影响、TABLE/FILE/DOCUMENT三来源确认 | 现有环境完成；最终制品HTTP19、缺tenant_label三入口零源请求、原候选重启6组通过；后端独立14项正反断言通过、全部发现关闭。统一259与末次ZIP补丁后8项分轮记录。见`c5-integration-report.md` |
| P3 转换过滤 | 冻结码表/严格类型、授权先行、转换失败不得被业务过滤或LIMIT隐藏 | 独立五P2全部关闭；最终制品公开HTTP5项GREEN、基础制品RED保留，contract91/data42回归通过。见`c5-transform-review.md`及`p3-transform-http-smoke.json` |
| P4 制品基础 | 条件写/幂等/并发/损坏/大对象 | 真实MinIO6条与独立审查通过；`c6-artifact-review.md` |
| P4 门禁/事件领域核 | 五门禁/仅超范围豁免、历史资格/id序/DEAD/重放规则 | 已接入实际发布服务，原14条与独立复审通过；`c6-policy-review.md` |
| P4 前端 | 五页面、trace、固定修订/发布摘要、凭据生命周期、恢复冲突可恢复 | 独立复审和真实发布/授权/消费/diff/恢复/trace已通过；追加目录状态问题独立9项及实际按钮流程关闭；`c6-browser-final.md` |
| P4 outbox运行组件 | PG领取/租约/顺序/重放、HTTP确认/有界响应/期限、DEAD告警 | 37条测试与独立39项PG/HTTP断言通过；实际五场景作者测试及根relay停用→进程重启→原事件有序送达通过；`c6-outbox-review.md` / `c6-integration-report.md` |
| P4 发布事务/版本/授权/事件 | 五写/CAS/幂等/就绪屏障/旧版本不读草稿/恢复、六步四层授权、真实五事件场景/重启/损坏隔离 | 本地完成：独立复核、根HTTP10+正式数据8、最终包两类原P4重启5+5、真实存储停机/恢复3+3通过；测试资源问题修正后最终单轮362项全verify通过，见`c6-final-verify-summary.json` |
| P4 独立溯源 | TRIAL/LOCAL_TEST/SERVICE/EXTERNAL事实、真实来源+映射+版本、时间线/图 | 实际HTTP/持久化和页面通过，原数据trace6授权/11节点/10边且全证据身份摘要比对一致；`c6-browser-final.md` / `p4-data-persistence-smoke.json` |
| C7 前端完整性修正 | 旧页面响应隔离、对象树所见修订；双树拖拽/连线/确认；空间概览与四维筛选 | 四项及发布状态增量独立关闭；真实READY概览已补验。单次25文件/230项通过；最新OpenAPI生成后7项定向、类型/格式/构建通过，见`web-combined-verification.md` |
| OM-D6 完整 EL 推理 | ELK优先、仅明确不完整转HermiT、原公理保真、共享预算、失败阻断 | 完成：三基础属性实际发布与失败边界通过；ELK共享中断污染修复后14项定向通过，独立原15项及取消隔离5组通过、Spec/Quality批准；最终包原六模型HTTP及完整362项统一回归通过 |
| 跨模块消费 | 联合J-01～J-05、智能体运行实际本体版本证据 | 需要双方独立联调环境；本工程默认PG通知收件箱仅证明本地事件闭环，不等于技能中心上架 |

联合场景来源：`文档/设计/详细设计/智能体构建/前置契约-图定义·trace模型·技能调用·授权确认.md` §8 及双轨实施计划 §4.2。该表涉及智能体运行身份/恢复/ANY/调试等另一模块能力；本体侧负责契约、固定版本实际调用与证据输出，不能把本模块单测称为整个联合场景已经通过。

2026-10-21 02:55:13，C7本地出口完成：最终14模块整套构建成功，后端362项、0失败/错误/跳过；前端25文件/230项及生成类型后7项定向、类型/格式/构建通过；原数据重启、对象存储真实停机/恢复、OpenAPI客户端对齐、桌面/390px浏览器与独立审查均完成。原失败与分轮证据保留，最终结果见`c6-final-verify-summary.json`、`c6-integration-report.md`。五类外部实例、跨系统联合场景及OM-D1/D2继续待外部核实/决策。

## C8 当前本地出口（2026-10-21）

| 出口 | 当前实际证据 |
| --- | --- |
| 成立项整改、并发/查询/阶段/时间边界 | 后端/数据/UI/IAM/契约文档独立复核；BR-01/BR-02等追加发现关闭，c8-*-review.md |
| 完整自定义角色与权限 | 真实PG默认拒绝/并发最后管理员/修订/重置会话、实际最终HTTP8项、35权限目录及真实角色CRUD/撤权/只读页面，c8-iam-http-final.json与c8-browser.md |
| 最终后端 | 14模块407项、0失败错误跳过，最终JAR SHA63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C，c8-full-verify-summary.json |
| 最终前端 | 最后CSS之后31文件259项，type/format/build通过；桌面/390px、会话过期、冻结执行/批量用例/冲突定位，c8-ui-fixes.md与c8-browser.md |
| T01/T02/T03 | 四段解析与逐项语义（含④完整映射）通过；三入口缺绑定零源访问；IAM、范围、五门禁、事件资格无新增矛盾，c8-contract-doc-review.md/c8-integration-report.md |
| 最终包与旧基线 | 10条HTTP/兼容脚本退出0：新发布11含tests先行；原P1/P2各5、P3六组、P4弃用/积压/正式数据各5，c8-final/http-suite-summary.json |
| 外部边界 | 五类真实源、J-01～J-05、OM-D1/D2继续保留；C11表来源METADATA_ONLY，没有实例样例抽取 |