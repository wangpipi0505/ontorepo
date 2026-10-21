package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.model.infra.RdfCodec;
import cn.ontorepo.trace.application.FrozenBundleLoader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/** OM-D5：超EL构造必须完整保留且默认阻断，只有实际OWNER对同摘要当前修订的例外允许发布。 */
class P4GateTest extends P4Fixture {
    @Autowired FrozenBundleLoader bundles;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.ontorepo.trace.application.ConsistencyChecker checker;

    @Test
    void engineFailureRemainsFailedAndCannotBeApprovedAsSubsetException() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        var c = freeze(space);
        String id = c.path("id").asText();
        org.mockito.Mockito.doReturn(
                        Map.of(
                                "kind",
                                "CONSISTENCY",
                                "conclusion",
                                "FAILED",
                                "reason",
                                "ENGINE_ERROR",
                                "details",
                                Map.of("incomplete", true)))
                .when(checker)
                .check(org.mockito.ArgumentMatchers.anyMap());
        var result = data(post("/onto/candidates/" + id + "/validate"), null);
        boolean found = false;
        for (var check : result.path("checks"))
            if ("CONSISTENCY".equals(check.path("kind").asText())) {
                found = true;
                assertEquals("FAILED", check.path("conclusion").asText());
                assertEquals("ENGINE_ERROR", check.path("reason").asText());
            }
        assertTrue(found);
        call(
                post("/onto/candidates/" + id + "/waivers"),
                ownerToken,
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "不能豁免引擎不完整"),
                409);
        call(post("/onto/candidates/" + id + "/review"), ownerToken, bound(space, c), 409);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM gate_waiver", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
    }

    @Test
    void inverseAndSymmetricProfileRequiresBoundOwnerWaiverBeforeRealCommit() throws Exception {
        String space = createSpace(),
                left = createClass(space, "A", null),
                right = createClass(space, "B", null),
                operation = createOperation(space, 20);
        data(
                post("/onto/relations"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "name",
                        "合作",
                        "fromClassId",
                        left,
                        "toClassId",
                        right,
                        "cardinality",
                        "MANY_TO_MANY",
                        "inverseName",
                        "反向合作",
                        "transitive",
                        false,
                        "symmetric",
                        true,
                        "description",
                        "人工保留完整语义"));
        configure(space, operation);
        var old = data(get("/onto/test-cases").param("spaceId", space), null).get(0);
        var test = new LinkedHashMap<String, Object>(json.convertValue(old, Map.class));
        test.put("spaceId", space);
        test.put("expectedDraftRev", rev(space));
        test.put("expected", Map.of("status", "SUCCEEDED"));
        test.put("matchMode", "CONTAINS");
        data(put("/onto/test-cases/" + old.path("id").asText()), test);
        var candidate = freeze(space);
        String id = candidate.path("id").asText();
        var validated = data(post("/onto/candidates/" + id + "/validate"), null);
        boolean profile = false;
        for (var check : validated.path("checks"))
            if ("CONSISTENCY".equals(check.path("kind").asText())) {
                profile = true;
                assertEquals("NOT_CHECKED", check.path("conclusion").asText());
                assertEquals("SUBSET_EXCEEDED", check.path("reason").asText());
            }
        assertTrue(profile);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var b = bound(space, candidate);
        call(post("/onto/candidates/" + id + "/review"), ownerToken, b, 409);
        call(post("/onto/candidates/" + id + "/prepare"), ownerToken, b, 409);
        db.update(
                "INSERT INTO space_grant(space_id,user_id,role,data_scope,attributes)"
                        + " VALUES(?,?,'EDITOR',NULL,'{}')",
                UUID.fromString(space),
                viewer);
        var approval = Map.of("kind", "SUBSET_EXCEEDED", "reason", "OWNER已逐条复核逆属性与对称公理，保留完整超EL语义");
        call(post("/onto/candidates/" + id + "/waivers"), viewerToken, approval, 403);
        var waiver = data(post("/onto/candidates/" + id + "/waivers"), approval);
        assertEquals(candidate.path("sha256").asText(), waiver.path("candidateSha256").asText());
        assertEquals(
                candidate.path("draftRev").asLong(),
                db.queryForObject(
                        "SELECT draft_rev FROM gate_waiver WHERE candidate_id=?",
                        Long.class,
                        UUID.fromString(id)));
        data(post("/onto/candidates/" + id + "/review"), b);
        assertEquals(
                "READY_TO_COMMIT",
                data(post("/onto/candidates/" + id + "/prepare"), b).path("status").asText());
        var release = publish(new Ready(space, operation, candidate));
        assertEquals("READY", release.path("status").asText());
        var model =
                RdfCodec.model(
                        bundles.payload(
                                bundles.release(
                                        UUID.fromString(release.path("id").asText()), true)));
        try {
            assertTrue(
                    model.contains(
                            null,
                            org.apache.jena.vocabulary.OWL.inverseOf,
                            (org.apache.jena.rdf.model.RDFNode) null));
            assertTrue(
                    model.contains(
                            null,
                            org.apache.jena.vocabulary.RDF.type,
                            org.apache.jena.vocabulary.OWL.SymmetricProperty));
        } finally {
            model.close();
        }
        createClass(space, "发布后的新草稿", null);
        call(post("/onto/candidates/" + id + "/waivers"), ownerToken, approval, 409);
    }
}
