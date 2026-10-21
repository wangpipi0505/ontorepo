# P1 首批闭环实现与验证记录

日期：2026-10-20。依据：[执行计划](../implementation-plan.md)、[接口契约](../../contracts/p1-api.md)、[设计基线摘要](../../contracts/design-baseline.json)。

## 1. 结论与交付范围

首批闭环通过本地验证：登录 → 空间 → 对象层级/基础属性 → 模型 SELECT 操作 → 冻结候选 → 试执行 → 执行记录。工程可以独立启动并继续迭代。此结论对应事项台账 OM-P1-01～05，不等同于整个 P1 出口通过。

后端采用 Java 21 / Spring Boot 3.5.5，按 common、space、model、operation、trace、designer-api 模块分层。前端采用 Vue 3.5 / TypeScript / Pinia / Element Plus，拆分路由页面、布局、业务对话框、组合式逻辑、API 包和共享样式。公共接口、事务与权限边界、并发处理、冻结约束及组件职责均补充中文说明。

真实数据库为独立 PostgreSQL 16.15，开发库 `onto_dev`、测试库 `onto_test`。用户令牌只存摘要；写入与审计同事务，空间行锁结合修订防止覆盖；冻结载荷有 SHA-256 和数据库不可更新约束；执行从冻结内容读取模型及操作。

## 2. 执行证据

| 验证 | 命令/方式 | 实际结果 | 证据 |
| --- | --- | --- | --- |
| 后端测试 | 配置独立测试库后 `mvn -B verify` | 测试阶段 22 条通过，0 失败、0 错误、0 跳过；随后打包阶段遇到运行中 JAR 的 Windows 文件锁 | [测试摘要](backend-tests.json)，本地 `onto-designer-api/verify.log` |
| 后端打包 | 停止本工程服务后 `mvn -B -DskipTests package` | 20:38:01 BUILD SUCCESS；仅复用刚完成的测试结果，不把跳过测试当成新测试通过 | 本地 `onto-designer-api/package.log` 与生成的可执行 JAR |
| 前端测试 | `pnpm test` | 6 个文件、18 条通过，包含 5 条真实 Element Plus 表单提交组件测试 | [前端 RED/GREEN 记录](../../web/evidence.md) |
| 前端类型 | `pnpm typecheck` | 通过，退出码 0 | 同上 |
| 前端格式与构建 | `pnpm format:check`、`pnpm build` | 最后一次窄屏 CSS 修复后重新执行均通过；Vite 编译 1673 模块 | 终端实际输出；生产文件在 `web/apps/onto-admin/dist` |
| API 闭环 | `node deploy/Smoke-Api.mjs` | 8 项通过 | [API 结果](api-smoke.json) |
| 重启持久化 | `Stop-Local.ps1 -WithDatabase` → `Initialize-Local.ps1` → `Start-Local.ps1` → `node deploy/Check-Persistence.mjs` | 实际停止并重新启动本工程应用和 PG 后，5 项通过 | [持久化结果](persistence-smoke.json) |
| 浏览器 | Playwright CLI / Chromium，真实前后端 | 登录、新建、层级、属性下拉、操作、冻结、试执行、详情及窄屏交互通过 | [浏览器结果](browser-smoke.json)与下方截图 |
| 接口类型 | 实际服务 `/v3/api-docs` 导出，`pnpm api:generate` | 16 条路径，生成 TypeScript 契约并通过类型检查 | [OpenAPI](../../contracts/openapi.json) |

后端 22 条中，15 条为真实 PG 身份/权限/事务/闭环测试，7 条为查询策略测试。前后端测试、API 冒烟和浏览器用例存在覆盖重叠，不汇总为不重复用例数。

浏览器场景使用独立的“算法任务领域”空间：两个根对象“任务”“算法”，“侦察任务”继承“任务”，包含 INTEGER 必填属性“优先级”。冻结 r5 后，两次真实试执行均返回三个对象，摘要一致。浏览器末段未捕获 pageerror；本地 favicon 404 不影响业务。管理员 OWNER 页面链路已验证，VIEWER/无授权反例由后端测试覆盖。

## 3. 审查与联调发现的修复

| 问题 | 修复及验证 |
| --- | --- |
| 空间更新入口的事务自调用导致行锁未覆盖完整写入 | 将事务落实到外部调用入口；真实四线程竞争由多个成功修复为仅一个成功，其余修订冲突 |
| 旧表单可借共享新修订提交，导致覆盖风险 | 捕获表单打开时修订，409 后锁定表单；全量刷新使用空间修订前后夹读，拒绝旧实体配新修订；新增失败用例后通过 |
| 旧请求迟到的 401 清空新会话 | 请求令牌与当前令牌一致才清会话；双会话延迟用例通过 |
| 缺失必填查询参数返回 500，修订小数/字符串可被隐式转换 | 统一 400 INVALID_ARGUMENT；关闭不符合契约的标量强制转换；真实 HTTP 回归通过 |
| 试执行事实字段不足 | 持久化实际调用者、TRIAL、输入/输出摘要、独立跟踪标识及步骤；数据库断言通过 |
| 真实登录没有 POST；外层 form 嵌套 el-form | 登录和四个编辑框仅保留一个 el-form；5 条提交组件测试及真实浏览器 POST 均通过 |
| 窄屏执行结果表格撑开卡片内部 | 共享 stack 网格使用 `minmax(0, 1fr)`；卡片 client/scroll 同为 364，内部同为 332，表格独立滚动；截图复核通过 |

独立代码审查覆盖前后端接口、空间授权、事务、候选隔离、资源边界和前端修订/会话竞态。发现的重要问题已修复；后续新增表单及布局修复由实际组件测试、类型/构建检查及浏览器复核验证。

## 4. 页面证据

截图经过视觉检查；页面使用深蓝导航、浅灰背景、白色工作面和统一蓝色主操作。

- [桌面对象与属性](screenshots/model-desktop.png)
- [桌面候选与试执行](screenshots/candidate-desktop.png)
- [桌面执行详情](screenshots/execution-desktop.png)
- [窄屏对象与属性](screenshots/model-mobile.png)
- [窄屏属性编辑](screenshots/property-mobile.png)
- [窄屏候选与试执行](screenshots/candidate-mobile.png)

桌面视口 1440×1000；窄屏 390×844。窄屏页面宽度为 390，属性表可在容器内横向滚动，并实测点击右侧编辑入口；未将截图存在当成可操作性通过。

## 5. 保留边界与下一批任务

- 属性高级分面、单位码表、关系、约束、规则和冲突检查、TTL/OWL、ELK、用例登记尚待完成，对应 OM-P1-06～09；完整 P1 出口 OM-P1-10 继续跟踪。
- 首批模型元查询不连接外部数据源；候选当前使用 PG 冻结载荷，尚无正式 release、MinIO 制品、五项门禁、CAS 发布、RELEASED 事件和网关。本次不构成 T01/T02/T03 发布实现验收。
- OM-D1 外部既有内核盘点、OM-D2 正式命名空间归属仍待负责人提供结论。示例 URI 仅用于本地验证。
- 本地启动端口为前端 15184、后端 18182、PG 15472。启动/停止脚本核验工程标识和进程归属；数据库目录及随机凭据被 Git 忽略。启动与复测方式见 [README](../../README.md)。
