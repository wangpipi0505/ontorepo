# C4 / P2 后端独立审查任务

请独立检查需求符合性与代码质量，两项分别给出结论。依据 `c4-backend-brief.md`、`contracts/p2-supply.md`、`c4-backend-report.md`、工程 AGENTS.md；代码包 `c4-backend-review-package.txt` 含本批新文件及主要集成点。当前工程尚无提交，包是明确列举的当前源码，不伪称 Git 差异。必要时读取关联源码；不要扫描 node_modules、target 或相邻工程。

本批包括助手声明/契约、OWNER 守卫与主体授权、冻结的 TRIAL/LOCAL_TEST/SERVICE 三入口授权、失败事实、知识/context-pack/真实 Lucene、确定性 NL 与候选能力。C1 既有模型/导入已独立审查关闭；契约纯核89条也已独立审查关闭。本次需检查接入是否破坏这些边界，不能以既有纯核通过推定新调用者正确。

重点核验：

- 授权行缺失拒绝；已存在记录的 null dataScope 才表示不限。实际身份/属性来自服务端。跨空间、VIEWER、最后 OWNER、过期修订与权限检查/锁/审计同事务。
- `tenant_label` 未定义/无绑定在三入口统一拒绝，执行器零调用，失败事实持久化。任何源列过滤绑定在 P2 模型执行中都不得被当作已应用过滤。MODEL_ONLY 旧候选不能获得 CONTRACT/内部供给资格。
- 候选声明/知识/模型/策略/用例不可变；冻结后改草稿不改变结果。输入/输出均用冻结 Schema，成功 meta 为实际上下文，失败无 meta。预览 manifest 四元组全 null。
- Lucene 确实进行检索，词条及来源稳定关联，缓存有实际容量界限、可重建；context-pack 的概念/属性/问答明确来源及期望断言性质。
- NL 选择不能偷读 expected operation；命中/歧义/参数示例/EXACT/CONTAINS 与实际执行状态一致。不得伪造自然语言理解能力或生成执行证据。
- 控制器保持薄层，错误信息不泄漏 SQL/凭据，关键不变量有中文注释，API 与前端固定契约一致。

已执行证据：完整 reactor 190条零失败（`c4-final-verify.log`），三种守卫缺失×三入口严格零执行器调用定向通过（`c4-zero-executor.log`）；真实HTTP6项通过 `p2-http-smoke.json`，P1 HTTP8项通过，OpenAPI真实导出49路径。它们不能替代独立审查。

允许读取源码、现有测试与日志，必要时在独立临时目录创建探针/编译；不写产品代码、不覆盖共享 target、不运行共享清库夹具。若需真实PG独立验证，先向root申请自己的schema/库或Maven窗口。不得停止本工程或相邻服务，不启动Docker。应用正在由root做页面联调。所有问题整批列出，含触发条件、影响、精确位置与可复核证据，落 `c4-backend-review.md`；无问题也要明确 Spec 与 Quality 的实际覆盖边界。
