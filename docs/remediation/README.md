# 整改执行包（R1~R3）

> 版本 v1.0，2026-10-21
> 本目录是**执行侧**的整改入口：决策、批次、任务清单与关闭规则。设计与验收基线仍在仓库外 `文档/设计/详细设计/领域本体构建/`（详设 v2.10、验收对照表、整改实施计划 v1.1、任务拆分 v1.0）。

## 1. 当前状态

- 代码基线：后端 407 项、前端 31 文件/259 项、typecheck/build 通过；JAR `63B3A145…A674C`。
- 验收结论：**有条件通过**；P1 共 5 项，其中 1 项（画布/证据图）已决策在途，4 项待实现。
- 本批次：R1 验收解锁 → R2 体验与画布 → R3 质量优化。
- 决策：DEC-1~DEC-6 已确认，见 [decisions.md](decisions.md)。

## 2. 批次

| 批次 | 目标 | 任务 | 估算 | 出口 |
|---|---|---|---|---|
| R1 | 关闭 4 项未响应 P1、统一文档口径、恢复可复现 | A1~A4、D1~D4 | 约 12 人日 | P1-2~P1-5 有代码+测试+页面；对照表状态列可用 |
| R2 | 关闭 P1-1，交付 IA 与画布 | OM-UI-GRAPH、B2、A5、B3、B4 | 约 18.5 人日 | 8 区 14 页；2.1③④/2.5④ 走查通过 |
| R3 | 清 P2 与卫生尾项 | C1~C9、D5 | 约 18 人日 | P2 关闭或转外部；证据归档 |

## 3. 任务索引

| 编号 | 任务 | 批次 | 执行卡 |
|---|---|---|---|
| OM-R-A1 | MODEL_ONLY 发布口径（DEC-1=A） | R1 | [r1-checklist.md](r1-checklist.md#om-r-a1) |
| OM-R-A2 | 服务端点启停 | R1 | [r1-checklist.md](r1-checklist.md#om-r-a2) |
| OM-R-A3 | NL 结果解释 + EXPLAIN | R1 | [r1-checklist.md](r1-checklist.md#om-r-a3) |
| OM-R-A4 | 审计要素与检索 | R1 | [r1-checklist.md](r1-checklist.md#om-r-a4) |
| OM-R-D1 | git 基线与卫生 | R1 | [r1-checklist.md](r1-checklist.md#om-r-d1) |
| OM-R-D2 | 验收对照表状态列 + D1~D9 | R1 | 文档线（仓库外） |
| OM-R-D3 | 详设/开发计划同步 | R1 | 文档线（仓库外） |
| OM-R-D4 | 测试脚本可复现 | R1 | [r1-checklist.md](r1-checklist.md#om-r-d4) |
| OM-UI-GRAPH | 图谱画布 + 证据关系图（DEC-2 全量） | R2 | 见实施计划 B1 |
| OM-R-B2 | IA 重组 S1~S6 | R2 | 见实施计划 B2 |
| OM-R-A5 | 证据图节点交互 | R2 | 见实施计划 A5 |
| OM-R-B3/B4 | 就绪度/搜索/面包屑；术语与错误 | R2 | 见实施计划 B3/B4 |
| OM-R-C1~C9 | 质量优化 | R3 | 见实施计划 WS-C |

## 4. 关闭规则（Definition of Done）

1. 代码合入 + 真实 PostgreSQL/MinIO 测试通过（新增先 RED 后 GREEN）。
2. 页面/接口证据（截图或 HTTP JSON）落 `docs/evidence/continuation/r*-<task>.md`。
3. 验收对照表状态更新为"已实现/部分实现"并附证据链接。
4. 台账状态更新；未运行验证不得标关闭。
5. 涉及迁移只追加、不改历史；涉及权限必须后端显式校验。

## 5. 约束

- 不修改相邻工程（`代码/agent-platform` 等）；不引 H2/内存 Map 代替持久化。
- 空间写入必须走空间行锁 + `expectedDraftRev`；候选载荷不可变。
- 画布编辑复用既有 `ont_class/ont_relation` CRUD 与 `useDraftRevision`，不新增写接口。
- 外部五类数据源、跨系统 J-01~J-05 不在本批次（OM-P3-EXT / OM-P4-EXT）。

## 6. 关联证据

- 后端全量：`docs/evidence/continuation/c8-full-verify.md`
- 前端/浏览器：`docs/evidence/continuation/c8-ui-fixes.md`、`c8-browser.md`
- 集成：`docs/evidence/continuation/c8-integration-report.md`
- 独立审核：`文档/设计/评审记录/领域本体构建实现独立审核-20261021.md`
- 验收审核与体验评审：`文档/设计/评审记录/领域本体构建验收审核与体验评审-20261021.md`
