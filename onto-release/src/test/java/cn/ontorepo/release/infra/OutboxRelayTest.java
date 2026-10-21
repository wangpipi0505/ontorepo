package cn.ontorepo.release.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.application.OutboxRelay;
import cn.ontorepo.release.domain.OutboxPolicy;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 使用真实 PG 收件箱和本地受控 HTTP 证明确认、重试及事务外发送；不连接外部平台。 */
class OutboxRelayTest {
    private OutboxDatabase database;
    private JdbcOutboxStore store;
    private HttpServer server;

    @BeforeEach
    void open() throws Exception {
        database = new OutboxDatabase();
        store = new JdbcOutboxStore(database.source, database.clock, Duration.ofSeconds(30), 3);
    }

    @AfterEach
    void close() throws Exception {
        if (server != null) server.stop(0);
        if (database != null) database.close();
    }

    private long append() throws Exception {
        var release = database.release("READY");
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            long id =
                    store.append(
                            connection,
                            database.space,
                            release,
                            OutboxPolicy.Type.RELEASED,
                            "READY",
                            "{\"manifestSha256\":\"" + "a".repeat(64) + "\"}");
            connection.commit();
            return id;
        }
    }

    private URI address() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events");
    }

    @Test
    void onlyDurablePgInboxConfirmationMarksEventSent() throws Exception {
        long id = append();
        var relay = new OutboxRelay(store, new PgInboxDestination(database.source, database.clock));
        assertEquals(1, relay.runOnce(10));
        assertEquals("SENT", database.text("status", id));
        assertEquals(1, database.count("publish_inbox"));
        assertEquals(0, relay.runOnce(10), "已确认事件不重复领取");
    }

    @Test
    void consumerCommittedButAckLostRetriesOriginalEventAndDeduplicates() throws Exception {
        long id = append();
        String payload = database.text("payload", id);
        var inbox = new PgInboxDestination(database.source, database.clock);
        var calls = new AtomicInteger();
        var relay =
                new OutboxRelay(
                        store,
                        delivery -> {
                            inbox.deliver(delivery);
                            if (calls.getAndIncrement() == 0)
                                throw new IOException("Simulated acknowledgement loss");
                        });
        relay.runOnce(10);
        assertEquals("PENDING", database.text("status", id));
        assertEquals(1, database.count("publish_inbox"));
        database.clock.advance(Duration.ofSeconds(5));
        relay.runOnce(10);
        assertEquals("SENT", database.text("status", id));
        assertEquals(1, database.count("publish_inbox"), "at-least-once由原eventId去重");
        assertEquals(payload, database.text("payload", id));
        assertEquals(2, database.count("publish_delivery_attempt"));
    }

    @Test
    void httpFailureThenConfirmationRetainsIdenticalRequestAndOriginalEventId() throws Exception {
        long id = append();
        var bodies = new ArrayList<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/events",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int status = bodies.size() == 1 ? 503 : 200;
                    byte[] body =
                            ("{\"accepted\":true,\"eventId\":" + id + "}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        var relay =
                new OutboxRelay(store, new HttpOutboxDestination(address(), Duration.ofSeconds(3)));
        relay.runOnce(10);
        assertEquals("PENDING", database.text("status", id));
        database.clock.advance(Duration.ofSeconds(5));
        relay.runOnce(10);
        assertEquals("SENT", database.text("status", id));
        assertEquals(2, bodies.size());
        assertEquals(bodies.getFirst(), bodies.getLast(), "投递重试不能重编译载荷或变更事件身份");
        assertTrue(bodies.getFirst().contains("\"eventId\":" + id));
    }

    @Test
    void httpSuccessForDifferentEventDoesNotAcknowledgeThisEvent() throws Exception {
        long id = append();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/events",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            ("{\"accepted\":true,\"eventId\":" + (id + 1) + "}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        new OutboxRelay(store, new HttpOutboxDestination(address(), Duration.ofSeconds(3)))
                .runOnce(10);
        assertEquals("PENDING", database.text("status", id), "仅HTTP 200不足以证明原事件已接收");
    }

    @Test
    void destinationCanLockOutboxRowBecauseNetworkSendIsOutsideClaimTransaction() throws Exception {
        long id = append();
        var transactionError = new AtomicReference<Exception>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/events",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    try (var connection = database.source.getConnection()) {
                        connection.setAutoCommit(false);
                        try (var statement =
                                connection.prepareStatement(
                                        "SELECT id FROM publish_outbox WHERE id=? FOR UPDATE"
                                                + " NOWAIT")) {
                            statement.setLong(1, id);
                            try (var result = statement.executeQuery()) {
                                if (!result.next())
                                    throw new IllegalStateException("Missing claimed event");
                            }
                        }
                        connection.rollback();
                    } catch (Exception failure) {
                        transactionError.set(failure);
                    }
                    byte[] body =
                            ("{\"accepted\":true,\"eventId\":" + id + "}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            transactionError.get() == null ? 200 : 503, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        new OutboxRelay(store, new HttpOutboxDestination(address(), Duration.ofSeconds(3)))
                .runOnce(10);
        assertNull(transactionError.get(), "目标发送期间不能持有outbox行锁");
        assertEquals("SENT", database.text("status", id));
    }

    @Test
    void persistentRetryErrorsContainSafeCodesRatherThanDestinationExceptionSecrets()
            throws Exception {
        long id = append();
        new OutboxRelay(
                        store,
                        delivery -> {
                            throw new IOException(
                                    "password=private-token jdbc:postgresql://internal-secret");
                        })
                .runOnce(10);
        var safe = database.text("last_error", id);
        assertNotNull(safe);
        for (String secret : List.of("password", "private-token", "jdbc:", "internal-secret"))
            assertFalse(safe.contains(secret), "事件API可见lastError不得传播凭据/连接串");
    }

    @Test
    void sequentialBatchDoesNotLetLaterEventLeaseExpireBeforeItsNetworkSend() throws Exception {
        long first = append();
        long second = append();
        var inbox = new PgInboxDestination(database.source, database.clock);
        var relay =
                new OutboxRelay(
                        store,
                        delivery -> {
                            database.clock.advance(Duration.ofSeconds(20));
                            inbox.deliver(delivery);
                        });
        assertEquals(2, relay.runOnce(2), "串行发送应即时领取，不能提前领取整批再耗尽后项租约");
        assertEquals("SENT", database.text("status", first));
        assertEquals("SENT", database.text("status", second));
    }

    @Test
    void httpBodyHasDeadlineEvenWhenPeerSendsHeadersThenStalls() throws Exception {
        long id = append();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var releaseBody = new java.util.concurrent.CountDownLatch(1);
        server.createContext(
                "/events",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().flush();
                    try {
                        releaseBody.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        long start = System.nanoTime();
        try {
            new OutboxRelay(store, new HttpOutboxDestination(address(), Duration.ofMillis(200)))
                    .runOnce(1);
            long millis =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 1200, "响应头已到达也必须限制响应体读取时限，actual=" + millis);
            assertEquals("PENDING", database.text("status", id));
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void inboxReceiptContainsDigestRatherThanConsumerPullAddresses() throws Exception {
        var release = database.release("READY");
        long id;
        try (var connection = database.source.getConnection()) {
            connection.setAutoCommit(false);
            id =
                    store.append(
                            connection,
                            database.space,
                            release,
                            OutboxPolicy.Type.RELEASED,
                            "READY",
                            "{\"manifestUrl\":\"https://internal-onto/releases/manifest\"}");
            connection.commit();
        }
        new OutboxRelay(store, new PgInboxDestination(database.source, database.clock)).runOnce(1);
        var receipt = database.receipt(id);
        assertTrue(receipt.contains("payloadSha256"));
        assertFalse(receipt.contains("https://"), "安全收件箱目录不得通过receipt泄露拉取地址");
        assertFalse(receipt.contains("manifestUrl"));
    }

    @Test
    void duplicateInboxIdentityWithDifferentPayloadIsRejected() throws Exception {
        append();
        var original = store.claim(1).getFirst();
        var inbox = new PgInboxDestination(database.source, database.clock);
        inbox.deliver(original);
        var changed =
                new JdbcOutboxStore.Delivery(
                        original.id(),
                        original.spaceId(),
                        original.releaseId(),
                        original.type(),
                        original.boundStatus(),
                        "{\"tampered\":true}",
                        original.leaseToken(),
                        original.attemptNo());
        var conflict = assertThrows(OutboxProblem.class, () -> inbox.deliver(changed));
        assertEquals("INBOX_EVENT_CONFLICT", conflict.code());
        assertEquals(1, database.count("publish_inbox"));
    }
}
