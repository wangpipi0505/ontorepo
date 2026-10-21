# 独立审核整改主执行过程记录（本轮本地范围已完成）

2026-10-21。用户已授权继续工作，并明确 OM-D7 为完整可自定义角色与权限管理。正式补充设计与台账 OM-IAM 已建立；不能以此前 362/230 结果代表本轮通过。

## 已完成变更

- 基线 `.runtime/independent-audit-20261021-baseline`：597 文件及摘要；Git 仓库存在但无初次提交，不擅自提交。
- 正式独立审核第10节追加事实核验；C07 共享 ELK 配置、C03 E1 主链缺失、无 Git、Waiver 类型漂移、无401跳转等不成立结论已纠正。
- C01/C02/C06：共用冻结守卫判据、FREEZING 截止与恢复、阶段前置/长任务回写，SHACL/prepare分类，见 c8-release-fixes.md。
- C03/C10/C11及数据卫生：实际步骤、Parquet严格数值、先验/词表/元数据证据、RDF关闭、同批字节验摘要消费、表名白名单，见 c8-data-fixes.md。
- IAM：V13、用户生命周期/自定义多角色、注册权限目录、逐请求功能授权、内置角色/最后管理员/修订/安全审计、bootstrap一次初始化、密码重置与登录并发、API Key ACTIVE。根代理实现；所有 Controller 显式注解。
- 前端 C12/IAM：独立冲突与定位、冻结规则试执行、复用批量用例、自定义角色用户审计页面、权限驱动导航/按钮/路由、过期提示、C11文案；见 c8-ui-fixes.md。
- CanonicalPayload 注释已如实说明列表顺序由调用者保证；SpaceService INSERT 已显式列名。
- Outbox 生产装配改为当前事务连接上的 PG clock_timestamp 时间权威，保留可控时钟构造供协议测试；不截短活跃租约，数据库本身时钟回拨仍采用保守等待。

## 已有运行结果与失败记录

1. `.runtime/c8-combined-green.log`：59 项（onto-data 2 + API 57），0失败/错误/跳过、14模块成功，09:46:54结束。含 IAM 初版9、发布/守卫新增9、数据新增9（部分分类交叠，以上只按模块合计59）、P3Mapping14及既有P4子集。
2. `.runtime/c8-iam-path-red.log`：独立 IAM 审查发现原始 URI 前缀绕过；矩阵参数路径实际200写入而期望403，1项有效RED。已按 MVC HandlerMethod 校验替代原始字符串开关。
3. `.runtime/c8-outbox-clock-red-p4.log`：节点时钟提前1天可抢占有效租约，1项有效RED。前一次 `c8-outbox-clock-red.log` 用错测试库被严格夹具拒绝（未执行业务），保留但不计业务反例。
4. `.runtime/c8-outbox-clock-green.log`：原协议13+新时间反例1，共14项全过，19.417秒。
5. `.runtime/c8-iam-security-green.log`：14项中13过1失败；归一化路径与认证补测4均通过，唯一失败为重复运行遗留自定义角色编码冲突。已修改 WorkflowFixture：TRUNCATE 用户后清除 NOT built_in 测试角色，保留内置目录，等待 `c8-iam-security-green-r2.log`。
6. 前端30文件252项及后续RouterLink专项21、类型/格式/构建通过（c8-ui-fixes.md）；尚未新后端真实浏览器验收。

## 当前协作与下一步

- `/root/audit_release_fixes` 独占 Maven/PG 窗口；继续 IAM14重跑、C04/C05五项RED与实现。C04仅直接候选trial缩空间锁，LOCAL_TEST/正式服务保留原语义；专用授权共享gate须覆盖撤权/归档，四连接不死锁、失败不留session锁；C05三次批量事实读取+V14有效索引。控制器/IAM/Outbox仍由root所有。
- `/root/audit_ui_review` 只读独立UI复核，有“rule.read用户冲突结果被无关定位目录403遮挡”的候选待复现。
- `/root/audit_data_review` 只读独立数据复核。
- `/root/audit_iam_review` 第一轮报告需修复1个P1路径绕过；修复及GREEN后发其复核，补测已写 IamAuthenticationTest。
- `/root/audit_ui_fixes` 已结束，待复审缺陷或新后端启动后 followup 浏览器/390px，勿自行改服务。
- 新后台尚未启动：当前运行仍旧 SHA `66F0E72C8257E7E2762CE681312A49738F818E770714CC3A3E369B6FB71291FC`；当前前端登录依赖新增 `/auth/permissions`，需新JAR启动才能联调。
- root已请求C04 RED后打阶段 `-DskipTests package`（仅用于联调，不宣称最终验收），再用安全Stop-Local与复制版Start脚本启动，原运行JAR/PG/MinIO保留；最终C04完成后完整verify+新最终Jar再验证。
- 后续必须完成独立复核、完整后端verify、OpenAPI导出/生成、前端复验、真实HTTP/浏览器/390px、原候选/发布兼容与重启、正式设计/台账/审核最终状态。外部五类数据源、跨系统与D1/D2保持原边界。

