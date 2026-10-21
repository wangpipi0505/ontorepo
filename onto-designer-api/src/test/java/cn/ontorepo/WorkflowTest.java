package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 所有业务用例连接真实 PostgreSQL；直接 SQL 仅建立未对外开放的成员授权与损坏场景。 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowTest extends WorkflowFixture {
    @Test
    void loginTokenIsHashedRevocableAndExpired() throws Exception {
        JsonNode login =
                call(
                                post("/onto/auth/login"),
                                null,
                                Map.of("username", "owner", "password", "test-password-123"),
                                200)
                        .get("data");
        String token = login.get("token").asText();
        assertEquals(43, token.length());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM auth_session WHERE token_hash=?",
                        Integer.class,
                        token));
        call(get("/onto/auth/me"), token, null, 200);
        call(post("/onto/auth/logout"), token, null, 200);
        call(get("/onto/auth/me"), token, null, 401);
        db.update(
                "UPDATE auth_session SET expires_at=now()-interval '1 second' WHERE user_id=?",
                viewer);
        call(get("/onto/spaces"), viewerToken, null, 401);
        call(post("/onto/auth/login"), null, Map.of("username", "owner", "password", "wrong"), 401);
    }

    @Test
    void invisibleAndViewerCannotMutateOrExecute() throws Exception {
        String s = createSpace();
        grant(s, viewer, "VIEWER");
        call(get("/onto/spaces/" + s), strangerToken, null, 403);
        assertEquals(0, call(get("/onto/spaces"), strangerToken, null, 200).get("data").size());
        call(post("/onto/classes"), strangerToken, cl(s, "禁止", null, 0), 403);
        call(post("/onto/classes"), viewerToken, cl(s, "禁止", null, 0), 403);
        call(
                post("/onto/spaces/" + s + "/candidates"),
                viewerToken,
                Map.of("expectedDraftRev", 0),
                403);
        String op = createOperation(s, 50);
        String c = freeze(s).get("id").asText();
        call(post("/onto/candidates/" + c + "/trial"), viewerToken, Map.of("operationId", op), 403);
        call(get("/onto/candidates/" + c + "/operations"), strangerToken, null, 403);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM op_execution", Integer.class));
    }

    @Test
    void siblingUniquenessCyclesAndCrossSpaceAreRejectedAtomically() throws Exception {
        String s = createSpace(), other = createSpace();
        String a = createClass(s, "A", null),
                b = createClass(s, "B", a),
                x = createClass(other, "X", null);
        long r = rev(s);
        int audit = db.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
        call(put("/onto/classes/" + a), ownerToken, cl(s, "A", b, r), 400);
        call(post("/onto/classes"), ownerToken, cl(s, "C", x, r), 400);
        call(post("/onto/classes"), ownerToken, cl(s, "A", null, r), 409);
        assertEquals(r, rev(s));
        assertEquals(audit, db.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        call(
                delete("/onto/classes/" + a).param("spaceId", s).param("expectedDraftRev", "" + r),
                ownerToken,
                null,
                409);
        call(post("/onto/classes"), ownerToken, cl(s, "过期", null, r - 1), 409);
        assertEquals(
                "DRAFT_REV_CONFLICT",
                call(post("/onto/classes"), ownerToken, cl(s, "过期", null, r - 1), 409)
                        .get("code")
                        .asText());
    }

    @Test
    void propertyCrudChecksOwnerAndPreventsClassDeletion() throws Exception {
        String s = createSpace(),
                other = createSpace(),
                cl = createClass(s, "A", null),
                foreign = createClass(other, "B", null);
        Map<String, Object> b =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "domainClassId",
                                cl,
                                "name",
                                "名称",
                                "rangeType",
                                "STRING",
                                "required",
                                true,
                                "description",
                                "基础属性",
                                "expectedDraftRev",
                                rev(s)));
        data(post("/onto/properties"), b);
        String p =
                data(get("/onto/properties").param("spaceId", s).param("classId", cl), null)
                        .get(0)
                        .get("id")
                        .asText();
        b.put("expectedDraftRev", rev(s));
        call(post("/onto/properties"), ownerToken, b, 409);
        b.put("domainClassId", foreign);
        call(put("/onto/properties/" + p), ownerToken, b, 400);
        b.put("domainClassId", cl);
        b.put("rangeType", "INTEGER");
        data(put("/onto/properties/" + p), b);
        call(
                delete("/onto/classes/" + cl)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
        data(
                delete("/onto/properties/" + p)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
        data(
                delete("/onto/classes/" + cl)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
    }

    @Test
    void frozenModelAndOperationSurviveDraftChangesWithStableDigest() throws Exception {
        String s = createSpace(), a = createClass(s, "旧名称", null);
        createClass(s, "第二个", null);
        String op = createOperation(s, 1);
        JsonNode first = freeze(s), same = freeze(s);
        assertEquals(first.get("sha256"), same.get("sha256"));
        byte[] frozenBytes =
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(first.get("id").asText()));
        assertTrue(json.readTree(frozenBytes).get("space").has("createdAt"), "冻结空间应保留稳定创建时间");
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE publish_candidate SET payload=payload WHERE id=?",
                                UUID.fromString(first.get("id").asText())));

        data(put("/onto/classes/" + a), cl(s, "新名称", null, rev(s)));
        data(
                delete("/onto/operations/" + op)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
        assertEquals(
                1,
                data(get("/onto/candidates/" + first.get("id").asText() + "/operations"), null)
                        .size());
        JsonNode execution =
                data(
                        post("/onto/candidates/" + first.get("id").asText() + "/trial"),
                        Map.of("operationId", op));
        assertEquals("SUCCEEDED", execution.get("status").asText());
        assertEquals(first.get("sha256"), execution.get("candidateSha256"));
        assertEquals(1, execution.get("rows").size());
        assertNotEquals("新名称", execution.get("rows").get(0).get("name").asText());
        assertEquals(3, execution.get("steps").size());
        assertEquals(1, data(get("/onto/spaces/" + s + "/executions"), null).size());
        assertTrue(
                db.queryForObject(
                        "SELECT octet_length(payload)>0 FROM publish_candidate WHERE id=?",
                        Boolean.class,
                        UUID.fromString(first.get("id").asText())));
    }

    @Test
    void corruptionFailsClosedAndPersistsExecutionAudit() throws Exception {
        String s = createSpace();
        String op = createOperation(s, 50);
        String id = freeze(s).get("id").asText();
        db.execute("ALTER TABLE publish_candidate DISABLE TRIGGER candidate_no_update");
        try {
            db.update(
                    "UPDATE publish_candidate SET payload=? WHERE id=?",
                    "broken".getBytes(StandardCharsets.UTF_8),
                    UUID.fromString(id));
        } finally {
            db.execute("ALTER TABLE publish_candidate ENABLE TRIGGER candidate_no_update");
        }
        call(get("/onto/candidates/" + id + "/operations"), ownerToken, null, 409);
        JsonNode result =
                data(post("/onto/candidates/" + id + "/trial"), Map.of("operationId", op));
        assertEquals("FAILED", result.get("status").asText());
        assertEquals("CANDIDATE_CORRUPT", result.get("errorCode").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM op_execution_step", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action='CANDIDATE_TRIAL'",
                        Integer.class));
    }

    @Test
    void concurrentWritersHaveOneWinner() throws Exception {
        String s = createSpace();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task =
                    () ->
                            mvc.perform(
                                            post("/onto/classes")
                                                    .header("Authorization", "Bearer " + ownerToken)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsBytes(
                                                                    cl(
                                                                            s,
                                                                            UUID.randomUUID()
                                                                                    .toString(),
                                                                            null,
                                                                            0))))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var first = pool.submit(task);
            var second = pool.submit(task);
            List<Integer> statuses = new ArrayList<>(List.of(first.get(), second.get()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, rev(s));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM ont_class", Integer.class));
    }

    @Test
    void archiveIsOwnerOnlyAndPreventsLaterWritesAndTrials() throws Exception {
        String s = createSpace();
        grant(s, viewer, "EDITOR");
        String op = createOperation(s, 50), c = freeze(s).get("id").asText();
        call(
                post("/onto/spaces/" + s + "/archive"),
                viewerToken,
                Map.of("expectedDraftRev", rev(s)),
                403);
        JsonNode archived =
                data(post("/onto/spaces/" + s + "/archive"), Map.of("expectedDraftRev", rev(s)));
        assertEquals("ARCHIVED", archived.get("status").asText());
        call(post("/onto/classes"), ownerToken, cl(s, "禁止", null, rev(s)), 409);
        call(post("/onto/candidates/" + c + "/trial"), ownerToken, Map.of("operationId", op), 409);
        data(get("/onto/candidates/" + c + "/operations"), null);
    }

    @Test
    void openapiDescribesRealRequestAndResponseDtos() throws Exception {
        JsonNode doc = call(get("/v3/api-docs"), ownerToken, null, 200);
        assertTrue(doc.get("paths").has("/onto/classes"));
        assertTrue(doc.get("components").get("schemas").has("ClassWrite"));
        assertTrue(doc.get("components").get("schemas").has("Execution"));
    }

    @Test
    void concurrentSpaceUpdatesMustNotLoseRevisions() throws Exception {
        String s = createSpace();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            Callable<Integer> task =
                    () ->
                            mvc.perform(
                                            put("/onto/spaces/" + s)
                                                    .header("Authorization", "Bearer " + ownerToken)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsBytes(
                                                                    Map.of(
                                                                            "name",
                                                                            UUID.randomUUID()
                                                                                    .toString(),
                                                                            "domain",
                                                                            "并发测试",
                                                                            "expectedDraftRev",
                                                                            0))))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(task));
            List<Integer> statuses = new ArrayList<>();
            for (var f : futures) statuses.add(f.get());
            assertEquals(
                    1,
                    statuses.stream().filter(status -> status == 200).count(),
                    statuses.toString());
            assertEquals(3, statuses.stream().filter(status -> status == 409).count());
        }
        assertEquals(1, rev(s));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action='SPACE_UPDATE'",
                        Integer.class));
    }

    @Test
    void executionFactHasPrincipalAndDigests() throws Exception {
        String space = createSpace();
        String operation = createOperation(space, 50);
        String candidate = freeze(space).get("id").asText();
        JsonNode result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", operation));
        assertEquals(
                4,
                db.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE"
                                + " table_name='op_execution' AND column_name IN"
                                + " ('principal_id','caller','input_digest','output_digest')",
                        Integer.class));
        Map<String, Object> fact =
                db.queryForMap(
                        "SELECT principal_id,caller,input_digest,output_digest FROM op_execution"
                                + " WHERE id=?",
                        UUID.fromString(result.get("id").asText()));
        assertEquals(owner, fact.get("principal_id"));
        assertEquals("TRIAL", fact.get("caller"));
        assertEquals(64, fact.get("input_digest").toString().length());
        assertEquals(64, fact.get("output_digest").toString().length());
    }

    @Test
    void fractionalAndStringRevisionCannotBeSilentlyCoerced() throws Exception {
        String space = createSpace();
        call(
                put("/onto/spaces/" + space),
                ownerToken,
                Map.of("name", "拒绝隐式类型转换", "domain", "", "expectedDraftRev", 0.5),
                400);
        call(
                put("/onto/spaces/" + space),
                ownerToken,
                Map.of("name", "拒绝隐式类型转换", "domain", "", "expectedDraftRev", "0"),
                400);
        assertEquals(0, rev(space));
    }

    @Test
    void auditFailureRollsBackModelAndRevision() throws Exception {
        String space = createSpace();
        db.execute(
                "CREATE FUNCTION test_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " RAISE EXCEPTION 'simulated audit failure'; END $$");
        db.execute(
                "CREATE TRIGGER test_audit_failure BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE"
                        + " FUNCTION test_reject_audit()");
        try {
            call(post("/onto/classes"), ownerToken, cl(space, "必须回滚", null, 0), 500);
        } finally {
            db.execute("DROP TRIGGER test_audit_failure ON audit_log");
            db.execute("DROP FUNCTION test_reject_audit()");
        }
        assertEquals(0, rev(space));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ont_class", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    @Test
    void missingRequiredQueryArgumentsReturn400() throws Exception {
        String space = createSpace();
        assertEquals(
                "INVALID_ARGUMENT",
                call(get("/onto/properties").param("spaceId", space), ownerToken, null, 400)
                        .get("code")
                        .asText());
        assertEquals(
                "INVALID_ARGUMENT",
                call(
                                delete("/onto/classes/" + UUID.randomUUID())
                                        .param("spaceId", space),
                                ownerToken,
                                null,
                                400)
                        .get("code")
                        .asText());
    }
}
