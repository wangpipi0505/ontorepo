package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.trace.application.FrozenBundleLoader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

/** 另一真实PG事务持空间锁，用pg_stat_activity确认等待后才迁移，避免用随机sleep猜竞争顺序。 */
class P4ConcurrencyTest extends P4Fixture {
    @Autowired DataSource source;
    @MockitoSpyBean FrozenBundleLoader bundles;

    private void lock(Connection connection, String space) throws Exception {
        connection.setAutoCommit(false);
        try (var sql =
                connection.prepareStatement(
                        "SELECT id FROM ontology_space WHERE id=? FOR UPDATE")) {
            sql.setObject(1, UUID.fromString(space));
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private void waiting() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query"
                                + " LIKE '%ontology_space%'",
                            Integer.class)
                    > 0) return;
            Thread.sleep(10);
        }
        fail("工作线程未进入已确认的空间锁等待");
    }

    @Test
    void deprecationCommittedWhileInvocationWaitsCannotExecuteStaleReadySnapshot()
            throws Exception {
        var ready = ready();
        var release = publish(ready);
        UUID id = UUID.fromString(release.path("id").asText());
        String endpoint =
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText();
        data(put("/onto/endpoints/" + endpoint + "/grants/" + owner), Map.of("status", "ACTIVE"));
        var read = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            Object row = invocation.callRealMethod();
                            read.countDown();
                            return row;
                        })
                .when(bundles)
                .release(eq(id), eq(false));
        try (var connection = source.getConnection();
                var worker = Executors.newSingleThreadExecutor()) {
            lock(connection, ready.space());
            var request =
                    worker.submit(
                            () ->
                                    mvc.perform(
                                                    post("/onto/internal/releases/"
                                                                    + id
                                                                    + "/ops/listClasses")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + ownerToken)
                                                            .contentType("application/json")
                                                            .content("{}"))
                                            .andReturn());
            assertTrue(read.await(10, TimeUnit.SECONDS));
            waiting();
            try (var sql =
                    connection.prepareStatement(
                            "UPDATE ontology_release SET status='DEPRECATED' WHERE id=?")) {
                sql.setObject(1, id);
                sql.executeUpdate();
            }
            connection.commit();
            var response = request.get(15, TimeUnit.SECONDS).getResponse();
            assertEquals(409, response.getStatus(), response.getContentAsString());
            assertNull(response.getHeader("X-Ontology-Release"));
            assertFalse(json.readTree(response.getContentAsString()).has("meta"));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM op_execution WHERE subject_type='RELEASE' AND"
                                    + " status='SUCCEEDED'",
                            Integer.class));
        }
    }

    @Test
    void editorRevokedWhileValidationWaitsCannotPersistMachineReports() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        db.update(
                "INSERT INTO space_grant(space_id,user_id,role,data_scope,attributes)"
                        + " VALUES(?,?,'EDITOR',NULL,'{}')",
                UUID.fromString(space),
                viewer);
        var read = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            Object row = invocation.callRealMethod();
                            read.countDown();
                            return row;
                        })
                .when(bundles)
                .payload(argThat(row -> id.equals(row.get("id"))));
        try (var connection = source.getConnection();
                var worker = Executors.newSingleThreadExecutor()) {
            lock(connection, space);
            var request =
                    worker.submit(
                            () ->
                                    mvc.perform(
                                                    post("/onto/candidates/" + id + "/validate")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + viewerToken))
                                            .andReturn());
            assertTrue(read.await(10, TimeUnit.SECONDS));
            waiting();
            try (var sql =
                    connection.prepareStatement(
                            "UPDATE space_grant SET role='VIEWER' WHERE space_id=? AND"
                                    + " user_id=?")) {
                sql.setObject(1, UUID.fromString(space));
                sql.setObject(2, viewer);
                sql.executeUpdate();
            }
            connection.commit();
            var response = request.get(15, TimeUnit.SECONDS).getResponse();
            assertEquals(403, response.getStatus(), response.getContentAsString());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM publication_check WHERE candidate_id=?",
                            Integer.class,
                            UUID.fromString(id)));
        }
    }
}
