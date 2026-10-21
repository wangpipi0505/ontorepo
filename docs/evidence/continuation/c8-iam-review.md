# C8 系统用户、角色与权限后端独立审查

审查日期：2026-10-21。审查依据：`文档/设计/详细设计/领域本体构建/系统用户角色权限管理补充设计.md` v1.0 与工程 `AGENTS.md`。独立审查者只读生产代码；未修改生产代码，未启动服务，未执行 Maven 或访问 PostgreSQL。本文记录首次审查快照，后续修复应另记复核结论。

## 当前复核结论（2026-10-21 第二次）

**Spec 通过本次后端实现审查；Quality 通过，R1 关闭。** 已独立检查修复代码、有效 RED 日志和 14 项 GREEN 日志，没有发现新的明确阻断问题。该结论限定于本文 IAM 后端范围，不替代前端验收、全工程回归或实际部署验收。首次发现和证据保留如下，第二次复核细节见文末。

## 首次结论（保留历史）

- **Spec：暂不通过。** 本地 RBAC、角色自定义组合、实时多角色并集、空间权限叠加、内置角色保护、最后管理员保护与审计结构符合补充设计；HTTP 默认拒绝仍存在一处路径表示差异导致的绕过。
- **Quality：需修复一项 P1。** 未发现第二项有充分依据的生产缺陷；下文明确区分已确认的代码问题与尚缺运行证据的验收项。

## R1 / P1：按原始 URI 前缀决定是否执行权限注解，可被等价路由绕过

位置：[`WebBoundary.java`](../../../onto-designer-api/src/main/java/cn/ontorepo/api/WebBoundary.java)，首次审查代码第 55–60 行，具体条件为 `path.startsWith("/onto/") && handler instanceof HandlerMethod`。

拦截器登记的 `/onto/**` 与控制器匹配使用 Spring 解析后的路径；`request.getRequestURI()` 保留矩阵参数及百分号编码。`/onto;x/spaces` 和 `/%6fnto/spaces` 均可匹配 `/onto/spaces` 处理器，但原始字符串不满足上述前缀条件。因此请求完成身份认证后直接跳过 `@RequiresPermission("space.create")`。[`SpaceService.create`](../../../onto-space/src/main/java/cn/ontorepo/space/application/SpaceService.java) 依赖 HTTP 功能权限边界，没有再次校验 `space.create`；无角色的有效账号因而可以创建空间。对已有空间成员，其他只在边界检查功能权限的业务接口也有相同风险。

可复现步骤：管理员创建 `roleIds=[]` 的 ACTIVE 用户并正常登录；先向正常 `/onto/spaces` 提交合法建空间请求，应为 403；再以同一会话和请求体向 `/onto;x/spaces` 提交请求。修复前该请求通过本应默认拒绝的功能权限边界。系统用户/角色应用服务自身会再次校验功能权限，不能把 `/onto;x/system/users` 作为此缺陷的主要复现路径。

已完成的独立验证仅使用工程实际依赖 `spring-web/core/jcl 6.2.10` 的 `PathPatternParser`、`PathContainer` 在 JDK 21 JShell 中解析路径，无服务或数据库依赖：

```text
/onto;x/spaces matched(/onto/**)=true handler(/onto/spaces)=true guard=false
/%6fnto/spaces matched(/onto/**)=true handler(/onto/spaces)=true guard=false
/onto/spaces   matched(/onto/**)=true handler(/onto/spaces)=true guard=true
```

建议直接依据已解析的业务 `HandlerMethod` 判定权限策略，不把原始 URI 前缀作为执行权限检查的条件。保留登录公开、身份自服务仅认证与受保护 OpenAPI 的既定区分。补充真实 HTTP/MockMvc 回归：矩阵参数、编码段和配置 context path 下，授权不足均失败且数据库没有写入；正常授权请求仍成功。当前报告未宣称已执行端到端 HTTP 复现。

## 已检查且未发现缺陷的关键不变量

| 检查点 | 代码依据与判断 |
| --- | --- |
| 自定义角色与多角色并集 | `PermissionService.effective` 只纳入 ACTIVE 用户、ACTIVE 角色和目录内权限，使用集合去重；无角色结果为空，未知权限无法授予。 |
| 内置角色 | `SystemRoleService.editable` 阻止更新与删除内置角色；其权限由服务端目录计算，普通成员不含 `system.*`。 |
| 管理并发 | 管理写事务先锁 `iam_state`，随后重新检查操作者的当前权限；修订、业务变更与安全审计同事务提交。 |
| 最后管理员 | 用户状态/角色修改后，在同一串行管理事务中验证至少一个 ACTIVE 内置 SYSTEM_ADMIN 用户；拒绝时整个修改回滚。自定义管理角色不改变这一明确设计约束。 |
| 密码重置与停用 | 登录在事务内对用户持有 `FOR SHARE`，重置/停用更新用户行后撤销已有会话，可将登录与变更串行化；会话及 API Key 身份认证均增加用户 ACTIVE 条件。并发运行证据仍待补。 |
| 引导幂等 | `iam_state.initialized` 受行锁保护，仅首次授予 SYSTEM_ADMIN；已有用户密码使用 `ON CONFLICT DO NOTHING` 保留。撤权后重启不恢复的运行证据仍待补。 |
| 审计安全 | 用户/角色变更审计来自公开字段查询；密码重置仅记录修订号；密码哈希、会话令牌和 API Key 没有进入这些快照或系统列表响应。 |
| 组合授权 | 现有空间 OWNER/EDITOR/VIEWER、端点授予和数据守卫未被系统管理员替代；功能权限在 HTTP 层叠加。系统用户/角色服务没有创建隐式空间成员授权。 |
| 迁移兼容 | V13 追加本地用户状态/修订及 RBAC 表；既有用户只补 MEMBER，未改写旧迁移、候选载荷或空间成员关系。 |

