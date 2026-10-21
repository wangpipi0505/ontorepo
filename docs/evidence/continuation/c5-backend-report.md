# C5 / P3 后端实施与验证报告

状态：基础管理、冻结执行、三来源抽取与转换过滤已落地；2026-10-21 00:39:37 根工程 verify **259 项通过**并打包，保留原 P1/P2。最后ZIP非空目录载荷预算补丁已通过完整抽取8项定向并重新打包。后端实现与验证已交付；新版公开HTTP/浏览器复验和最终独立审查由主任务收口，尚不替主任务标记P3出口完成。

## 范围与边界

- 以 `contracts/p3-data.md` 和详设 §7.2/§7.5/§8.5/§8.7/§8.9/§8.10 为准，复用 `onto-data` 的九类协议内核、AES-256-GCM 和 `LabelScope`。
- 本次新增 `onto-mapping`、`onto-ingest`，业务定义、快照、候选决策与执行证据全部由真实 PostgreSQL 持久化。空间写入沿用同事务内权限、行锁、`expectedDraftRev`、修订递增和审计。
- 五类外部实例 ClickHouse / MongoDB / Redis / Cassandra / Neo4j 没有本地实例，**待外部实例验收**。代码能力与实例验证分别返回，不启动 Docker、不伪造连接成功。
- 没有修改 `onto-data` / `onto-artifact` / `onto-release` / `web` / `deploy` / 正式设计 / 固定契约；转换下推增量由主任务另行协调。没有 Git 提交。

## 文件清单

| 类别 | 文件 |
|---|---|
| 模块接入 | 根 `pom.xml`；`onto-mapping/pom.xml`；`onto-ingest/pom.xml`；`onto-trace/pom.xml`；`onto-designer-api/pom.xml` |
| 映射领域 | `onto-mapping/src/main/java/cn/ontorepo/mapping/domain/MappingValues.java`、`MappingDependencies.java` |
| 源与文件管理 | 同模块 `application/DatasourceService.java`、`ManagedFileService.java` |
| 三层映射与查询 | 同模块 `application/MappingService.java`、`FrozenDataExecutor.java` |
| 三来源抽取 | `onto-ingest/src/main/java/cn/ontorepo/ingest/domain/ExtractionEngine.java`；`application/SuggestionService.java` |
| HTTP / DTO | `onto-designer-api/src/main/java/cn/ontorepo/api/P3Dtos.java`、`DatasourceController.java`、`MappingController.java`、`IngestController.java`；现有 `ApiDtos.java` 追加执行证据 |
| 冻结与统一入口 | `onto-trace/.../application/CandidateService.java`、`FrozenExecutor.java`、`SupplyService.java` |
| 守卫与引用 | `onto-contract/.../GuardValidator.java`、`onto-space/.../GuardService.java`、`onto-model/.../PropertyPolicy.java`、`CodebookService.java` |
| 追加迁移与上传预算 | `onto-designer-api/src/main/resources/db/migration/V9__p3_mapping_ingest.sql`；`application.properties` |
| 定向验证 | `onto-contract/.../P3GuardTest.java`；API 测试 `P3DataTest.java`、`P3Fixture.java`、`P3MappingTest.java`、`P3IngestTest.java`、`P3ProtocolTest.java`、`P3TransformationTest.java` |

