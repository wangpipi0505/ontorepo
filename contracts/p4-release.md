# P4 发布、消费与追溯接口

以详设 §7.1/§8.3/§8.4/§8.6/§8.7/§8.8 为权威。下述管理 API 位于 `/onto`。发布、弃用、端点授权、事件重放均要求空间 OWNER；执行权限仍按六步授权，不以管理角色替代 endpoint_grant。

## 候选升级与门禁

保留现有冻结与试执行接口，但新候选冻结为正式发布能力准备全量冻结载荷、预分配标识和 MinIO bundle。历史候选字节不改；缺发布能力的旧候选继续本地兼容试验，提示重新冻结后方可发布。

- `POST /spaces/{spaceId}/candidates` 原 `{expectedDraftRev}` 不变，扩展返回 candidate 状态、`preReleaseId/preVersionNo/bundleSha256`。版本号使用空间独立递增序列，取消/失败不回收。
- `GET /candidates/{id}/publication` -> `{candidateId,candidateSha256,draftRev,status,preReleaseId,preVersionNo,checks:[],waivers:[],artifacts:[],review:null|{actorId,reason,reviewedAt},releaseId:null|string}`。artifact 仅安全制品类型/摘要/大小/就绪，不包含对象存储凭据。
- `POST /candidates/{id}/validate` 执行 CONFLICT/CONSISTENCY/CONTRACT；结论绑定该候选摘要。返回 publication。现有检查页面仍可读取细项，但只有全部必要项满足才进入 TESTING。
- `POST /candidates/{id}/tests` 保持现有接口；CONTRACT 未通过的新发布候选拒绝运行，所有启用用例实际完成才建立 TEST 门禁。零启用用例应明确显示“缺少验收用例”并阻断发布，不能用空集合通过伪造测试证据。
- `POST /candidates/{id}/review`：`{expectedDraftRev,candidateSha256,reason}`。OWNER 人工确认冻结 diff 和所有机器门禁，建立 REVIEW；摘要/修订不符拒绝。不自动代表其他门禁通过。
- `POST /candidates/{id}/prepare`：`{expectedDraftRev,candidateSha256}`。五门禁符合后生成正式 manifest/context pack/Lucene index 制品并归档复核，全部就绪才 READY_TO_COMMIT。上传失败保持可重试 REVIEWING，无 release。
- `POST /candidates/{id}/commit`：`{expectedDraftRev,expectedSha256}`。返回正式 release；同候选已 COMMITTED 幂等返回原 releaseId。非幂等路径必须复核真实对象摘要、空间行锁/CAS、授权与五项门禁，再一次短事务写 release/version/context_pack/outbox/candidate；不会在 READY 后补制品。
- `POST /candidates/{id}/cancel`：`{expectedDraftRev,candidateSha256,reason}`，记录 CANCELLED/VOID，不能取消已提交候选。

五门禁记录 `{kind,conclusion,reason,candidateSha256,checkedAt,report}`。只有 CONSISTENCY 的 NOT_CHECKED/SUBSET_EXCEEDED 可由既有 OWNER 豁免覆盖；该豁免绑定摘要及当前草稿修订。其他错误/超时/缺守卫/测试失败/未复核不允许豁免。草稿修改使旧候选提交失败，旧结果仍可审计但不用于新候选。

OM-D6（详设 v2.7）：OWL 2 EL profile 通过后优先使用 ELK；只有其明确报告推理不完整时，才由 HermiT 完整检查同一原公理集的一致性和不可满足命名类，两引擎共享本次检查的总超时预算。报告保留实际引擎及补充校验原因。普通引擎异常、超时不得转入自动补验并伪装通过，非 EL 仍走原 SUBSET_EXCEEDED/OWNER 显式豁免。原始 FAILED 必须归一化为门禁 FAILED，不能变成 NOT_CHECKED。

bundle 是唯一执行权威，candidate/release 均经 ArtifactStore.loadVerified 加载；PG payload 可作为旧候选兼容或缓存核对，不能在 MinIO 损坏时静默回退。候选 bundle 上传后与正式版本复用同一 bytes/SHA；release 中引用该对象即可，不为“release 路径”改字节。正式 manifest 顶层按既定集合封装所有操作清单，单条 item 保持 §8.8 标准。

## 版本浏览与恢复

