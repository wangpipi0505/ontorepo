# C8 正式设计与契约独立复核

日期：2026-10-21。审查者：`c8_contract_doc_review`。范围：详设 v2.8 §7.1/§8.4/§8.7/§8.8、IAM 补充设计 v1.0、契约测试及指定守卫/发布测试与证据。仅访问 ontorepo 及对应正式文档；未访问相邻平台，未修改生产代码、测试或正式文档，未运行 Maven、Java、数据库或 HTTP。

**文档及断言静态复核通过，实际最终回归待作者补齐。** 初读发现的两处问题已由 root 修正，本审查已复核修正文本/测试源码：①§8.4 的 FAILED 重跑必须排除 allocation_status=VOID；②第④段导入结果缺少全字段映射与消费侧缺省值断言。不能把此处的源码审查称为新增 Java 测试已实际通过。UI 后续发现的“先 tests 后 validate”阶段卡住问题由发布作者及 backend reviewer 继续处理，不在本报告结论中关闭。

## 1. 本审查实际执行的验证

2026-10-21T02:19:38.353Z，以 Node 只读抽取详设 §8.8 中恰好四个 JSON 代码块，逐段 `JSON.parse`，与 `onto-contract/src/test/resources/sample-1.json` 至 `sample-4.json` 作 `assert.deepStrictEqual`，并逐段重算 SHA-256，与 `docs/evidence/continuation/c8-final/design-sample-alignment.json` 的记录比较。命令退出 0，四段全部通过；没有覆盖原对齐 JSON。

| 样例 | JSON 解析 | 测试夹具深比较 | c8-final 所记源码摘要 |
| --- | --- | --- | --- |
| ①操作声明 | 通过 | 通过 | `2cbc7087558707ca5625484de6537b7624d338cf9e8793b95e2dd19407665766`，一致 |
| ②manifest | 通过 | 通过 | `b69bf8e6a7ce3ab509f55b5a2d3b515d182f808b3ff826bf5c57254a828d0f6b`，一致 |
| ③原始响应 | 通过 | 通过 | `d8c995ae438c99612f10236a99539f379b41c49a480b51b48cdb6cc48c908506`，一致 |
| ④导入结果 | 通过 | 通过 | `8d7cdc1a3f94930be51a8524d69061a95f64b5a7b4d2d1082c0f60341db7999f`，一致 |

上述命令仅证明可解析、夹具一致及证据摘要未漂移，未执行 Java Schema、契约生成器或业务流程。

## 2. T01：逐项断言覆盖审查

`ContractTest.fourSamplesParseAndEveryContractStageIsValidated` 保留以下真实断言：参数示例通过 input_schema；③整体通过②信封级 output_schema，业务 data 再通过操作输出 Schema；manifest 与响应 meta 四元组全对象相等；导入 outputSchema/guardAttrs/returnSpec 一致；SYNC/HTTP 与 5000ms；预览四元组均 null 且 preview=true；从①生成预览后正式化的整个结果与②相等；生成不得修改传入声明。此全对象比较覆盖 manifest 的完整 URL、POST、risk/exec/error 等字段，而非只检查能解析。

初读时④仅有关键字段比较，不能称全部导入字段逐项校验。root 新增 `importedSampleMapsEveryManifestFieldAndConsumerDefault` 后，本审查已核对：

- 从②显式映射 17 个字段，包括 invocationKind、adapterType、category、endpoint/httpMethod、timeoutMs、input/output Schema、guardAttrs、四执行标志、riskLevel、errorSemantics、returnSpec、ontologyMetaExpectation；每个源指针均断言非 MissingNode。
- 显式构造消费侧 skillId/versionNo/DRAFT、authRef=null、concurrencyLimit=10、outputMaxBytes=1048576 与 defaultsApplied；确认 manifest 未提供被使用的三个缺省来源字段。
- 构造外层 result=ACCEPTED 后与④**整个对象相等**，因此遗漏、额外或错误导入字段会失败；另外用④的输入/输出 Schema 分别校验①参数示例及③原始响应。
- 方法注释明确这是本工程契约示例验证，不是外部技能中心真实导入证据。原主要正反例保留；AOSP 格式调整不改变其所述断言。

其他契约测试抽读确认：删除整个 meta；未知守卫/无绑定/空守卫；缺参数示例及示例类型错误；非法枚举、缺 adapter、超时及 guard_attrs 类型错误；preview 不可当正式版本；风险降级；禁止外部 Schema 引用；信封根 required、code、meta 四字段类型及 digest 正则被弱化；本地引用/锚点/递归及包装引用边界；布尔型绑定标识符与重复绑定等均有负例。`P3GuardTest` 的协议资源标识符测试与业务查询资源绑定解析测试职责不同，不能互相替代。

