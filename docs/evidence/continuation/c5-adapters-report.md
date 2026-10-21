# C5 适配内核交付记录

日期：2026-10-20。修改范围仅 `onto-data/` 与本报告；未启动Docker、未访问相邻工程、未提交Git。固定接口和连接字段见 `onto-data/README.md`。本次是P3适配内核，业务管理、映射快照、执行事实落库由主执行者接入；不宣称P3整体出口关闭。

## 实际验证状态

| 类别 | 实现 | 实际验证与待验 |
|---|---|---|
| 关系库 | PostgreSQL官方JDBC、受限元数据、参数化SELECT/JOIN/WHERE/LIMIT、只读事务、主键证据 | 本工程真实PG（ONTO_DB_URL），随机 `c5_<uuid>` schema，测试后仅删除该schema；A/B/null/空串、LIMIT前范围过滤、数值类型、主键证据、部分白名单缺失失败已验证 |
| 数仓 | ClickHouse官方JDBC 0.9.8，独立system.tables/system.columns查询、MergeTree范围、物理分片/偏移证据 | 无本机ClickHouse，未进行真实连接/元数据/查询验收；不能用PG改kind代替 |
| 数据湖 | MinIO官方SDK 9.0.3 + okhttp-jvm5.3.2（与onto-artifact一致）、Apache Parquet 1.15.2、桶/前缀白名单、基础Schema、EQ/IN下推和列裁剪 | 本工程真实MinIO，上传具有PAR1头尾的真实Parquet，A/B/null、类型、隐藏列不输出、物理rowIndex与前缀越界验证；升级9.0.3后重跑通过，仅删除本次随机对象 |
| 文件 | Commons CSV、Jackson JSON、Apache POI .xlsx、真实路径约束、大小/行数上限、公式拒绝 | 真实临时CSV/JSON/Excel，范围过滤后截断、数值类型、行证据、公式拒绝、../和绝对路径拒绝、Windows目录junction真实路径逃逸、16MiB超大文件拒绝全部通过。进程无symlink创建特权，因此使用无需该特权的junction验证同一重解析边界 |
| HTTP | JDK HttpClient，GET/固定POST模板、精确端点、禁止重定向、分页与完整响应体截止时间/大小 | 真正本地HttpServer；第二页授权记录可达，未声明端点和跨站重定向拒绝；慢响应体100ms超时测试。此测试不代表外部HTTP平台验收 |
| 文档库 | Mongo官方同步驱动5.5.1，库/集合白名单、find受限过滤/投影/LIMIT、类型/空白守卫、样本Schema与索引 | 无本机MongoDB，协议代码编译和网络前守卫拒绝测试；真实权限/metadata/查询待外部实例 |
| 键值 | Jedis 6.0.0，JSON STRING、受限前缀SCAN/精确键、步数与记录上限、无JOIN | 无本机Redis，编译与网络前守卫拒绝；真实认证/SCAN/边界待外部实例 |
| 宽列 | Apache Cassandra官方Java driver4.18.1，关闭驱动全量Schema刷新，按白名单keyspace/table参数化读取system_schema.columns/indexes；完整分区键EQ、守卫分区键、禁止ALLOW FILTERING | 无本机Cassandra，编译与网络前守卫拒绝；真实认证/Schema/分区查询待外部实例 |
| 图库 | Neo4j官方driver5.28.5，标签白名单、参数化只读节点/单跳关系模板、两端守卫、元素ID证据 | 无本机Neo4j，编译与守卫拒绝，关系模板参数绑定与目标标签越界测试；真实连接、Schema、事务与元素证据待外部实例 |

五类缺环境状态遵照用户决定保留外部验收；九类代码存在不能算九类真实接入通过。

## 授权与失败边界

QueryPlanner仅接受结构化表/列/等值JOIN与EQ/IN/比较过滤；标识符严格，源表全部校验accessScope，JOIN两端列存在。标签值只绑定参数，空交集恒假，未分类默认拒绝；每个参与表必须有守卫绑定，同一属性跨表可使用 `attr@resource`。默认/最大500行、默认/最大10秒。执行入口在任何元数据/网络读取前拒绝空守卫和未解析范围属性。凭据不进入SourceSpec.toString、DataProblem或probe错误，原始源异常不返回调用者。

探针顺序为连通、认证、范围，失败后阶段NOT_RUN；范围阶段验证整个声明白名单，不能只因部分资源可读而通过。文件根目录是服务端批准目录，HTTP origin/模板与JDBC连接是服务端受控配置；业务层不得接受调用者覆盖连接或守卫绑定。

## TDD和命令

