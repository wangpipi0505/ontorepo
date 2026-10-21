# C1 后端独立复审

日期：2026-10-20。最新结论：**初审 5 项及 R2 追加重导入问题已关闭，C1 本次后端实现范围通过。**原始发现保留供追溯，最终验证及适用范围见文末。

依据：详设 70 v2.6 §8.1、§8.2、§8.9、§8.11，开发计划 v1.6 的 OM-P1-06～09，`contracts/p1-extension.md`，以及相对首片基线的 `c1-backend-review-package.txt`。同时核对实施报告及现有回归测试。已接受用户确定的逆/对称手工编辑例外与仅 OWNER、候选摘要绑定的超范围豁免规则。

本报告中的 P1/P2 表示缺陷优先级，不表示开发期次。正式发布、外部实例查询、映射执行、P2～P4 未开始能力不作为本次缺陷。复杂 OWL 不一定需要进入可编辑投影，但导入后的未投影语义必须保留；不能通过改写或删除原公理获得一致性结论。

## 发现

### C1-R1 [P1] 导入关系的对称性编辑没有同步至实际 RDF

位置：[RdfCodec.java:139](G:/207/项目/算法重组工具/代码/ontorepo/onto-model/src/main/java/cn/ontorepo/model/infra/RdfCodec.java:139)，第 139～142 行；新建关系的对应处理位于第 155～156 行。

触发：导入带 domain/range 的普通 `owl:ObjectProperty`，再通过关系编辑保存 `symmetric=true`。该实体保留 `sourceIri`，因此执行导入分支；这里只同步 `TransitiveProperty` 随后 `continue`，跳过对称公理。导出及候选检查使用的图仍没有 `owl:SymmetricProperty`。反向操作同样失败：导入对称关系后将 `symmetric=false`，原图中的对称公理仍存在。

实际反例输出：

```text
R1 imported relation symmetric=true emitted=false
R1 imported relation symmetric=false still present=true
```

影响：用户保存的关系语义与候选执行/推理图不一致；设置对称性后，ELK 可能检查缺失该公理的图，违背用户明确要求的“完整保存语义并标记超 EL”。修复应对导入投影同步已编辑的对称性，并覆盖导入关系增加/维护逆边的对应路径，同时保留其他未投影公理。需要分别验证导入后的开启、关闭以及候选检查，不仅验证手工新建关系。

### C1-R2 [P1] 未编辑的导入数据类型会被静默改写，往返图不再同构

位置：[RdfCodec.java:110](G:/207/项目/算法重组工具/代码/ontorepo/onto-model/src/main/java/cn/ontorepo/model/infra/RdfCodec.java:110)，第 110～112 行；来源为 [ImportService.java:187](G:/207/项目/算法重组工具/代码/ontorepo/onto-model/src/main/java/cn/ontorepo/model/application/ImportService.java:187)，第 187～193 行。

触发：导入 `ex:p a owl:DatatypeProperty; rdfs:domain ex:A; rdfs:range xsd:dateTime`，不进行任何编辑，立即导出。`rangeType()` 将未列出的类型统一投影为 `STRING`；随后 RDF 投影把原始 `xsd:dateTime` 视为与投影不一致，直接替换成 `xsd:string`。自定义数据类型及其他未识别 XSD 类型也会走该分支。

实际反例输出：

```text
R2 untouched dateTime roundtrip isomorphic=false
R2 exported range=http://www.w3.org/2001/XMLSchema#string
```

影响：导入即导出的核心验收失败，候选检查也针对被改写的语义运行。修复需要区分原始类型与用户明确修改的投影字段；不支持编辑的类型应保留原 RDF 并明确标记或限制相关编辑，不能把默认投影值当作用户修改。回归须包含 `xsd:dateTime`、自定义数据类型以及未编辑往返图同构。

### C1-R3 [P2] 大写 UUID 的策略绑定通过校验后会在运行时失效

位置：[StrategyService.java:66](G:/207/项目/算法重组工具/代码/ontorepo/onto-operation/src/main/java/cn/ontorepo/operation/application/StrategyService.java:66)，新增第 66 行、更新第 73 行；执行匹配位于 [FrozenExecutor.java:34](G:/207/项目/算法重组工具/代码/ontorepo/onto-trace/src/main/java/cn/ontorepo/trace/application/FrozenExecutor.java:34)，第 34～36 行。

触发：操作上限为 50，策略 `maxRows=1,resultLimit=1`，仅通过 `boundOps` 绑定操作。将绑定 UUID 以合法大写形式提交。保存时 `Input.uuid()` 和数据库归属校验接受该 UUID，但 JSONB 写入原始 `ops`，保留大写；冻结包中的操作 ID 为小写。执行器以大小写敏感的 `List.contains()` 判断绑定，整条策略被跳过。

实际反例使用同一个 SELECT 返回 3 行：

```text
R3 uppercase UUID accepted=true
R3 uppercase binding row count=3
R3 canonical binding row count=1
```

影响：已经保存的策略资源上限不生效；若策略带读取范围，该匹配路径也会直接跳过范围处理。修复应在保存边界规范化 UUID 集合，并对旧候选采用等价 UUID 匹配，不能改写旧候选字节/摘要。应同时核查其他 JSON 引用集合的保存和匹配口径。

### C1-R4 [P2] DATE 枚举被生成为字符串 RDF 项，合法日期实例被 SHACL 拒绝

位置：[ShaclSchema.java:91](G:/207/项目/算法重组工具/代码/ontorepo/onto-model/src/main/java/cn/ontorepo/model/infra/ShaclSchema.java:91)，第 91～94 行。

