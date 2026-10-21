# 独立审核数据侧整改记录（2026-10-21）

范围：OM-C03 的真实执行步骤、OM-C10、OM-C11，以及 RDF 资源释放、受管文件校验与消费间隔、映射实体表白名单。正式设计、计划、台账及审核原记录由主执行者统一同步。本记录不替代整体验收结论。

## 核验结论与实现

| 项目 | 核验结论 | 本轮处理 |
|---|---|---|
| C03 E1 来源链 | 原实现已有 SOURCE_RECORD、META_SNAPSHOT、MAPPING_SNAPSHOT 和 ONTOLOGY_VERSION，并有真实源回归证据；“完全没有来源链”不成立 | 保留原链及旧事实，不重写历史证据 |
| C03 执行步骤 | 评分及解释已执行，但原来仅记录 RULE_EXECUTION 汇总 | FrozenExecutor 在条件实际命中时追加 RULE_FIRE，评分成功后追加含规则 ID 和实际得分的 SCORING，仅启用并实际生成解释时追加 EXPLAIN；保留原汇总步骤。未命中规则、未启用解释不会伪造对应步骤 |
| C10 Parquet 过滤 | Jackson 数值访问器会把字符串/布尔强制转值，小数截断或整数溢出可能被下推隐藏为空结果 | INT32/INT64 验证真实数值、整数等价性及精确范围；FLOAT/DOUBLE 验证数值且转换后有限；BOOLEAN/BINARY 要求原始布尔/字符串类型；非法值统一 FILTER_TYPE_MISMATCH。对业务过滤和标签下推共用校验，兼容 LabelScope 规范后的 DecimalNode 整数 |
| C11 规则先验 | 原固定 .80/.85/.75 不是统计概率，默认词表不能适配所有领域 | 保留 confidence 接口字段以兼容旧客户端，location 新增 confidenceKind=RULE_PRIOR、confidenceBasis、confidenceMeaning；明确分值仅供候选排序、未经校准。新增服务端 ONTO_EXTRACTION_TERMS 可配置词表，通用句式和通用术语兜底保留 |
| C11 表样例 | 当前表抽取仅使用元数据与外键，无实例样例 | 每条 TABLE 候选显式带 evidenceScope=METADATA_ONLY、samplesIncluded=false 和 sampleOmissionReason；未引入任何实例读取，不扩大默认拒绝策略 |
| RDF 释放 | 导入文档解析失败会跳过临时图关闭，累积图也没有交付调用方却未关闭 | 单个导入图使用 finally 关闭；模型构建失败关闭累积图；成功构建后继续由既有调用方负责关闭 |
| 受管文件 | 先校验路径再重新打开，内容可在两次读取间替换；实例文件适配器原来未对实际读取字节复验冻结摘要 | ManagedFileService.bytes 返回已校验的有界字节，SuggestionService 直接解析这批字节。FileSourceAdapter 对同批读取字节验证受管配置 sha256 后再用于 CSV/JSON/XLSX，保留 16 MiB、格式、字段、默认拒绝边界。目录预检不再被视为后续读取的完整性保证 |
| 映射实体表 | 当前调用方均为固定常量，未发现可利用外部注入；公开 helper 缺白名单 | model 只接受 ont_class、ont_property、ont_codebook、ont_relation、ont_operation；非法类别在触及数据库前拒绝 |

## C11 配置与边界

`ONTO_EXTRACTION_TERMS` 为服务端逗号分隔词表（支持英文或中文逗号）。空配置沿用原 12 个默认词；最多 256 个去重词条、每条 1–64 字符、总配置不超过 8192 字符，空项及控制字符拒绝。配置不接受正则、脚本或网络地址执行；词条仅参与文本包含判断。

现有先验分值：CLASS 0.80、PROPERTY 0.85、RELATION 0.75。新增 location 说明随新候选持久化；不回写旧候选或旧冻结包，不把历史字段补成未经发生的证据。

表样例缺失的显式说明是本轮设计收敛及安全边界，不能记录为“用户已单独批准降级”。若后续补充实例样例，必须先定义并复用调用身份、空间权限、分类标签/数据范围求交、字段脱敏、限行预算与来源证据；不能因持有数据源连接凭据就直接读取实例样例。本轮没有实施此未定义链路。

## 验证

由发布整改代理统一占用 Maven 窗口执行，数据整改代理未并发启动构建、数据库测试或服务重启。

- RED：`.runtime/c8-data-red.log`。标准 reactor 在 onto-data 的 2 项预期失败后停止。
- 完整 RED：`.runtime/c8-data-red-all.log`。同一命令临时加 `-Dmaven.test.failure.ignore=true` 以采集下游 API 反例，实际为 **7 项测试、7 项断言失败、0 error**（2 个数据适配器测试、5 个 API 模块纯测试）。该日志末尾的 BUILD SUCCESS 来自显式忽略失败，不能用作通过证据。
- 初始反例覆盖：Parquet 错误过滤类型被隐藏、三个真实文件格式替换后绕过冻结摘要、规则/评分/解释步骤缺失、表候选缺少先验和无样例说明、抽取重新打开已校验路径、非法表名进入数据库。
- 在初始反例基础上补充：整数标签 DecimalNode 正向兼容；FLOAT/DOUBLE/BOOLEAN/数值等价整数正向过滤；NaN/Infinity 拒绝；配置词表替换默认词但保留通用句式；RDF 导入中途失败时已成功图、失败图与累积图全部关闭。
- 定向 GREEN：`.runtime/c8-combined-green.log` 中 **ParquetIntegrationTest 1、FileIntegrityAuditTest 1、DataAuditRegressionTest 7，共 9 项通过，0 失败、0 error、0 跳过**。真实 MinIO Parquet 用例同时验证 22 个非法标量、错误标签类型以及 6 个数值/类型正向兼容分支；三个受管文件格式均验证替换后的查询及元数据读取拒绝。
- 既有集成回归：由主执行者统一执行并记录最终结果。本阶段移交时仅确认上述数据侧定向验证通过，不将尚未结束的合并构建记为整体验收通过。

原有五类外部数据源真实实例验收仍按用户决定保留外部验收；本轮没有启动 Docker 或把协议代码检查写成外部实测通过。
