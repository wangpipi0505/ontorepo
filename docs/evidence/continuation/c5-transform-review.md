# C5 转换属性过滤独立审查

审查日期：2026-10-21。范围仅限本次转换属性过滤增量；必读依据为 `AGENTS.md`、`contracts/p3-data.md`、`onto-data/README.md` 与 `c5-transform-report.md`。使用 code-review 与 ponytail 技能。未运行 Maven，未改实现或原测试断言，未写共享 target，未启停工程基础服务，未访问相邻工程，未提交 Git。

**当前复审结论：五项P2均已关闭；本次增量Spec与Quality通过。首轮反例完整保留如下，复审证据见文末。五类外部真实实例验收仍不在此次通过范围内。**

## 首轮结论

**Spec：暂不通过。Quality：需修复后复审。** 独立探针确认五项 P2。未发现可证实的 SQL 注入或未授权坏值提前转换问题；已独立验证多码表、多过滤与 JOIN 的参数顺序、源原值返回和双端主键证据。

作者已有 36 项 onto-data 与 91 项 onto-contract 通过的结果，但现有新增用例未覆盖下述反例。该结果作为作者验证记录引用，不代替本审查的独立证据。五类外部实例仍待验收。

### P2-1：非 SQL 路径会在前一个业务谓词或 LIMIT 处隐藏授权转换错误

定位：`AdapterSupport.java:149`；`FileSourceAdapter.java:168`；`HttpSourceAdapter.java:217`；`S3ParquetSourceAdapter.java:254`；`RedisSourceAdapter.java:123`。

全部行标签均为 A，授权范围也是 A。两行为 `(id=1, raw="10", other="ok")`、`(id=2, raw="bad", other="no")`。过滤 `TO_NUMBER(raw) EQ 10`，LIMIT=1，CSV/JSON 文件路径只返回第一行，未验证第二行。HTTP 第一页第一行、第二页第二行，亦提前返回。真实 MinIO Parquet 同样返回第一行。三项均应为 TRANSFORM_FAILED。

将 LIMIT 改为500，按顺序增加 `IDENTITY(other) EQ "ok"` 与 `TO_NUMBER(raw) EQ 10`，三项仍只返回第一行：`matches` 在第一个业务谓词不匹配时立即 return false，未转换同一授权行的剩余过滤列。Redis共享同一方法并在LIMIT立即返回，静态路径存在同因风险；本轮没有真实Redis实例证明。

修复方向：全部标签通过后，先验证该授权行所有需要转换的过滤值，再作业务比较；达到结果LIMIT后只停止收集结果，仍须在既有扫描/分页/时间预算内验证余下授权数据。身份类旧计划可以保留原快路径，避免扩大非增量行为。

独立证据：`.runtime/c5-transform-review/probe-output.txt` 的两项 file；`bounded-probe-output.txt` 的四项 HTTP/S3；独立源码为 `TransformReviewProbe.java`、`TransformReviewBoundedProbe.java`。

### P2-2：PG numeric 文本不等于 JDBC BigDecimal.toString，导致 TO_STRING 漏匹配与 CODEBOOK 误失败

定位：`PostgreSqlTransforms.java:145` 与 `:150`；源类型白名单 `:114` 起。

真实PG字段 `numeric` 保存 `0.0000001`。JDBC `getObject` 返回的值 `toString()` 为 `1E-7`，`ValueTransform.TO_STRING.apply` 同样为 `1E-7`；以该字符串过滤的SQL却返回0行。冻结码表 `{ "1E-7": "small" }` 在Java命中，在SQL中则报 TRANSFORM_FAILED。

根因：允许 numeric 源，却对所有转换统一使用源 `::text`；PG输出 `0.0000001`，并非Java BigDecimal.toString所需文本。SQL不能静默以不同键匹配。应实现可证明等价的文本表达，或针对不能证明等价的源类型/转换组合明确拒绝并如实更新能力边界；保留 numeric→TO_NUMBER 等本来等价的组合。

独立证据：`probe-output.txt` 中 `pg_jdbc_numeric_toString=1E-7`、`java_to_string=1E-7`、`pg_to_string_java_key=ROWS:[]`、`java_codebook=small`、`pg_codebook_java_key=ERROR:TRANSFORM_FAILED`。

### P2-3：PG TO_NUMBER 的 ASCII 正则缩窄了既定 BigDecimal 文本语义

定位：`PostgreSqlTransforms.java:154`。

文本 `١٠`（U+0661 U+0660）可被Java `new BigDecimal(text)` 转为10，因此 `ValueTransform.TO_NUMBER.apply` 返回10。相同PG text经TO_NUMBER过滤EQ10却报TRANSFORM_FAILED。当前SQL正则只接受 `[0-9]`，未保持约定的BigDecimal文本转换语义。该偏差也使同一冻结映射在文件与PG上行为不同。

独立证据：`probe-output.txt` 中 `java_unicode_number=10` 与 `pg_unicode_number=ERROR:TRANSFORM_FAILED`。

