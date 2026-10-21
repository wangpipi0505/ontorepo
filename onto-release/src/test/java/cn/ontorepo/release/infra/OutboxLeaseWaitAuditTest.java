package cn.ontorepo.release.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.OutboxPolicy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 独立复核：确认/失败写回若等待行锁跨越租约期限，取锁前的时间不能继续授权写回。 */
class OutboxLeaseWaitAuditTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leaseMustStillBeValidAfterWaitingForTheEventRow(boolean acknowledge) throws Exception {
        try (var database = new OutboxDatabase()) {
            // 使用生产数据库时钟构造器；真实等待合法的最短租约，不修改持久期限或系统时钟。
            var store = new JdbcOutboxStore(database.source, Duration.ofSeconds(5), 3);
            var release = database.release("READY");
            try (var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                store.append(
                        connection,
                        database.space,
                        release,
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        "{}");
                connection.commit();
            }
            var delivery = store.claim(1).getFirst();
            var worker = Executors.newSingleThreadExecutor();
            try (var blocker = database.source.getConnection()) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var statement = blocker.createStatement();
                        var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertTrue(row.next());
                    blockerPid = row.getInt(1);
                }
                try (var statement =
                        blocker.prepareStatement(
                                "SELECT id FROM publish_outbox WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, delivery.id());
                    try (var row = statement.executeQuery()) {
                        assertTrue(row.next());
                    }
                }
                try {
                    var result =
                            worker.submit(
                                    () ->
                                            acknowledge
                                                    ? store.acknowledge(delivery)
                                                    : store.fail(
                                                            delivery, "DESTINATION_UNAVAILABLE"));
                    awaitBlocked(database, blockerPid);
                    assertTrue(leaseIsValid(blocker, delivery.id()), "反例必须先证明写回在租约有效时已经等待行锁");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (leaseIsValid(blocker, delivery.id()) && System.nanoTime() < deadline)
                        Thread.sleep(25);
                    assertFalse(leaseIsValid(blocker, delivery.id()), "等待数据库时钟越过原租约期限");
                    blocker.commit();
                    assertFalse(result.get(10, TimeUnit.SECONDS), "已在行锁等待中到期的租约不能再确认成功或记失败");
                    assertEquals("PENDING", database.text("status", delivery.id()));
                    assertEquals(
                            delivery.leaseToken().toString(),
                            database.text("lease_token", delivery.id()));
                } finally {
                    blocker.rollback();
                    worker.shutdown();
                    assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
                }
            } finally {
                worker.shutdownNow();
            }
        }
    }

    private static boolean leaseIsValid(Connection connection, long id) throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "SELECT lease_until>clock_timestamp() FROM publish_outbox WHERE id=?")) {
            statement.setLong(1, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getBoolean(1);
            }
        }
    }

    private static void awaitBlocked(OutboxDatabase database, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (var connection = database.source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity WHERE"
                                    + " ?=ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, blockerPid);
            while (System.nanoTime() < deadline) {
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next());
                    if (row.getInt(1) > 0) return;
                }
                Thread.sleep(10);
            }
        }
        fail("确认/失败写回应已被目标行锁阻塞");
    }
}