触发：DATE 属性设置 `enumValues:["2026-10-20"]`，实际实例值为 `"2026-10-20"^^xsd:date`。保存校验接受 ISO 日期字符串，但这里调用 `createTypedLiteral(Object)` 按 Java 字符串生成 `xsd:string`，没有使用已计算的属性 datatype。`sh:in` 按 RDF 项匹配，因此合法日期不属于该枚举。

真实 Jena SHACL 反例输出：

```text
R4 DATE enum valid value conforms=false
sourceConstraintComponent: sh:InConstraintComponent
message: RDF term "2026-10-20"^^xsd:date not in expected values
```

影响：合法数据导致候选 CONFLICT 检查失败，导出的 shape 也错误。应按属性实际 RDF 数据类型构造枚举字面量，并测试合法日期通过、非枚举日期失败。此次只验证 DATE 缺陷，不将其他标量类型的问题作无证据扩展。

### C1-R5 [P2] 草稿冲突检查未加载策略，误报所有显式策略引用

位置：[ConflictService.java:26](G:/207/项目/算法重组工具/代码/ontorepo/onto-operation/src/main/java/cn/ontorepo/operation/application/ConflictService.java:26)，第 26～33 行；实际引用校验位于第 116～122 行。

触发：正常创建策略，并将同空间操作的 `strategyRef` 设为该策略，再调用 `GET /spaces/{id}/conflicts`。`draft()` 只读取 class/property/rule/constraint/operation，不读取 strategy；`inspect()` 因而创建空的 `strategyIds`，对每个非空 `strategyRef` 返回 `REFERENCE: 操作策略引用缺失`。

此项由确定性的完整调用链确认，未冒称已运行 HTTP 反例。候选冻结包含 strategies，因此相同模型在草稿检查与候选检查中还会出现不同结果。修复应补齐草稿检查所需的策略快照，并回归验证正常策略引用无错误、真实缺失引用仍报错。

## 验证与范围

- R1～R4 使用现有构建产物运行独立 Java 源文件反例；调用实际 `RdfCodec`、`FrozenExecutor`、`ShaclChecker`，后者运行真实 Jena SHACL。反例代码为 `.runtime/C1ReviewProbe.java`，完整输出为 `.runtime/c1-review-probe.log`。
- 反例只构造内存测试输入，不修改业务数据库或候选；未执行 Maven、未重打包、未重启服务、未改业务代码、未提交 Git、未访问相邻工程。
- 已审查空间写事务/行锁、同空间引用、候选摘要与冻结隔离、OWNER 豁免、执行失败留痕、ELK 不完整/超时/异常分支及确定性测试比较逻辑。本次未在这些其他路径新增已确认问题，也不因此声称穷尽所有边界。
- 实施报告中的 47 条回归通过是已有验证记录，本次未重新执行；本次新增独立证据是上述四组运行反例和一项静态调用链缺陷。

反例重跑命令（在本工程目录执行，凭据仅进入进程环境，不打印）：

```powershell
. deploy/Environment.ps1
$null = Import-OntoEnvironment
Set-OntoJava
[xml]$reviewReport = Get-Content -LiteralPath 'onto-designer-api/target/surefire-reports/TEST-cn.ontorepo.P1ExtensionTest.xml' -Raw
$reviewCp = ($reviewReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$reviewCp = ((Get-ChildItem -Directory -Filter 'onto-*' | ForEach-Object { Join-Path $_.FullName 'target/classes' }) -join ';') + ';' + $reviewCp
& java '-Xmx512m' '--class-path' $reviewCp '.runtime/C1ReviewProbe.java' 2>&1 | Tee-Object -FilePath '.runtime/c1-review-probe.log'
```

## 修复复核与关闭（2026-10-20 22:15）

首次修复经过 55 条完整 Maven verify，独立复审确认 R1/R3/R4/R5 与真实反例及回归一致。R2 的首次修复保留新导入未知 datatype 原图，但复审另发现：同一 IRI 先建 INTEGER 并被规则引用，再导入 dateTime 时未知投影跳过导致引用保护失效。原反例 `.runtime/C1ReimportReviewProbe.java` 在真实测试 PG 的回滚事务中得到 PREVIEW 无冲突、apply 成功、双 range；该剩余问题未被当作关闭。

追加修复仅涉及 `ImportService` 与 `C1ReviewRegressionTest`。主执行者独立核对源码：`validatePropertyReplacement` 在预览的未知类型跳过之前执行；apply 在空间锁内、任何写入之前对原图全部数据属性重算，不能相信旧版预览。已引用属性先由 `PropertyPolicy.prevent` 拒绝；无引用的已有基础属性若将变为不可投影结构则明确冲突；全新未知 IRI 仍保留原图。Db 对 UUID 的字符串映射与新旧 domain 对比一致。

本轮两条回归先实际失败，再覆盖 dateTime/自定义 datatype、旧预览空 conflicts、修订/原图/导入文档/审计原子不变。因开发期间共享测试库夹具干扰和 P3 临时测试编译状态，未将受干扰 Maven 运行计为成功；最终从当前源独立 javac 后通过 JUnit Platform 运行指定 28 条真实 PG 回归，全部成功、无跳过，22:10:25 正常关闭连接池。源码/字节摘要及 CodeSource 证据在 `.runtime/c1-final-compilation-hashes.json` 和实施报告中。

原重导入探针源码未改，针对独立编译类与复制的 C1 模块 JAR 再运行：预览报告规则引用冲突、apply=REJECTED/IMPORT_CONFLICT，事务回滚，见 `.runtime/c1-review-reimport-green.log`。以上关闭 R2 追加问题。此次没有重新打包完整应用；最终运行 JAR 与 P2 集成后还需统一打包和 HTTP 回归，不据此宣称 P2～P4 或整体验收完成。