**仍待作者执行并登记：**新增全对象导入测试及当前完整 reactor 的实际结果。`c8-final/design-sample-alignment.json` 中的 semanticValidation 是测试定位说明，不是通过计数。旧轮 91 项或当前测试源码不作为本轮最终执行证明。

## 3. T02：前置失败与历史运行期失败

详设 §8.8 继续要求 region/tenant_label 等必需守卫逐项定义且具备适用执行绑定；info_attrs 只供说明，不能替代 guard_attrs。纯模型允许合法主体属性授权规则；实例 dataQuery 必须覆盖每个实际查询资源的有效列绑定，仅主体属性规则不能代替数据过滤。§8.4/§8.7 不存在守卫豁免、强行推进 TESTING 或通过空结果把失败算作成功的口径。

| 证据/测试源码 | 实际证明范围 |
| --- | --- |
| `P2SupplyTest.filterBindingAndUndefinedAndMissingGuardsRefuseBeforeExecutorAcrossThreeEntries` | 明确建立独立 format2 历史候选身份，覆盖未定义、已定义无绑定、空守卫；TRIAL/SERVICE 400，LOCAL_TEST 报告 passed=false；累计 9 个 FAILED 事实，执行器 never、MODEL_SELECT 步骤 0。它不是新发布候选已进入 TESTING 的证据。 |
| `P4CompilationTest.invalidFrozenDeclarationVoidsAllocationAndNeverStartsLocalTest` | 新冻结失败为 FAILED/VOID；tests/prepare 409，test_run/LOCAL_TEST/release 为 0，重新冻结版本号递增。当前源码覆盖 guard/mixed 两类失败。 |
| `P3AuditGuardTest` | 实例操作仅主体属性规则在冻结阶段 FAILED；TRIAL/SERVICE 精确 GUARD_ATTR_UNRESOLVED/NO_FILTER_BINDING，tests 409；另有遗漏关联资源或不存在守卫列的冻结失败。更名真实源表用于暴露错误下探，不等同独立 HTTP 请求计数。 |
| `Smoke-Data-Guards.mjs` | 同步元数据后清零真实 HTTP 源计数；region 合法绑定而 tenant_label 仅有类型；TRIAL/SERVICE 精确拒绝且无 meta。新候选分支要求 tests 409、FAILED、无 TEST PASSED/LOCAL_TEST/release/RELEASED、prepare 409；历史分支为 HTTP 200 但用例和报告均失败。两分支都要求源计数 0。 |

只读核对原 HTTP 证据：`p3-guards-http-smoke.json` 时间 `2026-10-20T16:45:29.115Z`，历史候选 LOCAL_TEST 为 HTTP 200/业务失败；`p4-guards-http-smoke.json` 时间 `2026-10-20T18:21:28.588Z`，新候选 LOCAL_TEST 为 409 CANDIDATE_TERMINAL、blockedBeforeTests=true。两者 TRIAL/SERVICE 均 400，sourceRequests=0。旧 JSON 未逐项序列化最新脚本的 reason 和全部发布断言；原 `.runtime/c7-final-p4-guards-events.log` 有成功终点，C7 审查记录解释了当时 root 实际执行的无 RELEASED 断言。以上均是历史作者证据，不改名为 C8 当前包重新执行。

**仍待作者执行并登记：**最终 C8 包的三入口 HTTP 守卫冒烟及新目录输出；原历史运行期记录保留其原始身份和时间。

## 4. T03、IAM、门禁与事件资格

详设 §8.7 新增 IAM 前置条件与补充设计一致：ACTIVE 身份、当前 ACTIVE 角色功能权限并集、原空间/端点权限及数据守卫共同满足。SYSTEM_ADMIN 不自动获得空间、端点或 OWNER 豁免。A/B 表明确是在这些前提已通过时计算数据范围，未将功能权限等同数据授权。

T03 的数据口径不变：服务端点仅 A 得 A；存在且 NULL 的端点授予不收窄；缺授予拒绝。TRIAL/LOCAL_TEST 的等价端点范围同源于 space_grant.data_scope，角色至少 EDITOR；内部 A/B 或 A 的结果由同一空间范围裁剪。数据源 access_scope 是实际资源白名单，不是凭据技术可达范围。

`WorkflowFixture` 已显式给旧业务测试用户 MEMBER，确保测试继续到达空间/端点/数据边界，而不是误把无功能角色拒绝当成范围检查。`P4ScopeTest` 的实际范围是：空间/策略 AB 时 TRIAL 3 条（A 的 T-A/T-CROSS 与 B 的 T-B），排除未分类；正式端点 A 时 2 条且排除 T-B，端点 NULL 时 3 条；后续空间收窄到 B 时正式服务精确 T-B、TRIAL 1 条。它使用 B 作为收窄实例，不能写成该测试逐行执行了设计表中的“内部仅 A”。LOCAL_TEST 在该例采用明确 CONTAINS 匹配；不能凭其 passed=true 声称逐条验证了 AB 完整集合。其余授权/数据守卫测试提供补充覆盖，但本报告不声称每个表格单元都有独立端到端精确断言。

