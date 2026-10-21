package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 正式入口真实授权/身份/meta；客户端无法借OWNER角色或API key替代端点及空间授予。 */
class P4GatewayTest extends P4Fixture {
    void endpointGrant(String endpoint, UUID user, Object scope) throws Exception {
        var b = new LinkedHashMap<String, Object>();
        b.put("status", "ACTIVE");
        b.put("dataScope", scope);
        b.put("expiresAt", null);
        data(put("/onto/endpoints/" + endpoint + "/grants/" + user), b);
    }

    @Test
    void formalRuleExecutionPersistsActualFrozenRuleAndServiceEvidence() throws Exception {
        String space = createSpace(), cl = createClass(space, "校验对象", null);
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "name",
                        "冻结校验",
                        "applicableClassId",
                        cl,
                        "kind",
                        "VALIDATION",
                        "condition",
                        "",
                        "action",
                        Map.of("type", "ACCEPT", "message", "冻结规则通过"),
                        "severity",
                        "ERROR"));
        String rule =
                data(get("/onto/rules").param("spaceId", space), null).get(0).path("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "code",
                        "listClasses",
                        "name",
                        "列出对象",
                        "kind",
                        "RULE_CHECK",
                        "ruleIds",
                        List.of(rule),
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        String operation =
                data(get("/onto/operations").param("spaceId", space), null)
                        .get(0)
                        .path("id")
                        .asText();
        configure(space, operation);
        var old = data(get("/onto/test-cases").param("spaceId", space), null).get(0);
        var test = new LinkedHashMap<String, Object>(json.convertValue(old, Map.class));
        test.put("spaceId", space);
        test.put("expectedDraftRev", rev(space));
        test.put(
                "expected",
                Map.of(
                        "rows",
                        List.of(Map.of("ruleId", rule, "passed", "true", "message", "冻结规则通过"))));
        data(put("/onto/test-cases/" + old.path("id").asText()), test);
        var candidate = freeze(space);
        String c = candidate.path("id").asText();
        data(post("/onto/candidates/" + c + "/validate"), null);
        assertTrue(data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        var b = bound(space, candidate);
        data(post("/onto/candidates/" + c + "/review"), b);
        data(post("/onto/candidates/" + c + "/prepare"), b);
        var release = publish(new Ready(space, operation, candidate));
        String id = release.path("id").asText();
        endpointGrant(
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText(),
                owner,
                null);
        var result = data(post("/onto/internal/releases/" + id + "/ops/listClasses"), Map.of());
        assertEquals("冻结规则通过", result.path("rows").get(0).path("message").asText());
        String execution =
                db.queryForObject(
                        "SELECT id::text FROM op_execution WHERE subject_type='RELEASE' ORDER BY"
                                + " created_at DESC LIMIT 1",
                        String.class);
        var trace = data(get("/onto/executions/" + execution + "/trace"), null);
        boolean found = false;
        for (var evidence : trace.path("evidence"))
            if ("RULE_VER".equals(evidence.path("type").asText())) {
                found = true;
                assertEquals(rule, evidence.path("ref").asText());
                assertTrue(evidence.path("sha256").asText().matches("[a-f0-9]{64}"));
                assertEquals(id, evidence.path("detail").path("releaseId").asText());
            }
        assertTrue(found, "真实执行过的冻结规则必须有版本证据");
    }

    @Test
    void endpointGrantDoesNotReplaceSpaceReadAndViewerCanOnlyUseExplicitFormalEndpoint()
            throws Exception {
        var r = ready();
        var release = publish(r);
        String id = release.path("id").asText();
        String endpoint =
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText();
        endpointGrant(endpoint, stranger, null);
        var denied =
                call(
                        post("/onto/internal/releases/" + id + "/ops/listClasses"),
                        strangerToken,
                        Map.of(),
                        403);
        assertEquals("NO_PERMISSION", denied.path("code").asText());
        assertFalse(denied.has("meta"));
        data(
                put("/onto/spaces/" + r.space() + "/grants/" + stranger),
                Map.of(
                        "expectedDraftRev",
                        rev(r.space()),
                        "role",
                        "VIEWER",
                        "attributes",
                        Map.of("region", "A")));
        var response =
                call(
                        post("/onto/internal/releases/" + id + "/ops/listClasses"),
                        strangerToken,
                        Map.of(),
                        200);
        assertEquals(id, response.path("meta").path("releaseId").asText());
        assertEquals("v1", response.path("meta").path("version").asText());
        assertFalse(response.path("meta").path("preview").asBoolean());
        call(
                post("/onto/candidates/" + r.candidate().path("id").asText() + "/trial"),
                strangerToken,
                Map.of("operationId", r.operation()),
                403);
        String execution =
                db.queryForObject(
                        "SELECT id::text FROM op_execution WHERE subject_type='RELEASE' AND"
                                + " status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
                        String.class);
        var trace = data(get("/onto/executions/" + execution + "/trace"), null);
        assertEquals(6, trace.path("authorization").size());
        for (var step : trace.path("authorization")) assertTrue(step.path("passed").asBoolean());
        assertEquals("RELEASE", trace.path("execution").path("subjectType").asText());
        assertEquals(id, trace.path("execution").path("subjectId").asText());
        var serviceEvidence = trace.path("evidence").findValuesAsText("type");
        assertTrue(serviceEvidence.contains("SERVICE_VER"), "正式调用须保存实际冻结服务定义证据");
    }

    @Test
    void apiKeyIsHashOnlyExternalFactUsesActualVersionAndRevocationIsImmediate() throws Exception {
        var r = ready();
        var release = publish(r);
        String id = release.path("id").asText(),
                code = data(get("/onto/spaces/" + r.space()), null).path("code").asText();
        String endpoint =
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText();
        endpointGrant(endpoint, owner, null);
        var created =
                data(
                        post("/onto/api-credentials"),
                        Map.of(
                                "spaceId",
                                r.space(),
                                "principalId",
                                owner.toString(),
                                "name",
                                "直接消费",
                                "expiresAt",
                                java.time.Instant.now().plusSeconds(600).toString()));
        String key = created.path("key").asText();
        assertFalse(
                data(get("/onto/api-credentials").param("spaceId", r.space()), null)
                        .toString()
                        .contains(key));
        assertNotEquals(
                key, db.queryForObject("SELECT key_hash FROM api_credential", String.class));
        var response =
                mvc.perform(
                                post("/onto/r/" + code + "/releases/" + id + "/ops/listClasses")
                                        .header("X-API-Key", key)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn();
        assertEquals(
                200,
                response.getResponse().getStatus(),
                response.getResponse().getContentAsString());
        assertEquals(id, response.getResponse().getHeader("X-Ontology-Release"));
        var body = json.readTree(response.getResponse().getContentAsString());
        assertEquals(
                release.path("bundleSha256").asText(),
                body.path("meta").path("contentDigest").asText());
        assertEquals(
                "EXTERNAL",
                db.queryForObject(
                        "SELECT caller FROM op_execution WHERE subject_type='RELEASE' ORDER BY"
                                + " created_at DESC LIMIT 1",
                        String.class));
        var alias =
                call(
                        post("/onto/s/" + code + "/ops/listClasses").param("releaseId", id),
                        ownerToken,
                        Map.of(),
                        200);
        assertEquals(body.path("meta"), alias.path("meta"));
        var listed = data(get("/onto/spaces/" + r.space() + "/executions"), null);
        var callers = new HashSet<String>();
        for (var item : listed) callers.add(item.path("caller").asText());
        assertTrue(
                callers.containsAll(Set.of("SERVICE", "EXTERNAL", "LOCAL_TEST")),
                "执行列表必须保留三类实际入口身份：" + listed);
        data(
                post(
                        "/onto/api-credentials/"
                                + created.path("credential").path("id").asText()
                                + "/revoke"),
                null);
        var rejected =
                mvc.perform(
                                post("/onto/r/" + code + "/releases/" + id + "/ops/listClasses")
                                        .header("X-API-Key", key)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn();
        assertEquals(401, rejected.getResponse().getStatus());
        assertNull(rejected.getResponse().getHeader("X-Ontology-Release"));
        assertFalse(json.readTree(rejected.getResponse().getContentAsString()).has("meta"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "boolean", "null"})
    void formalHttpPreservesAnyLegalJsonRootThroughFrozenSchema(String type) throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        Object example =
                switch (type) {
                    case "array" -> List.of(1, 2);
                    case "boolean" -> true;
                    default -> null;
                };
        var declaration =
                json.convertValue(
                        data(
                                get("/onto/operations/" + operation + "/assistant")
                                        .param("spaceId", space),
                                null),
                        Map.class);
        var b = new LinkedHashMap<String, Object>(declaration);
        b.put("spaceId", space);
        b.put("expectedDraftRev", rev(space));
        b.put("inputSchema", Map.of("type", type));
        var tool = new LinkedHashMap<String, Object>((Map<String, Object>) b.get("assistantTool"));
        tool.put("param_examples", Collections.singletonList(example));
        b.put("assistantTool", tool);
        data(put("/onto/operations/" + operation + "/assistant"), b);
        var test =
                json.convertValue(
                        data(get("/onto/test-cases").param("spaceId", space), null).get(0),
                        Map.class);
        var t = new LinkedHashMap<String, Object>(test);
        t.put("spaceId", space);
        t.put("expectedDraftRev", rev(space));
        t.put("input", example);
        data(put("/onto/test-cases/" + test.get("id")), t);
        var c = freeze(space);
        String candidate = c.path("id").asText();
        data(post("/onto/candidates/" + candidate + "/validate"), null);
        assertTrue(
                data(post("/onto/candidates/" + candidate + "/tests"), null)
                        .path("passed")
                        .asBoolean());
        var bound = bound(space, c);
        data(post("/onto/candidates/" + candidate + "/review"), bound);
        data(post("/onto/candidates/" + candidate + "/prepare"), bound);
        var release = publish(new Ready(space, operation, c));
        String id = release.path("id").asText();
        String endpoint =
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText();
        endpointGrant(endpoint, owner, null);
        var result =
                mvc.perform(
                                post("/onto/internal/releases/" + id + "/ops/listClasses")
                                        .header("Authorization", "Bearer " + ownerToken)
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(example)))
                        .andReturn();
        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(id, result.getResponse().getHeader("X-Ontology-Release"));
    }
}
