# C5 / P3 后端独立审查

初审时间：2026-10-21（Asia/Shanghai）。审查对象为 P3 管理、三层映射、冻结实例执行及三来源抽取接入。依据工程 AGENTS.md、`contracts/p3-data.md`、C5 brief/report，以及详设 v2.6 §7.2/7.5/8.5/8.7/8.9/8.10、开发计划 v1.6 P3。

**最终复审结论（2026-10-21 00:46 后）：本次后端范围 Spec：APPROVE；Quality：APPROVE。原 5 项 P2 全部关闭，R05 后续目录载荷遗漏亦已关闭。新包 11 项独立 HTTP 修复/增量断言及另 3 项目录正反对照通过；无本审查尚未关闭的发现。五类外部实例仍明确待验。最终制品、证据和限制见文末“最终复审与关闭记录”。**

## 初审结论与基线（历史保留）

- **Spec：REQUEST_CHANGES。** 发现 5 项 P2，均已通过公开 HTTP 独立复现；修复及新包复审完成前不批准 P3 出口。
- **Quality：REQUEST_CHANGES。** 控制器、应用服务、映射 DSL、规则抽取的主要职责划分和中文边界说明可读；阻断项是去重、完整性与依赖关系的实现错误，以及 LIMIT/解析预算边界。没有另外罗列纯风格重构建议。
- HTTP 基础包 SHA-256：`FE51FF585FE578B0B0E23CA417ACF064670D0B32737D459A817F09727188523F`，地址为本工程 `127.0.0.1:18182/onto`。所有写入均在本审查新建空间；只读工程公开 `onto_sources` 样例，不修改根任务的空间、源记录或服务。
- 审查中作者已开始修改源码。下面保留**原始实证**；源码看见修改不等于运行包已验证。原探针用于重现旧行为，修复后应另外新增复审断言，不放宽原断言。

## 发现

### C5-R01 / P2：属性映射更新被自身阻断，而同字段重复映射被接受

定位：`onto-mapping/src/main/java/cn/ontorepo/mapping/application/MappingService.java`，`validate` 属性分支约 189 行；调用入口 `save`。

原实现遍历所有属性映射，仅在同对象、同本体属性且 `sourceField` 不同时报错，既没有排除正在修改的映射，也没有拒绝完全相同的重复项。

复现：建立 CSV `id,alternate,region` 和一条 `id → 标识` 属性映射。PUT 同一映射把 `sourceField` 改成快照内合法字段 `alternate`，返回 `400 INVALID_ARGUMENT / 同对象属性不能有多个来源字段`；原定义再次 POST 则 `200 OK`。重复后四项完整性仍全过，但绑定投影该属性的 data-query 返回 `400 AMBIGUOUS_PROPERTY_MAPPING`。

影响：前端无法正常更改来源字段，普通重试能创建破坏后续操作的重复映射。

修复：以 `(objectMappingId,targetPropertyId)` 唯一核验，更新时排除当前稳定 id；完整性复核也须识别历史重复。必要时使用数据库唯一约束保证同一业务键。

证据：`c5-review-probe.mjs` / `c5-review-probe.json`。状态：**OPEN，已见作者修改去重源码，待新包独立复审**。

### C5-R02 / P2：必填属性完整性只在全空间找任意映射，漏检同类其他对象映射

定位：同文件 `validate` 的必填属性遍历（初审源码约 407–419 行；当前已变动）。

复现：同一类建立两个对象映射，仅第一个映射配置其必填属性。`GET /mappings/validate` 返回 `valid=true`，四项均通过，`coverage={mapped:2,pending:0,unmappable:0}`。

影响：第二条实例路径缺少类必填属性，工作台和出口校验却显示完成；为该对象建立真实查询后才遇到映射缺失。

修复：逐个活动类的对象映射检查该类必填属性，以对象映射和属性的组合定位缺项；覆盖度分母和 `coverageBasis` 应与实际检查口径一致。

