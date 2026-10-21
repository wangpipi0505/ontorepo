# C8 数据整改独立复审（2026-10-21）

复审者：`audit_data_review`，独立于实现者 `audit_data_fixes`。结论：**Spec APPROVE（按当前明确的实现边界）；Quality APPROVE**。在本轮差异及其调用链范围内，没有发现需要退回修改的可达缺陷。本结论不替代主执行者的最终全量回归、运行包验收或五类外部数据源验收。

## 范围与方法

读取工程 `AGENTS.md`、[数据整改记录](c8-data-fixes.md)、当前详设 OM-C11 修订，并将代码与 `.runtime/independent-audit-20261021-baseline/repo` 逐文件比较。检查 8 个生产实现文件、3 个相关测试文件，以及 `FrozenDataExecutor`、`DatasourceService`、`AdapterSupport`、`QueryPlanner`、`LabelScope` 的实际调用链。

本次只读生产代码，没有启动 Maven、数据库查询、HTTP、源连接或服务重启。`FrozenDataExecutor` 本轮的 `FrozenGuardBindings` 替换属于 C01 联动，由对应复审覆盖；这里仅核对它仍沿用冻结源配置及现有来源证据链，不对 C01 的整个门禁作独立通过声明。

## 逐项复审

| 项目 | Spec / Quality | 核验依据及结论 |
|---|---|---|
| C03 真实步骤 | 通过 / 通过 | `FrozenExecutor` 先实际求值条件，命中才追加 RULE_FIRE；评分返回实际得分后才追加 SCORING；仅 `explain=true` 且形成解释时追加 EXPLAIN。仍执行原冻结规则、评分和排序逻辑，保留 RULE_EXECUTION 汇总。新测试同时覆盖命中、未命中、关闭解释和 RULE_CHECK；未把原有 E1 来源链重新实现或伪造为本轮新增。 |
| C10 Parquet 标量 | 通过 / 通过 | INT32/INT64 先验证数值节点、数值等价整数及精确范围，再用 exact 转换；LabelScope 规范后的 DecimalNode 整数仍能使用。FLOAT/DOUBLE 拒绝非数值及转换后的非有限值；BOOLEAN/BINARY 不再从其他 JSON 类型强制转换。业务过滤与标签下推共用 `in` 校验，实际行仍经过授权及 `matches` 复核。下推错误不再被字符串转零、小数截断或整数溢出隐藏为空结果。 |
| C11 分值与词表 | 按修订边界通过 / 通过 | 新候选保留旧 `confidence` 字段，并在 location 明示 RULE_PRIOR、类别规则依据和未经校准含义；未声称固定分值是统计概率。服务端配置词表有总长、条目数量和单条长度限制，按普通文本包含匹配，不执行正则/脚本；通用句式和术语兜底仍在。服务为每次抽取新建引擎，未把可变候选列表变为共享实例。 |
| C11 表证据 | 按修订边界通过 / 通过 | TABLE 路径只消费已有元数据快照及外键定义，所有新表候选明确 METADATA_ONLY、samplesIncluded=false 和省略原因；没有为补样例直接使用连接凭据读取实例。当前详设第 720 行已明确此边界。因此这里只确认“实现与修订后的明确边界一致”，**不确认实例样例抽取已实现，也不称用户单独批准过降级**。 |
| 文件同批字节 | 通过 / 通过 | `SuggestionService` 直接解析 `ManagedFileService.bytes` 返回的已校验字节；不再使用校验后的 Path 再次打开。受管读取先限制路径，读取最多 8 MiB+1，再检查实际长度及摘要。`FileSourceAdapter` 最多读取 16 MiB+1，对实际读取字节核验冻结配置 SHA 后，让 CSV/JSON/XLSX 共用这批字节。生产 FILE 路径的 `DatasourceService.source` 要求冻结配置具有文本 SHA，故适配器可选 SHA 分支不意味着受管生产入口可以省略摘要。 |
| 旧文件候选兼容 | 通过 / 通过 | 冻结配置仍使用原 root、resource 和 sha256；没有回查当前源配置改写旧候选，没有调整冻结载荷、对象键或历史事实。保留 `frozenPath` 的目录预检，并在真正消费时复验摘要。未修改旧包的协议字段。此为调用链静态核验，原历史候选运行验证仍由最终验收承担。 |
| RDF 异常关闭 | 通过 / 通过 | 每个导入临时图在 finally 关闭；构建途中 RuntimeException/Error 时关闭尚未交付的累积图，再传播失败。成功模型仍由既有调用方负责关闭。回归用例包含先成功一张图、第二张图解析失败，检查两张临时图与累积图均关闭。 |
| 实体表名白名单 | 通过 / 通过 | `MappingService.model` 在 SQL 构造及数据库调用之前拒绝 null 或非五种固定实体表。现有合法调用不变；回归断言非法表名不会触及数据库。原实现未发现用户可控制的表名调用，因此将其定性为防御加固，未夸大为已证实 SQL 注入漏洞。 |