Java 已按工程四空格风格格式化，主要新增代码使用显式类型导入；中文注释说明授权、冻结、事务和解析预算边界。PDF 使用官方 Apache PDFBox 3.0.8（[官方发布页](https://pdfbox.apache.org/download.html)），Office 使用既有 Apache POI；没有用纯文本伪造二进制文档支持。父级固定 Commons IO 2.20.0，避免 Jena/Hadoop 的旧传递版本破坏 CSV/POI 的真实解析 API；在新模块依赖入口排除 Hadoop 的竞争日志绑定，保留 Spring Boot Logback。

## 固定 API 对齐

1. 数据源 CRUD 返回空间修订，目录仅返回 `id/spaceId/code/name/kind/dialect/accessScope/status/lastTestAt`。连接仅入参存在，使用随机 nonce 的 AES-GCM 密文存储，源 ID 作为 AAD。`connection=null` 保留原配置；缺主密钥拒绝能力，不明文回退。
2. probe 返回 `stages[{stage,status,code,message}],testedAt`，后段失败不会被填成通过。sync 追加不可变 `meta_snapshot`，返回 `space/snapshot/diff`；diff 区分资源、字段、主键、关系、索引，给出映射及相关操作 ID。
3. 文件目录和上传返回安全文件描述；文件名只作展示，实际资源为服务端 UUID 扩展名。FILE 只接受 `fileId`，服务器解析目录与摘要，客户端 `root/path` 被拒绝。最大 8 MiB；ZIP 最多 256 条目（目录也计数）、总解压 32 MiB；目录首个实际字节非空即拒绝，不依赖 ZIP 声明 size，也不让 getNextEntry 隐式解压绕过预算；拒绝目录逃逸、外部实体、外部关系和宏/嵌入二进制。
4. 三层映射只保存结构化定义。对象模板只解释 `${field}` 和固定分隔符；属性转换只有五项白名单；关系双端同空间、同源、同快照且字段真实存在。原始 SQL / Cypher / CQL 不属于映射 DSL，未知顶层管理字段明确拒绝。
5. 完整性返回四项 `CLASS_OBJECT/REQUIRED_PROPERTY/RELATION_KEYS/REACHABLE_REFERENCE`，issues 带实体定位；覆盖度计入活动类的全部属性，包含尚未映射的可选属性，`coverageBasis` 明示分母。必填属性逐个活动对象映射核对；同类其他对象已有映射不能代替当前对象。影响分析、阻删、目录和元数据 diff 共用依赖遍历，包含关系双端、投影及仅用于过滤的属性。
6. `data-query` 独立维护，null 回到 MODEL；RELATION 模式必须选择 from 对象，两个端点同本体属性 ID 不唯一时拒绝。投影键为本体属性 ID，另提供 `objectId/classId`；关系增加 `relationId/fromObjectId/toObjectId`。转换后值保留 JSON 数值/布尔/null 类型，并按冻结本体类型和输出 Schema 复核。关系基数检查在同一授权与业务谓词下最多读取 500 行完整性集合，先检查再按业务 maxRows 截断；达到预算返回 CARDINALITY_CHECK_INCOMPLETE，不能把未穷尽集合宣称通过。不能由单列原生标量真实 PG 主键证明唯一的对象模板同样使用500行预算；命中预算返回 OBJECT_KEY_CHECK_INCOMPLETE，复合拼接不因“包含主键”被错误视为无碰撞。
7. 候选追加冻结数据源密文与 accessScope、所有元数据快照、三层映射和其摘要，以及操作 dataQuery。旧候选 bytea 与摘要不重写；运行不回查当前映射或连接草稿。FILE 执行额外核对冻结摘要，拒绝磁盘上被篡改的字节。
8. 逐必需守卫检查每个 JOIN 参与资源的显式绑定。`table` 为协议原资源标识，结构层非空、≤512、无控制字符，字段单列规则保留；执行层与冻结元数据精确匹配，并应用协议 accessScope。SQL 多资源使用 `attr@resource`，非 SQL 单资源使用 `attr -> column`。纯模型路径继续拒绝两类源列绑定。
9. TRIAL / LOCAL_TEST / SERVICE 共用候选加载、授权、输入 Schema、冻结执行、输出 Schema 和事实持久化。有效标签范围来自当前权威空间授权与冻结策略，预留受信 `endpointScope` 参数；任何缺少必需绑定的源查询在解密、metadata 或 query 之前拒绝。成功执行追加 `SOURCE_RECORD/META_SNAPSHOT/MAPPING_SNAPSHOT/ONTOLOGY_VERSION`，授权上下文摘要另以执行证据归档。
10. 抽取仅产生 PENDING 候选；确认/拒绝同一个空间修订事务，候选类、属性和关系映射到稳定实体 UUID；允许修正到已有同空间实体。未接受依赖、异批/跨空间引用、重复决策和同名冲突都有定位错误，整批回滚。

## RED → GREEN 证据

| 证据 | 实际失败与修复 |
|---|---|
| `c5-first-red.log` | 两条新守卫测试真实失败：协议资源/数组绑定不被支持，以及重复/互斥声明未被拒绝。随后两条 GREEN，原 89 条契约回归保留。 |
| `c5-api-red.log` | 源目录接口真实 404，新增管理边界后安全目录、权限、密文存储与三段失败探针通过。 |
| `c5-ingest-red.log` | 真实 CSV/Office 解析暴露旧 Commons IO 冲突；父级依赖收敛后六种真实格式及确认流程通过，见 `c5-ingest-test.log`。 |
| `c5-identity-red.log` | 不同源主键构造同一对象标识，首版误报 SUCCEEDED；改为比较真实 SOURCE_RECORD 主键，返回 OBJECT_KEY_DUPLICATE。 |
| `c5-integrity-red.log` / `c5-integrity-green.log` | 三条真实 RED：无变化同步仍影响关系、已映射属性被删除、原始查询字段静默忽略。修复后全部 GREEN，并验证可选属性仍计为 pending。 |
| `c5-file-integrity-red.log` | CSV 文件冻后被直接改写，首版仍执行成功；冻结文件 SHA 校验修复，最终结果见统一回归。 |
| `c5-legacy-guard-red.log` | 原回归发现错误 reason 从 NO_FILTER_BINDING 漂移；恢复旧错误语义，未删改原测试。 |
| 独立 `c5-review-probe.json` / `c5-cardinality-probe.json` / `c5-ingest-review-probe.json` | 真实公开 HTTP 发现属性映射更新排除自身、同定义重复创建、逐对象必填、LIMIT 隐藏基数、关系目标属性依赖、ZIP 目录计数六类反例；保留原证据不改断言，新增对应回归并修复。2026-10-21 00:24:00 定向 29 项（API27 + 纯核2）全部通过，见 c5-review-fixes-green.log。 |
| `c5-review-fixes-red.log` / `c5-review-fixes-green.log` | 2026-10-21 00:16:32，P3 定向 API 27 项中 26 通过，新增冻结转换场景在执行前发现码表仅被映射引用时可删除（预期 409、实际 200）。已补 CodebookService 引用阻删，c5-review-fixes-green.log 已 GREEN；同时真实 SQL 数值/布尔/码表转换、冻结隔离、坏值失败全部通过。 |
| `c5-date-red.log` / `c5-date-green.log` | 真实 PG DATE 的 IDENTITY 投影原误报 TRANSFORM_FAILED；只在 JSON 输出边界把原生日期编码为 ISO 日期，严格输入/转换规则保持，2026-10-21 00:25:47 单条 GREEN。 |
| `c5-key-limit-red.log` / `c5-key-limit-green.log` | 非 PK priority 模板的两个真实源主键构造相同身份，maxRows=1 原误报 SUCCEEDED；补500行完整性预算，实际重复与未穷尽预算明确拒绝。单列原生标量真实 PG PK 的单变量模板可证明唯一，仍沿用业务 LIMIT。2026-10-21 00:29:24 三阶段断言 GREEN，包含500多行真实PK仍返回1行及只保留实际返回源证据。 |
| `c5-zip-directory-red.log` / 独立 zip-directory-probe-output.txt | 独立反射探针与真实上传控制器集成（MockMvc）均发现合法DOCX可附带33MiB目录载荷（仅约36KiB压缩包）而被接受。最小修复读取目录首字节确认空目录，非空立即拒绝，空目录继续计条目；2026-10-21 00:43:23 完整抽取8项GREEN（c5-zip-directory-green.log），涵盖33MiB/1字节目录拒绝与空目录允许。 |

测试使用工程配置导入后覆盖专属 `onto_test_p3`，不使用 P2 测试库，不改人工 `onto_sources`。JDK21，测试 JVM `-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`。交付日志中的请求令牌及连接敏感值已脱敏。

2026-10-20 23:59:01，根工程 `mvn -B '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test` 退出 0：契约 91（原 89 + 新 2）、适配器 28、API/业务 116，总 **235**，全部 0 failures / 0 errors / 0 skipped。P3 API/业务 19 项加纯核守卫 2 项；原 P1/P2 原样保留。`c5-regression.log` 是统一运行日志；WorkflowTest 中模拟审计失败的错误日志属于既有预期回滚用例，测试最终通过。

2026-10-20 23:59:38，主任务先停止本工程 API/Vite 并释放 JAR 后，`mvn -B -DskipTests package` 退出 0（见 `c5-base-package.log`）。基础版 JAR SHA-256：`FE51FF585FE578B0B0E23CA417ACF064670D0B32737D459A817F09727188523F`。打包不替代前述真实回归；这份制品不作为转换过滤已完成的证明。

2026-10-21 00:39:37，根工程 `mvn -B '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' verify` 退出0：契约91、适配器42、API/业务126，共 **259**，0 failures / errors / skipped，含 P3 API29项。日志 `c5-final-verify.log`、逐类XML汇总 `c5-final-verify-summary.json`。未删除原断言或改变旧1秒预算；历史RED/冷启动问题不以本次通过覆盖。此版本在非空ZIP目录补丁之前，JAR大小188087918字节，SHA-256 `EFE336F372674F18BBE79839891D17059849404E421B2BDF89BC028D12CD4C73`；后续只做该单点完整抽取回归与重打包，并单列最终制品摘要。

2026-10-21 00:43:23，最后ZIP补丁完整 `P3IngestTest` **8项**通过；00:43:54 根工程 `mvn -B -DskipTests package`退出0，见 `c5-delivery-package.log`。最终交付JAR为 `onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`，188087936字节，SHA-256 **`E2D5852EAD14145E767BD50D0F9A9E55DB8D317C76DB6F19ED4C8C31B5DE30C8`**。`c5-delivery-summary.json`将259项统一验证版本与ZIP补丁后8项定向验证分别记录；没有宣称整套260项已重新执行。API/Vite由主任务管理，PG/MinIO/19180 HTTP样例保持运行；本任务Maven窗口已交回。

## 已核验真实场景

- **任务使用算法**：测试库 `p3_source.tasks` 与 `p3_source.algorithms` 各有真实主键和外键。对象模板 `task:${task_id}` / `algorithm:${std_code}`，JOIN 为 `tasks.algo_code = algorithms.std_code`；投影包含算法端标准码。A 区主体只得到 `T-A -> ALG-A`；`T-B`、`T-NULL`、`T-CROSS -> ALG-B` 均不泄露。
- **A / B / null / 交集**：A 主体两端都下推 A；切换 B 主体只返回 `T-B`；范围 null 仍拒绝未分类行。范围不限时，A→B 两端各自都有合法标签的关联可见，这不等于强制两端标签值相等。缺目标表绑定、即使源表已被改名不可用，仍先报 `GUARD_ATTR_UNRESOLVED(RESOURCE_BINDING_MISSING)` 并持久化 FAILED 事实。
- **类型、身份和证据**：整数优先级保持 JSON 整数，启用标志保持布尔；DATE 原生投影在 JSON 边界输出 ISO 日期，不放宽任意字符串或时间戳。关系身份与对象模板一致；非 PK 模板的重复对象身份不因业务 LIMIT 隐藏。SOURCE_RECORD 保存 `task_id` 和 `std_code` 真实主键；其他三类证据分别关联实际元数据摘要、三层映射摘要和候选原始摘要。
- **冻结隔离**：冻结后把当前源改为不可达端口并替换对象模板，旧候选仍使用原加密连接和身份模板成功执行；冻结 bytea 完全相同。
- **元数据与阻删**：真实 ALTER TABLE 添加字段后 diff 精确出现字段路径并定位受影响对象/属性/关系；原快照内容保持不变；引用中的源删除返回 409 与可定位映射 ID。
- **协议贯通**：API 登记 → probe/sync → 映射 → 冻结 → 试执行，真实覆盖受管 CSV/JSON/XLSX、19180 受控 HTTP POST 分页和真实 MinIO Parquet。前两行 B/null、后一行 A，`maxRows=1` 仍只返回 T-A，证据分别为文件行、HTTP 页/序号或 Parquet 物理 rowIndex。
- **三来源**：TABLE 从真实快照取得外键并生成 MANY_TO_ONE 候选关系；FILE 实际解析 CSV/嵌套 JSON/Excel 工作表；DOCUMENT 实际解析 TXT、PDF 页与 Word 段落。每项有真实 sourceName 与定位，抽取前后模型数相同。人工接受依赖批次后才写入模型；拒绝保留审计；重复决策、缺依赖、同名冲突、跨空间与陈旧修订被拒绝。

## 尚待收口

1. 根工程259项统一verify已通过；最后ZIP非空目录载荷预算补丁完整抽取8项定向GREEN并已重打包。最终制品与统一verify版本分别记录，不把单点后续验证写成整套重新运行。
2. **转换属性过滤已接入并通过整链验证**：冻结属性映射与码表编译为 `ValueTransform`，输出与过滤共用同一转换。IDENTITY过滤保持原值谓词兼容，目标类型在投影输出检查；实际转换携带冻结本体类型。内核独立五项复核关闭，SQL数值/布尔/码表、冻结码表改稿隔离与非法值失败均GREEN。
3. 主任务基础版公开HTTP19项已通过，见 c5-integration-report.md；新制品的公开HTTP/浏览器复核及R05最终独立复审待主任务执行。
4. 五类外部实例保持待验；文件/文档解析为受限规则方案，无OCR、无公式/宏执行，不支持的内容明确拒绝。非主键模板和关系基数达到500行完整性预算时明确失败，需要缩小业务过滤范围。
