package cn.ontorepo.release.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.OutboxPolicy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

/** 两个生产配置仓储共享真实 PG；模拟应用节点时钟跳变，活跃租约不能被另一节点提前抢占。 */
class OutboxClockAuditTest {
    @Test
    void nodeClockJumpCannotStealAnActiveLeaseOrPostponeNewEvents() throws Exception {
        try (var database = new OutboxDatabase()) {
            var config = new ReleaseConfiguration();
            var first = config.jdbcOutboxStore(database.source);
            var second = config.jdbcOutboxStore(database.source);
            var release = database.release("READY");
            try (var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                first.append(
                        connection,
                        database.space,
                        release,
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        "{}");
                connection.commit();
            }
            var claimed = first.claim(10);
            assertEquals(1, claimed.size());
            // 仅注入节点时钟，不修改系统时间或数据库时钟，也不截短实际租约。
            var field = JdbcOutboxStore.class.getDeclaredField("clock");
            field.setAccessible(true);
            field.set(second, Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
            assertTrue(second.claim(10).isEmpty(), "节点时钟提前一天仍不能抢占数据库中的有效租约");
            assertTrue(first.acknowledge(claimed.getFirst()));
            field.set(second, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
            var next = database.release("READY");
            try (var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                second.append(
                        connection,
                        database.space,
                        next,
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        "{}");
                connection.commit();
            }
            var nextDelivery = second.claim(10);
            assertEquals(1, nextDelivery.size());
            assertEquals(next, nextDelivery.getFirst().releaseId());
            assertTrue(first.acknowledge(nextDelivery.getFirst()));
        }
    }
}