证据：`c5-review-probe.json` 的最后一项。状态：**OPEN，已见作者逐对象修改源码，待新包独立复审**。

### C5-R03 / P2：基数检查在 LIMIT 之后，缩小 maxRows 可掩盖实际关系违例

定位：`onto-mapping/src/main/java/cn/ontorepo/mapping/application/FrozenDataExecutor.java`，构造 `QuerySpec` 的 `policy.maxRows()`、调用适配器后遍历 `queried.rows()`，再以 `fromTargets/toSources` 判断 `cardinalityCheck`（当前约 228–242、304–311 行）。

复现：公开 PostgreSQL 样例中 `ALG-B` 对应 `T-B` 与 `T-CROSS`。在自建空间声明算法→任务 `ONE_TO_ONE`，两端 region 显式绑定，空间范围不限，`cardinalityCheck=true`。同一查询 `maxRows=1` 冻结试执行为 `SUCCEEDED` 一行；仅改 `maxRows=20` 后冻结试执行为 `FAILED / CARDINALITY_VIOLATION`。

影响：关系完整性取决于返回上限，已发现不满足本体约束的数据可被小 LIMIT 错误标为成功；重复身份检查也需审慎处理同类截断问题。

修复：在有效授权和业务过滤之后、最终结果 LIMIT 之前，执行有界的违例存在性检查或等效受限计划；不能只校验被截断的结果。不能证明时应明确拒绝，不能默认为通过。

证据：`c5-cardinality-probe.mjs` / `c5-cardinality-probe.json`。状态：**OPEN，待修复和新包独立复审**。

### C5-R04 / P2：关系 to 端属性依赖未展开，影响分析漏报并允许删除被操作引用的映射

定位：`MappingService.impact`（原约 375 行附近，当前约 349 行已改委托）；`delete` 复用其 operations 阻删。`DatasourceService.operationIds` 的同类直接对象匹配还用于目录和元数据 diff。

复现：RELATION data-query 明确投影 to 端属性。查询该属性映射的 `GET /mappings/impact?kind=properties&id=...` 返回 `operations=[]`。随后 DELETE 此映射返回 `200`，但之后完整性 `affectedOperations` 包含原操作，证明删除已破坏已有操作引用。

原根因：属性先归一化成所属对象映射，仅与 `q.objectMappingId`（关系 from 端）比较；查找关联关系时仍用原属性映射 id，未展开 to 端关系。

影响：任务使用算法等双端投影的目标属性变更没有正确操作提示，阻删可被正常 API 绕过，目录/diff 的目标端操作定位也会漏报。

修复：统一从映射到操作的依赖解析，识别关系两端及投影/过滤所用属性；影响、删除、目录和 diff 共用同一口径。

证据：`c5-cardinality-probe.json` 的 `toPropertyDependency`，含实际 operationId、空 impact、删除成功及删除后的 affectedOperations。状态：**OPEN，已见作者提取 MappingDependencies 源码，待新包独立复审**。

### C5-R05 / P2：Office ZIP 256 条目上限不计目录，可被实际上传绕过

定位：`onto-mapping/src/main/java/cn/ontorepo/mapping/application/ManagedFileService.java:190` 与 `:194`。

复现：读取工程真实 `domain.docx`，保持所有原有文件条目不变，另加 300 个空目录。以 DOCX 上传成功，返回 `200 OK`，尽管总 ZIP 条目已远超 256。该探针没有压缩炸弹、外部地址或宏。

根因：上限使用只存非目录内容的 `out.size()`，而 `e.isDirectory()` 直接 `continue`。目录不计入条目预算，压缩包处理耗时亦无法由该预算约束。

影响：声明的条目限制可被普通 Office 文件绕过，上传的完整 ZIP 遍历仍可大量消耗解析工作，破坏固定解析边界。

修复：每次 `getNextEntry()` 后用独立计数递增并检查，包括目录；保留文件重名检查和 32 MiB 解压总量限制。目录若允许携带数据，也应计入实际读取/跳过预算。

