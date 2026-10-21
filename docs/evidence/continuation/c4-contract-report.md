# C4 契约核阶段记录

2026-10-20，主执行者实现独立 `onto-contract`。此记录只覆盖纯契约核，尚未接入管理 API 和三类执行入口，不能代替 P2/P4 出口。

- 根据详设 §8.8 提取四段 JSON 为测试资源，保留原字段和值。
- 使用 [networknt JSON Schema Validator 2.0.4](https://github.com/networknt/json-schema-validator/tree/2.0.4) 执行 Draft 2020-12 Schema/实例校验；外部引用在解析前拒绝，布尔/数值不做隐式转换。
- 实现预览编译、正式化、消费导入校验及守卫定义/类型/绑定校验；预览四元组为空且带 preview 标记，正式化不改动其他业务契约。
- 信封包装后迁移业务 Schema 的本地 JSON Pointer，保留业务根引用语义；原输出节点不改写。

RED：实现前测试编译因缺失 ContractGenerator 等类型失败，见本地 `.runtime/contract-red.log`。随后 11 条通过；补充带 `$defs` 的业务 Schema 包装反例，产生 INVALID_SCHEMA（本地 `.runtime/contract-reference-red.log`），修复指针作用域后通过。

GREEN：`mvn -B -f onto-contract/pom.xml test`，2026-10-20 21:11:46，12 条，0 失败、0 错误、0 跳过。包含四段样例逐项校验、缺 meta、tenant_label 无绑定/缺定义、缺 guards/示例、示例类型错误、非法枚举/字段类型、preview 拒绝、风险不可下调、三种外部引用拒绝、本地引用及 additionalProperties、包装后引用保持。

实现时另有一次手工抄写测试摘要长度错误，改为直接取原样例摘要后通过；未通过放宽 64 位摘要规则解决。

后续：加入 reactor、API/冻结门禁/三入口执行绑定、前端助手声明和实际发布流程；统一评审时复核异常边界与标准子集限制。

## 2026-10-20 独立审查修复与复审证据

已逐项处理 `c4-contract-review.md` 的 5 项发现。仅修改 `onto-contract/` 的实现和新增测试，并在本报告追加证据；公共 API、networknt 2.0.4 依赖、原 12 个测试及四段原始样例均保留。未修改父 pom、其他模块、正式需求文档或运行配置，未提交 Git。以下结果仅表示纯契约核完成本轮修复，不代表运行期三入口验收。

统一执行命令（每轮 RED/GREEN 均使用同一命令，Maven 和测试 JVM 上限 512m）：

```powershell
. ./deploy/Environment.ps1
$null = Import-OntoEnvironment
Set-OntoJava
Set-OntoBuildLimits
mvn -B -f onto-contract/pom.xml '-DargLine=-Xmx512m' test
```

| 审查发现 | 新增真实行为覆盖 | RED 结果 | 修复及 GREEN |
| --- | --- | --- | --- |
| 1. 字面量/属性映射误当 Schema | `wrappingDoesNotRewriteInstanceValuesOrUnknownAnnotations`：const、enum、default、examples、未知注释 5 例；`keywordLikePropertyNamesAreOrdinaryBusinessFields`；`externalLookingReferencesInsideAnnotationsStayOpaque` | 21:29:39，共 19 例，5 个字面量改写断言失败、2 个合法 Schema 被 `EXTERNAL_SCHEMA_REF` 误拒；原 12 例通过 | 新增 `SchemaTraversal`，仅沿标准 Schema 单值/映射/数组位置遍历，属性映射键、实例字面量与未知注释不参与检查或改写。21:30:23，19/19 通过 |
| 2. 悬空引用在门禁阶段漏检 | `unresolvedOrNonSchemaLocalTargetsFailBeforeRuntime` 8 例：属性内/未使用 `$defs`/未触发条件中的悬空引用、字符串/数组/属性映射目标、缺失命名锚点及动态锚点；逐例检查 Schema、preview 和正式导入；另验证有效锚点与 true/false 目标 | 21:31:26，共 28 例，8 例“应抛错但未抛错”；日志 `onto-contract/target/review-02-red.log` | 新增 `SchemaReferences`，按资源作用域索引真实 Schema 位置和锚点，提前检查全部引用；元 Schema 验证之后显式调用标准库 `initializeValidators()`，同时限制加载器只使用内置 2020-12 元 Schema。21:32:28，28/28 通过；`review-02-green.log` |
| 3. 消费导入放行弱信封 | `importRejectsWeakenedResponseEnvelope` 17 例：删除根 object/type 和 code/data/meta 任一必填，删除 code=OK 或 data Schema，删除 meta object/type、四元组任一必填，改错四元组任一字符串类型，删除或放松摘要正则 | 21:33:29，共 45 例，17 例全部重现错误放行；`review-03-red.log` | `validateBody` 在标准 Schema 校验后检查 §8.8 显式信封结构，保留 code=OK、根三字段必填、meta 四元组字符串及原规定摘要断言，失败为 `INVALID_OUTPUT_ENVELOPE`；生成、正式化和导入共用。21:34:08，45/45 通过；`review-03-green.log` |
| 4. 根 $id 包装后失效 | `businessRootIdPreservesItsLocalReferenceScope`：URN/HTTP/相对 `$id` 3 例；嵌套资源中同名锚点与本地指针、带 `$id` 的动态锚点递归、HTTP `$id` 回环监听；校验合法数据、错误类型拒绝、正式化保持 Schema | 21:35:02，共 51 例，5 例遭 `NESTED_SCHEMA_ID` 拒绝，相对根 ID 原始编译亦报 `INVALID_SCHEMA`；`review-04-red.log` | 移除粗暴的嵌套 ID 禁令；迁移只处理与业务根共享资源的本地指针，遇到 `$id` 保留独立资源；标准库使用稳定、禁止加载的内联基址解析相对 ID。21:35:41，51/51 通过；`review-04-green.log`；回环监听 0 连接 |
| 5. 守卫标识符接受布尔值 | `booleanGuardIdentifiersNeverBecomeExecutableBindings`：table、column、主体 attribute 分别使用 true/false，共 6 例；另验证合法字符串主体属性绑定 | 21:36:15，共 58 例，6 例全部重现错误放行；`review-05-red.log` | 先 `isTextual()` 再做标识符白名单，禁止 `asText()` 隐式转换；精确断言 `GUARD_ATTR_UNRESOLVED`、操作/属性 field、`NO_FILTER_BINDING` reason。21:36:39，58/58 通过；`review-05-green.log` |

引用迁移最终边界补充：

- `encodedJsonPointersKeepTheirBusinessTargetAfterWrapping` 覆盖编码后的 `/`、`~0/~1` 转义属性名、空格键名。21:38:09，编码 `/` 的本地引用在标准库编译处先重现 `INVALID_SCHEMA`；仅在编译副本和真正引用关键字中做等价 URI 片段规范化，21:39:03，61/61 通过。日志为 `review-pointer-red.log`、`review-pointer-green.log`。
- 官方 2020-12 元 Schema 保留兼容关键字 `dependencies`，networknt 对其仍执行断言。新增 `legacySchemaDependenciesRetainTheirLocalReferences` 与 `legacyDependenciesCannotHideExternalSchemaReferences`，21:39:55 两例先失败；遍历器补充其中对象/布尔 Schema 值，字符串依赖数组保持不透明。日志为 `review-dependencies-red.log`。

最终命令结果：**2026-10-20 21:40:24 +08:00，63 例，0 失败、0 错误、0 跳过，BUILD SUCCESS**。原 `ContractTest` 12 例全部通过；新增 `ContractReviewRegressionTest` 51 例全部通过。最终完整日志位于 `onto-contract/target/review-final.log`，结构化结果位于 `onto-contract/target/surefire-reports/`。构建仍有既有 SLF4J 无 provider 提示和 API 弃用提示，不影响测试结果。

原只读探针亦重新执行至悬空引用段：普通 `$ref` 属性名通过、const 包装前后完全相同、根 `$id` preview 通过、外部 HTTP 引用继续拒绝、监听 0 连接；悬空引用现于 `validateSchema`/preview 提前拒绝。探针旧流程未捕获 preview 的预期拒绝而提前退出，不能把该探针退出码视为整套探针通过；其余发现以新增自动化回归结果为证。

语义及库行为核对来源：[JSON Schema Draft 2020-12 Core](https://json-schema.org/draft/2020-12/json-schema-core)、[官方 2020-12 元 Schema](https://json-schema.org/draft/2020-12/schema)、[networknt 2.0.4 Schema.initializeValidators](https://github.com/networknt/json-schema-validator/blob/2.0.4/src/main/java/com/networknt/schema/Schema.java)、[SchemaLoader 默认禁止远程加载及 allow 配置](https://github.com/networknt/json-schema-validator/blob/2.0.4/src/main/java/com/networknt/schema/resource/SchemaLoader.java)、[兼容关键字范围](https://github.com/networknt/json-schema-validator/blob/2.0.4/src/main/java/com/networknt/schema/keyword/KeywordType.java)。

## 2026-10-20 第二轮复审新增发现 6：非推进递归修复

本轮依据 `c4-contract-review.md` 的第二轮结论，修复 `{"$ref":"#"}` 等循环在实例校验和输入参数示例校验中触发 `StackOverflowError` 的问题。仅修改 `SchemaReferences.java`，新增 `SchemaRecursionTest.java`，并追加本报告；原 63 个测试保留，未增加依赖、未改公共 API、未改运行配置或其他模块、未提交 Git。

实现：在引用目标/资源/锚点解析后、调用标准库编译前，构建仅包含“不推进实例位置”的评估图。`$ref`、`$dynamicRef`、`allOf`/`anyOf`/`oneOf`、`not`、`if`/`then`/`else`、`dependentSchemas` 及兼容 `dependencies` 构成同位置边；`properties`、数组项及其他下降到子实例的关键字不构成这种边。动态引用解析到动态锚点时，额外检查文档内同名锚点可能重绑定的目标，通过虚拟分派节点避免引用与锚点数量的笛卡尔积。迭代拓扑消除法发现循环后返回 `ContractProblem(code=INVALID_SCHEMA, field=schema, reason=NON_ADVANCING_SCHEMA_CYCLE)`；检测器不递归追踪引用链，也不捕获 `Error`/`Throwable`。

新增真实行为覆盖：

- `nonAdvancingCyclesFailAtEveryContractBoundary` 共 17 例：直接自引用、命名/动态锚点自引用、双定义互相引用、未使用定义内循环、三类组合关键字、否定/条件分支、两类依赖 Schema、子属性内带 `$id` 的自循环、编码/转义指针循环、跨资源动态锚点重绑定。每例检查 Schema 校验、实例校验、输入 preview、输出 preview、输入导入、输出导入以及 formalize 七个失败边界。
- `recursionThroughChildInstancesRemainsValid` 共 9 例：`properties`、`items`、`prefixItems`、`contains`、`additionalProperties`、`patternProperties`、`unevaluatedProperties`、`unevaluatedItems` 和通过子属性交替递归的双定义。逐例验证正常数据通过、错误叶子类型报 `SCHEMA_VALIDATION_FAILED`、生成包装后仍保留语义、输入参数示例同样正确接受/拒绝，传入 Schema 不被修改。已有根 `$id` 和动态锚点递归测试继续保留并通过。

RED：使用前述统一 512m Maven 命令，2026-10-20 21:53:17 +08:00，共 89 例，新增 17 个循环用例全部失败，明确记录应抛 `ContractProblem` 却放行或实际抛出 `StackOverflowError`；原 63 例和 9 例正常递归通过。日志：`onto-contract/target/review-cycle-red.log`。

GREEN：21:54:19，89/89 通过，日志 `onto-contract/target/review-cycle-green.log`。补齐正常递归的输入参数示例正反例后，最终再次运行：

```powershell
. ./deploy/Environment.ps1
$null = Import-OntoEnvironment
Set-OntoJava
Set-OntoBuildLimits
mvn -B -f onto-contract/pom.xml '-DargLine=-Xmx512m' test
```

**最终结果：2026-10-20 21:55:45 +08:00，89 例，0 失败、0 错误、0 跳过，BUILD SUCCESS。** 其中原 `ContractTest` 12 例、首轮 `ContractReviewRegressionTest` 51 例、本轮 `SchemaRecursionTest` 26 例。日志：`onto-contract/target/review-cycle-final.log`。

原复审探针另外使用 `-Xmx512m -Xss512k` 运行 `ContractRereviewProbe.java cycle`，退出码 0，日志 `onto-contract/target/review-cycle-probe.log`：

```text
direct-cycle-schema=INVALID_SCHEMA
direct-cycle-output-preview=INVALID_SCHEMA
direct-cycle-instance=INVALID_SCHEMA
direct-cycle-input-preview=INVALID_SCHEMA
```

本轮修复已具备复审条件；以上只覆盖纯契约核，不代替管理 API、冻结门禁或运行期三入口验收。
