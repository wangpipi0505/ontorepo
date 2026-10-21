# P3 数据源、映射和建模候选接口

沿用 `/onto`、会话身份与统一信封。管理资源归属空间；所有草稿修改带 `spaceId/expectedDraftRev`，空间锁、权限、写入、修订与审计同事务。源配置只有 OWNER 可写，映射与抽取确认允许 EDITOR，VIEWER 只读。任何响应、审计、异常均不能带连接凭据或完整连接串。

## 数据源与元数据

- `GET /datasources?spaceId`：`[{id,spaceId,code,name,kind,dialect,accessScope:string[],status,lastTestAt}]`。
- `POST /datasources`、`PUT /datasources/{id}`：`{spaceId,expectedDraftRev,code,name,kind,connection:object|null,accessScope:string[]}`；更新的 connection=null 表示保留已加密配置，首次创建必须提供。返回更新空间。类型九选一：RELATIONAL/WAREHOUSE/LAKE/FILE/HTTP/DOCUMENT/KEY_VALUE/WIDE_COLUMN/GRAPH。dialect 从 connection 提取为非敏感字段；不可返回 connection。
- `DELETE /datasources/{id}?spaceId&expectedDraftRev`：引用中的源拒绝删除，返回影响对象；历史候选源快照不改。
- `POST /datasources/{id}/probe`：`{spaceId}`；OWNER 才可触发网络探测。结果 `{stages:[{stage:"CONNECTIVITY|AUTHENTICATION|SCOPE",status:"PASSED|FAILED|NOT_RUN",code,message}],testedAt}`。前段失败后不得伪造后段通过，安全错误消息无凭据。
- `POST /datasources/{id}/sync`：`{spaceId,expectedDraftRev}`；OWNER 发起元数据同步并递增修订。返回 `{space,snapshot:{id,sha256,capturedAt,resources},diff:{added,removed,changed,affectedMappings}}`。相同内容可复用摘要，快照记录不可变，diff 使用上一快照，受影响映射明确定位。
- `GET /datasources/{id}/snapshots?spaceId` 返回摘要列表；`GET /datasources/{id}/snapshots/{snapshotId}?spaceId` 返回同源不可变快照。资源结构 `{name,fields:[{name,type,nullable}],primaryKey:string[],relations:[],indexes:[]}`；额外能力字段可增加，不能删既定字段。
- `GET /datasources/capabilities?spaceId`：每类协议的已实现查询、限制、实际本地验收/待外部验收状态，不能将“代码支持”写成“实例已连接”。

连接配置 AES-256-GCM 加密，主密钥来自本工程环境配置，缺密钥拒绝启动该能力。冻结源快照包含加密配置及 accessScope，不含明文；执行只从冻结配置解密，不回读当前草稿源配置。源范围在元数据、计划、实际执行均检查。

## 管理文件

`POST /data-files?spaceId&expectedDraftRev` 使用 multipart 文件（字段名 `file`），OWNER 上传，最大 8 MiB，允许 CSV/JSON/XLSX/PDF/DOCX/TXT；拒绝可执行内容及扩展名不匹配。保存到本工程管理目录，以服务端 UUID 命名，返回 `{space,file:{id,name,mediaType,size,sha256,resource}}`，resource 是服务端分配的安全相对资源名。不接受客户端绝对路径作为上传地址；源配置引用已授权 fileId，由服务器解析管理路径。文件名用于展示/证据，不能参与目标路径拼接。PDF/DOCX 仅用于建模抽取。`GET /data-files?spaceId` 返回当前空间文件目录（上述安全字段），便于后续选取；FILE连接 `{dialect:"csv|json|excel",fileId}`，accessScope只能包含该文件的resource，root/path由服务器生成，不接受用户覆盖。

## 三层映射

