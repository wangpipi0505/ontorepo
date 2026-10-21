# C5 适配内核独立评审

初审时间：2026-10-20 22:29（Asia/Shanghai）；修复复核：22:38。初审范围：`c5-adapters-review-package.txt`、20 个主源码文件、8 个测试文件、README/POM，以及 root 原有 CredentialCipher/LabelScope。依据：`c5-adapters-brief.md`、`c5-adapters-review-brief.md`、`c5-adapters-report.md`、`c5-boundary-report.md` 与工程 AGENTS.md。修复复核直接读取当前源码，未使用旧审查包代替当前实现。

**当前 Spec：APPROVE（本次适配内核代码评审范围）；Quality：APPROVE。初审发现的 3 个 P1 均已独立复现修复结果并关闭；本轮未发现新增的可确认缺陷。** 九类代码均存在；ClickHouse、MongoDB、Redis、Cassandra、Neo4j 按用户决定保留外部真实实例验收，既不记作协议已通过，也不因缺实例记作代码失败。该结论不代替 P3 整体管理、映射和执行事实验收，也不表示五类外部协议运行正确性已确认。

## 修复后独立复核

复核文件为当前 `FileSourceAdapter.java`、`HttpSourceAdapter.java`、`QueryPlanner.java`、新增 `ReviewRegressionTest.java`，并读取交付报告的修复补充与 `onto-data/review-final-verify.log`。该日志确认为 22:36:37 BUILD SUCCESS，27 条、0 失败、0 错误、0 跳过。本轮没有运行 Maven 或重复全套。

独立验证另将三个当前生产源码及评审者自写探针编译到系统临时目录 `ontorepo-c5-rereview-<随机值>`，独立目录位于 classpath 首位；因此运行的是本轮检查的修复源码，而不是假定共享 target 已更新。仍使用 Java 21、`-Xmx256m` 和 ASCII Unix-domain 临时目录参数。实际输出：

```text
HTTP_ORIGINAL_COUNTEREXAMPLE=HTTP_ENDPOINT_REJECTED
XLSX_ORIGINAL_COUNTEREXAMPLE=EMPTY
XLSX_CONTROL=[{id=8.0}],LINE=3
PG_SQL_SET_ALL_UNICODE=MATCH,count=25
CH_SQL_SET_ALL_UNICODE=MATCH,SERVER_EXECUTION=NOT_RUN
PG_RUNTIME_ALL_25_BLANKS_TAB_LF_MIXED_EMPTY_NULL=DENIED,NONBLANK_CONTROLS=5
```

- **P1-1 已关闭**：实际 XLSX 完全重复原 A1=id/C1=region、A2=7/B2=A/C2=B 反例，查询返回空。再追加 A3=8/B3=B/C3=A，返回数字 8.0 且证据行号为 3。当前映射保存 `Cell.getColumnIndex()`，数据读取沿用物理列号；从来源消除错位，不是结果层补筛选。
- **P1-2 已关闭**：实际 endpoint 方法对原 `//attacker/rows` 输入返回 `HTTP_ENDPOINT_REJECTED`；同源 `/rows` 正向路径仍解析为 `https://trusted.example/rows`。代码在构造请求及附加头之前拒绝 `//`，随后又校验最终 scheme/host/有效 port/userinfo；原跨 origin 与凭据头外送路径已阻断。探针未请求外部主机。
- **P1-3 已关闭**：从当前 QueryPlanner 实际生成的 PostgreSQL Unicode 字符集提取码点，遍历 0 到 Character.MAX_CODE_POINT，结果与 Java 21 Character.isWhitespace 完全一致，共 25 个。再以该计划直接执行本工程 PG **只读 VALUES CTE**，逐个验证全部 25 个空白字符、混合空白、空串、null 均被 WHERE 拒绝；包含原 TAB/LF 反例。正向 A、NBSP(U+00A0)、FIGURE SPACE(U+2007)、NARROW NBSP(U+202F)、ZERO WIDTH SPACE(U+200B) 五个非 Java 空白值均保留。没有建表或访问共享测试数据。
- **SQL 新边界检查**：PostgreSQL BTRIM 使用固定显式 Unicode 字符集合，没有将用户输入拼接进字面量，谓词仍在 LIMIT 前。ClickHouse 字符类经 SQL 反斜线转义还原后，同样遍历全部 Unicode 码点与 Java 空白集合比较，完全一致；replaceRegexpAll 删除集合内字符后判断非空，在空白判定逻辑上与 String.isBlank 一致。本轮只验证生成模板与字符集，不将 Java 正则运行当成 ClickHouse/RE2 服务器验收。

