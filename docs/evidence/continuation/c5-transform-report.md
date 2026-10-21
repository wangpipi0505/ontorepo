# C5 转换属性过滤报告

## 设计与边界（实现前）

2026-10-20：只读核对AGENTS.md、continuation-plan、onto-data README及P3契约。共享Maven回归期间不修改生产、不启动Maven；由主执行者明确交接工作窗口后进入TDD。

### 固定API

新增`cn.ontorepo.data.domain.ValueTransform(Kind kind, Map<String, JsonNode> codebook, String rangeType)`；`Kind`为`IDENTITY/TO_STRING/TO_NUMBER/TO_BOOLEAN/CODEBOOK`。提供`identity()`，`apply(Object)`返回转换后的真实Java标量。码表由调用方从冻结载荷解析，不读取草稿；键为`code.toString()`，值为显式`value`优先，否则`label`。构造时冻结Map并仅允许不可变JSON标量/null，限制1000项及1MiB编码预算；非CODEBOOK禁止携带码表。rangeType可空，非空仅允许STRING/INTEGER/DECIMAL/BOOLEAN/DATE，由映射执行层传入冻结本体属性的rangeType。

`QuerySpec.Filter`新增第四字段`ValueTransform transform`；保留旧三参数构造默认IDENTITY，null同默认。值与列仍分别走参数化/标识符校验，不允许raw SQL、函数名或表达式输入。

语义与MappingValues一致：null保留null；IDENTITY保留原类型；TO_STRING为toString；TO_NUMBER为BigDecimal(toString)，失败报TRANSFORM_FAILED；TO_BOOLEAN接受Boolean及不区分大小写true/false和精确1/0，其他失败；CODEBOOK按字符串code匹配，无命中失败。非空转换结果严格遵循rangeType；类型错误不默默丢弃。

### 执行顺序

- PostgreSQL：授权资源和全部标签守卫先限制源；转换白名单函数内联于受控SQL；业务filter在LIMIT前。转换计划拟采用authorized/converted MATERIALIZED CTE与稳定内部列别名，使未授权坏值不触发转换。所有码表code/value及业务过滤值参数化，SQL只来自受控模板及已验证标识符；真实SQL转换错误脱敏为TRANSFORM_FAILED。
- CSV/JSON/XLSX、HTTP：复用共享matches，先完整检查全部授权标签，再转换业务过滤，最终由原适配器LIMIT；保留字段类型与原行定位。
- MinIO Parquet：非IDENTITY过滤不得按原值下推；保留守卫下推与裁剪所需列，转换业务过滤在matches及LIMIT前；保留物理rowIndex证据。转换过滤支持比较算子，原IDENTITY受既有Parquet EQ/IN边界。
- Redis：原有受限JSON键扫描复用matches可支持转换，精确_key优化不得错误使用转换后的值查原键。
- ClickHouse、MongoDB、Cassandra、Neo4j：涉及非IDENTITY转换时在连接前明确TRANSFORM_FILTER_UNSUPPORTED，保留各协议受限查询能力；不静默按源原值执行。

### 失败测试计划

1. 旧三参数Filter兼容；结构化转换值不可变，容器/未知rangeType/超预算拒绝。
2. 文本2与10经TO_NUMBER数值比较；布尔1/true、码表code->数值/标签；null保持null、空串/无效数字/无命中明确失败；INTEGER/DATE等严格类型。
3. 真实PG独立随机schema：未授权坏值不转换、授权坏值明确失败、转换过滤位于LIMIT前、IN及比较、码表/列注入、主键证据不丢失；只清理由本测试创建的schema。
4. 本地CSV/JSON/XLSX与真实受控HTTP分页：授权先于转换、后页匹配不被LIMIT截断、输出仍为原值供映射层投影。
5. 真实MinIO随机前缀Parquet：字符串数字比较和码表转换不会错误下推原值，后行匹配与物理rowIndex准确；仅清理本测试对象。
6. 无外部服务协议在任何网络前明确拒绝；原28条适配内核测试完整保留回归。

