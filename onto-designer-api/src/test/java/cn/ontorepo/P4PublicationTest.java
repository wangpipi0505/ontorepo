package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

/** 真实PG/MinIO发布主线；先证明没有实现时不能凭候选本地试验冒充发布。 */
@TestPropertySource(
        properties = {"onto.release.relay-enabled=false", "onto.release.integrity-enabled=false"})
class P4PublicationTest extends P4Fixture {
    @Test
    void freezingAllocatesNonReusableVersionAndVerifiedArtifactBeforePublication()
            throws Exception {
        String space = createSpace();
        var first = freeze(space);
        assertTrue(first.path("preVersionNo").asInt() > 0, "冻结即预分配正数版本");
        var publication =
                data(get("/onto/candidates/" + first.path("id").asText() + "/publication"), null);
        assertEquals(1, publication.path("artifacts").size());
        assertTrue(publication.path("artifacts").get(0).path("ready").asBoolean());
        var second = freeze(space);
        assertTrue(second.path("preVersionNo").asLong() > first.path("preVersionNo").asLong());
    }

    @Test
    void zeroOperationModelOnlyCandidatePublishesWithoutContractOrEndpoint() throws Exception {
        String space = createSpace();
        createClass(space, "任务", null);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        assertEquals(
                "MODEL_ONLY",
                data(get("/onto/candidates/" + id + "/publication"), null)
                        .path("capability")
                        .asText(),
                "无助手声明的候选标记为纯模型");
        data(post("/onto/candidates/" + id + "/validate"), null);
        var tested = data(post("/onto/candidates/" + id + "/tests"), null);
        assertTrue(tested.path("passed").asBoolean(), "零操作候选TEST门禁按空真通过");
        assertEquals("NO_OPERATION_NO_CASE", tested.path("reason").asText());
        var bound =
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "candidateSha256",
                        candidate.path("sha256").asText(),
                        "reason",
                        "已复核纯模型发布");
        data(post("/onto/candidates/" + id + "/review"), bound);
        assertEquals(
                "READY_TO_COMMIT",
                data(post("/onto/candidates/" + id + "/prepare"), bound).path("status").asText());
        var release =
                data(
                        post("/onto/candidates/" + id + "/commit"),
                        Map.of(
                                "expectedDraftRev",
                                rev(space),
                                "expectedSha256",
                                candidate.path("sha256").asText()));
        assertEquals("READY", release.path("status").asText());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM service_endpoint", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='RELEASED'",
                        Integer.class));
    }

    @Test
    void operationWithoutEnabledCaseStillCannotPublish() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        var tested = data(post("/onto/candidates/" + id + "/tests"), null);
        assertFalse(tested.path("passed").asBoolean(), "有操作但零用例不能显示测试通过");
        assertEquals("NO_ENABLED_TEST_CASES", tested.path("reason").asText());
        call(
                post("/onto/candidates/" + id + "/prepare"),
                ownerToken,
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "candidateSha256",
                        candidate.path("sha256").asText()),
                409);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
    }

    @Test
    void modelOnlyOperationWithCasePublishesWithoutEndpointAndCannotBeCalled() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        createCase(space, operation);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        assertEquals(
                "MODEL_ONLY",
                data(get("/onto/candidates/" + id + "/publication"), null)
                        .path("capability")
                        .asText());
        data(post("/onto/candidates/" + id + "/validate"), null);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean(),
                "无助手操作仍可用冻结用例完成本地测试");
        var bound =
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "candidateSha256",
                        candidate.path("sha256").asText(),
                        "reason",
                        "已复核纯模型发布");
        data(post("/onto/candidates/" + id + "/review"), bound);
        data(post("/onto/candidates/" + id + "/prepare"), bound);
        var release =
                data(
                        post("/onto/candidates/" + id + "/commit"),
                        Map.of(
                                "expectedDraftRev",
                                rev(space),
                                "expectedSha256",
                                candidate.path("sha256").asText()));
        assertEquals("READY", release.path("status").asText());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM service_endpoint", Integer.class));
        var rejected =
                call(
                        post(
                                "/onto/internal/releases/"
                                        + release.path("id").asText()
                                        + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        403);
        assertEquals("ENDPOINT_FORBIDDEN", rejected.path("code").asText());
    }

    private void createCase(String space, String operation) throws Exception {
        data(
                post("/onto/test-cases"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "name",
                        "纯模型用例",
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
    }

    @Test
    void entireReleaseIsPreparedThenCommittedOnceAndEndpointsDefaultDeny() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var bound =
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "candidateSha256",
                        candidate.path("sha256").asText(),
                        "reason",
                        "已复核冻结差异与报告");
        data(post("/onto/candidates/" + id + "/review"), bound);
        var prepared = data(post("/onto/candidates/" + id + "/prepare"), bound);
        assertEquals("READY_TO_COMMIT", prepared.path("status").asText());
        assertEquals(4, prepared.path("artifacts").size());
        var commit =
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "expectedSha256",
                        candidate.path("sha256").asText());
        var release = data(post("/onto/candidates/" + id + "/commit"), commit);
        assertEquals("READY", release.path("status").asText());
        var endpoints =
                data(
                        get("/onto/releases/" + release.path("id").asText() + "/endpoints"),
                        null);
        assertEquals(1, endpoints.size());
        assertTrue(endpoints.get(0).has("description"), "端点说明字段应随冻结操作返回");
        assertEquals(
                release.path("id"),
                data(post("/onto/candidates/" + id + "/commit"), commit).path("id"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publish_outbox WHERE type='RELEASED'",
                        Integer.class));
        var rejected =
                call(
                        post(
                                "/onto/internal/releases/"
                                        + release.path("id").asText()
                                        + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        403);
        assertEquals("ENDPOINT_FORBIDDEN", rejected.path("code").asText());
        assertFalse(rejected.has("meta"));
    }
}
