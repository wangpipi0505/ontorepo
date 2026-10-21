# C4 / P2 后端实施任务

需求权威：`contracts/p2-supply.md`、详设 §8.7/§8.8、开发计划 P2（工程上两级文档目录）。C1 最后一项修复仅限 ImportService 与 C1ReviewRegressionTest，P2 不得编辑这些文件；其他范围已完成。保留全部回归及旧候选字节。P2 前端已按固定契约完成 67 条测试/构建，接口差异必须及时同步。

## 所有权

负责现有 `onto-common/onto-space/onto-model/onto-operation/onto-trace/onto-designer-api` 与可新增 `onto-knowledge/`、父 `pom.xml` 的 P2 依赖；不编辑 `web/`、`onto-contract/`、`onto-artifact/`、`onto-data/`、deploy 或正式设计。公共 API/行为使用现有层次，不能把所有功能堆进 CandidateService。新迁移只能追加，不改已在本机运行过的迁移。

`onto-contract` 已完成独立复审，发现 1～6 全部关闭，89 条测试通过；API见其任务brief/源码，请加入reactor并依赖。`onto-data` 已有 CredentialCipher/LabelScope/DataProblem基础，LabelScope可按需依赖以统一范围表达（独立7例已过），适配器另任务正在本模块并行；不要将其称为九类数据源已验收。

## 实现

1. 助手声明独立管理接口、三段Schema/示例/守卫、按冻结策略资源限制生成契约预览；契约消费校验端点，完整错误code/field/reason不得丢。将声明、guardCfg、知识、候选格式标记完整冻结，禁止执行期回读草稿。预览四元组全部null，包装candidateSha单独为实际摘要；正式化留P4。
2. OWNER守卫配置与空间主体授权（含grant-users）、最后OWNER保护；授权记录缺失拒绝，dataScope null为存在记录不限。主体属性来自服务端数据库授权行，不接受客户端执行输入冒充属性；修改授权不改冻结对象定义，但当前授权须每次即时检查。
3. 统一候选执行授权：TRIAL/LOCAL_TEST/SERVICE内部入口共用冻结守卫复核，未定义/无绑定先拒绝且执行器零访问。PRINCIPAL_ATTRIBUTE绑定校验真实主体属性和已定义范围；过滤列绑定在P2模型查询不可适用时明确拒绝，P3接入真实查询下推。入口权限与范围保留六步顺序，guard错误不会被通用400吞掉或当执行成功。
4. 内部候选端点按 opCode 实际选择冻结操作，input/output使用冻结JSON Schema；成功响应data仅业务columns/rows或映射业务数据，meta取真实执行上下文，失败无meta。失败执行持久化后再抛HTTP错误，不得因外层事务回滚而丢事实。旧READY release内部端点待P4接入，当前不可假造发布表或返回READY。
5. 旧P1兼容边界：既有候选字节完全不变；未配置助手声明的纯包内模型操作仍可按MODEL_ONLY能力做P1本地试执行，清楚标为不具备供给/正式发布资格；不能经内部服务端点执行。任何已声明guard_attrs的操作必须执行守卫复核，不能借“兼容”绕过tenant_label无绑定反例。新外部数据操作（P3）一律要求完整声明与守卫，不能退回MODEL_ONLY。冻结格式显式记录能力/版本，不把无声明候选默认当CONTRACT通过。
6. 知识CRUD/同空间引用/来源、真实Lucene中文索引和context pack。索引按冻结摘要缓存、可从持久候选重建；上限避免无界内存，物理索引在P4归档。必须真实Lucene检索，不使用contains模拟。前端依据冻结 termDictionary 的 id（或 entryId）和 sourceRef 关联搜索来源，请稳定提供这些字段。
7. NL用例DIRECT/NL模式；NL的操作选择来自冻结术语/操作声明/示例，不能取expected operationId作为选择结果。歧义或未命中明确失败，TERM_MATCH持久化。操作命中与成功状态默认强制，EXACT完整结果，CONTAINS显式放宽。保持规则/评分步骤实际执行与策略边界。

## 测试与交付

先RED再GREEN，真实PG集成；现有回归全部保留。新增反例包括三种缺守卫、三候选入口无绑定且零执行、假身份属性、缺spaceGrant、VIEWER、跨空间/过期修订、Schema输入输出失败、冻结后改声明/知识仍用旧值、Lucene十条问题至少八命中且有可审计分母/预期表、NL错操作/歧义不能假通过、元数据成功和失败注入区别。

数据库与本地运行配置：`. deploy/Environment.ps1; $null=Import-OntoEnvironment; Set-OntoJava; Set-OntoBuildLimits`，Maven/测试JVM512m，真实PG已运行。不能输出密码。打包前通知root停本工程服务；不自行结束其他Java进程。不启动Docker。

可以参考并扩展既有测试fixture；不能注入会对日常业务生效的测试后门。输出 `docs/evidence/continuation/c4-backend-report.md`：完整实现、RED/GREEN命令结果、实际验收清单与未覆盖边界、OpenAPI同步情况。不提交Git，不以局部纯核通过替代P2出口。