Java21：本机通用JDK，Maven/JVM均512MiB。主命令：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -f onto-data/pom.xml '-DargLine=-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp' test
```

RED→GREEN轨迹：

- `tdd-red.log`：固定API/计划器缺失；`tdd-green.log`：10条通过。
- `adapters-red.log`/`native-red.log`：适配器缺失的编译RED；`adapters-green.log`：15条通过（真实PG/文件/HTTP）。
- `parquet-red.log`：先补齐Parquet运行依赖后，真实Parquet证据rowIndex断言失败；`parquet-green.log`：16条通过。
- `scope-join-red.log`：JOIN单边守卫与部分白名单probe错误通过两个反例均失败；`scope-join-green.log`：17条通过。
- `http-timeout-red.log`：慢响应体未超时反例失败；`http-timeout-green.log`：18条通过。
- `preflight-red.log`：JDBC守卫拒绝发生在读取元数据后；`preflight-green.log`：网络前拒绝通过。
- `graph-red.log`：单跳关系计划方法缺失；实现后关系模板绑定/双端守卫测试已通过。
- `stable-compile.log`：22:21:20编译通过（20主类/7测试类，MinIO9.0.3）。补充验证不新增未实现API，P2 reactor编译窗口期间冻结src。
- `final-verify.log`：22:25:15最终全量 **23条，0失败、0错误、0跳过，BUILD SUCCESS**。本轮包括真实PG JOIN与逐表主键证据，以及MinIO9.0.3真实Parquet、CSV/JSON/Excel与公式拒绝、Windows junction目录逃逸和超大文件。
- `s3-boundary-red.log`/`s3-prefix-red.log`：补充收尾反例，空/../等非法前缀可被直接构造的SourceSpec带入query。运行期断言确实失败后，query与metadata统一拒绝非法前缀；stat/get按同一QuerySpec剩余预算约束，最终23条全部通过。

本机JDK在默认短名临时目录下触发Unix-domain selector初始化错误；测试显式使用ASCII临时socket目录，未修改系统设置。第三方ClickHouse/Hadoop依赖会产生日志配置/本地Hadoop库提示，不代表服务接入已通过。

原始测试日志在 `onto-data/*.log`（已由工程通用gitignore排除），JUnit结果在 `onto-data/target/surefire-reports`。源码与POM于22:25:15后冻结，留出P2完整reactor回归窗口；未改变其他模块。

## 官方依据

原生实现前核对了[Mongo同步驱动](https://www.mongodb.com/docs/drivers/java/sync/current/)、[Redis Jedis](https://redis.io/docs/latest/develop/clients/jedis/connect/)、[Cassandra官方Java驱动](https://apache.github.io/cassandra-java-driver/4.18.1/core/)、[Neo4j只读事务](https://neo4j.com/docs/java-manual/current/transactions/)、[ClickHouse官方JDBC](https://github.com/ClickHouse/clickhouse-docs/blob/main/docs/integrations/language-clients/java/jdbc/jdbc.mdx)、[ClickHouse MergeTree](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/mergetree)、[MinIO Java API](https://github.com/minio/minio-java/blob/master/docs/API.md)、[MinIO9.0.3发行记录](https://github.com/minio/minio-java/releases/tag/9.0.3)、[Apache Parquet实现](https://github.com/apache/parquet-java)、[Apache POI](https://poi.apache.org/components/spreadsheet/quick-guide.html)。未自行复刻任何数据库网络协议。

## 真实环境验收后续

ClickHouse需真实MergeTree建表并验证权限、元数据、JOIN、scope A/B/null、驱动timeout与分片证据；Mongo需含混合类型/空白标签的集合；Redis需足量SCAN页和非STRING键；Cassandra需完整/缺失分区键、非法非分区过滤、不同权限角色；Neo4j需带两端分类标签的节点与关系，验证目标标签越界、只读事务、数字精度与元素ID。各类均需独立三阶段probe和来源证据，不能将未运行阶段标为通过。

## 独立评审三个P1的修复（2026-10-20 22:34）

依据 `c5-adapters-review.md` 逐一复现，使用独立 `onto-data/.runtime/review` 编译目录和已有Surefire classpath，不写共享target、不并发运行Maven。`red.log` 中三个精确反例均实际失败：Excel错误返回7.0（应8.0）；HTTP网络路径未拒绝；真实PG不限范围返回27条（应2条）。

- Excel：POI表头迭代跳过缺失单元格，原实现丢失了列号。现保留字段名称→真实列索引，稀疏表头仍按C列读取region；A2=7/B2=A/C2=B被过滤，A3=8/B3=B/C3=A返回8.0且证据行号为3。数值类型与证据不变。
- HTTP：端点直接拒绝以 `//` 开头的网络路径；URI.resolve 后再次比较scheme、host和有效port并拒绝userinfo，凭据头只能附加到校验通过的同origin URI。网络前反例含Authorization配置，无实际凭据发送。
- JDBC：使用当前JDK Character.isWhitespace 的完整码点集合。PostgreSQL生成显式Unicode字符集BTRIM，ClickHouse生成同集合的replaceRegexpAll字符类；谓词仍在LIMIT前执行，用户标签值仍只绑定参数。真实PG测试覆盖所有Java空白码点、混合空白、null、空串及普通有效标签；NBSP按Java String.isBlank规则保留，返回值仍是Integer/字符串并保留源主键证据。

22:34:35独立4个探针全部通过（`.runtime/review/green.log`）：三项真实回归与ClickHouse生成谓词静态检查。ClickHouse检查确认使用明确空白集合且不复用PG函数，仅检查SQL生成和字符集合，不宣称已在ClickHouse执行。函数依据：[PostgreSQL btrim](https://www.postgresql.org/docs/current/functions-string.html)、[ClickHouse字符串替换函数](https://clickhouse.com/docs/reference/functions/regular-functions/string-replace-functions)。

P2既有reactor结束后，在协调的专属窗口运行上述模块全量命令：**22:36:37 BUILD SUCCESS，27条、0失败、0错误、0跳过**（`onto-data/review-final-verify.log`）。包含原23条与新增4条，真实PG、真实MinIO Parquet、HTTP、文件typed值/来源证据全部保持。三项修复文件已格式化，源码冻结交原评审者独立复核。仍仅修改onto-data和本报告，未提交Git；ClickHouse及另外四类外部实例的真实验收状态不变。
