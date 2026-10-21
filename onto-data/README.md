# 数据适配内核 API

本模块独立构建：Java 21，`mvn -B -f onto-data/pom.xml -DargLine=-Xmx512m test`。业务层负责认证、空间权限、快照完整性、连接密文解密，适配器仅接收校验后的服务端对象。不要把请求直接反序列化成已授权的 QuerySpec。

`domain.SourceSpec(id,kind,connection,accessScope)`：connection 为解密后的 JsonNode，不记录日志；toString 隐藏连接。kind 与 dialect 必须明确匹配。`domain.AdapterRegistry.adapter(source)` 返回 `domain.SourceAdapter`。adapter 的 `probe` 返回 CONNECTIVITY/AUTHENTICATION/SCOPE 三阶段，失败后阶段 NOT_RUN；每段是 `ProbeResult.Stage(name,status,code)`，由管理层转换HTTP契约字段。`metadata` 返回 SourceMetadata.resources；每项含 name、fields(name,type,nullable)、primaryKey、relations、indexes。无可获得关系/索引时为空，不能当作完整性肯定结论。

`domain.QuerySpec(resource,projection,filters,joins,limit,timeoutMs,options,scope,guardBindings)` 是唯一查询入口。projection 为列列表；Filter(column,Operator,values) 支持 EQ/IN/GT/GE/LT/LE；新增四参数形式 `Filter(column,operator,values,ValueTransform)`，旧三参数保持IDENTITY语义。Join(resource,leftColumn,rightColumn) 为内等值连接。limit 默认/最大500，timeoutMs 默认/最大10000。JDBC 列采用完整 `schema.table.column`；其余协议为资源内字段名。所有路径/表/集合/标签必须在 accessScope 内。options 未声明时一律拒绝。scope 是 LabelScope.intersect 的最终结果；guardBindings 为模型快照中的必需属性→源字段映射，必须非空且覆盖全部 scope 属性。未分类值(null/空串)默认拒绝。授权范围过滤在 LIMIT 前完成，空交集恒假。

`ValueTransform(Kind kind, Map<String,JsonNode> codebook, String rangeType)` 仅由业务层从冻结映射和冻结码表构造，kind为IDENTITY/TO_STRING/TO_NUMBER/TO_BOOLEAN/CODEBOOK。codebook为冻结code字符串→JSON标量/null，显式value优先于label；限制1000项和1MiB UTF-8预算，非CODEBOOK必须为空。rangeType可空，非空为冻结本体STRING/INTEGER/DECIMAL/BOOLEAN/DATE，按严格标量类型验证。null保持null；数字严格使用BigDecimal文本语义；布尔只接受Boolean、大小写不敏感true/false或精确1/0；码表无命中/空字符串数字/类型不符均明确TRANSFORM_FAILED，不将失败变成null或无匹配。

文件/HTTP/Redis及Parquet在全部标签授权后先完成每行全部属性转换，再作业务比较。转换查询必须扫描协议预算内全部授权行，LIMIT只限制输出条数；不能因为前一业务谓词不匹配或已达到LIMIT而隐藏后续转换坏值。HTTP继续读取声明分页直到耗尽；触及页数/行数/时间预算仍未验证完成时明确拒绝。返回值仍为源原值，供映射层独立生成本体属性。Parquet涉及转换时只保留授权谓词下推，业务谓词全部在授权后的行上执行，不把转换目标值错误下推到源列。PostgreSQL 12+转换采用受控白名单函数与参数化码表，通过authorized/converted/validated物化阶段保持授权、转换校验、业务比较、LIMIT顺序；validated作为LEFT LATERAL的必需外层并读取校验计数，防止空IN/恒假条件或LIMIT跳过授权范围内的转换验证。无匹配内部校验行不对外输出，源原值及主键证据仍按原API返回。旧三参数IDENTITY SQL不改变。

过滤的IDENTITY必须使用rangeType=null（推荐identity()）；其含义仅为原值谓词，实际本体输出由映射层PropertyFacets校验。IDENTITY携带非空rangeType的Filter组合九类协议统一在任何连接前返回INVALID_TRANSFORM，不允许某些协议静默忽略。ValueTransform本身仍允许构造typed IDENTITY，用于业务层输出校验，不可混同为过滤支持。

PostgreSQL转换源类型支持text/varchar、smallint/integer/bigint、numeric/decimal、boolean、date，但numeric/decimal作为TO_STRING或CODEBOOK的源时明确TRANSFORM_SOURCE_TYPE_UNSUPPORTED：JDBC BigDecimal.toString可产生1E-7，PG ::text会产生0.0000001，不能假设文本等价。numeric/decimal仍支持TO_NUMBER/TO_BOOLEAN的严格语义。固定宽度char、浮点、时间戳及其他复杂类型的PG文本与JDBC Java.toString不能证明等价，同样明确拒绝。PG TO_NUMBER将JDK Character.digit(char,10)认可的BMP十进制数字按受控常量表转换成ASCII，再执行与BigDecimal对应的符号/小数/指数语法；例如阿拉伯数字١٠与全角１０等价为10。此规则以UTF-16单char为单位，与BigDecimal字符解析一致；不声称支持补充平面数字或任意Unicode数值字符。PG DATE转换过滤限定ISO四位年份日期及数据库可表达范围。ClickHouse/MongoDB/Cassandra/Neo4j暂在任何连接前拒绝非IDENTITY过滤（TRANSFORM_FILTER_UNSUPPORTED）；保留各协议已有受限原值查询边界。