证据：`c5-ingest-review-probe.mjs` / `c5-ingest-review-probe.json` 最后一项。状态：**OPEN，待修复和新包独立复审**。

## 已覆盖且未发现新增阻断的问题

静态审查覆盖新增 mapping/ingest 全部生产 Java、P3 控制器/DTO、V9、CandidateService/FrozenExecutor/SupplyService、GuardService/GuardValidator、模型引用阻删接入及操作兼容路径。

- AES-256-GCM 使用环境主密钥、随机 nonce 与 sourceId AAD；安全目录不返回配置密文或明文。FILE 的 root/path 由服务端生成、fileId 同空间校验、冻结摘要校验明确。主密钥缺失不明文回退。
- 管理写入口建立事务并通过空间锁检查角色、修订，修订和审计随业务回滚。源配置/探测/上传要求 OWNER；映射/抽取确认沿用 EDITOR 边界；读取需要空间授权。匿名真实 HTTP 读取已返回 401。
- 多资源守卫在调用 `sources.source` 解密及适配器 query 前，逐声明属性、逐参与资源精确匹配冻结绑定与元数据字段，并检查源 accessScope。通用资源名与 SQL 标识符校验分离；纯 MODEL 两种源绑定均拒绝。未见从请求主体伪造身份或放宽权限的新通道。
- 三层定义、加密配置、元数据、操作 dataQuery/码表随候选原始字节冻结，执行不回查当前映射/源草稿；证据取适配器实际 SOURCE_RECORD 并保存 meta/mapping/candidate 摘要。不能把此静态结论替代实际冻结篡改回归。
- 新增三段 probe、真实元数据追加与 diff 路径存在；未见把后续 NOT_RUN 填成 PASSED 的管理层伪造。目录/diff 的依赖遗漏已列 C5-R04。
- 独立真实 HTTP 已完成 CSV/JSON/XLSX/TXT/PDF/DOCX 六种上传与抽取，均有来源定位且仅生成 PENDING；PDF 使用真实 page，TXT/Word 没有伪页码。抽取后模型仍空。缺依赖确认返回定位错误且不写模型，逆序批量依赖接受成功，重复确认返回 409；纯文本冒充 DOCX 被拒绝。
- Office 采用 ZIP 白名单扫描和真实 POI，PDF 使用 PDFBox，未执行公式/宏/外部实体；总上传、解压字节、候选数量和处理时间均有明确边界代码。条目计数漏洞已列 C5-R05；没有运行破坏性高负载、解压炸弹或外部实体联网探针。

## 覆盖限制与复审要求

- 本轮只读实现及原测试，新增的三个独立 Node 探针及安全 JSON 结果均在本证据目录；未执行 Maven，未触碰共享 target，未启停服务/数据库，未直接写业务库，无 Git 提交。
- 本轮 HTTP 实证集中在映射/基数/依赖/解析确认；角色矩阵、三入口 A/B/null/空白、源白名单零访问、冻结改稿和磁盘字节篡改、NL 链路及最终 P1/P2/P3 全回归还须结合新包由主任务统一收口。本轮静态未见新增缺陷不等于这些场景已全部实测通过。
- `onto-data` 原独立内核已有专门审查；转换属性过滤增量由另一实施任务处理，本报告不把尚未完工部分重复当新发现，也不据此批准整个 P3。
- ClickHouse、MongoDB、Redis、Cassandra、Neo4j 五类保持**待外部实例验收**，按用户已确认边界处理，不因缺实例阻塞本轮代码审查，更不写成实际连接/执行通过。
- 需保留上述初审发现，补记新包 SHA、逐项正反断言与回归结果后才可变更状态。当前源码中已观察到的修复尚未独立运行验证。

## 复审阶段补充：R05 目录载荷绕过解压总量

