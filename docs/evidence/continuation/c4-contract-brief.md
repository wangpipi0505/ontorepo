# C4 独立契约核实施任务

阅读原详设 §8.8（包含四段完整 JSON），这是语义权威。此任务是 P2 中可独立验证的纯契约核，不提前宣称 P2 出口。独占新目录 `onto-contract/`；不得编辑父 pom、其他模块、web、契约文件、原设计文档。完成后主执行者接入依赖和服务。

## API

Java 包 `cn.ontorepo.contract`。使用清晰职责类，不做任意代码/网络执行。公共 API 使用 Jackson `JsonNode`，保持原 snake_case 消费契约：

- `ContractGenerator.preview(JsonNode operation, JsonNode guardCfg, String baseUrl, String spaceCode, String candidateId) -> ObjectNode`
- `ContractGenerator.formalize(JsonNode preview, String baseUrl, String spaceCode, String releaseId, String version, String sha256) -> ObjectNode`
- `ContractValidator.validateImport(JsonNode manifest) -> void`
- `GuardValidator.validate(JsonNode operation, JsonNode guardCfg) -> void`
- `JsonSchemaAssertions.validate(JsonNode schema, JsonNode instance) -> void`
- `ContractProblem` 有 `code/field/reason`，中文 message，不依赖 web 层。

operation 输入采用 `{code,input_schema,output_schema,assistant_tool,policy:{timeout_ms,max_rows}}`；guardCfg 为按属性名键控的对象，定义 `{type:"STRING"|"INTEGER"|"DECIMAL"|"BOOLEAN",filter_binding?:{table,column,operator:"IN"|"EQ"},authorization_rule?:{kind:"PRINCIPAL_ATTRIBUTE",attribute:string}}`。只认上述真正可执行白名单绑定形态，不接受非空任意字符串冒充绑定；绑定表列限制 SQL 标识符。运行集成会按该定义执行授权，若发现设计冲突请报告。

## 实现与验收

- 使用真正 Draft 2020-12 JSON Schema 库，禁止临时手写 required/type 检查冒充标准；schema/实例不允许访问网络 `$ref`，支持本地引用。
- 预览发布四元组 spaceCode/releaseId/version/contentDigest 均为 null，标记 preview。正式化只填值/路由，不能重生成其他字段导致门禁对象变化。输出信封 Schema 根 required=code/data/meta，完整四元组，sha25664位；参数示例逐项校验；执行属性布尔、毫秒整数、上限严格。
- 缺 guard_attrs 报 MISSING_GUARD_ATTRS；未定义/无绑定/非法类型分别精确错误；info_attrs 不进入 guard；完整集合逐项校验，不随调用者权限裁剪。
- 消费导入校验完整必填、SYNC/HTTP 及其他消费允许枚举、五类错误语义、字段类型；preview 不接受为正式版本证据。
- 不修改传入 JSON；正式输出 route 使用实际编码组成 `/onto/r/{spaceCode}/releases/{releaseId}/ops/{code}`，预览用 `/onto/internal/candidates/{candidateId}/ops/{code}`；baseUrl 必须绝对 HTTP(S)。
- 将原文四段 JSON 提取为测试资源，逐段真实解析，参数/原始信封响应/业务数据/meta一致/消费导入对象结构断言。包括移除 meta、tenant_label无绑定、secret_level缺定义、缺参数示例、非法枚举、非整数超时、guard数组错误、正式化保持其他内容等反例。
- Maven 子模块 parent 引用 `../pom.xml` 的 cn.ontorepo:ontorepo:0.1.0；自身仅纯 Java/Jackson/Schema/JUnit 依赖，版本需官方资料确认；可 `mvn -f onto-contract/pom.xml test` 独立运行，无需加入父 reactor。
- 先 RED 再 GREEN，中文 Javadoc/注释；报告写 `docs/evidence/continuation/c4-contract-report.md`。不提交Git。任务完成后由主执行者将模块接入服务；不得把纯核通过报告为运行期三入口通过。