## 实施与验证

2026-10-21（Asia/Shanghai）收到主执行者独占onto-data写入/Maven窗口后实施。只修改onto-data及本报告；未修改onto-mapping、前端、部署；未package运行中的API JAR，未提交git，未使用Docker或相邻工程。

### 实现结果

- ValueTransform与四参数Filter按设计落地，旧三参默认identity；原28条适配测试保留。
- PostgreSqlTransforms仅受控生成SQL，列名均经QueryPlanner与冻结元数据核验。码表和过滤值全部绑定参数；多个过滤+码表组合的参数顺序经真实PG证明。返回投影仍是源原值，JDBC继续提供真实主键证据；JOIN两端各一条证据。
- 实测发现MATERIALIZED仍会按需消费，单有两个物化CTE不足以保证LIMIT后面的授权坏值报错。最终增加validated物化聚合消费全部授权转换值；聚合必须参与最外层谓词，避免优化器删除。授权资源与标签在authorized阶段过滤，坏的B标签值不会触发转换；A标签的坏值、空数字、无匹配码表明确TRANSFORM_FAILED。SQLSTATE 22类错误仅在转换查询中映射该安全错误，不改变普通查询错误语义。
- AdapterSupport.matches先核对全部标签，再转换属性并比较；文件、HTTP、Redis保留原有授权/截止时间/扫描上限/证据与LIMIT路径。Redis的_key精确查询优化仅允许IDENTITY，转换值不会错误用于源键GET。
- S3涉及转换时不下推任何业务原值谓词，保留授权下推及列裁剪；转换后的GT/EQ/布尔匹配在最终LIMIT前完成，物理rowIndex仍准确。原IDENTITY仅EQ/IN的协议边界不变。
- ClickHouse、MongoDB、Cassandra、Neo4j在任何连接前明确拒绝非IDENTITY转换；无真实外部实例，不能称已连接。Redis共享转换路径已有代码，新增转换未做真实Redis验收。

### TDD命令与证据

全部命令先执行 `. ./deploy/Environment.ps1`、`$null = Import-OntoEnvironment`、`Set-OntoJava`、`Set-OntoBuildLimits`。仅进程内导入工程环境，不打印凭据。测试JVM与Maven堆限制512MiB；Windows Unix-domain临时目录为ASCII路径。

统一命令模板：

```powershell
mvn -B -pl onto-data -am '-Dtest=<测试类>' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test
```

| 时刻 | 范围 | 结果与证明 |
|---|---|---|
| 00:00:44 | TransformFiltersTest | RED，1失败0错误，旧Filter忽略转换后字符串数字与数值比较导致FILTER_TYPE_MISMATCH |
| 00:01:28 | TransformFiltersTest | GREEN，1通过；CSV 2/10数值GT与LIMIT前过滤，B/bad先被授权排除 |
| 00:02:33 | TransformJdbcTest, ValueTransformTest | RED，PG 1失败；旧SQL没有转换表达式；标量转换2通过 |
| 00:04:01 | PG转换 | RED，已匹配行之后的授权bad没有报错，揭示物化CTE惰性消费缺陷 |
| 00:04:45 | TransformJdbcTest | GREEN，聚合强制消费后授权bad与空串明确失败，null不匹配 |
| 00:05:33 | ParquetIntegrationTest, TransformProtocolBoundaryTest | RED，两失败；Parquet拒绝转换GT，受限原生协议先报连接配置错误而非能力拒绝 |
| 00:06:05 | 同上 | GREEN，两通过；真实MinIO数字/码表/布尔过滤与rowIndex、连接前能力拒绝 |
| 00:06:42 | TransformFiltersTest, ValueTransformTest | 6通过：JSON/XLSX布尔与码表、HTTP第二页匹配、真实错误/空/null/类型/冻结预算 |
| 00:07:20 | 全量-pl onto-data -am test | onto-contract 91、onto-data 36；0失败0错误0跳过 |
| 00:08:48 | TransformJdbcTest扩展 | 通过，多过滤参数顺序、JOIN对端守卫/主键证据、浮点源明确拒绝 |