作者已用独立 `entryCount` 修复目录不计条目数的问题。但 2026-10-21 第二轮静态复核发现：目录仍直接 `continue`，ZIP 目录条目允许携带数据，后续 `getNextEntry()` 隐式读完/解压它时没有累计 `total`。因此只修条目数量还不能关闭 R05。

独立有界探针读取真实 `domain.docx` 并添加一个含 33 MiB 零字节数据的目录条目；仅调用真实编译的 `ManagedFileService.zip`，未请求 HTTP、未写共享 target。结果：

```text
ACCEPTED directoryPayloadBytes=34603008 compressedArchiveBytes=36279 retainedFileEntries=7
```

该单次负载仅比固定 32 MiB 预算多 1 MiB，压缩归档 36,279 字节；明确绕过解压字节上限。证据为 `.runtime/c5-backend-review/ZipDirectoryBudgetProbe.java` 与 `zip-directory-probe-output.txt`。建议对目录执行真实首字节读取，非空立即拒绝，不依赖可能为未知值的 `entry.getSize()`；空目录仍须计入 256 条目预算。R05 继续 **OPEN**，等待本点修复及重新运行同一探针。

本轮另使用 `.runtime/c5-backend-review/DateBoundaryProbe.java` 反射真实编译的 `FrozenDataExecutor.sourceScalar`：`java.sql.Date` / `LocalDate` 输出 ISO 日期字符串，时间戳仍被严格 DATE 转换拒绝。此为编译代码的本地独立边界检查，不冒充新包 HTTP 的原生数据库 DATE 端到端验收。

## 最终复审与关闭记录

最终制品为 `onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`，独立读取文件核验：**188087936 字节，SHA-256 `E2D5852EAD14145E767BD50D0F9A9E55DB8D317C76DB6F19ED4C8C31B5DE30C8`**。root 按此制品启动 `18182/onto` 后执行本轮 HTTP；本审查未运行 Maven、未启停服务、未修改生产源码或原测试。

### 原发现逐项关闭

| 发现 | 代码复核与独立新包行为 | 最终状态 |
|---|---|---|
| C5-R01 | `MappingService.java:189` 排除当前 id，对同对象同本体属性统一拒绝重复。合法 `id→alternate` 更新成功，完全相同定义再 POST 返回 `409 PROPERTY_MAPPING_DUPLICATE`，目录仍只有 1 条属性映射。 | CLOSED |
| C5-R02 | `MappingService.java:391` 起逐对象映射统计属性。同类第二对象未配置必填属性时 `valid=false`、REQUIRED_PROPERTY 失败，issue 同时定位对象映射 id 与属性 id，coverage `mapped=2,pending=1,unmappable=0`。 | CLOSED |
| C5-R03 | `FrozenDataExecutor.java:241` 起将完整性集合与业务结果上限分开，先检查后截断，并对达到 500 行给明确未穷尽错误。同一公开样例违例在 `maxRows=1` 和 `20` 均为 `CARDINALITY_VIOLATION`；改为符合数据的 ONE_TO_MANY 后正常返回 1 行，只带实际该行的双端 2 条 SOURCE_RECORD。 | CLOSED |
| C5-R04 | `MappingDependencies` 统一展开关系双端和属性投影/过滤；impact、目录和 diff 调用共用解析。to 端属性 impact 和 catalog 均包含实际操作 id，删除返回 `409 MAPPING_IN_USE`，原映射保留。 | CLOSED |
| C5-R05 | `ManagedFileService.java:191` 使用所有条目的计数；`:198` 以实际首字节拒绝非空目录。原 300 空目录归档 HTTP 400。原 33 MiB 目录载荷本地反射由 ACCEPTED 变 REJECTED，最终 JAR 公开 HTTP 同样 400；真实 DOCX 及仅加一个空目录的 DOCX 均仍 200。 | CLOSED |

