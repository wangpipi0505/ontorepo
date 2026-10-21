# P2 供给、守卫与知识接口约定

本文件细化开发计划 P2，不取代详设 §8.7/§8.8。全部管理接口位于 `/onto`，继续统一信封、服务端身份、空间行锁、`expectedDraftRev` 和事务审计。字段与接口在前后端并行前固定；调整须同步主执行者。

## 助手声明

保留已有操作 CRUD 请求。新增独立 `GET /operations/{id}/assistant?spaceId` 与 `PUT /operations/{id}/assistant`：

```json
{
  "spaceId": "同空间 UUID",
  "expectedDraftRev": 1,
  "inputSchema": {"type":"object","properties":{}},
  "outputSchema": {"type":"object","required":["columns","rows"],"properties":{"columns":{"type":"array","items":{"type":"string"}},"rows":{"type":"array","items":{"type":"object"}}}},
  "assistantTool": {
    "name":"对象查询","description":"查询冻结模型",
    "guard_attrs":["region"],"info_attrs":[],
    "param_examples":[{}],
    "exec":{"readonly":true,"side_effect":false,"idempotent":true,"cancellable":true},
    "risk_level":"READ_ONLY",
    "error_semantics":[{"match":"HTTP 403","semantic":"NO_PERMISSION"}],
    "return_spec":{"modelSummary":{"mode":"TEMPLATE","template":"查询结果"},"structuredPath":"$.data","fileRefPaths":[],"cardTemplate":"查询结果"}
  }
}
```

读取返回 `{operationId,inputSchema,outputSchema,assistantTool}`；未配置值为 null。写入返回更新空间。管理请求外层 camelCase，assistantTool 内部严格沿用消费协议 snake_case，避免双重枚举及字段转换。

允许保存尚未配齐的声明草稿，需基本结构和 Schema 合法；明确生成、冻结 CONTRACT 检查及执行时必须完整。`GET /candidates/{id}/contracts` 只读取冻结声明，返回 `{candidateId,candidateSha256,manifests:[完整 manifest_preview]}`；任一操作错误整体失败并定位字段，不能跳过问题操作。`POST /contracts/validate` 接收完整正式 manifest，成功返回 `{valid:true}`，仅校验不持久化。

## 守卫定义与本地身份属性

`GET /spaces/{id}/guards` 返回 `{guardCfg:{...}}`；`PUT` 接收 `{expectedDraftRev,guardCfg}`，仅 OWNER 可修改，递增修订。定义形态：

```json
{"region":{"type":"STRING","authorization_rule":{"kind":"PRINCIPAL_ATTRIBUTE","attribute":"region"}}}
```

可执行过滤绑定形态 `{type:"STRING",filter_binding:{table:"task_table",column:"region_col",operator:"IN"}}`；类型枚举 STRING/INTEGER/DECIMAL/BOOLEAN。无绑定的草稿允许保存并显示错误，生成及执行必须拒绝；禁止接受非字符串表名、任意代码或模糊非空对象。

`GET /spaces/{id}/grants` 仅 OWNER 返回当前授权主体 `[{userId,username,displayName,role,dataScope,attributes}]`；`PUT /spaces/{id}/grants/{userId}` 接收 `{expectedDraftRev,role,dataScope,attributes}`。用户必须已在本地身份库存在；仅 OWNER 管理，不能移除最后一个 OWNER，不能自行通过执行请求补属性。`attributes` 是该空间授权下的可信主体属性，以守卫声明类型校验。`dataScope:null` 表示现有授权不限，缺授权仍拒绝。认证令牌明文、哈希、密码不返回。
`GET /spaces/{id}/grant-users` 仅该空间 OWNER 可读，返回本地可授予用户目录 `[{userId,username,displayName}]`，用于选择尚未授权的本地主体。不会借此自动授予任何空间权限，不接收客户端随意创建身份。

P2 的 PRINCIPAL_ATTRIBUTE 绑定要求当前有效空间授权具有对应非空且类型正确的属性；数据源列绑定在数据执行阶段必须转成源过滤，P2 模型执行不能假装实现源过滤，应拒绝 `GUARD_BINDING_NOT_APPLICABLE`。用户已确定：缺标签/未分类数据默认拒绝。

