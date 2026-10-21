# C5 独立适配内核评审

评审任务为 `c5-adapters-brief.md`，交付报告 `c5-adapters-report.md`，代码包 `c5-adapters-review-package.txt`（本仓库新文件未提交，以完整模块源/测试为审查包）。同时包括原有 CredentialCipher/LabelScope 基础，报告 `c5-boundary-report.md`；HTTP 管理、冻结映射及PG事实不是本次模块的职责，将由后续C5集成。

2026-10-20 22:22:16 全量21条通过，无跳过；真实PG随机schema、MinIO9真Parquet、文件CSV/JSON/Excel/junction、真实本地HTTP均有证据。不重复全部已运行测试；只为具体怀疑写定向probe/测试。Node无关，Java21/Maven512m；P2正在完整root reactor，若需编译本模块先向root协调窗口，不覆盖共享target。不能启动Docker/邻项目服务。

用户明确五类ClickHouse/MongoDB/Redis/Cassandra/Neo4j代码实现后待外部实例验收；报告必须诚实，不因此将未验协议视为已通过，也不因没有服务判代码已失败。静态检查这些边界内代码/官方API使用是否满足能力声明。

重点：参数化SQL/标识符/每表accessScope、JOIN两端标签范围、空交集/未分类拒绝且LIMIT前生效、守卫缺失网络前拒绝、类型与真实来源证据、文件canonical/junction边界、HTTP origin/分页/全body超时与size、湖前缀/真实Parquet投影/谓词、原生协议受限模板/分区键/SCAN/图两端范围、加密key/AAD/不可变范围对象、异常和toString不泄漏凭据。凡发现必须有具体可触发输入/路径/后果和行号。

只写 `c5-adapters-review.md`，不改业务代码。输出spec与quality结论，重要问题修复后再复核。当前不评P2/P4全部授权管理；不得用“需要完整上层验收”替代本次确定性缺陷检查。