主证据：`c5-backend-rereview.mjs` 与 `c5-backend-rereview.json`，进程退出 0、11 项 PASSED；目录最终制品正反对照为 `c5-directory-http-rereview.mjs` / `.json`，进程退出 0、3 项 PASSED。R05 的旧 ACCEPTED 输出和新 REJECTED 输出分别保留于 `.runtime/c5-backend-review/zip-directory-probe-output.txt`、`zip-directory-probe-recheck-output.txt`，没有覆盖旧结果。

### 新增边界的独立验证

- **CODEBOOK 引用阻删**：保存真实 CODEBOOK 属性映射后，删除码表返回 `409 CODEBOOK_IN_USE`；代码检查覆盖 `data_mapping.definition.transform.codebookId`，权限、空间锁和修订事务沿用原服务边界。
- **非主键对象身份与 LIMIT**：两条实际 CSV 行有不同物理源记录但 `alternate=SAME`，业务 `maxRows=1`，仍 `FAILED / OBJECT_KEY_DUPLICATE`。500 条唯一身份行明确 `OBJECT_KEY_CHECK_INCOMPLETE`；499 条唯一身份行成功且只返回 1 行、1 条 SOURCE_RECORD，未把完整性多读的记录当成返回证据。
- **真实 PG 主键证明**：静态复核只接受 RELATIONAL、单列原生标量实际主键和单变量模板，并要求实际来源证据的主键字段与冻结证明一致；非主键、复合变量、非 PG 路径不走该快路。作者真实 PG 单例 `c5-key-limit-green.log` 另覆盖超过 500 行的主键模板仍按业务 LIMIT 返回 1 行。本审查没有为此修改公开样例库或冒充独立 HTTP 500 行 PG 验证。
- **DATE 规范化**：独立反射真实编译方法验证 `java.sql.Date` 与 `LocalDate` 变成 ISO 字符串，Timestamp 不被放宽并返回 `TRANSFORM_FAILED`。已读取作者 `c5-date-green.log` 的真实 PG 单例 GREEN；本审查的独立证据是类型边界探针，不将其写成独立原生 PG DATE HTTP 用例。

### 历史证据与原样重跑

新包上原样运行三个旧脚本，并保留其**历史缺陷断言**的失败日志：

- `c5-review-probe-legacy-rerun.log`：原先预期字段修改被拒绝 400，现实际 200，历史断言退出 1。
- `c5-cardinality-probe-legacy-rerun.log`：原先预期小 LIMIT 错误成功，现实际 FAILED，历史断言退出 1。
- `c5-ingest-review-probe-legacy-rerun.log`：原先预期 300 目录被错误接受 200，现实际 400，历史断言退出 1。

这些是旧缺陷已无法按原预期复现的日志，**不是产品回归失败，也没有把它们计为通过的测试**；修复正确性由新脚本的显式正反断言证明。重跑后核对 `c5-rereview-original-hashes.json`，三个原 `.mjs` 和三个原 `.json` 共 6 份文件 SHA 均未改变。

### 回归版本口径与剩余限制

已只读核对作者 `c5-final-verify.log` / `c5-final-verify-summary.json`：2026-10-21 00:39:37 的根工程 verify 为 **259 项、0 failures/errors/skipped**，对应最后非空目录补丁前的制品 `EFE336...C4C73`。其后仅 ZIP 单点修改，`c5-zip-directory-green.log` 显示完整 `P3IngestTest` **8 项、0 failures/errors/skipped**，再 package 生成当前 `E2D585...E30C8`。该证据链不等于最后补丁后整套 260 项重新执行；本审查也没有另起 Maven 冒用独占测试窗口。

转换内核由 `c5-transform-review.md` 独立审查五项关闭，本轮只检查冻结接入和上述新边界，不重复审查整核。角色矩阵、三入口范围、NL/冻结持久性和前端联调的全面出口仍由主任务各自证据汇总；本报告通过的是指定后端代码及其修复范围。ClickHouse/MongoDB/Redis/Cassandra/Neo4j 五类保持**待外部实例验收**，本轮不扩张能力或验收承诺。
