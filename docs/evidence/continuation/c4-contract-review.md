# C4 契约核独立只读审查

日期：2026-10-20。范围：`onto-contract` 纯 Java 契约核。依据：`c4-contract-brief.md`、原详设 §8.8、`c4-contract-report.md` 和 `c4-contract-review-package.txt`。运行期三个入口的集成由其他任务负责，本审查不把尚未集成作为缺陷。

**最新结论（第三轮）：发现 1–6 均已关闭，纯契约核在本次审查范围内通过。** 末尾第三轮记录是当前结论；以下保留各轮发现和验证过程，不代表问题仍然存在。

首轮结论：**需要修改后复审**。标准 Schema 库、主要字段校验、样例提取、基本守卫完整性、预览四元组、正式化复制行为均已实现；但存在下列 5 项真实可复现的纯核问题，当时不能判定完整符合任务契约。各项均为 P2（会导致合法契约被拒绝，或不可消费/类型错误的契约被放行）。

## 发现

### 1. [P2] Schema 遍历把业务字面量当作 Schema，包装会改变实际约束

主定位：`onto-contract/src/main/java/cn/ontorepo/contract/ContractGenerator.java:96–103`。相关位置：`JsonSchemaAssertions.java:58–68`。

`relocatePointers` 遍历任意 JSON 对象并修改同名 `$ref`，不区分 Schema 位置与 `const`、`enum`、`default` 等实例数据位置。令操作的 `output_schema` 为：

```json
{"const":{"$ref":"#/literal"}}
```

原业务值 `{"$ref":"#/literal"}` 通过原 Schema。`preview` 生成后的 `data` Schema 却是：

```json
{"const":{"$ref":"#/properties/data/literal"}}
```

将原业务值放入合法响应信封后，校验报 `SCHEMA_VALIDATION_FAILED`。这不是本地引用迁移，而是修改了常量业务值，违反包装保留业务语义的要求。

同一类遍历问题还误拒合法属性名：`{"type":"object","properties":{"$ref":{"type":"string"}}}` 校验实例 `{"$ref":"literal"}` 时，被 `inspect` 当成外部 Schema 引用，报 `EXTERNAL_SCHEMA_REF`。应按 Draft 2020-12 的 Schema 位置遍历，只处理真正的引用关键字，不解释属性映射中的键名或实例字面量。

### 2. [P2] 悬空本地引用通过生成和正式导入，直到响应校验才失败

定位：`onto-contract/src/main/java/cn/ontorepo/contract/JsonSchemaAssertions.java:27–29`。

`validateSchema` 只把用户 Schema 当实例交给元 Schema 验证，没有验证其引用可解析性。令操作输出为：

```json
{"type":"object","properties":{"stdCode":{"$ref":"#/$defs/missing"}}}
```

实际结果：`validateSchema`、`preview`、`formalize`、`validateImport` 全部成功；对包含 `data.stdCode` 的原样例响应执行生成的 `output_schema` 才报 `INVALID_SCHEMA`。因此 CONTRACT 门禁可以放行一个无法执行输出断言的正式契约。

应在生成/导入时解析全部真正的本地引用，拒绝不存在或不是 Schema 的目标；仅元 Schema 校验不够。修复时继续保持外部 URI 禁止加载。

### 3. [P2] 导入校验未保证输出信封的 meta 必填约束

定位：`onto-contract/src/main/java/cn/ontorepo/contract/ContractValidator.java:28–29`。

对原样例 ②，仅把 `output_schema.required` 从 `["code","data","meta"]` 改成 `["code","data"]`，其他字段全部保留。`validateImport` 仍成功；再删除原样例 ③ 的整个 `meta`，该响应也通过导入的输出 Schema。

这里检查了“是合法 JSON Schema”，却没有检查 §8.8 要求的信封契约。消费导入因此接受不能保证完整版本证据的契约，与任务规定的根 `required=code/data/meta` 不符。应在导入阶段保证信封根、`code`、`meta` 四元组及摘要约束；至少增加“删除 meta 必填项后导入拒绝”的回归反例。

### 4. [P2] 合法业务根 $id 在包装后被自身嵌套检查拒绝

定位：`onto-contract/src/main/java/cn/ontorepo/contract/JsonSchemaAssertions.java:66–67`；触发路径 `ContractGenerator.java:78–80,53`。

反例：

```json
{"$id":"urn:example:business","type":"object",
 "$defs":{"code":{"type":"string"}},
 "properties":{"stdCode":{"$ref":"#/$defs/code"}}}
```