- `GET /mappings?spaceId` -> `{objects:[],properties:[],relations:[]}`。
- `POST /mappings/{objects|properties|relations}` 与 `PUT/DELETE /mappings/{objects|properties|relations}/{id}`。写入返回更新空间，删除请求修订放 query；同空间、同源/快照、字段与本体引用必须校验。
- 对象映射字段 `{id,datasourceId,sourceResource,targetClassId,objectKeyExpr,filter:object|null,metaSnapshotId}`。标识模板仅允许 `${field}` 与固定分隔符；重复/空对象标识必须报错。
- 对象标识唯一性不得仅在已截断结果中检查。仅在 PostgreSQL 单列原生标量真实主键、模板只引用这一变量且实际来源证据与冻结主键证明一致时，可使用主键唯一性证明；其他模板在同一授权及业务过滤匹配集中先核验再截断。达到 500 行完整性预算仍无法证明穷尽时返回 `OBJECT_KEY_CHECK_INCOMPLETE`；复合模板不假设分隔符天然无碰撞。
- 属性映射字段 `{id,objectMappingId,sourceField,targetPropertyId,transform:object|null,required:boolean}`。transform 为 `{kind:"IDENTITY|TO_STRING|TO_NUMBER|TO_BOOLEAN|CODEBOOK",codebookId?:string}`，不执行任意表达式/脚本；类型转换失败返回定位错误。
- 关系映射字段 `{id,datasourceId,relationId,fromObjectMappingId,toObjectMappingId,fromKeyExpr,toKeyExpr,join:{fromField,toField},cardinalityCheck:boolean,metaSnapshotId}`。连接两端来自冻结资源字段，同源可翻译 JOIN，协议不支持时明确拒绝。不能将客户端 raw SQL 当 src_query。
- 启用 `cardinalityCheck` 后，对同一授权范围及业务过滤匹配集先做基数检查，再截断到操作结果上限。当前完整性预算为 500 行；匹配集达到 500 行时返回 `CARDINALITY_CHECK_INCOMPLETE`，要求缩小业务范围，不把不完整检查记为通过。检查成功后只返回业务上限内的结果及对应 SOURCE_RECORD 证据。
- `GET /mappings/validate?spaceId` -> `{valid,checks:[{kind:"CLASS_OBJECT|REQUIRED_PROPERTY|RELATION_KEYS|REACHABLE_REFERENCE",passed,issues:[]}],coverage:{mapped,pending,unmappable},affectedOperations:[]}`。覆盖度分母为当前活动模型/可映射字段，展示明确计数，不用空集合伪造全部完成。
- `GET /mappings/impact?spaceId&kind&id` -> `{operations:[{id,code,name}],issues:[]}`，用于变更前提示。查询参数 `kind` 固定为路径类别复数 `objects|properties|relations`；响应中映射实体类别仍用 `OBJECT|PROPERTY|RELATION`。
- `GET /data-catalog?spaceId` -> 当前源、快照、映射计数与相关模型/操作，属于读取视图。

所有三层定义、原元数据快照与源加密配置进入候选，映射快照摘要随执行 evidence 保存。后续改稿不得改变旧候选查询。

## 操作数据查询

保留包内模型元查询，新增独立 `GET /operations/{id}/data-query?spaceId` 和 `PUT /operations/{id}/data-query`。请求 `{spaceId,expectedDraftRev,dataQuery:null|{mode:"OBJECT|RELATION",objectMappingId,relationMappingId?:string,propertyIds:string[],filters:[{propertyId,operator:"EQ|IN|GT|GE|LT|LE",inputParam:string}]}}`；null 切回 MODEL。返回更新空间，读取 `{operationId,dataQuery}`。实例操作必须有完整助手声明，不能使用 MODEL_ONLY 兼容绕过守卫。

`propertyIds` 与过滤项 `propertyId` 均指本体属性ID。OBJECT模式从选定对象映射的属性映射中选择；RELATION模式允许关系from/to两端对象的已映射属性，`objectMappingId` 必须是关系from端。两端使用相同本体属性ID导致指向不唯一时，配置须明确拒绝并提示拆分操作，不能任选一端。界面展示所属对象/字段以便区分，“任务使用算法”必须可投影算法端的标准编码。

