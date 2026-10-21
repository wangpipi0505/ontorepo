# 领域本体构建工具

独立的模块 2 工程。P1建模、规则、导入和候选执行，P2助手契约、守卫、知识供给与确定性NL用例，P3数据管理和三层映射，以及P4正式发布、授权消费、事件与溯源，均已完成代码与现有环境验收。2026-10-21 C8 独立审核成立项整改与完整自定义用户/多角色/权限管理已落地，最终后端单轮407项、前端31文件259项及类型/格式/构建通过；最终包实际HTTP、原候选/原正式版本重启兼容和桌面/390px验收通过，见[本轮联调](docs/evidence/continuation/c8-integration-report.md)。五类外部数据源、跨系统联合与OM-D1/D2保持台账边界。表抽取仅使用元数据证据，未读取实例样例。[首批验证记录](docs/evidence/p1-first-slice-20261020.md)保留最初交付切片的测试与截图。

设计基线为[详设 v2.8](../../文档/设计/详细设计/领域本体构建/详细设计70-领域本体构建工具（模块2）.md)、[开发计划 v1.8](../../文档/设计/详细设计/领域本体构建/领域本体构建工具开发计划.md)与[系统用户角色权限补充设计](../../文档/设计/详细设计/领域本体构建/系统用户角色权限管理补充设计.md)。执行状态见[本体事项台账](../../文档/设计/详细设计/领域本体构建/领域本体构建工具待办事项台账.md)，内部接口见 [P1](contracts/p1-api.md)、[P2](contracts/p2-supply.md)、[P3](contracts/p3-data.md)、[P4](contracts/p4-release.md)。当前各模块的真实验证与未完成项见[后续进度](docs/evidence/continuation/progress.md)；文末“首批交付边界”描述最初交付切片。

## 工程结构

| 目录 | 职责 |
| --- | --- |
| `onto-common` | 输入校验、统一异常、数据库基础设施 |
| `onto-space` | 用户、自定义多角色与功能权限、安全审计、空间授权与空间事务 |
| `onto-model` | 对象层级和属性建模 |
| `onto-operation` | 查询操作及 SPARQL 安全边界 |
| `onto-trace` | 不可变候选、模型加载、执行和留痕 |
| `onto-contract` | 技能契约生成、Schema 与守卫绑定校验 |
| `onto-knowledge` | 冻结知识包与 Lucene 中文检索 |
| `onto-data` | 凭据加密、标签范围与九类数据源适配 |
| `onto-mapping` | 源管理、元数据快照、三层映射及冻结实例查询 |
| `onto-ingest` | 表、结构化文件与文档候选抽取及显式人工确认 |
| `onto-artifact` | MinIO 不可变制品条件写与摘要校验 |
| `onto-release` | 五门禁与正式制品、发布事务、版本恢复、服务授权、可靠事件与溯源审计 |
| `onto-designer-api` | Spring Boot 启动、类型化 HTTP 接口、迁移及集成测试 |
| `web/apps/onto-admin` | 页面、布局、业务组件和状态 |
| `web/packages/api-client` | API 类型与请求封装 |
| `web/packages/shared-ui` | 样式变量和通用状态组件 |
| `deploy` | 独立环境初始化、验证、启动与停止脚本 |
| `docs/evidence` | 实际验证结果、证据及范围限制 |

## Windows 本地启动

要求 JDK 21、Maven 3.9、Node 22 或更高受 Vite 支持的版本、pnpm 11、PostgreSQL 16 和可运行的 MinIO 二进制。脚本在当前工程创建独立开发库与测试库，不复用相邻工程服务。MinIO 默认放在 `.tools/minio/minio.exe`，也可向 `Start-Storage.ps1` 传入 `-MinioExecutable`。