直接用 `JsonSchemaAssertions.validate` 验证 `{"stdCode":"ALG-001"}` 成功，没有外部访问。相同 Schema 作为操作 `output_schema` 时，`preview` 把它放到 `properties.data` 后，又因 `$id` 深度大于 0 报 `NESTED_SCHEMA_ID`。

这是合法根标识和文档内引用，任务未排除该标准用法。应明确保留业务 Schema 资源边界和引用基址，或在包装时做经过验证的等价处理；不能使包装前合法且可解析的根 Schema 在包装后必然失败。单纯删除此拒绝也不够，因为当前指针迁移还必须与 `$id` 的作用域一致。

### 5. [P2] 守卫绑定用 asText 接受了布尔值标识符

定位：`onto-contract/src/main/java/cn/ontorepo/contract/GuardValidator.java:29–31,36–37`。

在原样例的 `region` 定义中，将绑定改成：

```json
{"type":"STRING",
 "filter_binding":{"table":true,"column":false,"operator":"IN"}}
```

`GuardValidator.validate` 成功。将它改成：

```json
{"type":"STRING",
 "authorization_rule":{"kind":"PRINCIPAL_ATTRIBUTE","attribute":true}}
```

同样成功。原因是 `asText()` 把布尔值变成 `"true"`/`"false"`，而这些文本通过标识符正则。任务定义要求表/列为 SQL 标识符字段、主体属性为字符串；这两种 JSON 不是有效的执行绑定，却被纯核判定满足守卫。

应先对绑定字段执行 `isTextual()`，再检查白名单/标识符。不能依靠运行期碰巧找不到该字段来代替生成期精确拒绝。

## 已验证部分与边界

- 原详设 §8.8 的四段 JSON 与 `sample-1.json` 至 `sample-4.json` 去掉首尾空白后逐字一致。
- 使用现有编译产物反射调用现有测试方法，12 个测试断言全部通过（含参数化外部引用的 3 个输入）；没有修改或跳过现有断言。
- 外部 HTTP 引用报 `EXTERNAL_SCHEMA_REF`；`file:`/`classpath:` 外部引用由已有测试拒绝。
- 用回环 `ServerSocket` 监测外部 HTTP 引用及“HTTP 根 `$id` + 本地 `$ref`”验证，没有收到连接。后者可在原 Schema 上正常解析。本次未发现能绕过引用检查访问网络的反例。
- 已有测试覆盖缺 guard、未定义/未绑定 guard、参数示例缺失及类型错误、主要枚举/执行字段类型、风险下调、预览拒绝、普通 `$defs`、输出缺 meta、不修改输入及基本正式化保持。没有发现独立的“正式化重生成业务字段”问题。
- 模块职责清楚、依赖范围较小；主要质量缺口集中在 JSON Schema 语义处理及边界回归。`inspect` 和 `relocatePointers` 当前实现均把任意 JSON 树当 Schema 树，难以通过增加几个关键字特判可靠修正。

