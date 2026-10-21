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
    void zeroEnabledCasesCannotBecomeReadyOrPublish() throws Exception {
        String space = createSpace();
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        assertFalse(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean(),
                "新发布候选零用例不能显示测试通过");
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
