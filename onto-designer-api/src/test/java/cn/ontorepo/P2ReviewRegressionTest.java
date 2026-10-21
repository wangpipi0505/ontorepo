package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.data.domain.LabelScope;
import cn.ontorepo.trace.application.FrozenExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.stream.Stream;

/** 独立复审R1～R4：真实PG与真实HTTP转换边界，不替换执行器业务实现。 */
class P2ReviewRegressionTest extends WorkflowFixture {
    @Test
    void r2OpenApiRetainsAnyJsonInputInsteadOfAnObjectOnlyReference() throws Exception {
        var spec = call(get("/v3/api-docs"), ownerToken, null, 200);
        var trial =
                spec.path("components")
                        .path("schemas")
                        .path("Trial")
                        .path("properties")
                        .path("input");
        assertFalse(trial.has("$ref"));
        var service =
                spec.path("paths")
                        .path("/onto/internal/candidates/{id}/ops/{opCode}")
                        .path("post")
                        .path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("schema");
        assertFalse(service.has("$ref"));
        assertTrue(service.path("type").toString().contains("array"));
        assertTrue(service.path("type").toString().contains("null"));
    }

    @Test
    void r2LegacyExplicitNullInputKeepsP1EmptyAttributeDefault() throws Exception {
        String s = createSpace(), cl = createClass(s, "A", null);
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "默认校验",
                        "applicableClassId",
                        cl,
                        "kind",
                        "VALIDATION",
                        "condition",
                        "",
                        "action",
                        Map.of("type", "ACCEPT", "message", "通过"),
                        "severity",
                        "ERROR"));
        String rule = data(get("/onto/rules").param("spaceId", s), null).get(0).path("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "validate",
                        "name",
                        "校验",
                        "kind",
                        "RULE_CHECK",
                        "ruleIds",
                        List.of(rule),
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        String
                op =
                        data(get("/onto/operations").param("spaceId", s), null)
                                .get(0)
                                .path("id")
                                .asText(),
                c = freeze(s).path("id").asText();
        var b = new LinkedHashMap<String, Object>();
        b.put("operationId", op);
        b.put("input", null);
        assertEquals(
                "SUCCEEDED",
                data(post("/onto/candidates/" + c + "/trial"), b).path("status").asText());
    }

    @Test
    void r1NormalKnowledgeAliasStillSelectsAndRealAmbiguityStillFails() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, "region");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", Map.of("type", "object"), Map.of()));
        data(
                post("/onto/knowledge"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "term",
                        "对象",
                        "aliases",
                        List.of("实体", "!!!"),
                        "definition",
                        "领域对象",
                        "sourceRef",
                        "review:R1-valid"));
        localCase(s, op, Map.of(), "NL", "有哪些实体");
        String c = freeze(s).path("id").asText();
        assertTrue(data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "other",
                        "name",
                        "其他操作",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        for (var o : data(get("/onto/operations").param("spaceId", s), null))
            if ("other".equals(o.path("code").asText()))
                data(
                        put("/onto/operations/" + o.path("id").asText() + "/assistant"),
                        declaration(s, "region", Map.of("type", "object"), Map.of()));
        c = freeze(s).path("id").asText();
        assertFalse(
                data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        assertEquals(
                "NL_OPERATION_AMBIGUOUS",
                db.queryForObject(
                        "SELECT error_code FROM op_execution ORDER BY created_at DESC LIMIT 1",
                        String.class));
    }

    @Test
    void r2RuleObjectConstraintPreservesOriginalCompositionAndLocalReferences() throws Exception {
        String s = createSpace(), cl = createClass(s, "A", null);
        guards(s, "region");
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "校验",
                        "applicableClassId",
                        cl,
                        "kind",
                        "VALIDATION",
                        "condition",
                        "",
                        "action",
                        Map.of("type", "ACCEPT", "message", "通过"),
                        "severity",
                        "ERROR"));
        String rule = data(get("/onto/rules").param("spaceId", s), null).get(0).path("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "validate",
                        "name",
                        "属性校验",
                        "kind",
                        "RULE_CHECK",
                        "ruleIds",
                        List.of(rule),
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        String op =
                data(get("/onto/operations").param("spaceId", s), null).get(0).path("id").asText();
        var schema =
                Map.<String, Object>of(
                        "$defs",
                        Map.of(
                                "root",
                                Map.of(
                                        "oneOf",
                                        List.of(
                                                Map.of("type", "object"),
                                                Map.of("type", "array")))),
                        "$ref",
                        "#/$defs/root");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", schema, Map.of()));
        String c = freeze(s).path("id").asText();
        var effective =
                data(get("/onto/candidates/" + c + "/contracts"), null)
                        .path("manifests")
                        .get(0)
                        .path("input_schema");
        assertEquals("#/$defs/root", effective.path("$ref").asText());
        cn.ontorepo.contract.JsonSchemaAssertions.validate(effective, json.createObjectNode());
        assertThrows(
                cn.ontorepo.contract.ContractProblem.class,
                () ->
                        cn.ontorepo.contract.JsonSchemaAssertions.validate(
                                effective, json.createArrayNode()));
        call(post("/onto/internal/candidates/" + c + "/ops/validate"), ownerToken, Map.of(), 200);
        clearInvocations(executor);
        assertEquals(
                "SCHEMA_VALIDATION_FAILED",
                call(
                                post("/onto/internal/candidates/" + c + "/ops/validate"),
                                ownerToken,
                                List.of(),
                                400)
                        .path("code")
                        .asText());
        verify(executor, never()).execute(anyMap(), anyMap(), any());
    }

    @Test
    void r3UnknownOperationIsRecordedButMissingTargetOrGrantIsAnEntryRejection() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, "region");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", Map.of("type", "object"), Map.of()));
        String c = freeze(s).path("id").asText();
        call(post("/onto/internal/candidates/" + c + "/ops/unknown"), ownerToken, Map.of(), 404);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE caller='SERVICE' AND"
                                + " op_code='unknown' AND error_code='OPERATION_NOT_IN_CANDIDATE'",
                        Integer.class));
        call(
                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                strangerToken,
                Map.of(),
                403);
        call(
                post("/onto/internal/candidates/" + UUID.randomUUID() + "/ops/listClasses"),
                ownerToken,
                Map.of(),
                404);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM op_execution", Integer.class));
        verify(executor, never()).execute(anyMap(), anyMap(), any());
    }

    @MockitoSpyBean FrozenExecutor executor;

    void guards(String s, String name) throws Exception {
        data(
                put("/onto/spaces/" + s + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "guardCfg",
                        Map.of(
                                name,
                                Map.of(
                                        "type",
                                        "STRING",
                                        "authorization_rule",
                                        Map.of(
                                                "kind",
                                                "PRINCIPAL_ATTRIBUTE",
                                                "attribute",
                                                name)))));
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(name, "A")));
    }

    Map<String, Object> declaration(
            String s, String attr, Map<String, Object> schema, Object example) throws Exception {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "列出对象");
        tool.put("description", "列出对象模型");
        tool.put("guard_attrs", List.of(attr));
        tool.put("info_attrs", List.of());
        tool.put("param_examples", Collections.singletonList(example));
        tool.put(
                "exec",
                Map.of(
                        "readonly",
                        true,
                        "side_effect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        true));
        tool.put("risk_level", "READ_ONLY");
        tool.put(
                "error_semantics",
                List.of(Map.of("match", "HTTP 403", "semantic", "NO_PERMISSION")));
        tool.put(
                "return_spec",
                Map.of(
                        "modelSummary",
                        Map.of("mode", "TEMPLATE", "template", "结果"),
                        "structuredPath",
                        "$.data",
                        "fileRefPaths",
                        List.of(),
                        "cardTemplate",
                        "结果"));
        return new LinkedHashMap<>(
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "inputSchema",
                        schema,
                        "outputSchema",
                        Map.of("type", "object"),
                        "assistantTool",
                        tool));
    }

    void localCase(String s, String op, Object input, String mode) throws Exception {
        localCase(s, op, input, mode, "完全陌生的问题");
    }

    void localCase(String s, String op, Object input, String mode, String question)
            throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("spaceId", s);
        b.put("expectedDraftRev", rev(s));
        b.put("name", "复审用例");
        b.put("question", question);
        b.put("operationId", op);
        b.put("input", input);
        b.put("expected", Map.of("rows", List.of()));
        b.put("enabled", true);
        b.put("mode", mode);
        data(post("/onto/test-cases"), b);
    }

    static Stream<Arguments> punctuation() {
        return Stream.of(
                Arguments.of("!!!", List.of(), false), Arguments.of("正常术语", List.of("!!!"), false),
                Arguments.of("!!!", List.of("对象", "!!!"), false),
                        Arguments.of("正常术语", List.of("!!!", "对象"), true));
    }

    @ParameterizedTest
    @MethodSource("punctuation")
    void r1EmptyNormalizedKnowledgeNeverTurnsNoHitIntoSuccessOrAmbiguity(
            String term, List<String> aliases, boolean multiple) throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, "region");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", Map.of("type", "object"), Map.of()));
        if (multiple) {
            data(
                    post("/onto/operations"),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "code",
                            "other",
                            "name",
                            "其他操作",
                            "queryTemplate",
                            QUERY,
                            "maxRows",
                            20,
                            "timeoutMs",
                            1000));
            for (var o : data(get("/onto/operations").param("spaceId", s), null))
                if ("other".equals(o.path("code").asText()))
                    data(
                            put("/onto/operations/" + o.path("id").asText() + "/assistant"),
                            declaration(s, "region", Map.of("type", "object"), Map.of()));
        }
        data(
                post("/onto/knowledge"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "term",
                        term,
                        "aliases",
                        aliases,
                        "definition",
                        "无关定义",
                        "sourceRef",
                        "review:R1"));
        localCase(s, op, Map.of(), "NL");
        String c = freeze(s).path("id").asText();
        assertFalse(
                data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        assertEquals(
                "NL_OPERATION_NOT_FOUND",
                db.queryForObject(
                        "SELECT error_code FROM op_execution ORDER BY created_at DESC LIMIT 1",
                        String.class));
        verify(executor, never()).execute(anyMap(), anyMap(), any());
    }

    record InputCase(Map<String, Object> schema, Object value) {}

    static Stream<InputCase> inputs() {
        return Stream.of(
                new InputCase(
                        Map.of("type", "array", "items", Map.of("type", "string")), List.of("x")),
                new InputCase(Map.of("type", "string"), "x"),
                new InputCase(Map.of("type", "number"), 42),
                new InputCase(Map.of("type", "boolean"), true),
                new InputCase(Map.of("type", "null"), null),
                new InputCase(
                        Map.of(
                                "oneOf",
                                List.of(
                                        Map.of("type", "object"),
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "string")))),
                        List.of("mixed")));
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void r2EveryDeclaredJsonRootRunsThroughServiceTrialAndLocalTest(InputCase test)
            throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, "region");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", test.schema, test.value));
        String c = freeze(s).path("id").asText();
        assertEquals(
                json.valueToTree(test.schema),
                data(get("/onto/candidates/" + c + "/contracts"), null)
                        .path("manifests")
                        .get(0)
                        .path("input_schema"));
        assertAll(
                () ->
                        assertEquals(
                                0,
                                call(
                                                post(
                                                        "/onto/internal/candidates/"
                                                                + c
                                                                + "/ops/listClasses"),
                                                ownerToken,
                                                json.valueToTree(test.value),
                                                200)
                                        .path("data")
                                        .path("rows")
                                        .size()),
                () -> {
                    var b = new LinkedHashMap<String, Object>();
                    b.put("operationId", op);
                    b.put("input", test.value);
                    assertEquals(
                            "SUCCEEDED",
                            data(post("/onto/candidates/" + c + "/trial"), b)
                                    .path("status")
                                    .asText());
                },
                () -> {
                    localCase(s, op, test.value, "DIRECT");
                    String local = freeze(s).path("id").asText();
                    assertTrue(
                            data(post("/onto/candidates/" + local + "/tests"), null)
                                    .path("passed")
                                    .asBoolean());
                });
    }

    @Test
    void r3CorruptCandidateKeepsFactsAcrossAllThreeEntriesWithoutExecutorAccess() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, "region");
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, "region", Map.of("type", "object"), Map.of()));
        localCase(s, op, Map.of(), "DIRECT");
        String good = freeze(s).path("id").asText();
        UUID bad = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " publish_candidate(id,space_id,draft_rev,sha256,payload,format_version,capability)"
                    + " SELECT ?,space_id,draft_rev,sha256,?,format_version,capability FROM"
                    + " publish_candidate WHERE id=?",
                bad,
                new byte[] {0},
                UUID.fromString(good));
        var failed =
                call(
                        post("/onto/internal/candidates/" + bad + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        409);
        assertEquals("CANDIDATE_CORRUPT", failed.path("code").asText());
        assertFalse(failed.has("meta"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE subject_id=? AND caller='SERVICE'"
                                + " AND op_code='listClasses' AND principal_id=? AND"
                                + " error_code='CANDIDATE_CORRUPT'",
                        Integer.class,
                        bad,
                        owner));
        assertEquals(
                "CANDIDATE_CORRUPT",
                data(post("/onto/candidates/" + bad + "/trial"), Map.of("operationId", op))
                        .path("errorCode")
                        .asText());
        var local = data(post("/onto/candidates/" + bad + "/tests"), null);
        assertFalse(local.path("passed").asBoolean());
        assertFalse(local.path("results").get(0).path("executionId").asText().isBlank());
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE subject_id=? AND status='FAILED'"
                                + " AND error_code='CANDIDATE_CORRUPT'",
                        Integer.class,
                        bad));
        verify(executor, never()).execute(anyMap(), anyMap(), any());
    }

    static Stream<String> names() {
        return Stream.of("_region", "r".repeat(81), "r".repeat(128));
    }

    @ParameterizedTest
    @MethodSource("names")
    void r4HistoricalGuardNamesCanBeNarrowedByTheSameDataScope(String name) throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s, name);
        data(
                put("/onto/operations/" + op + "/assistant"),
                declaration(s, name, Map.of("type", "object"), Map.of()));
        String c = freeze(s).path("id").asText();
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(name, "A"),
                        "dataScope",
                        Map.of("attr", name, "in", List.of("A"))));
        assertEquals(
                "SUCCEEDED",
                data(post("/onto/candidates/" + c + "/trial"), Map.of("operationId", op))
                        .path("status")
                        .asText());
        assertTrue(
                LabelScope.parse(json.valueToTree(Map.of("attr", name, "in", List.of("A"))))
                        .allows(name, json.valueToTree("A")));
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(name, "A"),
                        "dataScope",
                        Map.of("attr", name, "in", List.of("B"))));
        assertEquals(
                "DATA_SCOPE_DENIED",
                call(
                                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                                ownerToken,
                                Map.of(),
                                403)
                        .path("code")
                        .asText());
    }
}
