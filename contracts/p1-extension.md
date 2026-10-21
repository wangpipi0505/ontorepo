# P1 剩余能力接口约定

沿用 `/onto`、Bearer、统一 Envelope、UUID、Space 返回值、expectedDraftRev 与失败码。所有写请求包含 `spaceId, expectedDraftRev`；GET 列表采用 `?spaceId=`。创建/更新返回最新 Space，删除带 spaceId/expectedDraftRev。下列是原详设未规定 HTTP 细节处的实现约定，不改变原验收范围。

## 属性、码表、关系与约束

`OntProperty` 与写请求增加 `facets`，旧请求缺省为 `{}`：`{unitCodebookId?:UUID,unitCode?:string,codebookId?:UUID,defaultValue?:scalar,enumValues?:scalar[],minimum?:number,maximum?:number,primaryKey?:boolean}`。类型必须真实匹配，不自动把文本转换数值；默认值符合枚举/范围/码表；minimum≤maximum；单位必须引用同空间 UNIT 码表中的条目；主键必填、单类至多一个。修改/删除受约束/规则/映射引用属性须报告影响并阻断破坏性变化。

`GET/POST /codebooks`、`PUT/DELETE /codebooks/{id}`：
`Codebook={id,spaceId,code,name,kind:"UNIT"|"VALUE",entries:{code,label}[]}`。

`GET /properties/{id}/impact?spaceId=` → `{references:{kind,id,name}[]}`。
`GET /spaces/{id}/properties` → 当前空间全部 `OntProperty[]`，用于规则/约束字段选择。原 `/properties?spaceId=&classId=` 继续要求 classId，以保持原调用和缺参校验语义。

`GET/POST /relations`、`PUT/DELETE /relations/{id}`：
`Relation={id,spaceId,name,fromClassId,toClassId,cardinality:"ONE_TO_ONE"|"ONE_TO_MANY"|"MANY_TO_ONE"|"MANY_TO_MANY",inverseOf:UUID|null,transitive:boolean,symmetric:boolean,subsetExceeded:boolean,description}`。
写请求不允许伪造 inverseOf，使用 `inverseName?:string` 自动创建/维护反向条目；修改端点/基数同步逆边；删除关联逆边在同一事务；类端点必须同空间且不同。反向基数 ONE_TO_MANY↔MANY_TO_ONE，其余不变。
`subsetExceeded` 是服务端只读字段，不能写回。用户已确认逆/对称关系为编辑器的两项超 EL 例外：完整保存语义，默认阻断发布，仅 OWNER 可对候选摘要显式豁免。

`GET/POST /constraints`、`PUT/DELETE /constraints/{id}`：
`Constraint={id,spaceId,name,kind:"RANGE"|"CARDINALITY"|"MUTEX"|"DEPENDENCY",targetType:"CLASS"|"PROPERTY"|"RELATION",targetRef:UUID,expr:object}`。
RANGE 目标 PROPERTY，expr `{min:number,max:number}`；CARDINALITY 目标 RELATION，expr `{min:nonnegativeInteger,max:nonnegativeInteger}`；MUTEX 目标 CLASS，expr `{classIds:UUID[]}` 表示与这些类互斥；DEPENDENCY 目标 PROPERTY，expr `{requires:UUID[]}` 表示非空时依赖的同类属性。拒绝非法 kind/target、空集、反向范围、自依赖和跨空间引用。

## 规则与策略

`GET/POST /rules`、`PUT/DELETE /rules/{id}`：
`Rule={id,spaceId,name,applicableClassId,kind:"VALIDATION"|"SCORING",condition:string,action:object,scoring:object|null,severity:"WARN"|"ERROR"}`。
condition 采用详设白名单表达式 `${属性UUID}`、字面量、比较、逻辑、算术和五种内置函数，不使用 SpEL/MVEL/Groovy 任意执行。空条件按 true；解析/类型/引用需逐项校验。
VALIDATION action `{type:"REJECT"|"ACCEPT",message:string}`；SCORING scoring `{metrics:{propertyId,weight,bands:{min,max,score}[]}[],missingValue:"REJECT"|"ZERO",tieBreak:"STABLE_ID",forbiddenProperties:UUID[],explain:boolean}`，权重非负且总和为 1，分段无重叠，禁算字段不得成为指标。

`GET/POST /strategies`、`PUT/DELETE /strategies/{id}`：
`Strategy={id,spaceId,name,policy:{maxRows:number,timeoutMs:number,resultLimit:number,failureMode:"FAIL",readScope:object|null},boundOps:UUID[]}`。
策略与操作资源上限取最小值，不能由调用参数扩大。

`GET /spaces/{id}/conflicts` → `{errors:Conflict[],warnings:Conflict[]}`；
`Conflict={kind:"REFERENCE"|"TYPE"|"CONSTRAINT"|"LOGIC",message,entityIds:string[]}`。引用/类型/互斥矛盾 ERROR；有交集且不同结论 WARN。实现须真正进行条件求交/蕴含判定，不能仅比较字符串。

## 导入、用例与候选检查

`POST /spaces/{id}/imports/preview` `{kind:"TTL"|"OWL",content:string,expectedDraftRev}` →
`ImportPreview={id,status:"PREVIEW"|"FAILED",targetDraftRev,added:number,updated:number,conflicts:{line,message}[],unsupported:{line,construct,message}[],errors:{line,message}[]}`。
`POST /imports/{id}/apply` `{expectedDraftRev}` → Space。用户确认前不得写草稿；语法错误不能应用；不支持构造保留原文进入冻结内容，明确标记，不能静默丢弃。
`GET /spaces/{id}/export` → `{content:string,format:"TTL"}`。导入即导出应满足 RDF 图同构，包含不支持构造。读取文件禁止外部 imports 网络解析。

`GET/POST /test-cases`、`PUT/DELETE /test-cases/{id}`：
`TestCase={id,spaceId,name,question,operationId,input:object,expected:object,matchMode:"EXACT"|"CONTAINS",enabled:boolean}`。
`POST /candidates/{id}/tests` → `{candidateSha256,passed:boolean,results:{caseId,name,passed,executionId,message}[]}`。所有启用用例从候选冻结副本读取，执行入口 LOCAL_TEST；不得用新草稿用例测试旧候选。

`POST /candidates/{id}/checks` → `CandidateChecks={candidateSha256,eligible:boolean,checks:{kind,conclusion,reason,message,details:object}[]}`，初期覆盖 CONFLICT/CONSISTENCY/TEST，后续加 CONTRACT/REVIEW；不能把初期 eligible 当作正式发布资格。
`POST /candidates/{id}/waivers` `{kind:"SUBSET_EXCEEDED",reason:string}` → `{id,candidateSha256,kind,reason,approver}`。仅 OWNER、只接受真实 NOT_CHECKED(SUBSET_EXCEEDED)，当前草稿修订必须匹配，不能豁免 INCONSISTENT/TIMEOUT/ENGINE_ERROR。

操作兼容旧请求，扩展 kind 为四类、`strategyRef?:UUID`、`ruleIds?:UUID[]`；试执行增加 `input?:object`。OBJECT_QUERY/RELATION_QUERY 执行包内模型 SELECT；RULE_CHECK/SCORING 执行冻结规则和输入，结果保留现有 columns/rows（字符串显示）及步骤。真实实例关系查询留给 P3 翻译链，不能冒充已完成。
