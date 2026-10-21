package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 正式端点复用P3真实PG映射执行，四层收窄不能由OWNER或宽凭据覆盖。 */
class P4ScopeTest extends P3Fixture {
    @Test
    void endpointScopeNarrowsFrozenPolicyAndSpaceWhileInternalEntryUsesSpaceScope()
            throws Exception {
        var x = scenario();
        String s = x.space();
        update(
                "/operations/" + x.operation() + "/data-query",
                s,
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "OBJECT",
                                "objectMappingId",
                                x.taskMap(),
                                "propertyIds",
                                List.of(x.taskProperty()),
                                "filters",
                                List.of())));
        update(
                "/spaces/" + s + "/grants/" + owner,
                s,
                Map.of(
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(),
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("A", "B"))));
        write(
                "/strategies",
                s,
                Map.of(
                        "name",
                        "冻结AB范围",
                        "boundOps",
                        List.of(x.operation()),
                        "policy",
                        Map.of(
                                "maxRows",
                                20,
                                "resultLimit",
                                20,
                                "timeoutMs",
                                5000,
                                "failureMode",
                                "FAIL",
                                "readScope",
                                Map.of("attr", "region", "in", List.of("A", "B")))));
        // 此例显式采用包含匹配，固定断言已知T-A；下方另以精确行数与身份集合检验每层收窄。
        write(
                "/test-cases",
                s,
                Map.of(
                        "name",
                        "固定源记录验收",
                        "question",
                        "任务",
                        "operationId",
                        x.operation(),
                        "input",
                        Map.of(),
                        "expected",
                        Map.of("rows", List.of(Map.of("objectId", "task:T-A"))),
                        "matchMode",
                        "CONTAINS",
                        "enabled",
                        true));
        var c = freeze(s);
        String candidate = c.path("id").asText();
        var trial =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals(3, trial.path("rows").size());
        assertFalse(trial.path("rows").toString().contains("T-NULL"));
        var validated = data(post("/onto/candidates/" + candidate + "/validate"), null);
        for (var check : validated.path("checks"))
            if ("CONSISTENCY".equals(check.path("kind").asText())
                    && "SUBSET_EXCEEDED".equals(check.path("reason").asText()))
                data(
                        post("/onto/candidates/" + candidate + "/waivers"),
                        Map.of("kind", "SUBSET_EXCEEDED", "reason", "OWNER已人工复核该冻结布尔属性的超EL公理"));
        assertTrue(
                data(post("/onto/candidates/" + candidate + "/tests"), null)
                        .path("passed")
                        .asBoolean());
        var b =
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "candidateSha256",
                        c.path("sha256").asText(),
                        "reason",
                        "已核对真实AB映射范围");
        data(post("/onto/candidates/" + candidate + "/review"), b);
        data(post("/onto/candidates/" + candidate + "/prepare"), b);
        var release =
                data(
                        post("/onto/candidates/" + candidate + "/commit"),
                        Map.of(
                                "expectedDraftRev",
                                rev(s),
                                "expectedSha256",
                                c.path("sha256").asText()));
        String id = release.path("id").asText();
        String endpoint =
                data(get("/onto/releases/" + id + "/endpoints"), null).get(0).path("id").asText();
        data(
                put("/onto/endpoints/" + endpoint + "/grants/" + owner),
                Map.of(
                        "status",
                        "ACTIVE",
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("A"))));
        var a =
                call(
                        post("/onto/internal/releases/" + id + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        200);
        assertEquals(2, a.path("data").path("rows").size());
        assertFalse(a.path("data").path("rows").toString().contains("T-B"));
        var unrestricted = new LinkedHashMap<String, Object>();
        unrestricted.put("status", "ACTIVE");
        unrestricted.put("dataScope", null);
        data(put("/onto/endpoints/" + endpoint + "/grants/" + owner), unrestricted);
        assertEquals(
                3,
                call(
                                post("/onto/internal/releases/" + id + "/ops/listClasses"),
                                ownerToken,
                                Map.of(),
                                200)
                        .path("data")
                        .path("rows")
                        .size());
        update(
                "/spaces/" + s + "/grants/" + owner,
                s,
                Map.of(
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(),
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("B"))));
        var narrowed =
                call(
                        post("/onto/internal/releases/" + id + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        200);
        assertEquals(1, narrowed.path("data").path("rows").size());
        assertEquals(
                "task:T-B", narrowed.path("data").path("rows").get(0).path("objectId").asText());
        assertEquals(
                1,
                data(
                                post("/onto/candidates/" + candidate + "/trial"),
                                Map.of("operationId", x.operation(), "input", Map.of()))
                        .path("rows")
                        .size());
        String execution =
                db.queryForObject(
                        "SELECT id::text FROM op_execution WHERE subject_type='RELEASE' AND"
                                + " status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
                        String.class);
        var trace = data(get("/onto/executions/" + execution + "/trace"), null);
        var kinds = new HashSet<String>();
        for (var evidence : trace.path("evidence")) kinds.add(evidence.path("type").asText());
        assertTrue(
                kinds.containsAll(
                        Set.of(
                                "SOURCE_RECORD",
                                "META_SNAPSHOT",
                                "MAPPING_SNAPSHOT",
                                "ONTOLOGY_VERSION",
                                "AUTHORIZATION_CONTEXT")));
    }
}