### P2-4：IDENTITY携带rangeType会使ClickHouse原有过滤不可用

定位：`QueryPlanner.java:37` 至`:43`；集成调用为 `FrozenDataExecutor.propertyTransform`（全部本体属性传入rangeType）。

`Filter(id, EQ, [1], ValueTransform(IDENTITY, {}, INTEGER))` 仍是原值过滤，但QueryPlanner把非空rangeType判断为transformed并对ClickHouse直接报TRANSFORM_FILTER_UNSUPPORTED。普通本体INTEGER属性通过新映射执行器即会携带该rangeType，因此并非仅人工构造计划才遇到。契约与README明确保留这四类协议的IDENTITY过滤。

共享AdapterSupport连接前拒绝判断只看非IDENTITY，而QueryPlanner额外判断rangeType，策略不一致；Jdbc路径先readMetadata，因此该类失败还会在已经访问源后才触发。需明确IDENTITY+rangeType的类型校验策略，不能把类型约束误作转换能力拒绝。

独立无网络探针：`extra-probe-output.txt` 中 `identity_range_clickhouse=TRANSFORM_FILTER_UNSUPPORTED`。该探针仅调用QueryPlanner，未连接ClickHouse。

### P2-5：PG空IN业务条件会让整个转换校验阶段不被消费

定位：`PostgreSqlTransforms.java:45` 的空values分支及最终外层WHERE拼接。

真实PG有已授权A标签行，其中raw="bad"。过滤 `TO_NUMBER(raw) IN []` 返回空数组，并未报TRANSFORM_FAILED。非空IN/普通谓词时同一数据会正确报错。空IN分支产生常量FALSE，且跳过该过滤的validation表达式，优化器可直接不执行converted/validated。

既定语义要求全部授权行转换校验先于任何业务谓词；空IN是业务条件，不能绕过转换验证。需保证校验消费不依赖外层业务WHERE有候选输出行，且为所有转换过滤建立验证。

独立证据：`probe-output.txt` 中 `pg_empty_in_bad=ROWS:[]`，对照 `pg_bad_after_limit=ERROR:TRANSFORM_FAILED`。

## 已独立通过的关键正例

- PostgreSQL numeric转换正常过滤：首个匹配行之后的授权坏值被完整消费，明确TRANSFORM_FAILED，证明通常的validated聚合确实参与执行。
- PostgreSQL两码表、三业务过滤、内JOIN组合，码表键含 `x' OR 1=1 --`：SQL不包含该载荷字面量；返回一行源原值，两端各保留一条主键证据。该正例独立于作者JUnit代码。
- ClickHouse、MongoDB、Cassandra、Neo4j：缺任何地址配置的非IDENTITY计划，均先报TRANSFORM_FILTER_UNSUPPORTED。此证明连接前能力拒绝，不证明外部实例接入。
- 静态核对旧三参数Filter默认IDENTITY；码表冻结Map、标量检查、1000项/1MiB预算；列名进入SQL前经完整元数据集合与标识符检查，码表及过滤值均参数绑定；错误由安全DataProblem或统一SOURCE_OPERATION_FAILED返回，JDBC与Parquet读取资源通过try-with-resources关闭。
- 静态核对HTTP/文件/Parquet/Redis先检查全部守卫标签；未投影过滤列仍被读取；Parquet存在非IDENTITY时不下推业务原值谓词；Redis转换后的_key不进入精确原键优化。

## 独立执行与隔离

探针保存在本工程 `.runtime/c5-transform-review/`。JDK为工程既有Java21，依赖classpath从已构建 `onto-data/target/surefire-reports/TEST-cn.ontorepo.data.domain.TransformJdbcTest.xml` 的java.class.path读取，编译输出仅到独立目录，不运行Maven。

```powershell
$reviewCp = Get-Content -Raw .runtime/c5-transform-review/classpath.txt
$reviewJava = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe'
& $reviewJava '-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' -cp "$PWD/.runtime/c5-transform-review;$reviewCp" TransformReviewProbe "$PWD"
& $reviewJava '-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' -cp "$PWD/.runtime/c5-transform-review;$reviewCp" TransformReviewExtraProbe "$PWD"
& $reviewJava '-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' -cp "$PWD/.runtime/c5-transform-review;$reviewCp" TransformReviewBoundedProbe "$PWD"
```

编译以同一JDK的javac，参数为 `-encoding UTF-8 -cp $reviewCp -d .runtime/c5-transform-review <探针.java>`。固定使用已有onto_test_p3，凭据只在Java进程读取本工程local-env.json，不输出秘密。PG每次创建独立 `c5tr_<UUID>` schema，finally只清自身schema。MinIO仅创建 `c5tr/<UUID>/rows.parquet`，finally只删自身对象。HTTP为单次测试创建127.0.0.1随机端口，finally关闭，不改变工程API服务。没有以运行中基础版API18182推断转换完成。

首次额外探针因Windows Unix-domain临时目录问题失败；加工程既有ASCII tmpdir参数后成功，失败不计产品缺陷。现有原28项适配测试与安全scope核由主执行者独立验证，本轮未重复全域测试。