未要求无关重构或删除兼容字段。原有五类外部源待验收状态保持，未把本地 MinIO 验证扩大为九类源完整实测。

## 已有测试证据核对

逐行核对 `.runtime/c8-combined-green.log`：

- 第 97 行：`ParquetIntegrationTest` 1 项通过；测试源包含错误类型、整数边界、非有限数值、标签下推以及数值等价整数和 FLOAT/DOUBLE/BOOLEAN 正向分支。
- 第 100 行：`FileIntegrityAuditTest` 1 项通过；CSV、JSON、XLSX 均在原摘要通过后替换文件，查询及元数据读取必须拒绝。
- 第 357 行：`DataAuditRegressionTest` 7 项通过，覆盖实际步骤、先验/元数据声明、配置词表、抽取使用校验字节、RDF 关闭和表名白名单。
- 上述数据整改范围合计 **9 项，0 失败、0 error、0 跳过**。同一合并日志最终第 730 行为 BUILD SUCCESS；这不是本复审者重新执行测试的结果，也不是整个工程最终全量回归。

未进行文件系统竞争压力测试、原历史包在线执行或外部五类源验证。代码已消除本次提出的“校验字节与解析字节不一致”窗口；最终运行证据由主执行者统一补录，避免并发构建污染。

## 复审代码指纹

以下为复审时 SHA-256，便于后续变更确认是否仍受本结论覆盖。

| 文件（类名） | SHA-256 |
|---|---|
| S3ParquetSourceAdapter | B2BD14B31331F40140C9FA0493D3278C0F11B6D2DA31DE53C3FEBC392F2D000B |
| FileSourceAdapter | 1F786A69444E5442ABA0B88B967A39C319C63C107F9176C37DFA2FD1EDED3E27 |
| ManagedFileService | 366C589A2DE6D714C4FE5E3FA638FDDBCC10B21123233B38CD6E839395228BDB |
| SuggestionService | CE4787618DBE9A32F886EA387CF0D8A34087C25150178E28647014FA9ECE67CB |
| ExtractionEngine | 403F80E46BE3EB5BFB87E5D5AC1B348FB807217ACE3410CBC623C6054C34E665 |
| RdfCodec | 4B286D1365A3C219F450ACEBE2B6FB6CDA0BA9F2D29E7BAC1DA412679C181156 |
| MappingService | DB2C24D905BD6A16CB66FB3591E994E1A44C75D8F2B8A40F6AB4D7A70756581F |
| FrozenExecutor | 3369C232ECEBC354E03E37DC79A37C2D767E62FA6AB7524C4E7D79548A1FE140 |
| ParquetIntegrationTest | FB7C01AD053C73E3637318EFB59C053B32420796FA5E86C1602EB9C4849871FB |
| FileIntegrityAuditTest | E7878AA4C435CC8171E678EEB968E55B694F8D8ACCF5018BFC902052550838B2 |
| DataAuditRegressionTest | 30A9E28BD6BD001ED2A309865D811D7B41A3A7992C3E38313A7C6F9A58F4CAF5 |
