package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.mapping.application.FrozenDataExecutor;
import cn.ontorepo.trace.application.CandidateService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.concurrent.*;

/** 真实PG并发证明：长试执行不占草稿锁，撤权仍线性化，四连接池不会因授权gate耗尽。 */
class ExecutionAuditTest extends P3Fixture {
    @Autowired CandidateService candidates;
    @MockitoSpyBean FrozenDataExecutor executor;
    @MockitoSpyBean Db records;

    @Test
    void draftEditCompletesWhileFrozenSourceExecutionIsWaiting() throws Exception {
        var x = scenario();
        UUID id = UUID.fromString(freeze(x.space()).path("id").asText());
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        blockSource(entered, resume);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var trial =
                    workers.submit(
                            () ->
                                    candidates.trial(
                                            owner, id, UUID.fromString(x.operation()), Map.of()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var edit = workers.submit(() -> createClass(x.space(), "查询期间新增草稿类", null));
            assertDoesNotThrow(() -> edit.get(3, TimeUnit.SECONDS), "冻结源查询不能阻塞普通草稿编辑");
            resume.countDown();
            assertEquals("SUCCEEDED", trial.get(15, TimeUnit.SECONDS).get("status"));
        } finally {
            resume.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void fourConcurrentTrialsFitFourConnectionsAndReleaseThemAfterCompletion() throws Exception {
        var x = scenario();
        UUID id = UUID.fromString(freeze(x.space()).path("id").asText());
        var entered = new CountDownLatch(4);
        var resume = new CountDownLatch(1);
        blockSource(entered, resume);
        var workers = Executors.newFixedThreadPool(4);
        try {
            var trials = new ArrayList<Future<Map<String, Object>>>();
            for (int i = 0; i < 4; i++)
                trials.add(
                        workers.submit(
                                () ->
                                        candidates.trial(
                                                owner,
                                                id,
                                                UUID.fromString(x.operation()),
                                                Map.of())));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "四个查询应同时到达源边界，而非被草稿行锁串行化");
            resume.countDown();
            for (var trial : trials)
                assertEquals("SUCCEEDED", trial.get(15, TimeUnit.SECONDS).get("status"));
            // 再占满连接池，验证短事务结束后会话连接已经归还，不要求额外的第5条连接。
            try (var a = sourceConnection();
                    var b = sourceConnection();
                    var c = sourceConnection();
                    var d = sourceConnection()) {
                assertFalse(a.isClosed());
                assertFalse(b.isClosed());
                assertFalse(c.isClosed());
                assertFalse(d.isClosed());
            }
        } finally {
            resume.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationOrArchiveWaitsForAuthorizedTrialAndThenPreventsAnyNewSourceAccess(
            boolean archive) throws Exception {
        var x = scenario();
        // 独立EDITOR用于撤权，OWNER继续负责授权管理。
        update(
                "/spaces/" + x.space() + "/grants/" + viewer,
                x.space(),
                Map.of("role", "EDITOR", "attributes", Map.of("region", "A")));
        UUID id = UUID.fromString(freeze(x.space()).path("id").asText());
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        blockSource(entered, resume);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var trial =
                    workers.submit(
                            () ->
                                    candidates.trial(
                                            viewer, id, UUID.fromString(x.operation()), Map.of()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var revoke =
                    workers.submit(
                            () ->
                                    archive
                                            ? data(
                                                    post("/onto/spaces/" + x.space() + "/archive"),
                                                    Map.of("expectedDraftRev", rev(x.space())))
                                            : update(
                                                    "/spaces/" + x.space() + "/grants/" + viewer,
                                                    x.space(),
                                                    Map.of(
                                                            "role",
                                                            "VIEWER",
                                                            "attributes",
                                                            Map.of("region", "A"))));
            awaitAuthorizationWait();
            assertFalse(revoke.isDone(), "撤权/归档不能越过仍在源查询的既有授权调用");
            resume.countDown();
            assertEquals("SUCCEEDED", trial.get(15, TimeUnit.SECONDS).get("status"));
            revoke.get(10, TimeUnit.SECONDS);
            clearInvocations(executor);
            assertThrows(
                    Problem.class,
                    () -> candidates.trial(viewer, id, UUID.fromString(x.operation()), Map.of()));
            verify(executor, never()).execute(anyMap(), anyMap(), any(), any());
        } finally {
            resume.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void fatalExecutionFailureReleasesAuthorizationGateAndPoolConnection() throws Exception {
        var x = scenario();
        UUID id = UUID.fromString(freeze(x.space()).path("id").asText());
        doThrow(new AssertionError("injected fatal source failure"))
                .when(executor)
                .execute(anyMap(), anyMap(), any(), any());
        assertThrows(
                AssertionError.class,
                () -> candidates.trial(owner, id, UUID.fromString(x.operation()), Map.of()));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var edit =
                    worker.submit(
                            () ->
                                    update(
                                            "/spaces/" + x.space() + "/grants/" + viewer,
                                            x.space(),
                                            Map.of("role", "VIEWER", "attributes", Map.of())));
            assertDoesNotThrow(() -> edit.get(3, TimeUnit.SECONDS), "异常路径不能向池归还仍持有授权锁的会话");
        } finally {
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedAuditRollsBackExecutionStepsAndEvidenceTogether() throws Exception {
        var x = scenario();
        UUID id = UUID.fromString(freeze(x.space()).path("id").asText());
        // 审计是整组事实的最后写入；在该点失败，前面已写入的事实、步骤、证据都必须回滚。
        doThrow(new IllegalStateException("injected audit failure"))
                .when(records)
                .update(startsWith("INSERT INTO audit_log"), any(Object[].class));
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> candidates.trial(owner, id, UUID.fromString(x.operation()), Map.of()));
            for (String table : List.of("op_execution", "op_execution_step", "evidence_ref"))
                assertEquals(0, db.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        } finally {
            reset(records);
        }
        // 回滚后同一线程仍能重新借用连接并正常执行，不留下失败事务或session授权锁。
        assertEquals(
                "SUCCEEDED",
                candidates
                        .trial(owner, id, UUID.fromString(x.operation()), Map.of())
                        .get("status"));
    }

    @Test
    void latestFiftyFactsUseThreeQueriesWithStableOrderingAndFullChildren() throws Exception {
        String space = createSpace();
        UUID candidate = UUID.fromString(freeze(space).path("id").asText());
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 55; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            db.update(
                    "INSERT INTO"
                        + " op_execution(id,space_id,trace_id,subject_type,subject_id,op_code,status,elapsed_ms,candidate_sha256,result_json,principal_id,caller,input_digest,output_digest,created_at)"
                        + " VALUES(?,?,?,'CANDIDATE',?,'audit','SUCCEEDED',1,?,?::jsonb,?,'TRIAL',?,?,?::timestamptz)",
                    id,
                    UUID.fromString(space),
                    UUID.randomUUID(),
                    candidate,
                    "a".repeat(64),
                    "{\"columns\":[\"value\"],\"rows\":[{\"value\":" + i + "}]}",
                    owner,
                    "b".repeat(64),
                    "c".repeat(64),
                    "2026-10-21T01:00:00Z");
            db.update(
                    "INSERT INTO op_execution_step(execution_id,step_order,kind,status,detail)"
                            + " VALUES(?,1,'SECOND','SUCCEEDED','2'),(?,0,'FIRST','SUCCEEDED','1')",
                    id,
                    id);
            db.update(
                    "INSERT INTO evidence_ref(execution_id,kind,ref)"
                            + " VALUES(?,'SOURCE_RECORD','{\"key\":\"expected\"}')",
                    id);
        }
        clearInvocations(records);
        var result = candidates.executions(owner, UUID.fromString(space));
        long reads =
                mockingDetails(records).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("list"))
                        .map(invocation -> invocation.getArgument(0).toString())
                        .filter(
                                sql ->
                                        sql.contains("FROM op_execution")
                                                || sql.contains("FROM evidence_ref"))
                        .count();
        assertEquals(3, reads, "历史列表必须批量读取事实、步骤和证据，不能逐执行N+1查询");
        assertEquals(50, result.size());
        ids.sort(Comparator.comparing(UUID::toString).reversed());
        for (int i = 0; i < 50; i++) {
            assertEquals(ids.get(i).toString(), result.get(i).get("id"));
            var steps = (List<?>) result.get(i).get("steps");
            assertEquals("FIRST", ((Map<?, ?>) steps.getFirst()).get("kind"));
            assertEquals(2, steps.size());
            assertEquals(1, ((List<?>) result.get(i).get("evidence")).size());
            assertEquals(1, ((List<?>) result.get(i).get("rows")).size());
        }
    }

    private void blockSource(CountDownLatch entered, CountDownLatch resume) {
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(resume.await(20, TimeUnit.SECONDS));
                            return invocation.callRealMethod();
                        })
                .when(executor)
                .execute(anyMap(), anyMap(), any(), any());
    }

    private java.sql.Connection sourceConnection() throws java.sql.SQLException {
        return Objects.requireNonNull(db.getDataSource()).getConnection();
    }

    /** 等待真实PG报告锁等待，再判断顺序，避免用一次固定sleep猜测工作线程是否开始。 */
    private void awaitAuthorizationWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query"
                                + " LIKE '%ontology_space%' OR query LIKE '%advisory%')",
                            Integer.class)
                    > 0) return;
            Thread.sleep(10);
        }
        fail("授权变更没有进入可观测的锁等待");
    }
}