- `GET /releases?spaceId` -> `[{id,spaceId,versionNo,candidateId,status,bundleSha256,manifestSha256,publishedAt,publishedBy}]`；管理列表可见 DEPRECATED/CORRUPTED 并明确不可消费。
- `GET /releases/{id}` -> 管理详情与制品摘要、端点列表、门禁报告，不默认返回制品下载 URL。
- `GET /releases/{id}/entities?kind` -> 从不可变 bundle 派生的 release_entity 投影；管理浏览允许历史状态，但执行仅 READY。
- `GET /releases/diff?spaceId&fromReleaseId&toReleaseId` -> `{fromReleaseId,toReleaseId,added:[],removed:[],changed:[]}`，投影对比，每项 `{kind,key,before?,after?}`。首次发布前的候选 diff 使用 `GET /candidates/{id}/diff`。
- `POST /releases/{id}/restore-draft`：`{expectedDraftRev,reason}`；OWNER 从经摘要核验的历史 bundle 全量恢复草稿，新 draft_rev。不会修改旧版本、旧候选或旧执行证据；所有外键/空间引用原子重建。被损坏制品不能恢复。
- `POST /releases/{id}/deprecate`：`{reason}`，READY→DEPRECATED，与 DEPRECATED outbox 同事务。重复请求幂等，不创建重复事件。
- `POST /releases/{id}/integrity-check`：对 bundle/manifest/pack/index 实际校验；损坏同事务置 CORRUPTED、事件告警和审计。已 DEPRECATED 仍可识别损坏并告警；告警不含拉取地址。

定时完整性扫描使用同一服务，时间间隔可配置；临时不可达与摘要损坏分开：不可达阻止消费并记可重试故障，不能凭网络超时谎报字节篡改。

## 服务端点与消费身份

- `GET /releases/{id}/endpoints` -> `[{id,releaseId,opCode,route,status,execAttrs}]`。发布生成对应只读操作端点，不自动给全部用户调用权。
- `GET /endpoints/{id}/grants`、`PUT /endpoints/{id}/grants/{userId}`：OWNER 管理 `{dataScope:null|scope,expiresAt:null|ISO timestamp,status:"ACTIVE|REVOKED"}`，缺记录拒绝、存在 NULL 才不限；本地身份库必须有该用户。写入审计；不改变冻结操作声明。
- `POST /api-credentials`：`{spaceId,principalId,name,expiresAt}`，OWNER 建立映射本地主体的 API key；仅本次返回明文 `key`，持久化只存不可逆哈希。`GET /api-credentials?spaceId` 返回安全目录，`POST /api-credentials/{id}/revoke` 撤销。不得赋予任意新身份或绕过空间/端点授权。
- `POST /internal/releases/{releaseId}/ops/{opCode}` 使用本地TOKEN身份；正式 manifest 沿用已批准契约生成器的 `/onto/r/{spaceCode}/releases/{releaseId}/ops/{opCode}`。同时提供 `/onto/s/{spaceCode}/ops/{opCode}?releaseId=...` 固定版本入口，两条路由进入同一消费网关，均校验空间与实际版本一致，均可接受本地TOKEN或 `X-API-Key`。TOKEN caller=SERVICE，API key caller=EXTERNAL。
- `GET /releases/{id}/manifest`、`GET /releases/{id}/context-pack` 仅 READY 且空间可读时返回经实际摘要验证的消费制品；非 READY 拒绝。原始密文源配置、内部bundle不对消费者直接返回。

六步顺序：认证→端点授权存在且有效→冻结守卫完整性→空间读取授权→策略/端点/空间交集与源白名单→非空标签过滤。端点授权收窄不可被 OWNER 身份或凭据连通范围扩大。TRIAL/LOCAL_TEST 端点等价层取同一 space_grant.data_scope。运行期缺 tenant_label 绑定三入口全部零源访问拒绝。

成功 `{code:"OK",data:业务结果,meta:{spaceCode,releaseId,version,contentDigest,preview:false}}`，头 `X-Ontology-Release` 为真实 releaseId；失败不注入成功 meta/版本头。候选成功继续 preview=true，不能充作已发布版本证据。

## 事件可靠投递

本地独立部署提供可靠的 PG 消费收件箱作为默认通知目的地，按 eventId 去重；可通过配置接入受控 HTTP 消费者，服务启动默认不向外部地址发消息。不得把日志打印视为成功消费；只有目的地确认接收后 SENT。

- `GET /events?spaceId` 返回安全 outbox 列表（id/type/boundStatus/status/retryCount/nextRetryAt/lastError）及人工处理状态。
- `POST /events/{id}/replay`：`{reason}`，OWNER 将 DEAD 事件回 PENDING，保留原 id/载荷与次数历史，审计原因；按事件 id 处理。
- `GET /events/inbox?spaceId` 读取本地接收证据及去重 id，便于独立环境闭环验证。

创建资格绑定事件写入时状态（RELEASED←READY、DEPRECATED←DEPRECATED、CORRUPTED_ALERT←CORRUPTED），投递不查当前 release 状态。每 release 按 id 顺序：前序 PENDING 无论到期与否阻塞后序，前序 SENT/DEAD 不阻塞；进程重启按相同 SQL 资格恢复。有限重试→DEAD并告警，手工REPLAY不生成新事件id。外发在事务之外，at-least-once + 消费去重；不要用数据库长锁包网络请求。

测试 §7.1 五场景及重启恢复，尤其停机期间发布后弃用仍先投 RELEASED、消费者拉取被拒、再收DEPRECATED；弃用重试直至DEAD后CORRUPTED_ALERT可达。重复、失败重试不得重编译制品或改变载荷。

## 溯源与审计