IAM 测试源码覆盖多角色并集及角色停用、无角色默认拒绝、系统管理员无空间授权、显式 HandlerMethod 权限与矩阵/编码路径、API key 的 ACTIVE/service.execute 条件。`c8-iam-http-stage.json` 是 8 项阶段 HTTP 通过记录，绑定运行包 `50CF88E132B5C1D4D69C030A402858BE95975AE54A99567C34EE54890A027ECE`；按主集成报告，该包不包含后续全部修复，因此不是最终包验收。

§8.4 五门禁和就绪屏障、§7.1 事件资格未因 IAM/T03 改变：守卫失败不可豁免；只有 OWNER 对同候选摘要/修订的 SUBSET_EXCEEDED 可显式豁免；正式制品就绪并复核摘要后才能 READY_TO_COMMIT/提交。READY 与 RELEASED 创建前制品完整。事件按创建时 bound_status 锚定（RELEASED←READY、DEPRECATED←DEPRECATED、CORRUPTED_ALERT←CORRUPTED），后续状态不取消历史事件资格；消费仍只允许 READY。`ReleaseGatePolicyTest` 与 `P4LifecycleTest` 的五场景源码覆盖该区别；`OutboxPolicy.eligible` 仅用事件 boundStatus，不用 release 当前状态。没有新增“历史 RELEASED 可投递，所以当前非 READY 制品可消费”的矛盾。

收尾增量只读核对了 root 在 §7.1 新增的“事件租约时间权威（OM-C8）”：生产使用 PostgreSQL clock_timestamp，ack/fail 取事件行锁后再核期限，回拨保守等待，300 秒仅限制新增退避。该段限制领取/确认的有效时间，并未改变 bound_status 的历史事件资格或 READY 的消费资格，与 T03 授权范围也无替代关系；其生产实现与实际回归由专门 Outbox/后端复核证据承担。

## 5. FREEZING 与阶段限制

§8.4“冻结中断恢复”的默认 15 分钟/1 秒至 1 小时配置、过期 FREEZING→FAILED/VOID、版本不复用、迟到结果不可复活、REVIEWING 不被冻结恢复清理，与所抽读 CandidateService、P4AuditReleaseTest 相符。源码断言涵盖有界恢复、迟到成功上传、预留事务后中断、READY_TO_COMMIT 拒绝 validate/tests/review、锁外迟到 validate 不覆盖后续状态。

初读 §8.4“阶段操作资格”仅列 FAILED 可重跑，未列 VOID 条件；已反馈 root。最终只读确认补充为：“下述允许重跑的 FAILED 仅限 allocation_status 非 VOID；冻结失败或中断恢复产生的 FAILED/VOID 必须重新冻结，不得复用作废预分配标识。”这与 PublicationService 锁内终态保护一致，该文档问题关闭。

**剩余交接：**先 tests 后 validate 的阶段顺序问题由 root 指派发布作者修复并交 backend reviewer；本报告不提前确认其实现/回归结果。最终 CI/reactor 汇总、最新 JAR 摘要、最终 IAM/T02/发布冒烟与重启兼容由 root 补入主集成报告。

## 6. 结论与边界

本次确实独立执行的是四 JSON 解析、夹具深比较和 c8-final 摘要对齐；其余 Java/HTTP 结论来自明确标识的源码抽读或既有作者证据。两项本审查发现已落实为文档澄清和全对象测试断言，并完成只读复核；新增测试的实际通过状态仍归作者唯一 Maven 窗口。未发现 T01/T02/T03 与完整 IAM、五门禁、READY/RELEASED 资格的新设计冲突。

五类外部数据库真实实例及跨系统 J-01～J-05 继续保持外部验收边界；本地契约夹具、收件箱和 trace 不替代外部智能体平台导入/上架/联合运行证据。

## 7. 主执行者追加最终运行结果（保持上文独立审查范围）

上述“待作者执行”项现已完成：最终单轮mvn verify于10:48:27成功，契约模块92项（ContractTest13，含新增第④段全字段映射）及整个407项均0失败/错误/跳过。最终包63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C的四段JSON再次解析/对齐；tenant_label有类型无绑定的最终HTTP三入口均未通过且源请求0。用例先行阶段缺口经独立BR-02复核、整套回归、最终新空间公开HTTP及原r7浏览器复验关闭。详见[c8-integration-report.md](c8-integration-report.md)、[407项汇总](c8-full-verify-summary.json)、[最终HTTP](c8-final/http-suite-summary.json)。本节为主执行者实际运行证据补录，不把上文只读复核改写为审查者执行了Java或HTTP。