`QueryResult(columns,rows,evidence)` 保留值的 Java 类型（数值/布尔/null/日期等）；rows 每项键为投影列，evidence 中 SourceRecord(sourceId,resource,key) 为源记录定位。JDBC 自动额外读取主键且不暴露为业务列；没有主键拒绝查询。文件采用行号定位，HTTP 采用页号/页内序号定位。上层继续补充映射快照/元数据快照/候选或版本摘要。

JOIN 每个参与表必须有分类守卫。同一属性需要绑定多个表时，使用 `region@public.algorithms -> public.algorithms.region`，其中 `region` 仍引用有效范围的同一属性；主表可用 `region -> public.tasks.region`。证据顺序按每行、再按主表/连接表；不同表各有一条 SourceRecord。

## 连接字段

| kind | dialect | connection 与 accessScope |
|---|---|---|
| RELATIONAL | postgresql | url/user/password；scope=`schema.table` |
| WAREHOUSE | clickhouse | url/user/password；scope=`database.table` |
| LAKE | s3-parquet | endpoint/accessKey/secretKey/bucket；scope=对象前缀；resource=对象键 |
| FILE | csv/json/excel | root（批准目录）；scope=相对文件路径；resource=相对路径 |
| HTTP | json | baseUrl/headers/endpoints；可选method=GET/POST、pageParam/pageSize/maxPages；scope=端点路径 |
| DOCUMENT | mongodb | uri/database；scope=`database.collection` |
| KEY_VALUE | redis | host/port/password/database；scope=键前缀 |
| WIDE_COLUMN | cassandra | contactPoints/port/datacenter/keyspace/user/password；scope=`keyspace.table` |
| GRAPH | neo4j | uri/user/password/database；scope=节点标签 |

连接对象只允许服务端受控配置。HTTP baseUrl 和文件 root 不应由执行请求覆盖；JDBC URL 不得启用允许本机文件读取的驱动选项。配置、原始 URI、源异常响应不输出给客户端。

## 调用示例

```java
SourceAdapter adapter = new AdapterRegistry().adapter(source);
SourceMetadata metadata = adapter.metadata(source);
LabelScope effective = LabelScope.intersect(policy, endpoint, space);
QuerySpec query = new QuerySpec("public.tasks", List.of("public.tasks.id"),
    List.of(new QuerySpec.Filter("public.tasks.id", QuerySpec.Operator.EQ,
        List.of(IntNode.valueOf(4)))), List.of(), 100, 3000, Map.of(), effective,
    Map.of("region", "public.tasks.region"));
QueryResult result = adapter.query(source, query);
```

具体能力与实际环境验证结果见 `docs/evidence/continuation/c5-adapters-report.md`。五类无本机实例的协议代码不能视为真实接入验收。

## 能力限制

- PostgreSQL：普通主键表，内等值 JOIN；所有列使用完整资源前缀，未绑定守卫在元数据访问前拒绝。
- ClickHouse：仅 MergeTree 家族（包含复制变体）；独立查询 system.tables/system.columns 且按 database/table 参数过滤。排序键不保证唯一，证据使用 `_part/_part_offset`；分片合并后物理证据位置可能失效，上层应同时归档查询事实与快照。无本机实例，尚待真实验收。
- S3：单对象最多16MiB、每前缀最多1000对象；Parquet 仅非重复基础类型列，EQ/IN 谓词下推与列裁剪，分类检查在输出上限之前；证据为对象键及物理 rowIndex。尚未支持嵌套、Decimal/INT96 等复杂类型，明确拒绝。无需接受客户端原始路径或SQL。
- 文件：单文件最多16MiB/10万行；CSV、JSON对象数组和 .xlsx 首工作表。公式单元格直接拒绝；不执行公式/宏，.xls/.xlsm 暂不支持。真实路径必须留在批准root之下，拒绝任意 `..` 和绝对路径。
- HTTP：baseUrl 必须是无凭据/查询参数的 origin；端点精确声明，仅GET或固定JSON体POST模板（页码参数自动绑定），未开放客户端任意请求参数。响应为JSON对象数组；pageParam 为页码参数，每页至多1000条、至多100页，页未耗尽而触及配置上限时明确拒绝。完整响应体受截止时间和16MiB上限约束；禁止全部重定向。
- MongoDB：仅原生 find，服务端受限过滤/投影/LIMIT；元数据字段来自首条样本并含索引，不能视为完整文档Schema，不接收聚合脚本。
- Redis：仅STRING内的JSON对象，键名自动增加 `_key` 字段；resource 是精确批准前缀，使用 `_key EQ` 可做精确键查，否则 SCAN 最多1000步/10万键，过滤在结果上限前，无JOIN。
- Cassandra：仅完整分区键EQ，守卫必须绑定同一分区键；不支持任意过滤、ALLOW FILTERING 或JOIN。分区键仅 int/bigint/uuid/boolean/text/varchar/ascii，其他类型明确拒绝。
- Neo4j：节点属性查询；或 options 同时声明 `relationship`（关系类型标识符）、`targetLabel`（同样须在accessScope）时查询单跳有向关系。关系模板对两端使用同名分类属性和同一有效范围，可投影 `_targetId`、`_relationshipId`；关系模板暂不支持额外filters。节点与关系元素ID进入证据；其他Cypher/SQL JOIN不支持。

Windows 当前JDK的 Unix-domain socket 临时路径需要ASCII目录；本机测试命令使用 `-DargLine="-Xmx512m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp"`。这只影响测试运行参数，不改变协议授权规则。