## 测试证据与需补充项

只读检查 `.runtime/c8-combined-green.log`：`IamBoundaryTest` 1 项、`IamManagementTest` 8 项均为 0 failures/errors/skipped。现有用例覆盖角色并集与即时变化、无角色拒绝、管理员仍需空间成员、未知/重复权限、内置角色不可变、修订冲突、角色引用保护、密码/停用顺序执行、审计安全及最后管理员并发。本文不把正在进行的更大批次标为已完成。

补充设计 §6 尚需以下有辨别力的验收，缺测不等于代码已存在缺陷：

1. 修复 R1 后对等价路由的真实 HTTP 默认拒绝，以及未标注处理器的运行时拒绝；当前“所有现有方法均已标注”反射检查不能替代这些用例。
2. 升级旧用户到 MEMBER、首次引导恰好一次、后续更改引导密码不覆盖密码、撤销原引导用户管理员角色后重启不重新授予；未配置引导用户不任意提权。
3. 用受控锁顺序验证旧密码登录与重置/停用竞争的两个先后顺序，确认重置/停用提交后不存在新建的有效旧密码会话，避免只用并发时间碰撞。
4. 有效 API Key 在主体停用后不能认证，主体移除 `service.execute` 后不能执行；确认功能权限撤销并未跳过原端点/空间/数据范围检查。
5. 管理者等待 `iam_state` 时被另一管理者撤销管理权限，释放锁后该排队写入被拒绝且无业务/审计副作用。

## 第二次复核记录：R1 已关闭

本次复核只读生产代码、测试代码与 root 提供的原始日志；未自行重新执行 Maven、服务或数据库操作。

### 修复核验

`WebBoundary` 已直接检查 MVC 选中的 `HandlerMethod` 的 `RequiresPermission` 注解，移除了原始 URI 前缀判断。业务方法缺注解时拒绝，权限未知或未授予时仍由 `PermissionService.require` 拒绝；仅 `org.springdoc.*` 文档处理器在已完成上方会话认证后免于业务权限注解检查。矩阵参数、百分号编码及 context path 不再控制是否执行功能权限。

`normalizedBusinessPathsCannotBypassFunctionPermission` 先创建空间，再撤销调用者全部功能角色但保留空间 OWNER；对 `/onto;x/classes` 与 `/%6fnto/classes` 提交合法建模请求均要求 403，最后断言该空间对象表仍为 0 行。这直接验证了功能权限必须与空间角色取交集，不能仅凭保留的 OWNER 身份写入。

### 有效 RED/GREEN 与测试隔离

| 证据 | 独立核查结果 |
| --- | --- |
| `.runtime/c8-iam-path-red.log` | 修复前 `POST /onto;x/classes` 实际匹配 `ModelController.createClass`，返回 200，空间修订从 0 到 1、对象数量为 1；断言期望 403 因而失败。该证据将首次静态路径解析发现落实到真实 HTTP 边界与数据库写入。 |
| `.runtime/c8-iam-security-green.log` | 首轮补验 14 项中 1 项失败：重复测试遗留自定义角色，使创建同编码角色返回 `RESOURCE_CONFLICT`。原始失败记录保留，未将其计为通过。 |
| `WorkflowFixture.setup` | `TRUNCATE local_user,ontology_space CASCADE` 后增加 `DELETE FROM local_role WHERE NOT built_in`。自定义角色不会沿用户外键反向清理，此修改补齐测试隔离，保留迁移内置角色；没有放宽生产唯一约束或权限条件。 |
| `.runtime/c8-iam-security-green-r2.log` | `IamAuthenticationTest` 4 项、`IamBoundaryTest` 1 项、`IamManagementTest` 9 项，共 14 项全部通过，failures/errors/skipped 均为 0；14 个模块构建成功，结束于 2026-10-21 09:56:12 +08:00。 |

### 新补认证用例的覆盖边界

- 引导幂等：首次授予一次管理员角色并产生一次引导审计，撤销角色后再次调用引导不会重新授予；现有密码哈希保持不变。无引导配置不提升任意账号权限。
- 密码更新先持锁：使用实际 PostgreSQL 连接持有用户更新锁；通过 `pg_stat_activity` 确认登录的 `FOR SHARE` 正在等待锁，再提交密码更新，旧密码登录返回 401。这是受控先后顺序，未依赖碰撞概率。
- API Key：凭据对应真实空间成员；删除该主体功能角色后通过 HTTP 服务入口返回 403，用户管理接口停用主体后 API Key 认证返回 401。该用例证明认证/功能权限边界，不宣称其执行了一个真实已发布操作。

现有补测没有覆盖所有建议组合，例如登录先持锁再重置的反向顺序、管理者等待 IAM 锁期间被撤权、配置 context path 的独立 HTTP 用例和旧版数据库逐步升级演练。静态复核中，相应事务锁与当前 HandlerMethod 权限判定未见违背设计的不变量；这些是可继续增强的验收覆盖，不能误写成已运行，也不构成本次 R1 的未关闭项。
