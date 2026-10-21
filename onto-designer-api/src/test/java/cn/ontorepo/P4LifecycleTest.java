package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.release.application.OutboxRelay;
import cn.ontorepo.release.domain.OutboxPolicy;
import cn.ontorepo.release.infra.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** §7.1五事件场景在真实发布表、MinIO、PG收件箱闭环；实际进程重启另外由root验收。 */
class P4LifecycleTest extends P4Fixture {
    @Autowired DataSource source;
    @Autowired OutboxRelay defaultRelay;

    static class Time extends Clock {
        Instant now = Instant.now().plusSeconds(1);

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }
    }

    JdbcOutboxStore store(Time clock) {
        return new JdbcOutboxStore(source, clock, Duration.ofSeconds(30), 3);
    }

    List<String> received() {
        return db.queryForList(
                "SELECT i.type FROM publish_inbox i JOIN ontology_release r ON r.id=i.release_id"
                        + " ORDER BY i.event_id",
                String.class);
    }

    void deprecate(String id) throws Exception {
        data(post("/onto/releases/" + id + "/deprecate"), Map.of("reason", "明确停止供给"));
    }

    void corrupt(String id, String field) throws Exception {
        String key =
                db.queryForObject(
                        "SELECT " + field + "->>'key' FROM ontology_release WHERE id=?",
                        String.class,
                        UUID.fromString(id));
        P4FailureTest.overwrite(key, "damaged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = data(post("/onto/releases/" + id + "/integrity-check"), null);
        assertEquals("CORRUPTED", result.path("release").path("status").asText());
    }

    @Test
    void scenarioOneReadyReleaseIsActuallyReceivedBeforeSent() throws Exception {
        publish(ready());
        assertEquals(1, defaultRelay.runOnce(20));
        assertEquals(List.of("RELEASED"), received());
        assertEquals("SENT", db.queryForObject("SELECT status FROM publish_outbox", String.class));
    }

    @Test
    void scenarioTwoDeprecationAfterPublishedNotificationCanRetry() throws Exception {
        var release = publish(ready());
        String id = release.path("id").asText();
        defaultRelay.runOnce(20);
        deprecate(id);
        var clock = new Time();
        var store = store(clock);
        new OutboxRelay(
                        store,
                        delivery -> {
                            throw new java.io.IOException("controlled receiver down");
                        })
                .runOnce(1);
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT status FROM publish_outbox WHERE type='DEPRECATED'", String.class));
        clock.advance(6);
        new OutboxRelay(store, new PgInboxDestination(source, clock)).runOnce(20);
        assertEquals(List.of("RELEASED", "DEPRECATED"), received());
    }

    @Test
    void scenarioThreeCorruptionAfterPublishedNotificationQuarantinesConsumer() throws Exception {
        var release = publish(ready());
        String id = release.path("id").asText();
        defaultRelay.runOnce(20);
        corrupt(id, "index_ref");
        defaultRelay.runOnce(20);
        assertEquals(List.of("RELEASED", "CORRUPTED_ALERT"), received());
        call(get("/onto/releases/" + id + "/manifest"), ownerToken, null, 409);
        assertFalse(
                db.queryForObject(
                                "SELECT payload::text FROM publish_outbox WHERE"
                                        + " type='CORRUPTED_ALERT'",
                                String.class)
                        .contains("http"));
        data(post("/onto/releases/" + id + "/integrity-check"), null);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='CORRUPTED_ALERT'",
                        Integer.class));
    }

    @Test
    void scenarioFourStoppedRelayPublishesHistoricalReleasedBeforeDeprecatedAndPullRejects()
            throws Exception {
        var release = publish(ready());
        String id = release.path("id").asText();
        deprecate(id);
        var order = new ArrayList<String>();
        var clock = new Time();
        var inbox = new PgInboxDestination(source, clock);
        var recovered =
                new OutboxRelay(
                        store(clock),
                        delivery -> {
                            order.add(delivery.type().name());
                            if (delivery.type() == OutboxPolicy.Type.RELEASED) {
                                assertEquals("READY", delivery.boundStatus());
                                call(
                                        get("/onto/releases/" + id + "/manifest"),
                                        ownerToken,
                                        null,
                                        409);
                            }
                            inbox.deliver(delivery);
                        });
        assertEquals(2, recovered.runOnce(20));
        assertEquals(List.of("RELEASED", "DEPRECATED"), order);
        assertEquals(order, received());
    }

    @Test
    void scenarioFiveDeprecationRetriesUntilDeadThenCorruptionAlertCanArrive() throws Exception {
        var release = publish(ready());
        String id = release.path("id").asText();
        defaultRelay.runOnce(20);
        deprecate(id);
        corrupt(id, "manifest_ref");
        var clock = new Time();
        var inbox = new PgInboxDestination(source, clock);
        var recovered =
                new OutboxRelay(
                        store(clock),
                        delivery -> {
                            if (delivery.type() == OutboxPolicy.Type.DEPRECATED)
                                throw new java.io.IOException("controlled offline");
                            inbox.deliver(delivery);
                        });
        recovered.runOnce(20);
        assertEquals(List.of("RELEASED"), received());
        clock.advance(6);
        recovered.runOnce(20);
        assertEquals(List.of("RELEASED"), received());
        clock.advance(11);
        recovered.runOnce(20);
        assertEquals(
                "DEAD",
                db.queryForObject(
                        "SELECT status FROM publish_outbox WHERE type='DEPRECATED'", String.class));
        assertEquals(List.of("RELEASED", "CORRUPTED_ALERT"), received());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_delivery_alert WHERE resolved_at IS NULL",
                        Integer.class));
    }
}