最终全量回归与XML汇总见下方追加记录。Surefire可重跑证据在onto-data/target/surefire-reports；构建期间既有SLF4J/Log4j无provider及HttpSourceAdapter过时API提示仍存在，没有新增依赖以隐藏提示。

### 真实环境和清理

新PG测试仅连接专属onto_test_p3，优先ONTO_TEST_DB_URL，缺省本工程127.0.0.1:15472地址；凭据来自工程环境文件。每次仅创建/删除自身c5t_<UUID> schema，不清理public或共享表。旧PG回归沿用其既有独立随机schema。MinIO测试使用c5/<UUID>/rows.parquet，finally只删除自身对象。CSV/JSON/XLSX由JUnit临时目录管理；HTTP绑定127.0.0.1临时端口，finally关闭。

### 明确能力限制与后续验收

- PostgreSQL转换SQL要求12+（MATERIALIZED）；本轮测试断言实际服务主版本满足要求，并输出安全主版本证据。
- 为避免PG文本与JDBC Java.toString不等价，转换源类型仅text/varchar、整数、numeric/decimal、boolean、date。固定char、浮点、timestamp及复杂类型明确TRANSFORM_SOURCE_TYPE_UNSUPPORTED；不悄悄强转或错误套码表。PG DATE过滤限定ISO四位年份及数据库可表达范围；普通数值受PostgreSQL numeric实际精度范围约束。
- PG物化校验会读取并转换全部授权范围，再执行业务LIMIT，维持10秒截止与只读事务；大数据成本高于索引可直接使用的IDENTITY旧模板。需要性能优化时必须保留同样失败与授权语义，不能以静默NULL替代。
- ClickHouse/MongoDB/Redis/Cassandra/Neo4j五类仍待外部真实实例验收；其中四类转换明确拒绝，Redis转换共享扫描实现未获真实实例验证。
- 此报告证明适配内核；冻结映射到该结构化API的集成、API重启回归和最终独立review由主执行者统一协调，不能将内核测试当成完整P3出口。

### 最终回归（2026-10-21 00:09:58 +08:00）

命令：`mvn -B -pl onto-data -am '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test`。

结果：BUILD SUCCESS；onto-contract 91、onto-data 36，共127项，0失败、0错误、0跳过。最终Surefire XML汇总与命令输出一致。真实PG主版本16（TRANSFORM_POSTGRES_MAJOR=16）；新增独立schema已在finally清理，MinIO测试对象已清理。该轮包含最终多过滤/JOIN/空授权交集/无命中码表/明确类型拒绝扩展。

已完成代码格式化（本工程google-java-format），测试目录保持原28项及新增8项，旧Parquet验收用例额外覆盖数字、布尔、码表三个转换。独占Maven窗口可交回主执行者；后续冻结映射API集成回归和独立审查尚需统一执行。

## 独立复审修复（2026-10-21，后续记录优先于上文初版结果）

独立审查代理提供三个真实探针：`.runtime/c5-transform-review/TransformReviewProbe.java`、`TransformReviewExtraProbe.java`、`TransformReviewBoundedProbe.java`。我在获源码窗口后、修改前用既有JDK21 classpath直接重跑前两探针（未占用Maven），亲见RED：FILE的LIMIT后bad及前置业务过滤隐藏bad；PG空IN绕过转换；numeric 0.0000001的JDBC BigDecimal.toString=1E-7而SQL定点文本不同；阿拉伯数字١٠在Java成功而PG失败；typed IDENTITY在ClickHouse被误当转换。审查者另以真实HTTP/MinIO复现相同坏值顺序问题。这些结果说明初版127通过不足以覆盖转换全量验证，已补真实反例自动化测试并修正。

