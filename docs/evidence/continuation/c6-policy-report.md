# P4 门禁与事件资格领域核

独立 `onto-release` 模块，尚未加入 reactor。此报告只证明确定性领域规则，不证明发布事务、数据库领取或事件真实投递完成。

实现：

- ReleaseGatePolicy：同摘要五项报告、同草稿修订、真实启用用例数量；缺项/重复报告/错摘要/零用例均阻断。仅 OWNER、同候选摘要/当前修订且理由非空的审批，可覆盖 CONSISTENCY 的 NOT_CHECKED(SUBSET_EXCEEDED)。守卫失败、超时、引擎异常、INCONSISTENT 和其他门禁不可豁免。
- OutboxPolicy：根据 bound_status 判定资格，无 release 当前状态输入；同版本最早 PENDING 即使未到期也阻塞后序，SENT/DEAD 不阻塞。非法资格交给持久层隔离为 DEAD，有限退避，人工重放保留原 id/资格并重置本轮预算（累计尝试由审计持久保留）。

RED：`.runtime/release-policy-red.log`，实现尚不存在。GREEN：2026-10-20 22:09:16，`mvn -B -f onto-release/pom.xml '-DargLine=-Xmx512m' test`，14 条全部通过，`.runtime/release-policy-green.log`。已使用 google-java-format AOSP 格式化。

测试包括停机期间发布后弃用的先后顺序、弃用重试期间损坏直至 DEAD 后告警可达、不同版本并行资格、重启时沿持久 due time 判断、重放原事件身份、各不可豁免原因。真正的五场景数据库/目的地联调仍在 C6 后续任务；不可用纯函数测试替代。

独立审查待完成。接入时数据库查询/原子领取要与领域规则一致，不可只在测试中调用 policy 而运行代码另写不同语义。