新增回归测试同时覆盖错误列拒绝和正确物理列/证据、初次 URI 的 origin、所有 Java 空白与 NBSP 边界，以及 ClickHouse 模板静态检查，能够直接保护此次修复行为。本轮未确认需要进一步修改的生产代码问题；未扩大到未请求的全仓审计。以下保留初审问题与复现证据，状态均已关闭。

## 初审确认问题（修复后均已关闭）

### P1-1：稀疏 Excel 表头导致分类列错位，返回范围外记录

位置：`onto-data/src/main/java/cn/ontorepo/data/infra/FileSourceAdapter.java:101`、`:113`—`:114`。

表头通过 `for (Cell cell : header)` 收集，POI 迭代器跳过不存在的单元格；随后读取数据时使用紧凑列表下标 `excel.getCell(j)`，丢失原始列索引。合法 XLSX 的空缺表头因此能改变守卫绑定实际读取的列。

可触发输入：A1=`id`，B1 不创建，C1=`region`；A2=数字 7，B2=`A`，C2=`B`。查询 projection=`[id]`、scope=`{"attr":"region","in":["A"]}`、guardBindings=`{region:region}`。源文件声明的 region 位于 C 列，记录实际 region=B，应被拒绝。独立探针调用真实 FileSourceAdapter，结果为：

```text
XLSX_ACTUAL_REGION=B_RETURNED=[{id=7.0}]
```

影响：元数据和查询共同将 B 列误认作 region，授权过滤虽然发生在 LIMIT 前，过滤对象却已错误；既可能泄漏范围外记录，也可能输出错列。建议拒绝缺失/空白表头，或保存每个字段的实际列索引并据此读取。新增上述精确反例，断言明确拒绝或正确读取 C 列后返回空结果。

### P1-2：HTTP 网络路径引用绕过 baseUrl origin，并可能携带凭据头

位置：`onto-data/src/main/java/cn/ontorepo/data/infra/HttpSourceAdapter.java:36`、`:48`；请求头附加在同文件 `:66`。

端点规则 `/[A-Za-z0-9_/-]*` 接受以 `//` 开头的路径。`URI.resolve` 将其解释为新的 authority，而非原 origin 下的路径；代码仅校验 baseUrl 自身，没有校验最终 URI 的 origin。

可触发配置：baseUrl=`https://trusted.example`，endpoints/accessScope 均含 `//attacker/rows`，resource 同值。独立探针直接调用实际 endpoint 方法，无网络请求，得到：

```text
HTTP_ENDPOINT=https://attacker/rows
```

影响：即使禁止重定向，首次请求也会越出受控 baseUrl；若 connection.headers 包含 Authorization 或其他令牌，该请求仍会附加这些头。这需要服务端批准的端点配置包含该值；普通执行调用者不能覆盖连接配置，因此不能夸大为任意调用者直接选择目的地，但适配内核承诺的 origin 边界确实未成立。建议拒绝网络路径引用，并对最终 URI 的 scheme/host/有效 port 与 baseUrl 做一致性校验；新增 `//attacker/rows` 的网络前拒绝测试。

### P1-3：JDBC 的空白分类规则与 LabelScope 不一致

位置：`onto-data/src/main/java/cn/ontorepo/data/domain/QueryPlanner.java:84`—`:85`；统一分类规则位于 `LabelScope.java:113`—`:119`。

LabelScope 使用 `String.isBlank()` 将纯制表符、换行等标签视为未分类，且不限范围同样拒绝。JDBC 仅使用 `TRIM(column) <> ''`；PostgreSQL 默认 TRIM 不移除制表符与换行。当有效 scope 对该属性不限时，不再生成 IN 条件，此类未分类行会通过 WHERE 并计入 LIMIT。

