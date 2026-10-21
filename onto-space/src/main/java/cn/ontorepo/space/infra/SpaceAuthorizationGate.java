package cn.ontorepo.space.infra;

import cn.ontorepo.common.domain.Problem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** 授权变化与直接试执行的空间级协调边界，与草稿行锁分开。 共享session锁允许同空间并发查询；授予变更/归档持排他事务锁，提交前不能越过正在使用旧授权的查询。 */
@Component
public final class SpaceAuthorizationGate {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public SpaceAuthorizationGate(DataSource source) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
    }

    /** 必须在空间行锁之前调用，且沿用外层授权修改事务，避免与查询的短事务形成反向锁序。 */
    public void exclusive(UUID space) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("授权修改必须处于事务中");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, key(space));
    }

    /**
     * 直接试执行独占一条池连接并绑定至当前线程，内部短事务复用它，不再申请第2条连接。 源访问期间保持自动提交；session锁不需要长事务，因此不会阻塞普通草稿编辑或保留MVCC快照。
     */
    public <T> T shared(UUID space, Supplier<T> work) {
        if (TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("独立试执行不能继承其他连接或事务");
        try (var connection = source.getConnection()) {
            boolean bound = false;
            try {
                connection.setAutoCommit(true);
                try (var statement =
                        connection.prepareStatement(
                                "SELECT pg_advisory_lock_shared(hashtextextended(?,0))")) {
                    statement.setString(1, key(space));
                    statement.execute();
                }
                TransactionSynchronizationManager.bindResource(
                        source, new ConnectionHolder(connection));
                bound = true;
                return work.get();
            } finally {
                try {
                    if (bound) TransactionSynchronizationManager.unbindResource(source);
                } finally {
                    // 即使事务框架解绑异常，物理连接上的session锁仍必须清理。
                    release(connection, space);
                }
            }
        } catch (SQLException failure) {
            throw new Problem(503, "AUTHORIZATION_COORDINATION_UNAVAILABLE", "授权协调暂不可用，请稍后重试");
        }
    }

    /** 异常路径也清理session锁；解锁失败物理abort，不能把仍持锁的逻辑连接归还池。 */
    private void release(Connection connection, UUID space) {
        try {
            if (!connection.getAutoCommit()) {
                // 正常短事务已经恢复自动提交；若事务初始化/清理异常，只能回滚，不能意外提交半成品。
                connection.rollback();
                connection.setAutoCommit(true);
            }
            try (var statement =
                    connection.prepareStatement(
                            "SELECT pg_advisory_unlock_shared(hashtextextended(?,0))")) {
                statement.setString(1, key(space));
                statement.execute();
            }
        } catch (SQLException failure) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abortFailure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("AUTHORIZATION_GATE_CONNECTION_ABORT_FAILED");
            }
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("AUTHORIZATION_GATE_CONNECTION_DISCARDED");
        }
    }

    /** 名称空间区分其他业务advisory锁；64位哈希碰撞最多增加等待，不扩大授权。 */
    private static String key(UUID space) {
        return "onto.authorization." + space;
    }
}
