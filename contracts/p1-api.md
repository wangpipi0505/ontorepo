# P1 首批接口契约

版本：p1.0，2026-10-20。此为领域本体工具内部管理接口；消费侧 skills-manifest 仍以详设 §8.8 为准。

## 公共约定

- API 前缀 `/onto`；所有 JSON 字段采用 camelCase。成功 HTTP 200，响应 `{code:"OK", message:"", data:T}`；错误 `{code:"错误码",message:"可读中文提示",data:null}`，HTTP 400/401/403/404/409/500。
- UUID 使用字符串；时间为 ISO-8601；列表返回数组。无记录返回空数组。
- 空间编码 1～80 字符（字母开头，字母/数字/下划线/短横线）；名称 1～200 字符；领域说明可空、最多 200 字符；命名空间 URI 最多 1000 字符。前后端使用相同长度边界。
- 登录 `POST /onto/auth/login`：`{username,password}` → `{token,expiresAt,user:{id,username,displayName}}`。其余接口使用 `Authorization: Bearer <token>`；令牌只保留在前端内存中，不写浏览器存储。`GET /onto/auth/me` → 用户；`POST /onto/auth/logout` → null。
- 所有草稿写入要求 `expectedDraftRev`。服务端加空间行锁后校验修订并递增，返回最新 `Space`；冲突 `DRAFT_REV_CONFLICT`，页面刷新后由用户重新编辑。
- `Space={id,code,name,namespaceUri,domain,status:"ACTIVE"|"ARCHIVED",draftRev,role:"OWNER"|"EDITOR"|"VIEWER",classCount,propertyCount,operationCount,createdAt}`。
- `OntClass={id,spaceId,name,parentId:string|null,description,deprecated:boolean}`；`OntProperty={id,spaceId,domainClassId,name,rangeType:"STRING"|"INTEGER"|"DECIMAL"|"BOOLEAN"|"DATE",required:boolean,description}`。
- `Operation={id,spaceId,code,name,kind:"OBJECT_QUERY",queryTemplate,maxRows,timeoutMs}`。首批仅模型元查询，不开放实例数据、关系查询、评分或规则执行。
- `Candidate={id,spaceId,draftRev,sha256,status:"TESTING",createdAt}`。这里只表示候选可试执行，不能用于正式版本消费。
- `Execution={id,traceId,subjectType:"CANDIDATE",subjectId,opCode,status:"SUCCEEDED"|"FAILED",errorCode:string|null,elapsedMs,createdAt,columns:string[],rows:Record<string,string|null>[],candidateSha256,steps:{kind,status,detail}[]}`。失败执行仍返回记录供页面展示；请求级授权/参数错误返回错误信封。

## 空间、对象与属性

| 方法 | 路径 | 请求 | data |
| --- | --- | --- | --- |
| GET | /spaces | 无 | Space[]，仅可见空间 |
| POST | /spaces | {code,name,namespaceUri,domain} | Space，创建者 OWNER |
| GET | /spaces/{id} | 无 | Space |
| PUT | /spaces/{id} | {name,domain,expectedDraftRev} | Space |
| POST | /spaces/{id}/archive | {expectedDraftRev} | Space；仅 OWNER |
| GET | /classes?spaceId={id} | 无 | OntClass[] |
| POST | /classes | {spaceId,name,parentId,description,deprecated,expectedDraftRev} | Space |
| PUT | /classes/{id} | 同创建请求 | Space |
| DELETE | /classes/{id}?spaceId={id}&expectedDraftRev={rev} | 无 | Space；有子类/属性时拒绝 |
| GET | /properties?spaceId={id}&classId={id} | 无 | OntProperty[] |
| POST | /properties | {spaceId,domainClassId,name,rangeType,required,description,expectedDraftRev} | Space |
| PUT | /properties/{id} | 同创建请求 | Space |
| DELETE | /properties/{id}?spaceId={id}&expectedDraftRev={rev} | 无 | Space |

同级对象名唯一、属性名在所属类中唯一；父类/所属类必须属于同一空间；禁止层级环。对象调整父级支持拖放，也提供可键盘操作的父级选择框。命名空间为绝对 HTTP(S) URI，结尾 `/` 或 `#`，空间创建后不可修改；示例 `https://example.org/ontology/task/`。该限制为首批工程约束，正式 URI 归属规范仍跟踪 OM-D2。

## 操作、候选与执行

| 方法 | 路径 | 请求 | data |
| --- | --- | --- | --- |
| GET | /operations?spaceId={id} | 无 | Operation[] |
| POST | /operations | {spaceId,code,name,queryTemplate,maxRows,timeoutMs,expectedDraftRev} | Space |
| PUT | /operations/{id} | 同创建请求 | Space |
| DELETE | /operations/{id}?spaceId={id}&expectedDraftRev={rev} | 无 | Space |
| GET | /spaces/{id}/candidates | 无 | Candidate[] |
| POST | /spaces/{id}/candidates | {expectedDraftRev} | Candidate，冻结不增加草稿修订 |
| GET | /candidates/{id}/operations | 无 | Operation[]，从冻结载荷读取并复核摘要，要求空间可见权 |
| POST | /candidates/{id}/trial | {operationId} | Execution，仅 EDITOR/OWNER |
| GET | /spaces/{id}/executions | 无 | Execution[]（最近 50 条） |

冻结完整保存首批已支持的空间、类、属性和操作；载荷规范化序列化，SHA-256 按 UTF-8 原始载荷字节计算，PG BYTEA 持久化并复核。P4 按详设升级 MinIO 制品归档、发布预分配、五项门禁、正式 manifest 和 CAS 提交；本批不生成 release 或 RELEASED 事件。

试执行操作下拉框必须使用所选候选的操作清单；草稿中操作的编辑或删除不能改变旧候选的可选操作及执行行为。

模型 RDF：类 URI=`namespaceUri+"class/"+UUID`，属性 URI=`namespaceUri+"property/"+UUID`；类含 `rdf:type owl:Class`、`rdfs:label`、`rdfs:comment`、可选 `rdfs:subClassOf`；属性含 `rdf:type owl:DatatypeProperty`、`rdfs:label`、`rdfs:domain`、`rdfs:range`（对应 XSD 类型）。试执行参数暂为空；查询通过本地 Jena 模型执行，必须拒绝外部数据集、SERVICE 和扩展函数，限制 queryTemplate ≤10000 字符、maxRows 1～500、timeoutMs 100～10000。

界面内置可编辑模板：

```sparql
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?class ?name WHERE {
  ?class a owl:Class ; rdfs:label ?name .
}
ORDER BY ?name
```
