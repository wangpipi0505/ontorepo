package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** C4：正式版本复用冻结候选知识索引检索，非成员拒绝。 */
class C4ReleaseKnowledgeTest extends P4Fixture {
    @Test
    void releaseKnowledgeSearchUsesFrozenCandidateIndex() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        data(
                post("/onto/knowledge"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "term",
                        "设备",
                        "aliases",
                        List.of("装备"),
                        "definition",
                        "生产设备",
                        "sourceRef",
                        "doc:equipment"));
        data(
                post("/onto/knowledge"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "kind",
                        "ENTITY",
                        "term",
                        "泵",
                        "aliases",
                        List.of(),
                        "definition",
                        "输送液体的设备",
                        "sourceRef",
                        "doc:pump"));
        data(
                post("/onto/knowledge"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "kind",
                        "RELATION",
                        "term",
                        "泵属于设备",
                        "aliases",
                        List.of(),
                        "definition",
                        "",
                        "sourceRef",
                        "doc:fact-1",
                        "subjectRef",
                        "泵",
                        "predicate",
                        "属于",
                        "objectRef",
                        "设备"));
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var body = bound(space, candidate);
        data(post("/onto/candidates/" + id + "/review"), body);
        data(post("/onto/candidates/" + id + "/prepare"), body);
        var release =
                data(
                        post("/onto/candidates/" + id + "/commit"),
                        Map.of(
                                "expectedDraftRev",
                                rev(space),
                                "expectedSha256",
                                candidate.path("sha256").asText()));
        String releaseId = release.path("id").asText();
        var result =
                data(
                        get("/onto/releases/" + releaseId + "/knowledge-search")
                                .param("q", "设备"),
                        null);
        assertEquals(releaseId, result.path("releaseId").asText());
        assertTrue(result.path("hits").size() >= 1);
        assertTrue(
                java.util.stream.StreamSupport.stream(result.path("hits").spliterator(), false)
                        .anyMatch(hit -> "设备".equals(hit.path("term").asText())));
        var fact =
                data(
                        get("/onto/releases/" + releaseId + "/knowledge-search")
                                .param("q", "属于"),
                        null);
        assertTrue(fact.path("hits").size() >= 1);
        assertEquals("RELATION", fact.path("hits").get(0).path("kind").asText());
        call(
                get("/onto/releases/" + releaseId + "/knowledge-search").param("q", "设备"),
                strangerToken,
                null,
                403);
    }
}
