package cn.ontorepo.release.infra;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * P4 持久化组件的真实 PostgreSQL 夹具。
 *
 * <p>仅允许专属 onto_test_p4 数据库，每例创建独立随机 schema，绝不清理开发库或其他测试的表。 此处 SQL 仅定义 outbox 组件所需的存储边界，不替代后续应用真实
 * Flyway 迁移验收。
 */
final class OutboxDatabase implements AutoCloseable {
    private final String schema = "p4_outbox_t_" + UUID.randomUUID().toString().replace("-", "");
    private final String url;
    private final String user;
    private final String password;
    final UUID space = UUID.randomUUID();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T00:00:00Z"));
    final DataSource source;

    OutboxDatabase() throws SQLException {
        url = required("ONTO_TEST_DB_URL");
        // 禁止通过省略变量或环境误配连接到开发库，更不允许使用相邻工程数据库。
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:15472/onto_test_p4(?:\\?.*)?"))
            throw new IllegalStateException(
                    "P4 outbox tests require the dedicated onto_test_p4 database");
        user = required("ONTO_TEST_DB_USER");
        password = required("ONTO_TEST_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password);
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        source = new SchemaDataSource();
        try (var connection = source.getConnection();
                var sql = connection.createStatement()) {
            sql.execute(
                    """
                    CREATE TABLE ontology_release(
                      id uuid PRIMARY KEY, space_id uuid NOT NULL, status varchar(20) NOT NULL
                    )
                    """);
            sql.execute(
                    """
                    CREATE TABLE publish_outbox(
                      id bigserial PRIMARY KEY,
                      space_id uuid NOT NULL,
                      release_id uuid NOT NULL REFERENCES ontology_release(id),
                      type varchar(30) NOT NULL,
                      bound_status varchar(20) NOT NULL,
                      payload jsonb NOT NULL,
                      status varchar(10) NOT NULL DEFAULT 'PENDING',
                      retry_count integer NOT NULL DEFAULT 0,
                      attempt_count integer NOT NULL DEFAULT 0,
                      next_retry_at timestamptz NOT NULL,
                      lease_token uuid,
                      lease_until timestamptz,
                      last_error varchar(300),
                      created_at timestamptz NOT NULL DEFAULT now(),
                      UNIQUE(release_id,type)
                    )
                    """);
            sql.execute(
                    """
                    CREATE TABLE publish_delivery_attempt(
                      id bigserial PRIMARY KEY,
                      event_id bigint NOT NULL REFERENCES publish_outbox(id),
                      attempt_no integer NOT NULL,
                      lease_token uuid NOT NULL,
                      outcome varchar(20) NOT NULL,
                      error varchar(300),
                      created_at timestamptz NOT NULL,
                      UNIQUE(event_id,attempt_no)
                    )
                    """);
            sql.execute(
                    """
                    CREATE TABLE publish_inbox(
                      event_id bigint PRIMARY KEY,
                      release_id uuid NOT NULL,
                      type varchar(30) NOT NULL,
                      received_at timestamptz NOT NULL,
                      receipt jsonb NOT NULL
                    )
                    """);
            sql.execute(
                    """
                    CREATE TABLE publish_delivery_alert(
                      event_id bigint PRIMARY KEY REFERENCES publish_outbox(id),
                      code varchar(80) NOT NULL,
                      created_at timestamptz NOT NULL,
                      resolved_at timestamptz
                    )
                    """);
        }
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException(
                    "Missing dedicated P4 test environment variable: " + name);
        return value;
    }

    UUID release(String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO ontology_release(id,space_id,status) VALUES(?,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, space);
            statement.setString(3, status);
            statement.executeUpdate();
        }
        return id;
    }

    void releaseStatus(UUID release, String status) throws SQLException {
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE ontology_release SET status=? WHERE id=?")) {
            statement.setString(1, status);
            statement.setObject(2, release);
            statement.executeUpdate();
        }
    }

    /** 仅夹具注入旧坏行，用于检验读取隔离；真实追加入口仍应拒绝非法资格。 */
    void corruptBoundStatus(long event, String status) throws SQLException {
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE publish_outbox SET bound_status=? WHERE id=?")) {
            statement.setString(1, status);
            statement.setLong(2, event);
            statement.executeUpdate();
        }
    }

    String text(String column, long event) throws SQLException {
        if (!java.util.Set.of("status", "payload", "last_error", "lease_token").contains(column))
            throw new IllegalArgumentException("Unsupported fixture column");
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT " + column + "::text FROM publish_outbox WHERE id=?")) {
            statement.setLong(1, event);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Missing test event");
                return result.getString(1);
            }
        }
    }

    long count(String table) throws SQLException {
        if (!java.util.Set.of(
                        "publish_outbox",
                        "publish_inbox",
                        "publish_delivery_attempt",
                        "publish_delivery_alert")
                .contains(table)) throw new IllegalArgumentException("Unsupported fixture table");
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    String receipt(long event) throws SQLException {
        try (var connection = source.getConnection();
                var sql =
                        connection.prepareStatement(
                                "SELECT receipt::text FROM publish_inbox WHERE event_id=?")) {
            sql.setLong(1, event);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Missing test receipt");
                return row.getString(1);
            }
        }
    }

    boolean alertResolved(long event) throws SQLException {
        try (var connection = source.getConnection();
                var sql =
                        connection.prepareStatement(
                                "SELECT resolved_at IS NOT NULL FROM publish_delivery_alert WHERE"
                                        + " event_id=?")) {
            sql.setLong(1, event);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Missing delivery alert");
                return row.getBoolean(1);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (!schema.matches("p4_outbox_t_[0-9a-f]{32}"))
            throw new IllegalStateException("Unsafe test schema");
        try (var connection = DriverManager.getConnection(url, user, password);
                var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /** 每个连接显式切入本例 schema，模拟重建 repository/relay 时仍读取相同持久事实。 */
    private final class SchemaDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            var connection = DriverManager.getConnection(url, user, password);
            connection.setSchema(schema);
            return connection;
        }

        @Override
        public Connection getConnection(String suppliedUser, String suppliedPassword)
                throws SQLException {
            throw new SQLException("Test DataSource credentials cannot be replaced");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter writer) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("p4-test");
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }

    /** 只控制测试时间，无 sleep；业务状态始终从真实 PG 读取。 */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
            now = initial;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