过滤语义以本体属性转换后的值为准，不能将数值输入直接与未转换的源文本比较。PostgreSQL使用受限的参数化转换表达式，文件/HTTP/Parquet/Redis按其受限计划先检查授权，再做转换与业务过滤，最后应用结果上限；CODEBOOK只读取冻结码表，非法转换或码表未命中明确失败。ClickHouse/MongoDB/Cassandra/Neo4j的当前受限模板不支持转换属性过滤，须在连接前以`TRANSFORM_FILTER_UNSUPPORTED`明确拒绝，仍可按原有范围使用IDENTITY属性过滤和转换属性投影。该限制应在能力说明和验收报告中列出，不能静默按源值比较或在已截断结果上补过滤。

转换查询在协议预算内验证所有已授权行的全部过滤转换；前一个业务谓词不匹配、空 IN 或已达到结果 LIMIT 都不能跳过坏值验证。IDENTITY 过滤保持原值语义，不附加本体 rangeType；实际投影输出仍由冻结本体类型严格校验。内核显式收到 IDENTITY + rangeType 的过滤组合时，九类协议统一在连接前返回 `INVALID_TRANSFORM`。PostgreSQL numeric/decimal 的 TO_STRING/CODEBOOK 当前明确拒绝 `TRANSFORM_SOURCE_TYPE_UNSUPPORTED`，避免数据库文本与 Java BigDecimal 科学计数文本不等价；其他受限类型和数字语法边界以 `onto-data/README.md` 为准。

执行解析冻结映射，生成 QuerySpec 及参数化 SQL/受限协议计划。过滤值来自经 Schema 校验的 inputParam，标识符只来自冻结元数据。查询先应用策略/入口/空间交集、必需标签非空和源 accessScope，再应用 LIMIT，不能取全量结果后作授权过滤。关系返回真实对象身份和关系数据，业务结构由冻结 outputSchema 校验；底层适配器不能把所有值强转字符串。

结果必须包含可定位的 SOURCE_RECORD、META_SNAPSHOT、MAPPING_SNAPSHOT、ONTOLOGY_VERSION 证据；缺守卫绑定时，三入口均在任何数据源访问前拒绝。

### 关系查询的多表守卫

P3扩展已有空间守卫配置，以便同一个授权属性覆盖JOIN各表。保留旧的单个 `filter_binding`，新增互斥的 `filter_bindings:[{table,column,operator:"IN|EQ"}]`（1～16项）；同一守卫禁止同时传单个与数组，数组内table不得重复。table可以是完整 `schema.table` 资源标识，column仅为该资源内的单列名，不接受SQL表达式。定义仍由OWNER维护、完整冻结并进入CONTRACT检查。

历史字段名 `table` 在九类适配中表示协议的原始资源标识，也包括受管 `UUID.csv`、HTTP `/items`、Parquet `prefix/object.parquet` 等。通用结构层仅要求非空、长度不超过512、无控制字符；不得将全部协议套用SQL表名正则。绑定的实际可用性由服务端与冻结元数据资源逐项精确匹配，并按该协议的accessScope规则核验覆盖（例如S3为批准前缀）；字段也须属于该资源。关系库查询仍由QueryPlanner独立校验SQL标识符并参数化；该字段不能作为SQL片段直接插值。单资源非关系适配器收到已核验的单列绑定，多表SQL查询才使用 `attr@resource` 键。

例如region配置tasks.region和algorithms.region两个绑定，主体dataScope仍为同一 `{attr:"region",in:["A"]}`。翻译器逐表核对冻结元数据与绑定，生成 `region@resource` 适配器键，对关联两端都执行同一有效范围。不得仅凭同名列猜测关联表已授权；每个参与资源缺少必需绑定即在源访问前拒绝。只有主体属性检查而无源过滤绑定，不能用于证明实例行标签过滤。P2纯模型操作对两种源过滤配置均维持 `GUARD_BINDING_NOT_APPLICABLE`。

## 三来源建模候选

