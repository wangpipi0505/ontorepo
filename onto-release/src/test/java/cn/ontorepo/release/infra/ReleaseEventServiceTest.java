package cn.ontorepo.release.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.common.infra.Db;
import cn.ontorepo.release.application.ReleaseEventService;
import cn.ontorepo.release.domain.OutboxPolicy;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.space.infra.SpaceAuthorizationGate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;

/** root发现的>200条事件反例：历史DEAD必须按原id重放，不能依赖有界管理列表取回。 */
class ReleaseEventServiceTest {
    @Test
    void replayOlderThanLatestTwoHundredEventsReturnsOriginalIdentity() throws Exception {
        try (var database = new OutboxDatabase()) {
            var db = new Db(new JdbcTemplate(database.source));
            UUID owner = UUID.randomUUID();
            db.update(
                    "CREATE TABLE ontology_space(id uuid PRIMARY KEY,draft_rev bigint,status"
                            + " text)");
            db.update("CREATE TABLE space_grant(space_id uuid,user_id uuid,role text)");
            db.update(
                    "CREATE TABLE audit_log(id bigserial PRIMARY KEY,space_id uuid,actor_id"
                            + " uuid,action text,draft_rev bigint,subject_id text,detail"
                            + " jsonb,created_at timestamptz DEFAULT now())");
            db.update("INSERT INTO ontology_space VALUES(?,0,'ACTIVE')", database.space);
            db.update("INSERT INTO space_grant VALUES(?,?,'OWNER')", database.space, owner);
            var store =
                    new JdbcOutboxStore(database.source, database.clock, Duration.ofSeconds(30), 3);
            long target;
            try (var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                var release = UUID.randomUUID();
                try (var sql =
                        connection.prepareStatement(
                                "INSERT INTO ontology_release VALUES(?,?,'READY')")) {
                    sql.setObject(1, release);
                    sql.setObject(2, database.space);
                    sql.executeUpdate();
                }
                target =
                        store.append(
                                connection,
                                database.space,
                                release,
                                OutboxPolicy.Type.RELEASED,
                                "READY",
                                "{}");
                connection.commit();
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                store.fail(store.claim(1).getFirst(), "DESTINATION_UNAVAILABLE");
                database.clock.advance(Duration.ofMinutes(1));
            }
            try (var connection = database.source.getConnection()) {
                connection.setAutoCommit(false);
                try (var sql =
                        connection.prepareStatement(
                                "INSERT INTO ontology_release VALUES(?,?,'READY')")) {
                    for (int i = 0; i < 201; i++) {
                        var release = UUID.randomUUID();
                        sql.setObject(1, release);
                        sql.setObject(2, database.space);
                        sql.executeUpdate();
                        store.append(
                                connection,
                                database.space,
                                release,
                                OutboxPolicy.Type.RELEASED,
                                "READY",
                                "{}");
                    }
                }
                connection.commit();
            }
            var service =
                    new ReleaseEventService(
                            db,
                            new SpaceService(db, new SpaceAuthorizationGate(database.source)),
                            store);
            assertTrue(
                    service.list(owner, database.space).stream()
                            .noneMatch(e -> ((Number) e.get("id")).longValue() == target));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(database.source));
            var replay =
                    tx.execute(
                            status -> service.replay(owner, target, Map.of("reason", "人工恢复旧失败事件")));
            assertEquals(target, ((Number) replay.get("id")).longValue());
            assertEquals("PENDING", replay.get("status"));
            assertEquals("PENDING", database.text("status", target));
        }
    }
}
