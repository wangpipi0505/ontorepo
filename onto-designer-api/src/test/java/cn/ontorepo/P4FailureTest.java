package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.artifact.*;
import cn.ontorepo.release.application.PublicationService;
import cn.ontorepo.space.application.AuthService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.concurrent.*;

/** 故障只注入真实存储边界，正常路径仍经过真实PG与MinIO；不以假制品完成发布。 */
class P4FailureTest extends P4Fixture {
    @MockitoSpyBean ArtifactStore storage;
    @Autowired PublicationService publication;

    @Test
    void committedCandidateCannotBeReviewedOrCancelledAgain() throws Exception {
        var r = ready();
        var released = publish(r);
        String id = r.candidate().path("id").asText();
        var b = bound(r.space(), r.candidate());
        call(post("/onto/candidates/" + id + "/review"), ownerToken, b, 409);
        call(post("/onto/candidates/" + id + "/cancel"), ownerToken, b, 409);
        assertEquals(
                "COMMITTED",
                data(get("/onto/candidates/" + id + "/publication"), null).path("status").asText());
        assertEquals(released.path("id").asText(), publish(r).path("id").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='RELEASED'",
                        Integer.class));
    }

    @Test
    void latePrepareCannotOverwriteConcurrentCommittedCandidate() throws Exception {
        var r = ready();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(
                        invocation -> {
                            if (once.compareAndSet(false, true)) {
                                entered.countDown();
                                assertTrue(resume.await(20, TimeUnit.SECONDS));
                            }
                            return invocation.callRealMethod();
                        })
                .when(storage)
                .storeImmutable(startsWith("release/"), any(byte[].class), anyString());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var b = bound(r.space(), r.candidate());
            String id = r.candidate().path("id").asText();
            var late =
                    worker.submit(
                            () ->
                                    mvc.perform(
                                                    post("/onto/candidates/" + id + "/prepare")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + ownerToken)
                                                            .contentType("application/json")
                                                            .content(json.writeValueAsBytes(b)))
                                            .andReturn());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            data(post("/onto/candidates/" + id + "/prepare"), b);
            var released = publish(r);
            resume.countDown();
            assertEquals(409, late.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(
                    "COMMITTED",
                    data(get("/onto/candidates/" + id + "/publication"), null)
                            .path("status")
                            .asText());
            assertEquals(released.path("id").asText(), publish(r).path("id").asText());
        } finally {
            resume.countDown();
        }
    }

    @Test
    void freezeFailureCannotOverwriteCancellationWhileStorageWasOutsideTransaction()
            throws Exception {
        String space = createSpace();
        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            UUID id = UUID.fromString(key.split("/")[1]);
                            var row =
                                    db.queryForMap(
                                            "SELECT sha256,draft_rev FROM publish_candidate WHERE"
                                                    + " id=?",
                                            id);
                            publication.cancel(
                                    owner,
                                    id,
                                    Map.of(
                                            "expectedDraftRev",
                                            row.get("draft_rev"),
                                            "candidateSha256",
                                            row.get("sha256"),
                                            "reason",
                                            "取消进行中的冻结"));
                            throw new ArtifactProblem("ARTIFACT_STORAGE_UNAVAILABLE", "取消后网络失败");
                        })
                .when(storage)
                .storeImmutable(
                        startsWith("candidate/"), any(byte[].class), eq("application/json"));
        var result = freeze(space);
        assertEquals("CANCELLED", result.path("status").asText());
        assertEquals(
                "VOID",
                db.queryForObject(
                        "SELECT allocation_status FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(result.path("id").asText())));
    }

    @Test
    void concurrentCommitReturnsSameReleaseAndCreatesOnlyOneEvent() throws Exception {
        var r = ready();
        var body = commitBody(r);
        UUID candidate = UUID.fromString(r.candidate().path("id").asText());
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Map<String, Object>> task =
                    () -> {
                        start.await();
                        return publication.commit(owner, candidate, body);
                    };
            var first = threads.submit(task);
            var second = threads.submit(task);
            start.countDown();
            assertEquals(
                    first.get(30, TimeUnit.SECONDS).get("id"),
                    second.get(30, TimeUnit.SECONDS).get("id"));
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='RELEASED'",
                        Integer.class));
    }

    @Test
    void draftChangeAfterReadyRejectsCommitWithoutChangingOldReports() throws Exception {
        var r = ready();
        var original = commitBody(r);
        createClass(r.space(), "测试后的新类", null);
        call(
                post("/onto/candidates/" + r.candidate().path("id").asText() + "/commit"),
                ownerToken,
                original,
                409);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
        assertTrue(db.queryForObject("SELECT count(*) FROM publication_check", Integer.class) >= 5);
    }

    @Test
    void freezeUploadFailurePersistsFailedAndVoidsAllocatedVersion() throws Exception {
        String s = createSpace();
        doThrow(new ArtifactProblem("ARTIFACT_STORAGE_UNAVAILABLE", "注入存储不可达"))
                .when(storage)
                .storeImmutable(
                        startsWith("candidate/"), any(byte[].class), eq("application/json"));
        var failed = freeze(s);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals(
                "VOID",
                db.queryForObject(
                        "SELECT allocation_status FROM publish_candidate WHERE id=?",
                        String.class,
                        UUID.fromString(failed.path("id").asText())));
        reset(storage);
        var next = freeze(s);
        assertTrue(next.path("preVersionNo").asLong() > failed.path("preVersionNo").asLong());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
    }

    @Test
    void prepareUploadFailureKeepsReviewingAndReusesExactPhysicalIndexPlan() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        configure(s, op);
        var c = freeze(s);
        String id = c.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        data(post("/onto/candidates/" + id + "/tests"), null);
        var b = bound(s, c);
        data(post("/onto/candidates/" + id + "/review"), b);
        doThrow(new ArtifactProblem("ARTIFACT_STORAGE_UNAVAILABLE", "注入上传失败"))
                .when(storage)
                .storeImmutable(startsWith("release/"), any(byte[].class), anyString());
        call(post("/onto/candidates/" + id + "/prepare"), ownerToken, b, 503);
        var before =
                db.queryForList(
                        "SELECT kind,sha256,bytes FROM candidate_artifact_plan ORDER BY kind");
        assertEquals(
                "REVIEWING",
                data(get("/onto/candidates/" + id + "/publication"), null).path("status").asText());
        reset(storage);
        var ready = data(post("/onto/candidates/" + id + "/prepare"), b);
        assertEquals("READY_TO_COMMIT", ready.path("status").asText());
        var after =
                db.queryForList(
                        "SELECT kind,sha256,bytes FROM candidate_artifact_plan ORDER BY kind");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).get("sha256"), after.get(i).get("sha256"));
            assertArrayEquals(
                    (byte[]) before.get(i).get("bytes"), (byte[]) after.get(i).get("bytes"));
        }
    }

    @Test
    void verifiedObjectCorruptionCannotFallBackToValidPgCandidateCache() throws Exception {
        String s = createSpace();
        createClass(s, "旧类", null);
        String op = createOperation(s, 20);
        var c = freeze(s);
        UUID id = UUID.fromString(c.path("id").asText());
        var ref =
                db.queryForObject(
                        "SELECT bundle_ref->>'key' FROM publish_candidate WHERE id=?",
                        String.class,
                        id);
        overwrite(ref, "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var failed = data(post("/onto/candidates/" + id + "/trial"), Map.of("operationId", op));
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("ARTIFACT_CORRUPT", failed.path("errorCode").asText());
        byte[] pg =
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?", byte[].class, id);
        assertEquals(c.path("sha256").asText(), AuthService.digest(pg));
    }

    static void overwrite(String key, byte[] bytes) throws Exception {
        var client =
                io.minio.MinioClient.builder()
                        .endpoint(System.getenv("ONTO_STORAGE_ENDPOINT"))
                        .credentials(
                                System.getenv("ONTO_STORAGE_ACCESS_KEY"),
                                System.getenv("ONTO_STORAGE_SECRET_KEY"))
                        .build();
        client.putObject(
                io.minio.PutObjectArgs.builder()
                        .bucket(System.getenv("ONTO_STORAGE_BUCKET"))
                        .object(key)
                        .stream(new java.io.ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                        .build());
    }

    @Test
    void unavailableStorageDoesNotFalselyCorruptAndFailedResponseHasNoVersionHeader()
            throws Exception {
        var release = publish(ready());
        String id = release.path("id").asText();
        doThrow(new ArtifactProblem("ARTIFACT_STORAGE_UNAVAILABLE", "暂时无法连接存储"))
                .when(storage)
                .loadVerified(any(ArtifactRef.class));
        var checked = data(post("/onto/releases/" + id + "/integrity-check"), null);
        assertEquals("READY", checked.path("release").path("status").asText());
        assertEquals("UNAVAILABLE", checked.path("integrity").path("conclusion").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='CORRUPTED_ALERT'",
                        Integer.class));
        var failed =
                mvc.perform(
                                get("/onto/releases/" + id + "/manifest")
                                        .header("Authorization", "Bearer " + ownerToken))
                        .andReturn();
        assertEquals(503, failed.getResponse().getStatus());
        assertNull(failed.getResponse().getHeader("X-Ontology-Release"));
        assertFalse(json.readTree(failed.getResponse().getContentAsString()).has("meta"));
    }
}
