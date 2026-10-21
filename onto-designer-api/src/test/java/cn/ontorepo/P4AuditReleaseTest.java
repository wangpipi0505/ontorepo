package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.artifact.ArtifactStore;
import cn.ontorepo.knowledge.application.FrozenKnowledge;
import cn.ontorepo.trace.application.CandidateService;
import cn.ontorepo.trace.application.ShaclChecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.concurrent.*;

/** 独立审核回归：状态不可倒退，基础设施失败不可冒充模型冲突或原始异常。 */
class P4AuditReleaseTest extends P4Fixture {
    @MockitoSpyBean ShaclChecker shacl;
    @MockitoSpyBean FrozenKnowledge knowledge;
    @MockitoSpyBean ArtifactStore storage;
    @Autowired CandidateService candidates;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testsBeforeValidationRespectMachineGateAndRemainRunnable(boolean assistant)
            throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        if (assistant) configure(space, operation);
        else
            data(
                    post("/onto/test-cases"),
                    Map.of(
                            "spaceId",
                            space,
                            "expectedDraftRev",
                            rev(space),
                            "name",
                            "先运行用例再发布校验",
                            "question",
                            "列出对象",
                            "operationId",
                            operation,
                            "input",
                            Map.of(),
                            "expected",
                            Map.of("rows", List.of()),
                            "enabled",
                            true));
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var waiting = data(get("/onto/candidates/" + id + "/publication"), null);
        if (!assistant) {
            // MODEL_ONLY 可按 DEC-1=A 纯模型发布；先跑 tests 仍停在 VALIDATING 等待机器检查。
            assertEquals("VALIDATING", waiting.path("status").asText());
            assertNull(
                    db.queryForObject(
                            "SELECT failure_code FROM publish_candidate WHERE id=?",
                            String.class,
                            UUID.fromString(id)));
            var contract =
                    java.util.stream.StreamSupport.stream(
                                    waiting.path("checks").spliterator(), false)
                            .filter(check -> "CONTRACT".equals(check.path("kind").asText()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("PASSED", contract.path("conclusion").asText());
            assertEquals("MODEL_ONLY_NO_CONTRACT", contract.path("reason").asText());
            assertEquals(
                    "TESTING",
                    data(post("/onto/candidates/" + id + "/validate"), null)
                            .path("status")
                            .asText());
            assertTrue(
                    data(post("/onto/candidates/" + id + "/tests"), null)
                            .path("passed")
                            .asBoolean());
            var body = bound(space, candidate);
            assertEquals(
                    "REVIEWING",
                    data(post("/onto/candidates/" + id + "/review"), body).path("status").asText());
            assertEquals(
                    "READY_TO_COMMIT",
                    data(post("/onto/candidates/" + id + "/prepare"), body).path("status").asText());
            return;
        }
        assertEquals("VALIDATING", waiting.path("status").asText(), "尚无冲突/一致性结果时，TEST通过不能跳过机器校验阶段");
        assertEquals(
                "TESTING",
                data(post("/onto/candidates/" + id + "/validate"), null).path("status").asText());
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var body = bound(space, candidate);
        assertEquals(
                "REVIEWING",
                data(post("/onto/candidates/" + id + "/review"), body).path("status").asText());
        assertEquals(
                "READY_TO_COMMIT",
                data(post("/onto/candidates/" + id + "/prepare"), body).path("status").asText());
    }

    @Test
    void successfulTestsCannotEraseFailedMachineGate() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        doThrow(new IllegalStateException("injected shacl failure")).when(shacl).check(anyMap());
        var failed = data(post("/onto/candidates/" + id + "/validate"), null);
        assertEquals("FAILED", failed.path("status").asText());
        var conflict =
                java.util.stream.StreamSupport.stream(failed.path("checks").spliterator(), false)
                        .filter(check -> "CONFLICT".equals(check.path("kind").asText()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var after = data(get("/onto/candidates/" + id + "/publication"), null);
        assertEquals("FAILED", after.path("status").asText(), "TEST通过不能清除已存在的机器失败");
        assertEquals(
                "MACHINE_GATE_BLOCKED",
                db.queryForObject(
                        "SELECT failure_code FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(id)));
        assertEquals(
                conflict,
                java.util.stream.StreamSupport.stream(after.path("checks").spliterator(), false)
                        .filter(check -> "CONFLICT".equals(check.path("kind").asText()))
                        .findFirst()
                        .orElseThrow());
        call(post("/onto/candidates/" + id + "/review"), ownerToken, bound(space, candidate), 409);
        reset(shacl);
        assertEquals(
                "TESTING",
                data(post("/onto/candidates/" + id + "/validate"), null).path("status").asText());
    }

    @Test
    void readyCandidateRejectsEarlierStagesWithoutChangingReportsOrArtifacts() throws Exception {
        var r = ready();
        String id = r.candidate().path("id").asText();
        var before = data(get("/onto/candidates/" + id + "/publication"), null);
        for (String action : List.of("validate", "tests", "review")) {
            var rejected =
                    call(
                            post("/onto/candidates/" + id + "/" + action),
                            ownerToken,
                            action.equals("review") ? bound(r.space(), r.candidate()) : null,
                            409);
            assertEquals("CANDIDATE_STAGE_CONFLICT", rejected.path("code").asText());
        }
        var after = data(get("/onto/candidates/" + id + "/publication"), null);
        assertEquals(before, after);
        assertEquals("READY_TO_COMMIT", after.path("status").asText());
    }

    @Test
    void shaclEngineErrorIsClassifiedInBothCheckReports() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        configure(s, op);
        String id = freeze(s).path("id").asText();
        doThrow(new IllegalStateException("injected private engine detail"))
                .when(shacl)
                .check(anyMap());
        var published = data(post("/onto/candidates/" + id + "/validate"), null);
        for (var report : published.path("checks"))
            if (report.path("kind").asText().equals("CONFLICT"))
                assertEquals("SHACL_ENGINE_ERROR", report.path("reason").asText());
        var local = data(post("/onto/candidates/" + id + "/checks"), null);
        for (var report : local.path("checks"))
            if (report.path("kind").asText().equals("CONFLICT"))
                assertEquals("SHACL_ENGINE_ERROR", report.path("reason").asText());
        assertFalse(local.toString().contains("private engine detail"));
    }

    @Test
    void prepareCompilationFailureHasSafeClassificationAndCanRetry() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        configure(s, op);
        var c = freeze(s);
        String id = c.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        data(post("/onto/candidates/" + id + "/tests"), null);
        var body = bound(s, c);
        data(post("/onto/candidates/" + id + "/review"), body);
        doThrow(new IllegalStateException("injected private compile detail"))
                .when(knowledge)
                .archive(anyMap());
        var failed = call(post("/onto/candidates/" + id + "/prepare"), ownerToken, body, 500);
        assertEquals("ARTIFACT_COMPILE_FAILED", failed.path("code").asText());
        assertFalse(failed.toString().contains("private compile detail"));
        reset(knowledge);
        assertEquals(
                "READY_TO_COMMIT",
                data(post("/onto/candidates/" + id + "/prepare"), body).path("status").asText());
    }

    @Test
    void freezingRecoveryIsBoundedAndDoesNotRecoverHumanReview() throws Exception {
        String s = createSpace();
        var first = freeze(s);
        var second = freeze(s);
        var reviewing = freeze(s);
        db.update(
                "UPDATE publish_candidate SET"
                        + " status='FREEZING',freeze_deadline=clock_timestamp()-interval '1 minute'"
                        + " WHERE id IN (?,?)",
                UUID.fromString(first.path("id").asText()),
                UUID.fromString(second.path("id").asText()));
        db.update(
                "UPDATE publish_candidate SET"
                    + " status='REVIEWING',freeze_deadline=clock_timestamp()-interval '1 minute'"
                    + " WHERE id=?",
                UUID.fromString(reviewing.path("id").asText()));
        assertEquals(1, candidates.recoverFreezing(1));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_candidate WHERE status='FAILED' AND"
                                + " failure_code='FREEZE_INTERRUPTED' AND allocation_status='VOID'",
                        Integer.class));
        assertEquals(1, candidates.recoverFreezing(10));
        assertEquals(0, candidates.recoverFreezing(10));
        assertEquals(
                "REVIEWING",
                db.queryForObject(
                        "SELECT status FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(reviewing.path("id").asText())));
        assertTrue(
                freeze(s).path("preVersionNo").asLong() > reviewing.path("preVersionNo").asLong());
    }

    @Test
    void deadlineRejectsLateSuccessfulUploadEvenBeforeRecoverySchedulerRuns() throws Exception {
        String s = createSpace();
        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            UUID id = UUID.fromString(key.split("/")[1]);
                            db.update(
                                    "UPDATE publish_candidate SET"
                                        + " freeze_deadline=clock_timestamp()-interval '1 second'"
                                        + " WHERE id=?",
                                    id);
                            return invocation.callRealMethod();
                        })
                .when(storage)
                .storeImmutable(
                        startsWith("candidate/"), any(byte[].class), eq("application/json"));
        var c = freeze(s);
        assertEquals("FAILED", c.path("status").asText());
        assertEquals(
                "VOID",
                db.queryForObject(
                        "SELECT allocation_status FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(c.path("id").asText())));
        assertEquals(
                "FREEZE_INTERRUPTED",
                db.queryForObject(
                        "SELECT failure_code FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(c.path("id").asText())));
        assertEquals(0, candidates.recoverFreezing(20), "迟到结果已经原子终结，后续扫描应幂等");
    }

    @Test
    void uncaughtFreezeInterruptionCanBeRecoveredAfterReservedTransactionCommitted()
            throws Exception {
        String s = createSpace();
        doThrow(new LinkageError("simulated process interruption"))
                .when(storage)
                .storeImmutable(
                        startsWith("candidate/"), any(byte[].class), eq("application/json"));
        assertThrows(
                LinkageError.class, () -> candidates.freeze(owner, UUID.fromString(s), rev(s)));
        var pending =
                db.queryForMap(
                        "SELECT id,status,allocation_status,pre_version_no FROM publish_candidate"
                                + " WHERE space_id=?",
                        UUID.fromString(s));
        assertEquals("FREEZING", pending.get("status"));
        assertEquals("RESERVED", pending.get("allocation_status"));
        db.update(
                "UPDATE publish_candidate SET freeze_deadline=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                pending.get("id"));
        assertEquals(1, candidates.recoverFreezing(20));
        reset(storage);
        assertTrue(
                freeze(s).path("preVersionNo").asLong()
                        > ((Number) pending.get("pre_version_no")).longValue());
    }

    @Test
    void lateValidationCannotOverwriteReviewAndReadyState() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        configure(s, op);
        var c = freeze(s);
        String id = c.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(resume.await(20, TimeUnit.SECONDS));
                            return invocation.callRealMethod();
                        })
                .when(shacl)
                .check(anyMap());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var late =
                    worker.submit(
                            () ->
                                    mvc.perform(
                                                    post("/onto/candidates/" + id + "/validate")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + ownerToken))
                                            .andReturn());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            data(post("/onto/candidates/" + id + "/tests"), null);
            var body = bound(s, c);
            data(post("/onto/candidates/" + id + "/review"), body);
            var ready = data(post("/onto/candidates/" + id + "/prepare"), body);
            resume.countDown();
            var response = late.get(20, TimeUnit.SECONDS).getResponse();
            assertEquals(409, response.getStatus());
            assertEquals(
                    "CANDIDATE_STAGE_CONFLICT",
                    json.readTree(response.getContentAsString()).path("code").asText());
            assertEquals(ready, data(get("/onto/candidates/" + id + "/publication"), null));
        } finally {
            resume.countDown();
        }
    }
}