标准语义核对参考：[JSON Schema Draft 2020-12 Core，Schema 位置、资源标识与引用](https://json-schema.org/draft/2020-12/json-schema-core)。普通属性名及 `const` 等实例值不是对应 Schema 关键字；`$id` 定义资源基址，包装时需要保持其引用语义。

## 可复现证据

临时探针：`.runtime/ContractReviewProbe.java`；输出：`.runtime/contract-review-probe.log`；依赖路径取自现有 Surefire 报告，写入 `.runtime/contract-review-classpath.txt`。

在工程根目录执行：

```powershell
$cp = (Get-Content -Raw '.runtime/contract-review-classpath.txt').Trim()
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe' `
  '-Xmx512m' '-Dfile.encoding=UTF-8' '-cp' $cp '.runtime/ContractReviewProbe.java'
```

关键实际输出：

```text
baseline-tests-passed=12
external-http-ref=EXTERNAL_SCHEMA_REF($ref,EXTERNAL_SCHEMA_REF)
http-id-with-local-ref=ACCEPT
schema-network-connections=0
business-property-$ref=EXTERNAL_SCHEMA_REF($ref,EXTERNAL_SCHEMA_REF)
const-original=ACCEPT
const-wrapped-schema={"const":{"$ref":"#/properties/data/literal"}}
const-wrapped-same-data=SCHEMA_VALIDATION_FAILED(...)
root-id-original=ACCEPT
root-id-preview=NESTED_SCHEMA_ID($id,NESTED_SCHEMA_ID)
dangling-validateSchema=ACCEPT
dangling-preview=ACCEPT
dangling-formal-import=ACCEPT
dangling-runtime-validation=INVALID_SCHEMA(schema,INVALID_SCHEMA)
weak-envelope-import=ACCEPT
weak-envelope-missing-meta=ACCEPT
non-string-filter-identifiers=ACCEPT
non-string-principal-attribute=ACCEPT
```

运行上限为 JVM `-Xmx512m`；未运行 Maven、未编辑业务文件、未提交 Git、未改动相邻工程。

## 2026-10-20 第二轮独立复审

本节为修复后的最新结论，前文保留首轮发现及当时证据。已重新读取全部契约核源码、新增 `ContractReviewRegressionTest`、修复报告及 `onto-contract/target/review-final.log`。

**符合性结论：原 5 项发现均已关闭；新增 1 项非推进递归导致栈溢出的 P2 问题仍需修复，因此纯核暂不予完整通过。** 修复后实际语义、错误分类与回归覆盖明显改善，没有发现“正式化重新生成业务内容”或外部引用访问网络的新反例。

| 原发现 | 本轮独立确认 | 状态 |
| --- | --- | --- |
| 1. 字面量/属性名被解释为 Schema | `const` 原值和包装后值一致；合法 `$ref` 属性名通过；遍历器仅进入 Schema 位置，实例与注释内容不解释 | 关闭 |
| 2. 悬空引用漏检 | 直接、未使用 `$defs` 内的悬空引用均在 preview 前报 `INVALID_SCHEMA`；外部引用继续报 `EXTERNAL_SCHEMA_REF` | 关闭 |
| 3. 弱信封被导入 | 删除 `meta` 必填项现报 `INVALID_OUTPUT_ENVELOPE`；新增 17 个信封变弱反例全部通过 | 关闭 |
| 4. 根 `$id` 包装失败 | URN/HTTP/相对根标识、嵌套资源及锚点均有实际实例正反例；自带 `$id` 的资源保持作用域 | 关闭 |
| 5. 守卫标识符宽松转换 | 布尔、数值、数组、对象、null 表名均报 `GUARD_ATTR_UNRESOLVED`；合法字符串绑定保留 | 关闭 |

本轮补充验证了没有根 `$id` 的命名锚点递归：沿 `child` 下降的合法树数据通过，错误子节点值报 `SCHEMA_VALIDATION_FAILED`。正式化前后移除 `endpoint` 和 `ontology_meta` 后，其余 JSON 完全相等。

### 新发现 6. [P2] 不推进实例位置的引用循环会触发 StackOverflowError

定位：`onto-contract/src/main/java/cn/ontorepo/contract/JsonSchemaAssertions.java:57–64`；门禁遗漏位置为同文件 `41–43`。

最小 Schema：

```json
{"$ref":"#"}
```

实际结果：

```text
direct-cycle-schema=ACCEPT
direct-cycle-output-preview=ACCEPT
direct-cycle-instance=StackOverflowError
direct-cycle-input-preview=StackOverflowError
```

`SchemaReferences` 证明引用目标存在，标准库初始化也成功，但没有防止校验对同一实例位置反复进入同一 Schema。将该 Schema 用作输出时，可以生成预览；用作输入并提供参数示例时，`preview` 直接抛出 `StackOverflowError`，绕过 `ContractProblem` 失败边界。这里没有大输入：Schema 仅 12 字节，实例 `{}`。增大线程栈只是延后失败。

应阻止不推进实例位置的引用/组合循环，或在执行时按 Schema 与实例位置识别重复执行状态，返回稳定的契约错误；同时保留沿对象属性/数组项下降的正常递归。不要把“所有递归引用一律禁止”作为替代修复。

证据：`.runtime/ContractRereviewProbe.java` 的 `cycle` 模式、`.runtime/contract-rereview-cycle.log`。独立进程使用 `-Xmx512m -Xss512k`，明确捕获 `Throwable` 仅为探针记录结果；业务实现没有修改。

### 本轮验证与质量结论

- 2026-10-20 21:43:19 +08:00，重新执行独立模块 `mvn -B -o -f onto-contract/pom.xml '-DargLine=-Xmx512m' test`；Maven 同样限制 `-Xmx512m`。63 条测试，0 失败/错误/跳过，`BUILD SUCCESS`，包含原 12 条与新增 51 条。
- 日志：`.runtime/contract-rereview-maven.log`；补充探针与输出：`.runtime/ContractRereviewProbe.java`、`.runtime/contract-rereview-probe.log`、`.runtime/contract-rereview-cycle.log`。
- `SchemaTraversal`、资源/锚点索引、加载器限制、显式信封检查与严格守卫字段读取的职责划分合理。回归测试直接覆盖公开 API 及真实 Schema 实例，修复没有放宽原有约束。
- 当前质量阻断仅为上述实际复现的循环栈溢出；正常递归、引用编码、资源作用域不能因修复而退化。本轮仍只评价纯核，不代表运行期入口或 P2/P4 完整验收。
- 未编辑业务文件、未提交 Git、未运行完整 reactor、未修改相邻工程。

## 2026-10-20 第三轮：发现 6 修复复审

**发现 6 关闭。此前发现 1–5 的关闭状态保持；纯契约核在本次需求符合性与代码质量审查范围内通过。** 该结论限于纯核，不能代替管理 API、冻结门禁、运行期三入口或 P2/P4 完整验收。

本轮只复核 `SchemaReferences.java` 的循环检测修复、新增 `SchemaRecursionTest.java`、`c4-contract-report.md` 最新记录，以及 `review-cycle-final.log` / `review-cycle-probe.log`。没有重复运行已有完整套件；仅为跨资源动态重绑定的正常递归补充了一个独立探针。

### 修复符合性

- 引用目标解析与非推进循环检测都在标准库编译之前执行。`$ref` / `$dynamicRef` 以及同实例位置的组合、条件、依赖关键字构成检测图；对象属性、数组项等下降到子实例的边不加入非推进图，保留正常递归。
- 拓扑消除使用队列迭代执行，循环检测本身不沿引用链递归调用，也未以捕获 `Error` / `Throwable` 掩盖原问题。失败返回稳定的 `INVALID_SCHEMA` / `NON_ADVANCING_SCHEMA_CYCLE`。
- 动态引用先核对初始目标是否为同名动态锚点，再通过虚拟分派节点纳入可能重绑定的锚点目标。新增测试覆盖跨资源重绑定导致的同位置循环；独立补充探针验证正常下降时仍按外层动态作用域绑定。
- 原最小探针的四个入口现在全部返回 `INVALID_SCHEMA`：Schema 校验、输出 preview、实例校验和输入 preview。原 `StackOverflowError` 反例已经消除。

### 证据核对

已核对完整构建日志：**2026-10-20 21:55:45 +08:00，89 条测试，0 失败、0 错误、0 跳过，BUILD SUCCESS**。由原 12 条、首轮新增 51 条和本轮新增 26 条组成。本轮 17 个循环用例分别检查七个公开契约边界；9 个正常递归用例验证输入参数示例、业务数据、包装后信封及错误叶子类型。该 89 条结果来自本轮已完成的独立模块构建日志，本次复审未重复执行。

日志 `onto-contract/target/review-cycle-probe.log` 核对结果：

```text
direct-cycle-schema=INVALID_SCHEMA
direct-cycle-output-preview=INVALID_SCHEMA
direct-cycle-instance=INVALID_SCHEMA
direct-cycle-input-preview=INVALID_SCHEMA
```

独立补充探针 `.runtime/SchemaDynamicRereviewProbe.java` 设置外层资源动态锚点 `node`，子属性进入另一 `$id` 资源，该资源的 `$dynamicRef` 初始目标为本地同名锚点。外层要求 `value`，本地缺省目标要求 `generic`；如此可以区分真正的动态重绑定与错误地只执行本地目标。

在 `-Xmx512m -Xss512k` 下的实际结果（`.runtime/schema-dynamic-rereview-probe.log`）：

```text
dynamic-rebinding-schema=ACCEPT
dynamic-rebinding-valid=ACCEPT
dynamic-rebinding-invalid=SCHEMA_VALIDATION_FAILED
dynamic-rebinding-wrapped-valid=ACCEPT
dynamic-rebinding-wrapped-invalid=SCHEMA_VALIDATION_FAILED
```

合法多层树按外层 `value` 约束通过；只有 `generic` 而缺 `value` 的错误叶子，在原业务 Schema 和生成信封中均拒绝。没有把正常跨资源动态递归误当成非推进循环。

### 质量结论

修复位于现有引用解析边界内，公共 API、标准校验库、正式化方式和原有守卫约束均保持。检测阶段和实例校验阶段职责清楚，回归包含失败入口与正常递归正反例。**本轮未发现新的可触发缺陷，发现 6 无待办项，可交回主流程继续集成。**

本轮仅修改审查报告并添加临时探针；未改业务文件、未提交 Git、未运行 Maven 或完整 reactor、未修改相邻工程。