可触发路径：普通 PG 表 `id primary key, region text`，region=`chr(9)` 或 `chr(10)`；QuerySpec.scope=`LabelScope.unrestricted()`、guardBindings=`{region:schema.table.region}`。无需写测试表即可在本工程 PG 只读验证计划中的原样谓词：

```sql
SELECT TRIM(chr(9)) <> '', TRIM(chr(10)) <> '';
```

独立探针输出：

```text
PG_TAB_ALLOWED=true,PG_NEWLINE_ALLOWED=true
LABEL_TAB_ALLOWED=false
```

影响：未分类默认拒绝这一明确用户约束在 JDBC 下失效；受限值集合通常会再次挡住这些值，不能据此忽略不限范围这一合法路径。建议按方言在 SQL WHERE 内采用与统一分类规则一致的空白校验，并保留 LIMIT 前过滤；补不限范围、tab/newline 和普通空格的真实 PG 反例。若修改跨方言 SQL，不应把未验的 ClickHouse 行为宣称已验。

## 检查范围与未发现问题的部分

- SQL 标识符、表 accessScope、投影/过滤列、连接两端列、值参数绑定、逐参与表守卫、空集合恒假、上限裁剪和主键证据已静态检查；现有真实 PG 测试覆盖 JOIN 两端分类和逐表证据。除上述空白分类外，本次未确认 SQL 注入、直接越表或空交集省略 WHERE 的缺陷。
- S3 最新补丁已纳入评审：非法空/父级前缀在读取前拒绝；stat/get 使用同一查询剩余预算；真实 Parquet schema、列裁剪、EQ/IN 下推与物理 rowIndex 代码及现有真 MinIO 测试已读。没有重跑真实上传测试。字节前缀语义与 README 一致，未另行要求目录分隔符语义。
- 文件 canonical path/junction 边界、大小/行数上限、JSON/CSV、Excel 公式拒绝已检查。现有 junction 反例属于真实重解析边界测试；本轮新增独立 XLSX 稀疏表头探针，没有修改业务文件。
- HTTP 的完整响应体 future 截止时间、响应大小、分页后再筛选的授权可达性、固定 POST 模板、重定向拒绝已检查；发现的 origin 问题是首次 URI 解析问题，与已有重定向测试不同。
- Mongo 原生 find 的受限 BSON、类型/空白守卫、投影与 LIMIT；Redis 受限前缀/精确键、SCAN 步数/计数；Cassandra 完整分区键 EQ、守卫分区键、无 ALLOW FILTERING；Neo4j 参数绑定和单跳两端标签范围均已静态检查。真实服务、角色权限、驱动运行行为仍待外部验收，不声称这些协议的生产正确性已证实。
- CredentialCipher 的必需 32 字节主密钥、随机 nonce、AES-GCM AAD/sourceId 绑定、密文边界和安全错误消息，以及 LabelScope 的构造校验、不可变标量集合、数值归一化、交集与未分类拒绝已检查；本轮未发现可触发的新问题。
- SourceSpec 防御复制/脱敏 toString、DataProblem 安全消息、adapter 异常统一包装已检查。未开展依赖漏洞扫描，也未覆盖 P2/P4 全部管理授权、映射冻结或事实落库。

## 初审验证证据与执行约束

交付方记录的最终全量结果为 22:25:15，23/23 通过、无跳过；这是已有执行证据，本评审没有重复全套，也没有将其当成上述反例已覆盖。

本轮仅将一个独立 Java 探针源/类及 XLSX 放在系统临时目录 `ontorepo-c5-review-<随机值>`，复用现有 Surefire XML 中的 classpath，以 Java 21 `javac -d <独立临时目录>` 编译，使用 `java -Xmx256m -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp` 运行。HTTP 验证只调用 URI 构造方法；Excel 验证调用真实适配器；PG 验证仅执行上面的常量 SELECT，无业务表读写、无建表。首次运行缺少 ASCII Unix-domain 临时目录参数，HttpClient 初始化失败；加上工程既有参数后完成全部三个探针，未因此改动产品代码。

未启动/停止任何服务，未启动 Docker，未访问相邻工程，未写共享 target，未提交 Git。仓库内仅写本评审文档。初审要求的三项修复、精确反例独立复核及协调窗口内完整回归现均已有上述证据；五类外部真实验收仍单独保留待办。
