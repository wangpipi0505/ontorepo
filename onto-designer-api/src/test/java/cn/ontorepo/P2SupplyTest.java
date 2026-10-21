package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;

/** P2 真实 PostgreSQL 验收，失败入口和冻结隔离优先。 */
class P2SupplyTest extends WorkflowFixture {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.ontorepo.trace.application.FrozenExecutor frozenExecutor;

    @Test
    void nlUsesOrdinaryFrozenParameterExampleValues() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        var declaration = assistant(s, List.of("region"));
        declaration.put(
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("code"),
                        "properties",
                        Map.of("code", Map.of("type", "string")),
                        "additionalProperties",
                        false));
        var tool = new LinkedHashMap<>((Map<String, Object>) declaration.get("assistantTool"));
        tool.put("name", "代号处理");
        tool.put("description", "从本地模型返回所有定义");
        tool.put("param_examples", List.of(Map.of("code", "ZX908")));
        declaration.put("assistantTool", tool);
        data(put("/onto/operations/" + op + "/assistant"), declaration);
        var test =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "参数例匹配",
                                "question",
                                "处理ZX908",
                                "operationId",
                                op,
                                "input",
                                Map.of("code", "ZX908"),
                                "expected",
                                Map.of("rows", List.of()),
                                "enabled",
                                true));
        test.put("mode", "NL");
        data(post("/onto/test-cases"), test);
        String c = freeze(s).path("id").asText();
        assertTrue(data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
    }

    @Test
    void contextPackContainsConceptPropertiesAndFrozenExampleAnswers() throws Exception {
        String s = createSpace(), cl = createClass(s, "设备", null), op = createOperation(s, 20);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "domainClassId",
                        cl,
                        "name",
                        "序列号",
                        "rangeType",
                        "STRING",
                        "required",
                        false,
                        "description",
                        "业务标识"));
        data(
                post("/onto/test-cases"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "示例问答",
                        "question",
                        "示例问题",
                        "operationId",
                        op,
                        "input",
                        Map.of(),
                        "expected",
                        Map.of("status", "SUCCEEDED"),
                        "matchMode",
                        "CONTAINS",
                        "enabled",
                        true));
        String c = freeze(s).path("id").asText();
        var pack = data(get("/onto/candidates/" + c + "/context-pack"), null);
        assertEquals(
                "序列号",
                pack.path("conceptCards").path(0).path("properties").path(0).path("name").asText());
        boolean qa = false;
        for (var item : pack.path("exampleQa"))
            if ("示例问题".equals(item.path("question").asText()))
                qa =
                        "SUCCEEDED".equals(item.path("answer").path("status").asText())
                                && item.path("sourceRef").asText().startsWith("test-case:");
        assertTrue(qa);
    }

    @Test
    void contractResourceLimitsMatchFrozenStrategyIntersection() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        createClass(s, "A", null);
        createClass(s, "B", null);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        data(
                post("/onto/strategies"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "资源交集",
                        "boundOps",
                        List.of(op),
                        "policy",
                        Map.of(
                                "maxRows",
                                3,
                                "resultLimit",
                                1,
                                "timeoutMs",
                                200,
                                "failureMode",
                                "FAIL",
                                "readScope",
                                Map.of())));
        String c = freeze(s).path("id").asText();
        var contract =
                data(get("/onto/candidates/" + c + "/contracts"), null).path("manifests").get(0);
        assertEquals(1, contract.path("exec_attrs").path("max_rows").asInt());
        assertEquals(200, contract.path("exec_attrs").path("timeout_ms").asInt());
        assertEquals(
                1,
                data(post("/onto/candidates/" + c + "/trial"), Map.of("operationId", op))
                        .path("rows")
                        .size());
    }

    @org.springframework.beans.factory.annotation.Autowired
    cn.ontorepo.trace.application.CandidateService candidateService;

    @org.springframework.beans.factory.annotation.Autowired
    cn.ontorepo.trace.infra.CanonicalPayload codec;

    @Test
    void originalLegacyCandidateBytesRemainUnchangedAndLocalOnly() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        var fresh = freeze(s);
        var payload =
                candidateService.payload(
                        candidateService.candidate(
                                owner, UUID.fromString(fresh.path("id").asText())));
        payload.remove("formatVersion");
        payload.remove("capability");
        payload.remove("knowledge");
        ((Map<?, ?>) payload.get("space")).remove("guardCfg");
        for (var row : codec.rows(payload, "operations")) {
            row.remove("assistantTool");
            row.remove("inputSchema");
            row.remove("outputSchema");
        }
        byte[] bytes = codec.encode(payload);
        String sha = cn.ontorepo.space.application.AuthService.digest(bytes);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO publish_candidate(id,space_id,draft_rev,sha256,payload)"
                        + " VALUES(?,?,?,?,?)",
                id,
                UUID.fromString(s),
                rev(s),
                sha,
                bytes);
        assertEquals(
                "SUCCEEDED",
                data(post("/onto/candidates/" + id + "/trial"), Map.of("operationId", op))
                        .path("status")
                        .asText());
        createClass(s, "新草稿", null);
        assertArrayEquals(
                bytes,
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?", byte[].class, id));
        call(
                post("/onto/internal/candidates/" + id + "/ops/listClasses"),
                ownerToken,
                Map.of(),
                409);
    }

    @Test
    void positiveNlUsesFrozenKnowledgeAndExactRowsWhileNoHitFails() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        var test =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "精确NL",
                                "question",
                                "列出对象模型",
                                "operationId",
                                op,
                                "input",
                                Map.of(),
                                "expected",
                                Map.of("rows", List.of()),
                                "enabled",
                                true));
        test.put("mode", "NL");
        data(post("/onto/test-cases"), test);
        String c = freeze(s).path("id").asText();
        assertTrue(data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        String testId =
                data(get("/onto/test-cases").param("spaceId", s), null).get(0).path("id").asText();
        test.put("question", "完全陌生的问题");
        test.put("expectedDraftRev", rev(s));
        data(put("/onto/test-cases/" + testId), test);
        c = freeze(s).path("id").asText();
        assertFalse(
                data(post("/onto/candidates/" + c + "/tests"), null).path("passed").asBoolean());
        assertEquals(
                "NL_OPERATION_NOT_FOUND",
                db.queryForObject(
                        "SELECT error_code FROM op_execution ORDER BY created_at DESC LIMIT 1",
                        String.class));
    }

    @Test
    void contractGateCannotAcceptIncompleteSupplyDeclaration() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("tenant_label")));
        var c = freeze(s);
        assertEquals("CONTRACT", c.path("capability").asText());
        var report = data(post("/onto/candidates/" + c.path("id").asText() + "/checks"), null);
        assertFalse(report.path("eligible").asBoolean());
        assertFalse(report.path("supplyEligible").asBoolean());
        boolean failed = false;
        for (var check : report.path("checks"))
            if ("CONTRACT".equals(check.path("kind").asText()))
                failed = "FAILED".equals(check.path("conclusion").asText());
        assertTrue(failed);
    }

    @Test
    void unsupportedReleaseRouteHasNoFakeReadyOrMeta() throws Exception {
        var failed =
                call(
                        post("/onto/internal/releases/" + UUID.randomUUID() + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        404);
        assertFalse(failed.has("meta"));
    }

    @Test
    void openApiIncludesP2AndConsumptionValidationPreservesErrors() throws Exception {
        var spec = call(get("/v3/api-docs"), ownerToken, null, 200);
        for (String path :
                List.of(
                        "/onto/operations/{id}/assistant",
                        "/onto/spaces/{id}/guards",
                        "/onto/spaces/{id}/grants/{userId}",
                        "/onto/knowledge",
                        "/onto/candidates/{id}/contracts",
                        "/onto/internal/candidates/{id}/ops/{opCode}"))
            assertTrue(spec.path("paths").has(path), path);
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("../docs/evidence/continuation/c4-openapi.json"),
                spec.toPrettyString());
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        var c = freeze(s);
        var preview =
                data(get("/onto/candidates/" + c.path("id").asText() + "/contracts"), null)
                        .path("manifests")
                        .get(0);
        var sample =
                new cn.ontorepo.contract.ContractGenerator()
                        .formalize(
                                preview,
                                "http://127.0.0.1:18182",
                                data(get("/onto/spaces/" + s), null).path("code").asText(),
                                UUID.randomUUID().toString(),
                                "1.0.0",
                                c.path("sha256").asText());
        assertTrue(data(post("/onto/contracts/validate"), sample).path("valid").asBoolean());
        ((com.fasterxml.jackson.databind.node.ObjectNode) sample).remove("guard_attrs");
        var error = call(post("/onto/contracts/validate"), ownerToken, sample, 400);
        assertTrue(error.has("field"));
        assertTrue(error.has("reason"));
    }

    Map<String, Object> assistant(String s, List<String> guards) throws Exception {
        return new LinkedHashMap<>(
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of()),
                        "outputSchema",
                        Map.of(
                                "type",
                                "object",
                                "required",
                                List.of("columns", "rows"),
                                "properties",
                                Map.of(
                                        "columns",
                                        Map.of("type", "array", "items", Map.of("type", "string")),
                                        "rows",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "object")))),
                        "assistantTool",
                        Map.of(
                                "name",
                                "列出对象",
                                "description",
                                "列出对象模型",
                                "guard_attrs",
                                guards,
                                "info_attrs",
                                List.of(),
                                "param_examples",
                                List.of(Map.of()),
                                "exec",
                                Map.of(
                                        "readonly",
                                        true,
                                        "side_effect",
                                        false,
                                        "idempotent",
                                        true,
                                        "cancellable",
                                        true),
                                "risk_level",
                                "READ_ONLY",
                                "error_semantics",
                                List.of(Map.of("match", "HTTP 403", "semantic", "NO_PERMISSION")),
                                "return_spec",
                                Map.of(
                                        "modelSummary",
                                        Map.of("mode", "TEMPLATE", "template", "查询结果"),
                                        "structuredPath",
                                        "$.data",
                                        "fileRefPaths",
                                        List.of(),
                                        "cardTemplate",
                                        "查询结果"))));
    }

    void guards(String s) throws Exception {
        data(
                put("/onto/spaces/" + s + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "guardCfg",
                        Map.of(
                                "region",
                                Map.of(
                                        "type",
                                        "STRING",
                                        "authorization_rule",
                                        Map.of(
                                                "kind",
                                                "PRINCIPAL_ATTRIBUTE",
                                                "attribute",
                                                "region")))));
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of("region", "华东")));
    }

    @Test
    void independentDeclarationAndFrozenContractsUseFrozenValues() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        assertTrue(
                data(get("/onto/operations/" + op + "/assistant").param("spaceId", s), null)
                        .path("assistantTool")
                        .isNull());
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        JsonNode c = freeze(s);
        String id = c.path("id").asText();
        var before = data(get("/onto/candidates/" + id + "/contracts"), null);
        assertEquals(c.path("sha256"), before.path("candidateSha256"));
        assertEquals(4, before.path("manifests").get(0).path("ontology_meta").size() - 1);
        assertTrue(
                before.path("manifests")
                        .get(0)
                        .path("ontology_meta")
                        .path("contentDigest")
                        .isNull());
        var changed = assistant(s, List.of("region"));
        var tool = new LinkedHashMap<>((Map<String, Object>) changed.get("assistantTool"));
        tool.put("name", "新草稿");
        changed.put("assistantTool", tool);
        data(put("/onto/operations/" + op + "/assistant"), changed);
        assertEquals(before, data(get("/onto/candidates/" + id + "/contracts"), null));
    }

    @Test
    void guardErrorsKeepCodeFieldReasonAndNeverExecute() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("tenant_label")));
        String c = freeze(s).path("id").asText();
        var e = call(get("/onto/candidates/" + c + "/contracts"), ownerToken, null, 400);
        assertEquals("GUARD_ATTR_UNRESOLVED", e.path("code").asText());
        assertTrue(e.has("field"));
        assertTrue(e.has("reason"));
        call(post("/onto/candidates/" + c + "/trial"), ownerToken, Map.of("operationId", op), 400);
        var service =
                call(
                        post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        400);
        assertFalse(service.has("meta"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution_step WHERE kind='MODEL_SELECT'",
                        Integer.class));
        org.mockito.Mockito.verify(frozenExecutor, org.mockito.Mockito.never())
                .execute(
                        org.mockito.Mockito.anyMap(),
                        org.mockito.Mockito.anyMap(),
                        org.mockito.Mockito.anyMap());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE status='FAILED'", Integer.class));
    }

    @Test
    void ownerGrantManagementRejectsViewerStaleAndLastOwner() throws Exception {
        String s = createSpace();
        grant(s, viewer, "VIEWER");
        call(get("/onto/spaces/" + s + "/grants"), viewerToken, null, 403);
        assertEquals(3, data(get("/onto/spaces/" + s + "/grant-users"), null).size());
        call(
                put("/onto/spaces/" + s + "/grants/" + owner),
                ownerToken,
                Map.of("expectedDraftRev", rev(s), "role", "VIEWER", "attributes", Map.of()),
                409);
        data(
                put("/onto/spaces/" + s + "/grants/" + stranger),
                Map.of("expectedDraftRev", rev(s), "role", "EDITOR", "attributes", Map.of()));
        call(
                put("/onto/spaces/" + s + "/grants/" + stranger),
                ownerToken,
                Map.of("expectedDraftRev", 0, "role", "EDITOR", "attributes", Map.of()),
                409);
        assertEquals(3, data(get("/onto/spaces/" + s + "/grants"), null).size());
    }

    @Test
    void serviceChecksTrustedAttributesSchemasAndReturnsBusinessOnly() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        createClass(s, "对象", null);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        String c = freeze(s).path("id").asText();
        var ok =
                call(
                        post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        200);
        assertEquals(
                Set.of("columns", "rows"), json.convertValue(ok.path("data"), Map.class).keySet());
        assertTrue(ok.path("meta").path("preview").asBoolean());
        assertEquals(64, ok.path("meta").path("contentDigest").asText().length());
        call(
                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                ownerToken,
                Map.of("principal", Map.of("region", "华东")),
                400);
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of("expectedDraftRev", rev(s), "role", "OWNER", "attributes", Map.of()));
        call(
                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                ownerToken,
                Map.of(),
                403);
        call(
                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                strangerToken,
                Map.of(),
                403);
    }

    @Test
    void knowledgeSearchAndContextAreFrozenAndLuceneRetrievesTenQuestions() throws Exception {
        String s = createSpace(), cl = createClass(s, "设备", null);
        String[] words = {"设备", "任务", "人员", "组织", "项目", "合同", "订单", "仓库", "产品", "供应商"};
        for (String word : words)
            data(
                    post("/onto/knowledge"),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "term",
                            word,
                            "aliases",
                            List.of(word + "信息"),
                            "definition",
                            word + "领域说明",
                            "conceptRef",
                            cl,
                            "sourceRef",
                            "设计说明/" + word));
        String c = freeze(s).path("id").asText();
        var pack = data(get("/onto/candidates/" + c + "/context-pack"), null);
        assertEquals(10, pack.path("termDictionary").size());
        assertTrue(pack.path("termDictionary").get(0).has("id"));
        assertTrue(pack.path("termDictionary").get(0).has("sourceRef"));
        int hits = 0;
        List<Map<String, Object>> observations = new ArrayList<>();
        for (String word : words) {
            var result =
                    data(
                            get("/onto/candidates/" + c + "/knowledge-search")
                                    .param("q", "请查询" + word + "信息")
                                    .param("limit", "1"),
                            null);
            String actual =
                    result.path("hits").size() > 0
                            ? result.path("hits").get(0).path("term").asText()
                            : "";
            boolean hit = word.equals(actual);
            if (hit) hits++;
            observations.add(
                    Map.of(
                            "question",
                            "请查询" + word + "信息",
                            "expectedTerm",
                            word,
                            "actualTop1",
                            actual,
                            "hit",
                            hit));
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("../docs/evidence/continuation/c4-lucene-retrieval.json"),
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                Map.of(
                                        "denominator",
                                        10,
                                        "minimumHits",
                                        8,
                                        "hits",
                                        hits,
                                        "observations",
                                        observations)));
        assertTrue(hits >= 8, "Lucene top1命中=" + hits + "/10");
        var k = data(get("/onto/knowledge").param("spaceId", s), null).get(0);
        data(
                delete("/onto/knowledge/" + k.path("id").asText())
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
        assertEquals(pack, data(get("/onto/candidates/" + c + "/context-pack"), null));
    }

    @Test
    void legacyModelCandidateCannotEnterService() throws Exception {
        String s = createSpace(), op = createOperation(s, 20), c = freeze(s).path("id").asText();
        assertEquals(
                "SUCCEEDED",
                data(post("/onto/candidates/" + c + "/trial"), Map.of("operationId", op))
                        .path("status")
                        .asText());
        call(
                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                ownerToken,
                Map.of(),
                409);
        call(get("/onto/candidates/" + c + "/contracts"), ownerToken, null, 409);
    }

    @Test
    void nlSelectsWithoutExpectedOperationAndPersistsAmbiguity() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "different",
                        "name",
                        "其他操作",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        String other = "";
        for (var o : data(get("/onto/operations").param("spaceId", s), null))
            if ("different".equals(o.path("code").asText())) other = o.path("id").asText();
        var test =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "错误操作不能通过",
                                "question",
                                "列出对象模型",
                                "operationId",
                                other,
                                "input",
                                Map.of(),
                                "expected",
                                Map.of("status", "SUCCEEDED"),
                                "matchMode",
                                "CONTAINS",
                                "enabled",
                                true));
        test.put("mode", "NL");
        data(post("/onto/test-cases"), test);
        String c = legacyContractCandidate(s);
        var failed = data(post("/onto/candidates/" + c + "/tests"), null);
        assertFalse(failed.path("passed").asBoolean());
        assertEquals(
                "listClasses",
                db.queryForObject(
                        "SELECT op_code FROM op_execution ORDER BY created_at DESC LIMIT 1",
                        String.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution_step WHERE kind='TERM_MATCH' AND"
                                + " status='SUCCEEDED'",
                        Integer.class));
        data(put("/onto/operations/" + other + "/assistant"), assistant(s, List.of("region")));
        c = freeze(s).path("id").asText();
        failed = data(post("/onto/candidates/" + c + "/tests"), null);
        assertFalse(failed.path("passed").asBoolean());
        assertEquals(
                "NL_OPERATION_AMBIGUOUS",
                db.queryForObject(
                        "SELECT error_code FROM op_execution ORDER BY created_at DESC LIMIT 1",
                        String.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution_step WHERE kind='TERM_MATCH' AND"
                                + " status='FAILED'",
                        Integer.class));
    }

    @Test
    void frozenOutputSchemaFailureCommitsAndNeverInjectsMeta() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        guards(s);
        var declaration = assistant(s, List.of("region"));
        declaration.put("outputSchema", Map.of("type", "object", "required", List.of("absent")));
        data(put("/onto/operations/" + op + "/assistant"), declaration);
        String c = freeze(s).path("id").asText();
        var failed =
                call(
                        post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        400);
        assertEquals("SCHEMA_VALIDATION_FAILED", failed.path("code").asText());
        assertFalse(failed.has("meta"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE status='FAILED' AND"
                                + " error_code='SCHEMA_VALIDATION_FAILED'",
                        Integer.class));
    }

    @Test
    void filterBindingAndUndefinedAndMissingGuardsRefuseBeforeExecutorAcrossThreeEntries()
            throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            String s = createSpace(), op = createOperation(s, 20);
            if (kind == 1)
                data(
                        put("/onto/spaces/" + s + "/guards"),
                        Map.of(
                                "expectedDraftRev",
                                rev(s),
                                "guardCfg",
                                Map.of("tenant_label", Map.of("type", "STRING"))));
            data(
                    put("/onto/operations/" + op + "/assistant"),
                    assistant(s, kind == 2 ? List.of() : List.of("tenant_label")));
            data(
                    post("/onto/test-cases"),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "name",
                            "拒绝无绑定",
                            "question",
                            "本地直接调用",
                            "operationId",
                            op,
                            "input",
                            Map.of(),
                            "expected",
                            Map.of("status", "SUCCEEDED"),
                            "matchMode",
                            "CONTAINS",
                            "enabled",
                            true));
            String c = legacyContractCandidate(s);
            call(
                    post("/onto/candidates/" + c + "/trial"),
                    ownerToken,
                    Map.of("operationId", op),
                    400);
            assertFalse(
                    data(post("/onto/candidates/" + c + "/tests"), null)
                            .path("passed")
                            .asBoolean());
            call(
                    post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                    ownerToken,
                    Map.of(),
                    400);
        }
        assertEquals(
                9,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE status='FAILED'", Integer.class));
        org.mockito.Mockito.verify(frozenExecutor, org.mockito.Mockito.never())
                .execute(
                        org.mockito.Mockito.anyMap(),
                        org.mockito.Mockito.anyMap(),
                        org.mockito.Mockito.anyMap());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution_step WHERE kind='MODEL_SELECT'",
                        Integer.class));
    }

    /**
     * P2既有坏声明用例明确验证format2历史候选的拒绝事实兼容。 P4新冻结已在契约编译阶段FAILED/VOID，不能为了历史断言放松正式门禁；另插独立历史身份，绝不改原候选字节。
     */
    private String legacyContractCandidate(String space) throws Exception {
        var fresh = freeze(space);
        assertEquals("FAILED", fresh.path("status").asText());
        var original =
                candidateService.candidate(owner, UUID.fromString(fresh.path("id").asText()));
        var payload = candidateService.payload(original);
        payload.put("formatVersion", 2);
        byte[] bytes = codec.encode(payload);
        String sha = cn.ontorepo.space.application.AuthService.digest(bytes);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " publish_candidate(id,space_id,draft_rev,sha256,payload,format_version,capability,status)"
                    + " VALUES(?,?,?,?,?,2,'CONTRACT','FROZEN')",
                id,
                UUID.fromString(space),
                fresh.path("draftRev").asLong(),
                sha,
                bytes);
        assertArrayEquals(
                bytes,
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?", byte[].class, id));
        return id.toString();
    }

    @Test
    void knowledgeReferencesRevisionAndPermissionsAreAtomic() throws Exception {
        String s = createSpace(), other = createSpace(), foreign = createClass(other, "外空间", null);
        grant(s, viewer, "VIEWER");
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "term",
                                "术语",
                                "aliases",
                                List.of("别名", "别名"),
                                "definition",
                                "含义",
                                "sourceRef",
                                "来源"));
        call(post("/onto/knowledge"), viewerToken, body, 403);
        body.put("conceptRef", foreign);
        call(post("/onto/knowledge"), ownerToken, body, 400);
        assertEquals(0, rev(s));
        body.remove("conceptRef");
        body.put("sourceRef", "");
        call(post("/onto/knowledge"), ownerToken, body, 400);
        body.put("sourceRef", "来源");
        data(post("/onto/knowledge"), body);
        assertEquals(
                1,
                data(get("/onto/knowledge").param("spaceId", s), null)
                        .get(0)
                        .path("aliases")
                        .size());
        call(post("/onto/knowledge"), ownerToken, body, 409);
        String op = createOperation(s, 20);
        var declaration = assistant(s, List.of("region"));
        declaration.put("spaceId", other);
        declaration.put("expectedDraftRev", rev(other));
        call(put("/onto/operations/" + op + "/assistant"), ownerToken, declaration, 400);
        call(get("/onto/knowledge").param("spaceId", s), strangerToken, null, 403);
    }

    @Test
    void validFilterBindingsAreRefusedByModelExecutorAndScopeIsNotExpanded() throws Exception {
        String s = createSpace(), op = createOperation(s, 20);
        data(
                put("/onto/spaces/" + s + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "guardCfg",
                        Map.of(
                                "region",
                                Map.of(
                                        "type",
                                        "STRING",
                                        "filter_binding",
                                        Map.of(
                                                "table",
                                                "tasks",
                                                "column",
                                                "region",
                                                "operator",
                                                "IN")))));
        data(put("/onto/operations/" + op + "/assistant"), assistant(s, List.of("region")));
        String c = freeze(s).path("id").asText();
        assertEquals(
                "GUARD_BINDING_NOT_APPLICABLE",
                call(
                                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                                ownerToken,
                                Map.of(),
                                403)
                        .path("code")
                        .asText());
        guards(s);
        data(
                put("/onto/spaces/" + s + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "guardCfg",
                        Map.of(
                                "region",
                                Map.of(
                                        "type",
                                        "STRING",
                                        "authorization_rule",
                                        Map.of(
                                                "kind",
                                                "PRINCIPAL_ATTRIBUTE",
                                                "attribute",
                                                "region"),
                                        "filter_binding",
                                        Map.of(
                                                "table",
                                                "tasks",
                                                "column",
                                                "region",
                                                "operator",
                                                "IN")))));
        c = freeze(s).path("id").asText();
        assertEquals(
                "GUARD_BINDING_NOT_APPLICABLE",
                call(
                                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                                ownerToken,
                                Map.of(),
                                403)
                        .path("code")
                        .asText());
        guards(s);
        data(
                put("/onto/spaces/" + s + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of("region", "华东"),
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("华北"))));
        c = freeze(s).path("id").asText();
        assertEquals(
                "DATA_SCOPE_DENIED",
                call(
                                post("/onto/internal/candidates/" + c + "/ops/listClasses"),
                                ownerToken,
                                Map.of(),
                                403)
                        .path("code")
                        .asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution_step WHERE kind='MODEL_SELECT'",
                        Integer.class));
    }
}