## 环境

仅本工程，禁止邻近 agent-platform。JDK21路径 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；Java512MiB、Node1024MiB/worker1。通用 helper `deploy/Environment.ps1` 读忽略的 `.runtime/local-env.json`，不输出秘密。PG15472，API18182，Vite15184，MinIO19100/19101。formatter `.runtime/google-java-format.jar --aosp`。原阶段启动脚本 `.runtime/Start-C6-Preview.ps1` 复制 SHA 命名JAR到 c6-running 并写 c6-runtime；新阶段应复制脚本改成 c8 名称以保留旧记录。没有外部部署/推送/首次提交授权。

## 最新执行状态（2026-10-21 追加）

- IAM 定向 R2：14 项全过，`.runtime/c8-iam-security-green-r2.log`；独立 IAM/Data/UI 均已复核通过，见对应 c8-*-review.md。
- 新 stage SHA `50CF88E132B5C1D4D69C030A402858BE95975AE54A99567C34EE54890A027ECE` 已启动，`.runtime/c8-runtime.json` 指向 c8-running 不可变副本。stage 用于联调，未包含 C04/C05。
- 真实 IAM HTTP 8 项通过，`c8-iam-http-stage.json`；UI 代理正在桌面/390px 浏览器验收。OpenAPI 已从 stage 导出 109 路径，UI 负责生成类型和最终全套前端检查。
- C04/C05 已实现；ExecutionAuditTest 初轮6项全过，随后增加审计失败事实原子回滚第7项；作者继续扩大回归。c8_backend_review 正在独立审查全部发布/执行/Outbox 范围。Maven 窗口仍归 audit_release_fixes。
- root 为旧基线持久性脚本新增 ONTO_EVIDENCE_DIR 输出选择；最终重跑写 c8-final/，不覆盖历史证据。§8.8 四 JSON 新解析/夹具对齐结果已写 c8-final/design-sample-alignment.json，语义结果待全量 verify。
- 最终 verify、最终 JAR 重启/兼容/T02/IAM HTTP、浏览器完成和正式文档关闭仍待完成。前文为过程历史，不代表当前最终状态。
## 恢复点更新（2026-10-21，本轮继续）

- 后端审查 BR-01：Outbox 行锁等待跨租约期限已独立 RED 2项→root修复→27项GREEN关闭；`c8-backend-review.md`已记录。C04/C05扩大回归48项全过，含ExecutionAudit7。
- 前端重新生成OpenAPI后完整31文件259项、typecheck/format/build通过；真实浏览器尚在继续。
- 首轮全量verify因真实浏览器新发现主动停止，保存 `.runtime/c8-full-verify-interrupted.log`（不是完整通过）。问题：tests先行成功无条件置REVIEWING，之后不能补机器validate。作者audit_release_fixes正在按门禁事实决定状态补RED/GREEN；最终需重新完整verify。
- root已新增ContractTest.importedSampleMapsEveryManifestFieldAndConsumerDefault，覆盖第④段整对象映射/defaults和实际Schema；已格式化，运行待唯一Maven窗口。doc reviewer复核中。
- 正式详设§8.4已补FAILED仅非VOID可重跑，VOID须重新冻结。
- UI真实只读和390宽检查已执行；发现PermissionPicker继承checkbox-group font-size/line-height=0导致文字重叠，UI作者修局部CSS并将实屏复验，最终前端检查按该变更重跑。
- 当前stage运行仍50CF...，最终jar未启动。`.runtime/Verify-C8-Final.ps1`已准备公开HTTP、T02、新发布、P1/P2/P3/P4原基线兼容检查（输出c8-final/，IAM另存final标签）。最终验收完毕后再关闭正式台账/审核。
## 最终结项（2026-10-21）

本轮本地工作全部完成，前文为过程历史，不再代表待办。最终14模块407项0失败/错误/跳过，前端31文件259项及最后CSS后类型/格式/构建通过；最终JAR SHA63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C已实际启动。最终109路径契约一致，10条HTTP/原P1～P4基线检查全部退出0，真实原r7状态复验/IAM/桌面与390px通过。c8_ui_reader已停用无角色、临时角色与凭据清理，初始管理员未变，业务/审计保留。

主执行者重新核验构建汇总、运行副本、HTTP与IAM记录的同一SHA一致，11份主文档本地文件链接可定位。正式独立审核第11节、台账OM-C8/OM-IAM、详设/开发计划/补充设计、README/后续计划/进度/矩阵已同步。c8-integration-report.md为主证据索引。Java/前端最后检查后无新增生产变更，不重复运行未改变的测试。不创建首次提交、不推送或外部部署。

保留外部五类源、跨系统J-01～J-05及OM-D1/D2；C11表抽取仅元数据、无实例样例读取。当前API18182/Vite15184/原PG15472/MinIO19100及样例源19180保持运行。