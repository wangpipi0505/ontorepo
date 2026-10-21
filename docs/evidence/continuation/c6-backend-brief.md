# C6 / P4 全量发布与消费后端

先读 `contracts/p4-release.md`、详设 §7.1/§8.3/§8.4/§8.6/§8.7/§8.8 以及开发计划 P4。本任务在 P2/P3 接入通过后实施，保留历史候选原字节、完整既有回归。

## 所有权与复用

负责 `onto-release/` 的 Spring/PG 服务及现有后端必要集成、父 pom；不编辑 web/deploy/正式设计/onto-data/onto-contract/onto-artifact。纯 `onto-artifact` 已独立审查通过，真实MinIO6例；`onto-release` 门禁/事件领域核14例及独立1851断言探针已复审通过，读取 c6-policy-report.md / c6-policy-review.md，复用该规则但仍须验证实际持久化调用链。

复用已验证 ArtifactStore 条件写与 loadVerified，不能重写简易 HTTP 上传覆盖不可变制品。服务通过配置 ONTO_STORAGE_* 建立唯一客户端；root 启动的19100/19101为本工程独立实例。追加下一编号迁移，不改已应用的V1..当前版本。

## 必须完成

1. 候选freeze即预分配标识/流水版本（失败作废不复用）、同步编译/上传/复核bundle，完整冻结知识/源/映射/guard/服务绑定意图；失败持久FAILED，无release。新候选所有执行从真实MinIO加载校验，不在对象损坏时回退PG草稿或payload。
2. 五门禁状态机与test_run、候选摘要绑定；CONTRACT失败不进入TESTING；人工REVIEW独立；超范围仅已有OWNER同摘要修订豁免可覆盖。缺用例明确阻断发布。旧MODEL_ONLY不获得供给能力。
3. prepare归档正式manifest、context pack、真实Lucene物理索引；预分配四元组填充，route固定release，且全制品可校验才READY_TO_COMMIT。上传故障停REVIEWING可重试。
4. Commit事前实对象摘要检查，空间锁/CAS/同候选幂等，在短事务五写release/version/context_pack/outbox/candidate；并发重复提交只一release一RELEASED。不能网络操作包在提交大事务中；检查与事务间状态再次校验。
5. bundle投影、版本diff/浏览、从经核验历史bundle重建草稿新rev（包括所有P1-P3域），旧版本/候选/事实不改。READY→DEPRECATED和损坏→CORRUPTED及outbox原子。
6. 服务端点/端点授予/API凭据hash+过期撤销、内外固定版本执行，复用现有冻结执行链并支持subject RELEASE，不得套候选id假装版本。六步授权顺序、四层范围交集、endpoint收窄、无绑定零源访问；无空间授权即使endpointgrant也拒绝。
7. 成功body meta与X-Ontology-Release头精确来自实际执行版本；失败没有成功meta/版本头。消费manifest/pack仅READY；历史通知不等于可消费。
8. 持久outbox relay：资格bound_status、release内id序、PENDING重试屏障、DEAD不阻塞、OWNER重放原id、重启一致。默认PG真实inbox确认接收+唯一id去重，可配置HTTP目的地但测试仅本地受控服务。事务外发送、领取租约/超时恢复，重试保留同payload；不要把日志打印当SENT。
9. 定时制品校验区分存储不可达和已确认损坏；损坏事务隔离+CORRUPTED_ALERT无拉取地址。定时任务受限批量，防止重复告警。
10. trace/API：事实根、授权快照、步骤、真实证据、时间线/图，空间权限；审计分页。P3证据完整性保留；不得臆造源记录或图节点。

## 测试与交付

TDD，真实独立PG/MinIO，完整 §7.1 五事件场景与故障恢复、CAS/重复并发提交、冻结编译失败、正式manifest准备中断、损坏隔离、旧版本改稿后行为不变、restore后旧候选不改、scope A/B/null及endpoint仅A、三入口tenant无绑定零源访问。人工审批/凭据权限和跨空间负例。直接API key调用持久EXTERNAL事实，无智能体平台也完整追溯。

root负责Maven共享窗口、运行配置及启停；禁止Docker/邻项目资源。JDK21/Maven512m和ASCII Unix-domain临时目录。自动测试专属 `onto_test_p4` 已就绪，Import环境后覆盖 `ONTO_TEST_DB_URL=jdbc:postgresql://127.0.0.1:15472/onto_test_p4`，保留本工程用户名/密码，不清理其他库。报告 `c6-backend-report.md`，完整场景命令/结果/差异/剩余范围；不提交Git。独立部署inbox通过不能称为智能体平台上架通过。