- `GET /executions/{id}/trace` -> `{execution,authorization,steps,evidence,timeline,graph:{nodes,edges}}`，从持久化事实构建，按执行所属空间授权。证据类型保留 SOURCE_RECORD/META_SNAPSHOT/MAPPING_SNAPSHOT/ONTOLOGY_VERSION/RULE_VER/SERVICE_VER 与真实摘要。
- `GET /audit?spaceId&limit&beforeId?` -> 审计记录及游标，包含模型/映射/导入/发布/授权/重放等处理活动，不能泄露密码/API key/连接串/敏感源行。
- 既有执行列表扩展 subjectType/subjectId/caller 与 trace入口；无智能体平台也能完整显示请求→术语→规则→查询→证据→版本。

所有未覆盖的跨系统联调明确标记；不能将本地收件箱验收写作智能体平台正式上架通过。

## 展示结构补充（前后端固定）

- `preVersionNo`、`versionNo` 为正整数；正式 `meta.version` 为 `v` 加版本号，例如 `v1`。候选状态包含 `FREEZING|VALIDATING|TESTING|REVIEWING|READY_TO_COMMIT|COMMITTED|FAILED|CANCELLED`，并保留旧候选 `FROZEN` 的兼容展示。冻结编译失败或机器门禁失败持久记录 `FAILED`，与详设§8.3一致；是否可重跑/推进由实际失败原因、制品和门禁决定，不能只看状态字符串。制品编译失败的预分配版本号不得回收，重新冻结产生新候选。
- publication 门禁 `conclusion` 统一为 `PASSED|FAILED|NOT_CHECKED`；一致性原始 `CONSISTENT|INCONSISTENT|NOT_CHECKED|FAILED` 放入 `report` 保留，仅 CONSISTENT 映射 PASSED，INCONSISTENT/FAILED 映射 FAILED。旧 `/checks` 响应不改其原始结论。已有 `/candidates/{id}/waivers` 继续接收 `{kind:"SUBSET_EXCEEDED",reason}` 并由服务端复核当前修订；publication 把既有审批记录归一化为上述安全豁免摘要。
- 候选和正式内部调用、固定版本正式路由的请求体均直接为业务 JSON 值，例如 `{taskId:"T-1"}`，不再套 `input` 层。根类型由冻结 inputSchema 决定，合法数组、标量和null不得被HTTP DTO或页面提前改成对象。规则/评分或具名参数查询自身的结构要求仍由真实执行器验证。
- publication 的制品项为 `{kind:"BUNDLE|MANIFEST|CONTEXT_PACK|KNOWLEDGE_INDEX",sha256,size,ready}`，豁免项沿用候选检查已有的 `{candidateSha256,reason,actorId,createdAt}` 并允许读取额外安全字段。未预分配的旧候选 `preReleaseId/preVersionNo` 为 null，UI 提示重新冻结。
- 版本详情为 `{release,artifacts,endpoints,checks}`；`release` 与版本列表实体同形。实体投影返回 `[{kind,key,value:object}]`，`kind` 查询枚举为 `CLASS|PROPERTY|RELATION|OPERATION|RULE|STRATEGY|KNOWLEDGE|MAPPING`，省略取全部。候选与版本 diff 均使用 `added/removed/changed` 三个数组的上述明细形状。
- 端点授权列表为 `[{userId,username,displayName,dataScope,expiresAt,status}]`；PUT 返回更新后该授权行。本地用户选择器复用 `/spaces/{id}/grant-users`。凭据目录为 `[{id,spaceId,principalId,name,status,createdAt,expiresAt}]`，创建返回 `{credential:安全目录实体,key}`，撤销返回更新实体。
- 端点 `status` 为 `ACTIVE|DISABLED`，非READY版本显示DISABLED；ACTIVE仅表示端点可接受授权检查，不代表调用者已有授权。凭据 `status` 为 `ACTIVE|REVOKED`，已过期仍须按expiresAt拒绝。正式 `meta.contentDigest` 精确等于执行版本的 `bundleSha256`。
- 事件项为 `{id,spaceId,releaseId,type,boundStatus,releaseStatus,status,retryCount,nextRetryAt,lastError,createdAt}`，`id` 为整数；重放返回更新项。收件箱项为 `{eventId,releaseId,type,receivedAt,receipt:object}`，不返回可被误用的制品下载地址。
- trace 的 `execution` 沿用既有执行实体；`authorization` 为 `[{order,kind,passed,detail}]`；`steps` 沿用持久步骤；`evidence` 为 `[{id,type,ref,sha256,detail:object}]`；`timeline` 为 `[{id,at,kind,title,detail}]`；图节点 `{id,label,kind,ref?}`、边 `{id,from,to,label}`。字段缺值使用 null，不虚构历史记录的授权步骤或时间。
- 审计返回 `{items:[{id,spaceId,actorId,action,subjectId,detail,createdAt}],nextBeforeId:null|integer}`；GET `limit` 默认50、最大200。按 id 倒序，游标请求不会覆盖前页。恢复草稿返回更新空间；弃用、完整性检查返回更新版本详情，包含最新状态及安全检查结果。状态改写与审计由后端原子完成。
