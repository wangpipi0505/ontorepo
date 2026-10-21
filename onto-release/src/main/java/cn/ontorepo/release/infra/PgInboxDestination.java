package cn.ontorepo.release.infra;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;

import javax.sql.DataSource;

/** 独立部署默认真实收件箱；唯一eventId接收，重复载荷必须一致，事务提交后才确认。 */
public final class PgInboxDestination implements OutboxDestination {
    private final DataSource source;
    private final Clock clock;

    public PgInboxDestination(DataSource source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void deliver(JdbcOutboxStore.Delivery delivery) {
        String digest = digest(delivery.payload());
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var sql =
                        connection.prepareStatement(
                                """
                                INSERT INTO publish_inbox(event_id,release_id,type,received_at,receipt)
                                VALUES(?,?,?,?,jsonb_build_object('accepted',true,'payloadSha256',?::text))
                                ON CONFLICT(event_id) DO NOTHING
                                """)) {
                    sql.setLong(1, delivery.id());
                    sql.setObject(2, delivery.releaseId());
                    sql.setString(3, delivery.type().name());
                    sql.setTimestamp(4, Timestamp.from(clock.instant()));
                    sql.setString(5, digest);
                    sql.executeUpdate();
                }
                try (var sql =
                        connection.prepareStatement(
                                """
                                SELECT 1 FROM publish_inbox
                                WHERE event_id=? AND release_id=? AND type=? AND receipt->>'payloadSha256'=?
                                """)) {
                    sql.setLong(1, delivery.id());
                    sql.setObject(2, delivery.releaseId());
                    sql.setString(3, delivery.type().name());
                    sql.setString(4, digest);
                    try (var row = sql.executeQuery()) {
                        if (!row.next()) throw new OutboxProblem("INBOX_EVENT_CONFLICT");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new OutboxProblem("INBOX_STORAGE_UNAVAILABLE");
        }
    }

    /** receipt用于安全展示，只保留摘要证明，不复制事件中的内部拉取地址。 */
    private static String digest(String payload) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            payload.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 unavailable");
        }
    }
}
