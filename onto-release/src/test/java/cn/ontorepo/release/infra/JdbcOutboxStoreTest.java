package cn.ontorepo.release.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.OutboxPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * 对真实 PG 领取/租约/历史资格/顺序/重放建立反例，不重复纯 OutboxPolicy 的真值表测试。
 *
 * <p>当前先落测试，等待 root Maven 窗口取得 RED；缺少实现不代表已观察到失败。
 */
class JdbcOutboxStoreTest {
    private OutboxDatabase database;
    private JdbcOutboxStore store;

    @BeforeEach
    void open() throws Exception {
        database = new OutboxDatabase();
        store = store();
    }

    @AfterEach
    void close() throws Exception {
        if (database != null) database.close();
    }

    private JdbcOutboxStore store() {
        return new JdbcOutboxStore(database.source, database.clock, Duration.ofSeconds(30), 3);
    }

    private long event(UUID release, OutboxPolicy.Type type, String bound) throws Exception {
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            long id =
                    store.append(
                            connection,
                            database.space,
                            release,
                            type,
                            bound,
                            "{\"releaseId\":\"" + release + "\",\"marker\":\"original-bytes\"}");
            connection.commit();
            return id;
        }
    }

    @Test
    void stateChangeAndOutboxAppendShareCallerTransaction() throws Exception {
        var release = database.release("READY");
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            store.append(
                    connection,
                    database.space,
                    release,
                    OutboxPolicy.Type.DEPRECATED,
                    "DEPRECATED",
                    "{\"reason\":\"rollback\"}");
            connection.rollback();
        }
        assertEquals(0, database.count("publish_outbox"), "回滚不能留下孤立生命周期通知");
    }

    @Test
    void releaseChangedAfterAppendDoesNotInvalidateHistoricalReleasedEvent() throws Exception {
        var release = database.release("READY");
        long released = event(release, OutboxPolicy.Type.RELEASED, "READY");
        database.releaseStatus(release, "DEPRECATED");
        long deprecated = event(release, OutboxPolicy.Type.DEPRECATED, "DEPRECATED");
        var first = store.claim(10);
        assertEquals(List.of(released), first.stream().map(JdbcOutboxStore.Delivery::id).toList());
        assertTrue(store.acknowledge(first.getFirst()));
        assertEquals(
                List.of(deprecated),
                store.claim(10).stream().map(JdbcOutboxStore.Delivery::id).toList());
    }

    @Test
    void notYetDuePendingPredecessorBlocksSuccessorEvenAcrossRepositoryRestart() throws Exception {
        var release = database.release("READY");
        long released = event(release, OutboxPolicy.Type.RELEASED, "READY");
        var delivery = store.claim(10).getFirst();
        store.fail(delivery, "DESTINATION_UNAVAILABLE");
        event(release, OutboxPolicy.Type.DEPRECATED, "DEPRECATED");
        assertTrue(store().claim(10).isEmpty(), "重建组件不能跳过尚未到期的前序PENDING");
        database.clock.advance(Duration.ofSeconds(5));
        assertEquals(released, store().claim(10).getFirst().id());
    }

    @Test
    void onlyDeadPredecessorUnblocksCorruptionAlert() throws Exception {
        var release = database.release("DEPRECATED");
        long deprecated = event(release, OutboxPolicy.Type.DEPRECATED, "DEPRECATED");
        long corrupted = event(release, OutboxPolicy.Type.CORRUPTED_ALERT, "CORRUPTED");
        for (int attempt = 0; attempt < 3; attempt++) {
            var delivery = store.claim(10).getFirst();
            assertEquals(deprecated, delivery.id());
            store.fail(delivery, "DESTINATION_UNAVAILABLE");
            if (attempt < 2) {
                assertTrue(store.claim(10).isEmpty());
                database.clock.advance(Duration.ofSeconds(attempt == 0 ? 5 : 10));
            }
        }
        assertEquals("DEAD", database.text("status", deprecated));
        assertEquals(corrupted, store.claim(10).getFirst().id());
        assertEquals(4, database.count("publish_delivery_attempt"), "每次领取留持久尝试历史");
    }

    @Test
    void concurrentClaimHasOneWinnerPerEvent() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        try (var threads = Executors.newFixedThreadPool(2)) {
            var work = (Callable<List<JdbcOutboxStore.Delivery>>) () -> store().claim(10);
            var results = threads.invokeAll(List.of(work, work));
            var claimed = new java.util.ArrayList<Long>();
            for (var result : results)
                claimed.addAll(result.get().stream().map(JdbcOutboxStore.Delivery::id).toList());
            assertEquals(List.of(id), claimed, "真实PG原子领取不能给两台relay同一有效租约");
        }
    }

    @Test
    void expiredLeaseCanRecoverButStaleAcknowledgementCannotOverwriteNewOwner() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        var abandoned = store.claim(1).getFirst();
        assertTrue(store().claim(1).isEmpty());
        database.clock.advance(Duration.ofSeconds(31));
        assertTrue(store().claim(1).isEmpty(), "租约超时也须持久计失败并执行领域退避");
        database.clock.advance(Duration.ofSeconds(4));
        var recovered = store().claim(1).getFirst();
        assertEquals(id, recovered.id());
        assertNotEquals(abandoned.leaseToken(), recovered.leaseToken());
        assertFalse(store.acknowledge(abandoned), "旧网络响应不能确认新租约");
        assertTrue(store.acknowledge(recovered));
        assertEquals("SENT", database.text("status", id));
    }

    @Test
    void replayRetainsOriginalIdentityAndPayloadAndAttemptHistory() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        String payload = database.text("payload", id);
        for (int attempt = 0; attempt < 3; attempt++) {
            store.fail(store.claim(1).getFirst(), "DESTINATION_UNAVAILABLE");
            database.clock.advance(Duration.ofMinutes(1));
        }
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            store.replay(connection, id);
            connection.commit();
        }
        assertEquals(payload, database.text("payload", id));
        assertEquals(id, store.claim(1).getFirst().id());
        assertEquals(1, database.count("publish_outbox"));
        assertEquals(4, database.count("publish_delivery_attempt"), "重放不抹除累计投递历史");
    }

    @Test
    void invalidHistoricalQualificationIsQuarantinedWithoutDelivery() throws Exception {
        var release = database.release("READY");
        long invalid = event(release, OutboxPolicy.Type.RELEASED, "READY");
        database.corruptBoundStatus(invalid, "DEPRECATED");
        long valid = event(release, OutboxPolicy.Type.DEPRECATED, "DEPRECATED");
        assertTrue(store.claim(10).isEmpty());
        assertEquals("DEAD", database.text("status", invalid));
        assertEquals(valid, store.claim(10).getFirst().id());
    }

    @Test
    void independentReleasesCanProgressWhileOneReleaseIsLeased() throws Exception {
        var left = database.release("READY");
        var right = database.release("READY");
        long first = event(left, OutboxPolicy.Type.RELEASED, "READY");
        long second = event(right, OutboxPolicy.Type.RELEASED, "READY");
        assertEquals(first, store.claim(1).getFirst().id());
        assertEquals(second, store.claim(1).getFirst().id());
    }

    @Test
    void repeatedProcessCrashesAlsoExhaustFiniteRetryBudget() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        assertEquals(id, store.claim(1).getFirst().id());
        for (int crash = 0; crash < 2; crash++) {
            database.clock.advance(Duration.ofSeconds(31));
            assertTrue(store().claim(1).isEmpty());
            database.clock.advance(Duration.ofSeconds(crash == 0 ? 4 : 9));
            assertEquals(id, store().claim(1).getFirst().id());
        }
        database.clock.advance(Duration.ofSeconds(31));
        assertTrue(store().claim(1).isEmpty());
        assertEquals("DEAD", database.text("status", id), "崩溃丢失ack不能形成无限租约续领");
        assertEquals(3, database.count("publish_delivery_attempt"));
    }

    @Test
    void deadEventHasPersistentAlertAndReplayClosesPreviousManualIntervention() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        for (int attempt = 0; attempt < 3; attempt++) {
            store.fail(store.claim(1).getFirst(), "DESTINATION_UNAVAILABLE");
            database.clock.advance(Duration.ofMinutes(1));
        }
        assertEquals(1, database.count("publish_delivery_alert"), "DEAD须进入持久告警台账");
        assertFalse(database.alertResolved(id));
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            store.replay(connection, id);
            connection.commit();
        }
        assertTrue(database.alertResolved(id));
        for (int attempt = 0; attempt < 3; attempt++) {
            store.fail(store.claim(1).getFirst(), "DESTINATION_UNAVAILABLE");
            database.clock.advance(Duration.ofMinutes(1));
        }
        assertEquals(1, database.count("publish_delivery_alert"));
        assertFalse(database.alertResolved(id), "重放再次耗尽预算须重新打开同事件告警");
    }

    @Test
    void appendIsIdempotentButCannotReplacePayloadOrAcceptInvalidQualification() throws Exception {
        var release = database.release("READY");
        long id = event(release, OutboxPolicy.Type.RELEASED, "READY");
        assertEquals(id, event(release, OutboxPolicy.Type.RELEASED, "READY"));
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            var failure =
                    assertThrows(
                            OutboxProblem.class,
                            () ->
                                    store.append(
                                            connection,
                                            database.space,
                                            release,
                                            OutboxPolicy.Type.RELEASED,
                                            "READY",
                                            "{\"changed\":true}"));
            assertEquals("EVENT_PAYLOAD_CONFLICT", failure.code());
            connection.rollback();
        }
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            var failure =
                    assertThrows(
                            OutboxProblem.class,
                            () ->
                                    store.append(
                                            connection,
                                            database.space,
                                            release,
                                            OutboxPolicy.Type.DEPRECATED,
                                            "READY",
                                            "{}"));
            assertEquals("INVALID_EVENT_QUALIFICATION", failure.code());
            connection.rollback();
        }
        assertEquals(1, database.count("publish_outbox"));
    }

    @Test
    void corruptionAlertRejectsNestedPullAddressBeforeAppend() throws Exception {
        var release = database.release("CORRUPTED");
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            var failure =
                    assertThrows(
                            OutboxProblem.class,
                            () ->
                                    store.append(
                                            connection,
                                            database.space,
                                            release,
                                            OutboxPolicy.Type.CORRUPTED_ALERT,
                                            "CORRUPTED",
                                            "{\"artifacts\":[{\"manifest\":\"https://private-storage/manifest.json\"}]}"));
            assertEquals("CORRUPTION_ALERT_CONTAINS_PULL_ADDRESS", failure.code());
            connection.rollback();
        }
        assertEquals(0, database.count("publish_outbox"));
    }
}
