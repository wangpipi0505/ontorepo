package cn.ontorepo.release.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class OutboxPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");

    private OutboxPolicy.Event event(
            long id,
            String release,
            OutboxPolicy.Type type,
            String bound,
            OutboxPolicy.Status status,
            int retry,
            Instant due) {
        return new OutboxPolicy.Event(id, release, type, bound, status, retry, due);
    }

    @Test
    void releaseThenDeprecationWhileRelayStoppedPreservesHistoricalOrder() {
        var released =
                event(
                        1,
                        "r",
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        var deprecated =
                event(
                        2,
                        "r",
                        OutboxPolicy.Type.DEPRECATED,
                        "DEPRECATED",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        // 故意没有当前版本状态参数：后续弃用不能撤销已创建 RELEASED 的投递资格。
        assertEquals(
                List.of(new OutboxPolicy.Decision(1, OutboxPolicy.Action.DELIVER)),
                OutboxPolicy.next(List.of(deprecated, released), NOW));
        var sent =
                event(
                        1,
                        "r",
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        OutboxPolicy.Status.SENT,
                        0,
                        NOW);
        assertEquals(2, OutboxPolicy.next(List.of(deprecated, sent), NOW).getFirst().eventId());
    }

    @Test
    void deprecationRetryBlocksCorruptionUntilDeadThenCorruptionCanProceed() {
        var deprecated =
                event(
                        2,
                        "r",
                        OutboxPolicy.Type.DEPRECATED,
                        "DEPRECATED",
                        OutboxPolicy.Status.PENDING,
                        1,
                        NOW.plusSeconds(30));
        var corrupt =
                event(
                        3,
                        "r",
                        OutboxPolicy.Type.CORRUPTED_ALERT,
                        "CORRUPTED",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        assertTrue(OutboxPolicy.next(List.of(deprecated, corrupt), NOW).isEmpty());
        var failed = OutboxPolicy.failure(deprecated, NOW, 2);
        assertEquals(OutboxPolicy.Status.DEAD, failed.status());
        var dead =
                event(
                        2,
                        "r",
                        deprecated.type(),
                        deprecated.boundStatus(),
                        failed.status(),
                        failed.retryCount(),
                        failed.nextRetryAt());
        assertEquals(3, OutboxPolicy.next(List.of(dead, corrupt), NOW).getFirst().eventId());
    }

    @Test
    void independentReleasesProgressAndMalformedEventIsQuarantined() {
        var future =
                event(
                        1,
                        "a",
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW.plusSeconds(10));
        var invalid =
                event(
                        2,
                        "b",
                        OutboxPolicy.Type.RELEASED,
                        "DEPRECATED",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        var valid =
                event(
                        3,
                        "c",
                        OutboxPolicy.Type.CORRUPTED_ALERT,
                        "CORRUPTED",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        assertEquals(
                List.of(
                        new OutboxPolicy.Decision(2, OutboxPolicy.Action.REJECT),
                        new OutboxPolicy.Decision(3, OutboxPolicy.Action.DELIVER)),
                OutboxPolicy.next(List.of(valid, invalid, future), NOW));
    }

    @Test
    void retriesAreBoundedAndRestartSelectionUsesPersistedDueTime() {
        var pending =
                event(
                        1,
                        "r",
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        var retry = OutboxPolicy.failure(pending, NOW, 3);
        assertEquals(1, retry.retryCount());
        assertEquals(NOW.plusSeconds(5), retry.nextRetryAt());
        var persisted =
                event(
                        1,
                        "r",
                        pending.type(),
                        pending.boundStatus(),
                        retry.status(),
                        retry.retryCount(),
                        retry.nextRetryAt());
        assertTrue(OutboxPolicy.next(List.of(persisted), NOW.plusSeconds(4)).isEmpty());
        assertEquals(1, OutboxPolicy.next(List.of(persisted), NOW.plusSeconds(5)).size());
    }

    @Test
    void replayKeepsEventIdentityAndBecomesEarliestPendingAgain() {
        var dead =
                event(
                        1,
                        "r",
                        OutboxPolicy.Type.RELEASED,
                        "READY",
                        OutboxPolicy.Status.DEAD,
                        3,
                        NOW.minusSeconds(10));
        var replay = OutboxPolicy.replay(dead, NOW);
        assertEquals(1, replay.id());
        assertEquals(dead.type(), replay.type());
        assertEquals(dead.boundStatus(), replay.boundStatus());
        assertEquals(0, replay.retryCount());
        var later =
                event(
                        2,
                        "r",
                        OutboxPolicy.Type.DEPRECATED,
                        "DEPRECATED",
                        OutboxPolicy.Status.PENDING,
                        0,
                        NOW);
        assertEquals(1, OutboxPolicy.next(List.of(later, replay), NOW).getFirst().eventId());
        assertThrows(IllegalArgumentException.class, () -> OutboxPolicy.replay(later, NOW));
    }
}