- `POST /suggestions/extract`：`{spaceId,expectedDraftRev,sourceKind:"TABLE|FILE|DOCUMENT",datasourceId?:string,metaSnapshotId?:string,resource?:string,fileId?:string}`。表来自已授权快照；文件/文档来自该空间管理文件。返回 `{space,batchId,count}`，仅生成 PENDING 候选，不直接写模型。
- `GET /suggestions?spaceId&batchId?` -> `[{id,batchId,sourceKind,kind:"CLASS|PROPERTY|RELATION",payload,confidence,location,status}]`。location 含源表字段/文件工作表列或 JSON 路径/文档页段与原文摘录。confidence 用规则证据生成，不能虚构模型概率。
- `POST /suggestions/decide`：`{spaceId,expectedDraftRev,decisions:[{id,decision:"ACCEPT|REJECT",payload?:object}]}`。批量以服务端稳定 id 映射候选类/属性/关系，允许确认前修正名称和关联；整批验证后原子落草稿。重复决策拒绝，不重复建模，REJECT 留审计。跨空间/陈旧修订均拒绝。

三来源各交付一份真实样例：表结构、CSV/JSON/XLSX 文件、PDF/DOCX/TXT 文档。规则式术语/句式抽取即可，不依赖 LLM。文件解析限制解压总量、条目数量与处理时限，不执行宏/外部实体/嵌入脚本。

## 验收范围

九类代码均实现边界内接入、元数据、查询。真实本地验证 PostgreSQL、MinIO Parquet、CSV/JSON/XLSX、受控 HTTP；用户已确认其余 ClickHouse/MongoDB/Redis/Cassandra/Neo4j 保留待外部实例验收。另验证任务使用算法全例、A/B/null 标签、四项映射完整性、冻结隔离、元数据 diff/影响分析及三来源确认/来源证据。

## 展示结构补充（前后端固定）

- capabilities 返回数组：`[{kind,dialect,implemented:boolean,verifiedLocally:boolean,capabilities:string[],limits:string[],verificationNote:string}]`。本地验证状态表示工程验收证据，实际登记源是否可达仍以该源 probe 为准。
- catalog 返回 `{datasources:[源安全概要加latestSnapshotId:null|string,mappingCount:number,classIds:string[],operationIds:string[]],bindings:[{datasourceId,objectMappingId,resource,classId,className,propertyCount,relationCount,operationIds:string[]}]}`。
- 元数据 diff 的 added/removed/changed 元素为 `{path,kind:"RESOURCE|FIELD|PRIMARY_KEY|RELATION|INDEX",before?:unknown,after?:unknown}`；affectedMappings 为 `[{id,kind:"OBJECT|PROPERTY|RELATION",reason,operationIds:string[]}]`。path 是展示用资源/字段路径，不能被当作执行SQL。
- 完整性 issues 为 `[{code,message,entityId?:string,field?:string}]`；coverage 的 mapped/pending/unmappable 均为非负整数，返回 `coverageBasis:string` 说明分母。affectedOperations 为 `[{id,code,name}]`。
- 建模候选 payload：CLASS `{name,description,parentId:null|string,parentSuggestionId?:string}`；PROPERTY `{name,description,rangeType:"STRING|INTEGER|DECIMAL|BOOLEAN|DATE",required,domainClassId?:string,domainSuggestionId?:string}`；RELATION `{name,fromClassId?:string,fromSuggestionId?:string,toClassId?:string,toSuggestionId?:string,cardinality:"ONE_TO_ONE|ONE_TO_MANY|MANY_TO_ONE|MANY_TO_MANY",transitive:boolean,symmetric:boolean}`。关系基数与现有手工关系四枚举一致，不能丢失任务外键指向算法的MANY_TO_ONE方向。同一关联的实体Id与suggestionId只能二选一；suggestionId必须来自同批且同时或已接受的候选。确认可修正为已有同空间实体Id，依赖未确认时返回定位错误。
- location 至少含 `sourceName:string`，其他字段按来源：TABLE `resource/field/sample/foreignKey`；FILE `sheet/column/jsonPath/sample`；DOCUMENT `page/paragraph/excerpt`。不产生不存在的页码，TXT按段落；原文摘录限制长度。
- FILE连接如上；其余连接字段与方言固定于 `onto-data/README.md`。RELATIONAL=postgresql；WAREHOUSE=clickhouse；LAKE=s3-parquet；HTTP=json；DOCUMENT=mongodb；KEY_VALUE=redis；WIDE_COLUMN=cassandra；GRAPH=neo4j。任何凭据字段只能输入，不在安全概要返回。