## 内部调用与统一执行

`POST /internal/candidates/{candidateId}/ops/{opCode}` 的 body 直接为操作输入，响应成功信封 `{code:"OK",data:业务结果,meta:{spaceCode,releaseId:null,version:null,contentDigest:candidateSha256,preview:true}}`。不得把执行记录 id 等元数据塞进业务结果而破坏 outputSchema；详细事实在执行记录中保留。失败只返回错误信封，没有 meta。正式 READY 版本内部调用入口 `/internal/releases/{releaseId}/ops/{opCode}` 留给 P4 真实 release 接入，不做假 READY 数据。

实际执行前按照统一身份/角色/冻结守卫完整性/空间范围检查。试执行、LOCAL_TEST、内部调用共用同一授权和冻结执行器，不能由客户端传 principal、caller 或可信属性。声明了守卫且 tenant_label 无绑定时，三个候选执行入口都拒绝并且执行器零访问。对旧 P1 候选保持原字节和本地模型演示能力，明确标记旧格式；旧格式不具备 CONTRACT 合格资格、不能经内部服务入口或正式发布消费。新供给候选的版本标记进入摘要。

输入和实际业务输出均按冻结 Schema 校验。超时、失败规则和输出上限由服务器强制。拒绝或运行失败的事实与步骤保持可追踪，不把失败当成功信封。

输入根类型完全遵从冻结 inputSchema，不限定为对象；声明/示例已允许的数组、标量、布尔和null必须可穿过实际HTTP及三入口。候选试执行/用例的外层控制对象仍保持原结构，其中 `input` 保存原JSON值；内部调用body直接为该值。规则/评分等需要属性对象的执行器可给出稳定的结构错误，但不能因传输DTO不匹配拒绝本来可执行的Schema合法输入。页面加载false/0/null时须原样保留。

## 知识管理、编译和确定性 NL

- `GET /knowledge?spaceId`、`POST /knowledge`、`PUT/DELETE /knowledge/{id}`，写请求含 `spaceId/expectedDraftRev`。实体 `{id,spaceId,term,aliases:string[],definition,conceptRef:string|null,sourceRef:string}`；别名去重、同空间引用、空名称和空来源拒绝。
- 所有知识进入冻结载荷；`GET /candidates/{id}/context-pack` 返回 `{candidateId,candidateSha256,conceptCards:[],termDictionary:[],relationSummaries:[],exampleQa:[]}`。概念卡/术语词典/关系摘要/示例问答从同一候选生成。
- `GET /candidates/{id}/knowledge-search?q=...&limit=10` 返回 `{candidateId,candidateSha256,hits:[{entryId,term,definition,score,matchedAlias}]}`。使用真实 Lucene 中文分析器，索引按候选摘要建立，不能遍历字符串 contains 冒充 Lucene；缓存不作为持久化权威，重启可由候选确定性重建。编译包在 P4 纳入不可变制品。
- 用例保留既有 `operationId` 作为期望命中操作，新增 `mode:"DIRECT"|"NL"`，默认 DIRECT 兼容旧用例。NL 使用冻结词典、操作说明与参数示例完成确定性匹配，匹配不唯一需报歧义，不得直接取用例的期望 operationId 冒充命中结果。匹配阶段保存 TERM_MATCH，后续沿同一执行链。默认 EXACT 比较成功状态、实际操作命中及完整 rows；CONTAINS 必须逐用例显式选择、仍强制操作命中。用户 OM-D4 已决定不采用 LLM 主观判分。

## 验收

真实 PostgreSQL：声明 CRUD/冻结隔离、守卫三类拒绝、三个候选入口缺绑定且执行器零访问、输入输出 Schema、VIEWER/跨空间/陈旧修订、可信身份属性授权、知识 CRUD/冻结隔离、Lucene 十个查询至少八个命中、NL 错误命中不得通过、评分步骤与资源上限。四段样例逐项标准校验保留。

前端：独立助手表单（字段/示例/守卫）、守卫绑定表单、授权主体属性表单、知识页、候选契约/知识包预览与搜索。使用真实 API，按候选摘要展示，编辑状态保持修订锁，OWNER 设置与 EDITOR 工作入口分别按实际角色开放；组件拆分与现有页面风格一致。