## 修复后复审

五项已通知主执行者与转换作者。等待源码修复及指定Maven持有者定向构建后，必须重新执行本审查保留的原探针；当前仍未关闭问题，不能将首轮作者全绿记录视为通过。


## 修复后独立复审结论（2026-10-21）

**Spec：通过本次转换过滤增量。Quality：通过本次增量。五项P2均已关闭。** 主执行者交付模块133项通过（contract91/data42）及定向15项通过的构建后，审查者原样执行保留的三个已编译独立探针，未改首轮探针源码或输出。复审没有运行Maven、修改共享target或生产源码。

| 问题 | 原反例复跑结果 | 结论 |
|---|---|---|
| P2-1 非SQL短路隐藏错误 | 文件两项、HTTP后页及前谓词两项、真实MinIO两项全部从返回id1变为TRANSFORM_FAILED | 关闭；Redis读取同一先完成全部转换的matches，转换查询禁用_key业务精确查优化并继续受限扫描；真实Redis仍待实例验收 |
| P2-2 numeric文本差异 | JDBC仍为1E-7；PG numeric→TO_STRING及CODEBOOK均改为TRANSFORM_SOURCE_TYPE_UNSUPPORTED | 关闭；明确能力拒绝，无静默错匹配。numeric→TO_NUMBER/TO_BOOLEAN保留，README逐组合说明限制 |
| P2-3 Unicode数字语义 | Java١٠→10；PG١٠过滤EQ10实际命中一行 | 关闭；JDK Character.digit(char,10)受控常量表覆盖BMP数字，与BigDecimal按UTF-16 char解析口径一致 |
| P2-4 IDENTITY/rangeType策略不一致 | 原ClickHouse规划探针改为INVALID_TRANSFORM；新增九类无地址配置查询全部先报INVALID_TRANSFORM；旧三参数IDENTITY仍rangeNull | 关闭；过滤层typed IDENTITY明确统一拒绝，映射层在生成过滤时归一化为identity()，输出层仍保留目标类型校验 |
| P2-5 PG空IN跳过验证 | 原pg_empty_in_bad由ROWS[]变为TRANSFORM_FAILED | 关闭；validated作为LEFT LATERAL必需外层，常量FALSE业务条件不能跳过验证 |

三个原探针的复审完整输出分别为：

- `.runtime/c5-transform-review/probe-recheck-output.txt`
- `.runtime/c5-transform-review/extra-probe-recheck-output.txt`
- `.runtime/c5-transform-review/bounded-probe-recheck-output.txt`

原PG“两码表+三个过滤+恶意码表键+JOIN”正例继续PASS，源原值和双主键证据仍正确。四类原生协议非IDENTITY无地址配置仍连接前TRANSFORM_FILTER_UNSUPPORTED。常规PG授权坏值位于LIMIT之后仍为TRANSFORM_FAILED。

### 追加独立边界探针

追加源码仅在独立.runtime目录：`TransformReviewIdentityProbe.java`、`TransformReviewSentinelProbe.java`。

`identity-probe-recheck-output.txt` 记录RELATIONAL/WAREHOUSE/LAKE/FILE/HTTP/DOCUMENT/KEY_VALUE/WIDE_COLUMN/GRAPH九类typed IDENTITY均在没有配置任何连接地址时INVALID_TRANSFORM。该探针还反射调用已构建FrozenDataExecutor.filterTransform，断言typed IDENTITY变为IDENTITY+rangeNull+空码表；非IDENTITY对象保持不变。旧三参数Filter及QueryPlanner.transformed亦保持原值计划。API完整classpath超过Windows命令长度，采用本地ASCII相对工程路径的 `identity-java.args` 参数文件；执行 `java '@.runtime/c5-transform-review/identity-java.args'`，未修改构建目录。

`sentinel-probe-recheck-output.txt` 记录新增LEFT LATERAL的三种无匹配结果均PASS：仅未授权B/bad；授权A/2但业务EQ10不匹配；授权A/2且业务IN[]。三项均断言rows与evidence同时为空，内部主键null哨兵没有进入业务输出或源证据。源码使用与原探针相同的独立PG schema创建/清理方式。

复审SQL与非SQL源码再次核对：全部标签授权后完成每行全部转换，转换查询达到LIMIT仍在原协议扫描/分页/时间预算内继续验证；Redis转换查询不使用任何业务_key原键优化。新限制与typed IDENTITY策略已在onto-data README明确写出。不存在把不支持的numeric文本转换假装完成、把预算未扫完当作完整验证的报告口径。

复审所有PG自建c5tr_uuid schema与MinIO自建随机c5tr对象均已在finally清理，临时HTTP随机端口已关闭。两个过程中的Java参数文件/Windows编码启动失败仅属于审查脚本调用问题，修正后已成功执行，不计产品缺陷。五类外部真实实例验收继续保留待办；本结论不代替主执行者全工程回归或最终P3出口验收。