### 修复与固定契约

1. FILE/HTTP/MinIO/Redis转换查询扫描协议预算内全部授权行，LIMIT只限制输出；全部Filter转换先完成，再做业务比较，不能被前一个业务过滤短路隐藏。HTTP读取到声明分页耗尽，若预算触顶仍未完成校验则明确失败。每行/每键继续核对deadline。原IDENTITY查询保留既有LIMIT路径；Redis涉及任一转换时也禁用原键精确查询优化，避免遗漏其他授权键坏值。
2. PG最终由validated物化聚合充当LEFT LATERAL必需外层，聚合计数进入内部ResultSet，业务匹配在lateral内过滤/LIMIT；无匹配时产生的一条主键null校验哨兵由JDBC剔除。空IN/恒假过滤无法让验证计数被优化掉；聚合必须先完整消费全部授权转换。真实主键非null，原投影/证据和正常空结果不改变。
3. PG numeric/decimal的TO_STRING/CODEBOOK明确TRANSFORM_SOURCE_TYPE_UNSUPPORTED，避免BigDecimal科学计数与PG ::text定点文本差异导致错配。numeric的TO_NUMBER/TO_BOOLEAN保留；这属于明确能力限制，不能称numeric文本转换已经支持。
4. PG TO_NUMBER按JDK Character.digit(char,10)常量表将BMP十进制数字规范化到ASCII，再执行严格符号/小数/指数语法。与BigDecimal UTF-16单char解析一致，不泛称任意Unicode数字；补充平面或任意Unicode数值字符未放宽。Java端BigDecimal定义不变。
5. 与映射作者固定：Filter的IDENTITY必须rangeType=null，保持原值谓词；输出仍由冻结PropertyFacets严格检查。非IDENTITY Filter才传冻结目标rangeType。内核收到IDENTITY+非空rangeType，在九类query入口及独立QueryPlanner入口均于任何连接前INVALID_TRANSFORM，不能不同协议静默忽略。typed IDENTITY ValueTransform本身仍可用于输出校验。

### 新增反例与定向验证

- TransformValidationOrderTest三项：真实CSV/JSON/XLSX、HTTP第二页、MinIO Parquet；每种同时验证LIMIT后坏值和前置业务谓词排除坏行，均要求TRANSFORM_FAILED。
- TransformJdbcTest新增一项：真实PG Arabic BMP ١٠与Java转换均为10；numeric文本/码表明确拒绝；bad+空IN明确失败、合法值+空IN为空且不输出内部哨兵。
- TransformProtocolBoundaryTest新增两项：九类typed IDENTITY预连接统一拒绝，以及ClickHouse普通三参IDENTITY仍产生旧SQL模板。
- 00:19:59定向命令：`mvn -B -pl onto-data -am '-Dtest=TransformValidationOrderTest,TransformJdbcTest,TransformProtocolBoundaryTest,TransformFiltersTest,ValueTransformTest,ParquetIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test`，BUILD SUCCESS，15项0失败0错误0跳过，真实PG16/MinIO/HTTP。

源码与测试已格式化；模块回归结果及工作窗口交接随后记录。Maven期间仅本工程独占串行运行，无package/部署/凭据输出。

### 复审修复后模块回归（2026-10-21 00:20:34 +08:00）

`mvn -B -pl onto-data -am '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test`：BUILD SUCCESS；onto-contract 91、onto-data 42，共133项，0失败、0错误、0跳过，XML属性汇总一致。原28项适配测试继续保留。该轮包含上述全部复审反例及旧PG/MinIO/HTTP/文件回归。工作窗口交还主执行者，由p3_backend继续冻结映射集成全量；独立审查探针复验另由reviewer记录。
