# C8 Outbox 时间与租约整改

日期：2026-10-21。生产装配统一使用当前事务连接上的 PostgreSQL `clock_timestamp()`，应用节点时钟不再决定领取、到期、重试或确认。保留显式 `Clock` 构造器供原确定性协议测试使用，`ReleaseConfiguration` 必须使用数据库时间构造器。数据库自身时钟回拨时保守等待；不能用 300 秒退避上限截短正在生效的租约。

## 节点时钟反例

`OutboxClockAuditTest` 使用实际生产配置和真实 PostgreSQL；仅将第二节点的测试时钟提前/推后一天，验证其不能抢占当前租约，正常确认与后续领取仍可完成。`.runtime/c8-outbox-clock-red-p4.log` 是有效 RED，第二节点曾抢占活跃租约；初次误用普通测试库的 `.runtime/c8-outbox-clock-red.log` 被严格隔离夹具拒绝，不计业务反例。

修复后 `.runtime/c8-outbox-clock-green.log` 包含原仓储协议 13 项和新增 1 项，共 14 项通过，0 失败/错误/跳过，耗时 19.417 秒。

## 独立复核补充：行锁等待跨越期限

复核者新增 `OutboxLeaseWaitAuditTest`：真实合法最短 5 秒租约，在租约仍有效时以另一连接持有事件行锁；确认或失败写回实际进入 PostgreSQL 锁等待，直到数据库时间自然越过期限才释放阻塞。此前取锁前读取的时间被继续用于条件，两个分支均错误返回 true。

有效 RED：`.runtime/c8-outbox-lease-wait-red.log`，2026-10-21 10:13:34 结束，2 项、2 预期失败、0 错误/跳过，16.680 秒。没有修改租约字段、服务器时钟或测试断言来制造过期。

已修复：`acknowledge` 和 `fail` 先按事件 ID、PENDING 状态、当前 lease token 取得行锁，再读取权威时间核验期限；同一事务内更新事件与累计尝试。不能仅将时间函数放进 WHERE，因为查询可能先判条件再等待行锁。过期确认/失败都返回 false，不改变事件、租约或尝试结果；仍由既有领取恢复逻辑处理到期事件。

追加修复 GREEN：`.runtime/c8-outbox-final-green.log`，10:15:32 结束，40.838 秒，共 27 项通过、0 失败/错误/跳过：LeaseWait 2、JdbcStore 13、Clock 1、Relay 10、ReleaseEventService 1。实际 PostgreSQL 的两个原失败分支均转绿；独立复核与最终完整 verify 另按后续证据记录。
