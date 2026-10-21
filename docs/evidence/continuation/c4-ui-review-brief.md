# P2 前端独立审查

评审本次 P2 前端任务，需求 `c4-ui-brief.md` 与 `contracts/p2-supply.md`；实现报告 `c4-ui-report.md`，审查包 `c4-ui-review-package.txt`。没有提交（仓库全新未跟踪），package提供本次P2全文件及相关共享文件；其他P1实现已有独立复审，不重新扩大检查范围。

任务已跑原50+新增17=67tests、typecheck/format/build全通过；无需重跑完整测试。对具体疑点补定向probe或组件测试，运行Node1024m/Vitest2。P2后端仍实施，不能把尚未联调作为已完成，也不将后端尚未实现端点误报为UI设计缺陷。

重点范围：助手Schema/示例值类型和协议枚举、OWNER设置/授予与普通写入口、修订冲突保留/迟到响应/切换操作或空间、候选摘要验证与preview四元组区分、内部调用meta失败处理、冻结词典来源关联、NL选择及默认精确匹配、ApiClient.requestEnvelope共享改动兼容已有调用。按计划确认组件化和390px CSS，不用jsdom冒充真实截图（root负责浏览器）。

只写 `c4-ui-review.md`，不修改业务。发现须具体路径/行/触发/后果/优先级及可复现证据；给spec和quality两项结论。无问题清楚说明范围/局限。
