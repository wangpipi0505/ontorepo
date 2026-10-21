package cn.ontorepo.release.infra;

import cn.ontorepo.release.domain.OutboxPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * PG是事件资格、顺序、租约和累计尝试的唯一权威。
 *
 * <p>本类的内部短事务仅处理领取和确认，从不调用目的地。append/replay使用应用层提供的事务连接，
 * 使版本状态/事件及OWNER审计与相关业务原子提交；身份检查不得由HTTP请求中的角色字段替代。
 */
public final class JdbcOutboxStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource source;
    private final Clock clock;
    private final boolean databaseTime;
    private final Duration lease;
    private final int maxAttempts;

    /** 领取结果不可变；payload来自既有持久事件，不重新编译制品或改写载荷。 */
    public record Delivery(
            long id,
            UUID spaceId,
            UUID releaseId,
            OutboxPolicy.Type type,
            String boundStatus,
            String payload,
            UUID leaseToken,
            int attemptNo) {}

    /** 生产租约统一使用当前事务连接上的 PG 时间，应用节点时钟漂移不能导致抢占。 */
    public JdbcOutboxStore(DataSource source, Duration lease, int maxAttempts) {
        this(source, Clock.systemUTC(), lease, maxAttempts, true);
    }

    /** 可控时钟仅供独立仓储协议测试；生产装配必须使用上方数据库时间构造器。 */
    public JdbcOutboxStore(DataSource source, Clock clock, Duration lease, int maxAttempts) {
        this(source, clock, lease, maxAttempts, false);
    }

    private JdbcOutboxStore(
            DataSource source, Clock clock, Duration lease, int maxAttempts, boolean databaseTime) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.databaseTime = databaseTime;
        if (lease == null
                || lease.compareTo(Duration.ofSeconds(5)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0
                || maxAttempts < 1
                || maxAttempts > 10) throw new IllegalArgumentException("事件租约或有限重试预算无效");
        this.lease = lease;
        this.maxAttempts = maxAttempts;
    }

    /** 复用本次事务连接，不额外借连接；数据库本身回拨时宁可等待，绝不截短活跃租约。 */
    private Instant now(Connection connection) throws SQLException {
        if (!databaseTime) return clock.instant();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT clock_timestamp()")) {
            if (!result.next()) throw new SQLException("数据库未返回租约时钟");
            return result.getTimestamp(1).toInstant();
        }
    }

    /** 状态迁移事务内追加；已存在的同类型事件不允许用不同载荷覆写。 */
    public long append(
            Connection connection,
            UUID space,
            UUID release,
            OutboxPolicy.Type type,
            String boundStatus,
            String payload)
            throws SQLException {
        requireTransaction(connection);
        Objects.requireNonNull(space);
        Objects.requireNonNull(release);
        Objects.requireNonNull(type);
        var event =
                new OutboxPolicy.Event(
                        1,
                        release.toString(),
                        type,
                        boundStatus,
                        OutboxPolicy.Status.PENDING,
                        0,
                        now(connection));
        var decision = OutboxPolicy.next(List.of(event), now(connection)).getFirst();
        if (decision.action() != OutboxPolicy.Action.DELIVER)
            throw new OutboxProblem("INVALID_EVENT_QUALIFICATION");
        validatePayload(payload, type);
        try (var sql =
                connection.prepareStatement(
                        """
                        INSERT INTO publish_outbox(space_id,release_id,type,bound_status,payload,next_retry_at)
                        VALUES(?,?,?,?,?::jsonb,?)
                        ON CONFLICT(release_id,type) DO NOTHING RETURNING id
                        """)) {
            sql.setObject(1, space);
            sql.setObject(2, release);
            sql.setString(3, type.name());
            sql.setString(4, boundStatus);
            sql.setString(5, payload);
            sql.setTimestamp(6, Timestamp.from(now(connection)));
            try (var result = sql.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }
        try (var sql =
                connection.prepareStatement(
                        """
                        SELECT id FROM publish_outbox
                        WHERE release_id=? AND type=? AND space_id=? AND bound_status=? AND payload=?::jsonb
                        """)) {
            sql.setObject(1, release);
            sql.setString(2, type.name());
            sql.setObject(3, space);
            sql.setString(4, boundStatus);
            sql.setString(5, payload);
            try (var result = sql.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }
        throw new OutboxProblem("EVENT_PAYLOAD_CONFLICT");
    }

    /** NOT EXISTS检查所有前序PENDING，不能仅检查已到期行。 SKIP LOCKED保证多relay不领取相同租约；租用中的前项依旧阻断同release后继。 */
    public List<Delivery> claim(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("领取批次须为1..100");
        return transaction(
                connection -> {
                    Instant now = now(connection);
                    List<Delivery> result = new ArrayList<>();
                    try (var sql =
                            connection.prepareStatement(
                                    """
                                    SELECT o.* FROM publish_outbox o
                                    WHERE o.status='PENDING' AND (o.lease_until IS NULL OR o.lease_until<=?)
                                      AND NOT EXISTS(SELECT 1 FROM publish_outbox p
                                        WHERE p.release_id=o.release_id AND p.id<o.id AND p.status='PENDING')
                                    ORDER BY o.next_retry_at,o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                                    """)) {
                        sql.setTimestamp(1, Timestamp.from(now));
                        sql.setInt(2, limit);
                        try (var rows = sql.executeQuery()) {
                            while (rows.next()) {
                                var event = event(rows);
                                // replay可能刚激活旧DEAD；取得目标行锁后以新语句快照再检查前项，避免旧快照越序。
                                if (hasPredecessor(connection, event)) continue;
                                var token = rows.getObject("lease_token", UUID.class);
                                if (token != null) {
                                    finishAttempt(
                                            connection,
                                            event.id(),
                                            token,
                                            "EXPIRED",
                                            "DELIVERY_LEASE_EXPIRED");
                                    var retry =
                                            OutboxPolicy.failure(
                                                    event,
                                                    rows.getTimestamp("lease_until").toInstant(),
                                                    maxAttempts);
                                    updateFailure(
                                            connection,
                                            event.id(),
                                            retry,
                                            "DELIVERY_LEASE_EXPIRED");
                                    event =
                                            new OutboxPolicy.Event(
                                                    event.id(),
                                                    event.releaseId(),
                                                    event.type(),
                                                    event.boundStatus(),
                                                    retry.status(),
                                                    retry.retryCount(),
                                                    retry.nextRetryAt());
                                }
                                var decisions = OutboxPolicy.next(List.of(event), now);
                                if (decisions.isEmpty()) continue;
                                if (decisions.getFirst().action() == OutboxPolicy.Action.REJECT) {
                                    quarantine(connection, event.id());
                                    continue;
                                }
                                UUID leaseToken = UUID.randomUUID();
                                int attempt = Math.addExact(rows.getInt("attempt_count"), 1);
                                try (var update =
                                        connection.prepareStatement(
                                                """
                                                UPDATE publish_outbox SET lease_token=?,lease_until=?,attempt_count=? WHERE id=?
                                                """)) {
                                    update.setObject(1, leaseToken);
                                    update.setTimestamp(2, Timestamp.from(now.plus(lease)));
                                    update.setInt(3, attempt);
                                    update.setLong(4, event.id());
                                    update.executeUpdate();
                                }
                                try (var audit =
                                        connection.prepareStatement(
                                                """
                                                INSERT INTO publish_delivery_attempt(event_id,attempt_no,lease_token,outcome,created_at)
                                                VALUES(?,?,?,'CLAIMED',?)
                                                """)) {
                                    audit.setLong(1, event.id());
                                    audit.setInt(2, attempt);
                                    audit.setObject(3, leaseToken);
                                    audit.setTimestamp(4, Timestamp.from(now));
                                    audit.executeUpdate();
                                }
                                result.add(
                                        new Delivery(
                                                event.id(),
                                                rows.getObject("space_id", UUID.class),
                                                UUID.fromString(event.releaseId()),
                                                event.type(),
                                                event.boundStatus(),
                                                rows.getString("payload"),
                                                leaseToken,
                                                attempt));
                            }
                        }
                    }
                    return List.copyOf(result);
                });
    }

    /** 只接受当前未过期租约的确认；已失效的网络响应不能篡改新领取者或SENT终态。 */
    public boolean acknowledge(Delivery delivery) {
        return transaction(
                connection -> {
                    // 先取得事件行锁，再读取权威时间。SQL 的 WHERE 可能先求值后等待行锁，
                    // 因此不能把取锁前的时间参数（或 WHERE 中的时钟函数）当成最终租约判定。
                    try (var sql =
                            connection.prepareStatement(
                                    """
                                    SELECT lease_until FROM publish_outbox
                                    WHERE id=? AND status='PENDING' AND lease_token=? FOR UPDATE
                                    """)) {
                        sql.setLong(1, delivery.id());
                        sql.setObject(2, delivery.leaseToken());
                        try (var row = sql.executeQuery()) {
                            if (!row.next()) return false;
                            var until = row.getTimestamp("lease_until");
                            if (until == null || !until.toInstant().isAfter(now(connection)))
                                return false;
                        }
                    }
                    try (var sql =
                            connection.prepareStatement(
                                    """
                                    UPDATE publish_outbox SET status='SENT',lease_token=NULL,lease_until=NULL,last_error=NULL
                                    WHERE id=? AND status='PENDING' AND lease_token=?
                                    """)) {
                        sql.setLong(1, delivery.id());
                        sql.setObject(2, delivery.leaseToken());
                        if (sql.executeUpdate() != 1) return false;
                    }
                    finishAttempt(connection, delivery.id(), delivery.leaseToken(), "SENT", null);
                    return true;
                });
    }

    /** 网络失败仅记安全分类码；策略计算退避与DEAD，累计尝试历史不覆盖。 */
    public boolean fail(Delivery delivery, String safeCode) {
        String code =
                safeCode != null && safeCode.matches("[A-Z][A-Z0-9_]{0,79}")
                        ? safeCode
                        : "DESTINATION_UNAVAILABLE";
        return transaction(
                connection -> {
                    try (var sql =
                            connection.prepareStatement(
                                    """
                                    SELECT * FROM publish_outbox WHERE id=? AND status='PENDING' AND lease_token=?
                                    FOR UPDATE
                                    """)) {
                        sql.setLong(1, delivery.id());
                        sql.setObject(2, delivery.leaseToken());
                        try (var row = sql.executeQuery()) {
                            if (!row.next()) return false;
                            // 与成功确认一致：行锁等待可能跨越期限，必须在锁内重新读取时间。
                            var checkedAt = now(connection);
                            var until = row.getTimestamp("lease_until");
                            if (until == null || !until.toInstant().isAfter(checkedAt))
                                return false;
                            var retry = OutboxPolicy.failure(event(row), checkedAt, maxAttempts);
                            updateFailure(connection, delivery.id(), retry, code);
                            finishAttempt(
                                    connection,
                                    delivery.id(),
                                    delivery.leaseToken(),
                                    retry.status() == OutboxPolicy.Status.DEAD ? "DEAD" : "FAILED",
                                    code);
                            return true;
                        }
                    }
                });
    }

    /** OWNER与原因由应用事务校验；重放保留原id/载荷，累计attempt_count不清零。 */
    public void replay(Connection connection, long id) throws SQLException {
        requireTransaction(connection);
        UUID release;
        try (var sql =
                connection.prepareStatement("SELECT release_id FROM publish_outbox WHERE id=?")) {
            sql.setLong(1, id);
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) throw new OutboxProblem("EVENT_NOT_FOUND");
                release = rows.getObject(1, UUID.class);
            }
        }
        // 按id锁全组，阻止重放老事件与后继领取交错；已有在途后继须等待租约结束再人工重放。
        OutboxPolicy.Event target = null;
        try (var sql =
                connection.prepareStatement(
                        "SELECT * FROM publish_outbox WHERE release_id=? ORDER BY id FOR UPDATE")) {
            sql.setObject(1, release);
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    if (rows.getLong("id") == id) target = event(rows);
                    var until = rows.getTimestamp("lease_until");
                    if (rows.getLong("id") > id
                            && "PENDING".equals(rows.getString("status"))
                            && until != null
                            && until.toInstant().isAfter(now(connection)))
                        throw new OutboxProblem("EVENT_DELIVERY_IN_PROGRESS");
                }
            }
        }
        try {
            OutboxPolicy.replay(target, now(connection));
        } catch (IllegalArgumentException e) {
            throw new OutboxProblem("EVENT_NOT_REPLAYABLE");
        }
        try (var sql =
                connection.prepareStatement(
                        """
                        UPDATE publish_outbox SET status='PENDING',retry_count=0,next_retry_at=?,
                            lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=?
                        """)) {
            sql.setTimestamp(1, Timestamp.from(now(connection)));
            sql.setLong(2, id);
            sql.executeUpdate();
        }
        try (var sql =
                connection.prepareStatement(
                        "UPDATE publish_delivery_alert SET resolved_at=? WHERE event_id=?")) {
            sql.setTimestamp(1, Timestamp.from(now(connection)));
            sql.setLong(2, id);
            sql.executeUpdate();
        }
    }

    private boolean hasPredecessor(Connection connection, OutboxPolicy.Event event)
            throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        """
                        SELECT 1 FROM publish_outbox WHERE release_id=? AND id<? AND status='PENDING' LIMIT 1
                        """)) {
            sql.setObject(1, UUID.fromString(event.releaseId()));
            sql.setLong(2, event.id());
            try (var row = sql.executeQuery()) {
                return row.next();
            }
        }
    }

    private OutboxPolicy.Event event(ResultSet row) throws SQLException {
        return new OutboxPolicy.Event(
                row.getLong("id"),
                row.getString("release_id"),
                OutboxPolicy.Type.valueOf(row.getString("type")),
                row.getString("bound_status"),
                OutboxPolicy.Status.valueOf(row.getString("status")),
                row.getInt("retry_count"),
                row.getTimestamp("next_retry_at").toInstant());
    }

    private void updateFailure(
            Connection connection, long id, OutboxPolicy.Retry retry, String code)
            throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        """
                        UPDATE publish_outbox SET status=?,retry_count=?,next_retry_at=?,last_error=?,
                            lease_token=NULL,lease_until=NULL WHERE id=?
                        """)) {
            sql.setString(1, retry.status().name());
            sql.setInt(2, retry.retryCount());
            sql.setTimestamp(3, Timestamp.from(retry.nextRetryAt()));
            sql.setString(4, code);
            sql.setLong(5, id);
            sql.executeUpdate();
        }
        if (retry.status() == OutboxPolicy.Status.DEAD) alert(connection, id, code);
    }

    private void quarantine(Connection connection, long id) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        """
                        UPDATE publish_outbox SET status='DEAD',last_error='INVALID_EVENT_QUALIFICATION',
                            lease_token=NULL,lease_until=NULL WHERE id=?
                        """)) {
            sql.setLong(1, id);
            sql.executeUpdate();
        }
        alert(connection, id, "INVALID_EVENT_QUALIFICATION");
    }

    /** DEAD告警与状态同事务；重放后再次DEAD重新打开原eventId台账，不重复造事件。 */
    private void alert(Connection connection, long id, String code) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        """
                        INSERT INTO publish_delivery_alert(event_id,code,created_at) VALUES(?,?,?)
                        ON CONFLICT(event_id) DO UPDATE SET code=excluded.code,
                            created_at=excluded.created_at,resolved_at=NULL
                        """)) {
            sql.setLong(1, id);
            sql.setString(2, code);
            sql.setTimestamp(3, Timestamp.from(now(connection)));
            sql.executeUpdate();
        }
    }

    private void finishAttempt(
            Connection connection, long event, UUID token, String outcome, String error)
            throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        """
                        UPDATE publish_delivery_attempt SET outcome=?,error=?
                        WHERE event_id=? AND lease_token=? AND outcome='CLAIMED'
                        """)) {
            sql.setString(1, outcome);
            sql.setString(2, error);
            sql.setLong(3, event);
            sql.setObject(4, token);
            sql.executeUpdate();
        }
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit())
            throw new IllegalStateException("Outbox write requires caller transaction");
    }

    private static void validatePayload(String payload, OutboxPolicy.Type type) {
        try {
            if (payload == null
                    || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
                throw new OutboxProblem("INVALID_EVENT_PAYLOAD");
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject()) throw new OutboxProblem("INVALID_EVENT_PAYLOAD");
            if (type == OutboxPolicy.Type.CORRUPTED_ALERT && containsPullAddress(node))
                throw new OutboxProblem("CORRUPTION_ALERT_CONTAINS_PULL_ADDRESS");
        } catch (java.io.IOException e) {
            throw new OutboxProblem("INVALID_EVENT_PAYLOAD");
        }
    }

    private static boolean containsPullAddress(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isTextual()) return node.textValue().matches("(?is).*(?:https?://|s3://).*?");
        for (var child : node) if (containsPullAddress(child)) return true;
        return false;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T transaction(Work<T> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new OutboxProblem("OUTBOX_STORAGE_UNAVAILABLE");
        }
    }
}