1. 从 [PostgreSQL 官方 Windows 下载入口](https://www.postgresql.org/download/windows/)获取二进制包，解压到 `.tools/postgresql-16.15/pgsql`；也可向初始化脚本传 `-PostgresBin`。本次开发环境已下载 16.15。
2. 在工程根目录运行：

```powershell
.\deploy\Initialize-Local.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\deploy\Initialize-Data.ps1
.\deploy\Start-Storage.ps1
Push-Location web
pnpm install --frozen-lockfile
Pop-Location
.\deploy\Test-Local.ps1
.\deploy\Start-Local.ps1
```

3. 打开 **http://127.0.0.1:15184**。用户名和随机初始口令在 `.runtime/local-env.json` 的 `ONTO_BOOTSTRAP_USERNAME` / `ONTO_BOOTSTRAP_PASSWORD` 中查看；不要提交或分享这个文件。前端会话只保留在内存，刷新页面需重新登录。

默认端口：前端 `15184`，后端 `18182`，独立 PostgreSQL `15472`。`.runtime/` 保存数据、日志和本地凭据，`.tools/` 保存开发工具，均被 Git 忽略。

Windows PostgreSQL 在中文路径下初始化可能产生 UTF-8 路径编码错误，初始化脚本将当前工程映射到空闲的 `O:`，并用工程随机标记核验归属。若 `O:` 已被其他目录占用，使用 `-Drive '其他空闲盘符:'`；脚本不会覆盖已有映射。数据仍在当前工程 `.runtime/pgsql`。重启机器后先重新执行初始化脚本恢复映射和启动数据库。

停止本工程前后端，保留数据库：

```powershell
.\deploy\Stop-Local.ps1
```

同时停止本工程数据库：

```powershell
.\deploy\Stop-Local.ps1 -WithDatabase
```

脚本按 PID、可执行文件及工程命令行标识核验进程，禁止按 `java` / `node` 名称批量结束进程。数据不会被删除。

## 验证

```powershell
# 完整后端集成测试，要求本工程真实 PostgreSQL/MinIO 已启动，数据密钥已初始化。
.\deploy\Test-Local.ps1 -BackendOnly

# 前端单测、严格类型检查、生产构建。
.\deploy\Test-Local.ps1 -FrontendOnly

# 后端运行时的可复现 API 闭环检查（创建带独立编码的验收空间）。
node .\deploy\Smoke-Api.mjs

# 已完成 API 冒烟并重启应用/数据库后，核验原候选和历史记录。
node .\deploy\Check-Persistence.mjs

# P2 供给、守卫与冻结知识真实 HTTP；应用重启后再执行持久化检查。
node .\deploy\Smoke-Supply.mjs
node .\deploy\Check-Supply-Persistence.mjs
node .\deploy\Check-Supply-Edges.mjs

# 详设 §8.8 四段 JSON 与实际契约测试资源一致性。
node .\deploy\Check-Design-Samples.mjs
```

测试库与运行库分别为 `onto_test`、`onto_test_p4`（P4 outbox 专属）和 `onto_dev`；`Test-Local.ps1` 会自动切换到 `onto_test_p4`，无需手工改配置。测试缺少配置或数据库不可达时必须失败，不能用跳过测试代替通过。真实命令结果见 `docs/evidence/`，无需将 Maven 日志或凭据粘贴到需求台账。

同一个测试库不能并行运行会清理夹具的集成套件。并行开发时使用本工程独立测试库及进程内 `ONTO_TEST_DB_URL` 覆盖；Maven 共享模块的 `target` 仍需串行构建。执行脚本默认约束 Maven/Test JVM 为 512 MiB、Node 为 1024 MiB。

Windows 会锁定运行中的 JAR。服务已启动时，先执行 `.\deploy\Stop-Local.ps1` 保留数据库，再运行后端测试/打包；完成后用 `.\deploy\Start-Local.ps1` 重新启动。

## 数据与制品开发环境

数据源管理接入前运行 `.\deploy\Initialize-Data.ps1`，首次生成随机 32 字节 AES-GCM 密钥及本工程受管文件目录；重复运行保留原密钥。不要随意替换 `ONTO_DATASOURCE_KEY`，旧候选密文需要同一密钥才能解密。

`.\deploy\Initialize-Samples.ps1` 在独立 `onto_sources` 库建立任务/算法样例，包含A、B、未分类和A关联B的反例，不覆盖已有样例行。`Start-Sample-Http.ps1` / `Stop-Sample-Http.ps1` 显式管理环回地址19180上的HTTP验收样例，GET/固定POST的 `/tasks` 每页2条，A数据在第二页，便于验证分页授权过滤；正常业务启动不自动启动该样例。P3管理API接入后可运行 `node .\deploy\Smoke-Data.mjs` 完成公开接口联调，实际通过状态以证据记录为准。

`Initialize-LakeSamples.ps1` 复用本工程onto-data测试已解析的依赖，生成并上传真实Parquet到独立随机前缀，报告 `p3-lake-fixture.json` 仅含安全定位。需先完成onto-data测试及启动MinIO；脚本不调用并发Maven、不覆盖原对象，也不把“上传成功”当作管理/查询链路验收。

`Initialize-DocumentSamples.ps1` 在运行过 `P3IngestTest` 后可生成真实 XLSX/DOCX/PDF，并回读核验内容。与既有CSV/JSON/TXT一起供公开上传接口验收使用，PDF预览及文件摘要在`docs/evidence/continuation/`。完成 `Smoke-Data.mjs` 且应用重启后，`Check-Data-Persistence.mjs` 重放原候选，核验各协议结果、冻结源范围和持久来源证据；脚本准备与实际通过分别记载。

`Smoke-Data-Transforms.mjs` 使用单独空间和受管 CSV 验证转换过滤、授权坏值隔离、LIMIT 后及前谓词不匹配时的转换失败、冻结码表隔离及引用阻删。P4 接入后，`Smoke-Release.mjs` 验证发布/消费/弃用/本地通知闭环，重启后用 `Check-Release-Persistence.mjs` 核验原版本与事件。脚本存在不代表该阶段已经通过，执行状态以持续实施记录为准；重启检查应先于重新运行冒烟脚本，以免覆盖原候选证据索引。

`Smoke-Data-Guards.mjs` 通过本机临时 HTTP 服务计数，证明缺 `tenant_label` 绑定时 TRIAL / SERVICE / LOCAL_TEST 三入口均不能通过且源请求为零。`Smoke-Release-Data.mjs` 补充正式 CSV 转换、空间/策略/端点范围交集、过期与撤销授权、真实 trace、二版本差异和恢复；成功并重启应用后运行 `Check-Release-Data-Persistence.mjs`，检查同一版本、来源引用和历史执行仍可使用。P3 实际结果已记录，P4 仍以各脚本生成的最终证据为准。

P4 事件积压与应用重启可单独复现。停止本工程 API/Vite 后，在启动终端设置 `$env:ONTO_RELEASE_RELAY_ENABLED='false'`，再运行 `Start-Local.ps1` 与 `node deploy/Smoke-Release.mjs --defer-events`。脚本要求发布后弃用的两个原事件均为 PENDING 且收件箱为空。随后停止 API/Vite，移除该进程变量并重新启动，运行 `node deploy/Check-Release-Persistence.mjs --deferred`；检查原事件 ID、绑定状态、接收顺序和弃用后的拒绝消费。两阶段写入独立的 `p4-deferred-*.json`，不会覆盖正常投递验收记录；不要修改事件数据来制造通过。

在独立开发环境验证对象存储停机时，先完成正式数据脚本，停止本工程 MinIO，再运行 `node deploy/Check-Release-Availability.mjs --unavailable`；使用 `Start-Storage.ps1` 恢复后运行同脚本的 `--recovered` 模式。脚本只验证原正式版本：不可达时503且保持READY、没有损坏告警，恢复后原摘要/原结果保持；不删除或改写任何对象。应与真实MinIO集成测试错开，失败时也须恢复存储进程。

制品存储使用本工程独立 MinIO，`.\deploy\Start-Storage.ps1` / `Stop-Storage.ps1` 管理自己的进程。默认端口 19100/19101，运行数据和凭据留在 `.runtime`，详细验证见[制品报告](docs/evidence/continuation/c6-artifact-report.md)。本地二进制为开发验证版本，生产需使用可追溯固定发行版。

本机不启动 Docker。用户已确认 ClickHouse、MongoDB、Redis、Cassandra、Neo4j 五类适配代码完成后保留真实实例待外部验收；[补验清单](docs/external-datasource-acceptance.md)列出逐协议样例及证据要求。PostgreSQL、MinIO Parquet、文件与本地受控 HTTP 的实际测试分别记录，不互相替代。

## 首批交付边界（历史记录）

以下描述首批制品的范围，当前进度以[持续实施记录](docs/evidence/continuation/progress.md)为准；P1后续能力已完成，P2～P4按各自证据继续验收。

- 已完成的首批属性范围只含类型/必填/说明，尚不包含高级分面、单位码表、关系/约束/规则、TTL/OWL 导入、ELK 推理和正式发布。
- 操作只支持包内模型的受限 SELECT 查询；外部数据源实例查询属于 P3，不能用模型元查询冒充。
- 候选保存完整首批模型和操作，加载时复核摘要，草稿修改不影响旧候选。候选载荷当前持久化在 PG；P4 再升级详设要求的 MinIO 制品归档、正式 manifest、五项门禁和 CAS 发布。
- 本批没有 `release`、READY 发布版本或 RELEASED 事件，不应作为智能体平台的正式本体发布服务接入。
- 本地环境脚本用于开发和验证；生产凭据、TLS、备份和部署拓扑需在交付阶段单独配置。

## 实现参考

查询资源释放与结果读取依据 [Apache Jena ARQ 应用 API](https://jena.apache.org/documentation/query/app_api.html)。项目在 Jena API 之上额外限制查询语法与外部访问，详细约束由查询策略测试覆盖。组件和交互依据已批准的详设与内部接口契约，不引入未确认的业务流程。
