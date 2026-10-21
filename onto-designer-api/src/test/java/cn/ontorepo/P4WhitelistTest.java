package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.mapping.application.DatasourceService;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;

/** 正常正例和显式拒绝断言共同验收；发布后仍须在解密/打开源之前拒绝超白名单操作。 */
class P4WhitelistTest extends P3Fixture {
    @MockitoSpyBean DatasourceService sources;

    @Test
    void publishedTokenAndExternalCallsCannotExceedFrozenSourceWhitelist() throws Exception {
        var x = scenario();
        String space = x.space();
        write(
                "/operations",
                space,
                Map.of(
                        "code",
                        "allowedAlgorithms",
                        "name",
                        "白名单内算法查询",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        20,
                        "timeoutMs",
                        1000));
        String good = "";
        for (var op : data(get("/onto/operations").param("spaceId", space), null))
            if ("allowedAlgorithms".equals(op.path("code").asText())) good = op.path("id").asText();
        assertFalse(good.isBlank());
        update(
                "/operations/" + good + "/data-query",
                space,
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "OBJECT",
                                "objectMappingId",
                                x.algorithmMap(),
                                "propertyIds",
                                List.of(x.algorithmProperty()),
                                "filters",
                                List.of())));
        assistant(space, good);
        var sourceUpdate =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "code",
                                "p3",
                                "name",
                                "仅算法资源获批",
                                "kind",
                                "RELATIONAL",
                                "accessScope",
                                List.of("p3_source.algorithms")));
        sourceUpdate.put("connection", null);
        update("/datasources/" + x.source(), space, sourceUpdate);
        write(
                "/test-cases",
                space,
                Map.of(
                        "name",
                        "白名单内真实成功",
                        "question",
                        "白名单内算法",
                        "operationId",
                        good,
                        "input",
                        Map.of(),
                        "expected",
                        Map.of(
                                "status",
                                "SUCCEEDED",
                                "rows",
                                List.of(Map.of("objectId", "algorithm:ALG-A"))),
                        "matchMode",
                        "CONTAINS",
                        "enabled",
                        true));
        write(
                "/test-cases",
                space,
                Map.of(
                        "name",
                        "白名单外明确拒绝",
                        "question",
                        "拒绝任务源",
                        "operationId",
                        x.operation(),
                        "input",
                        Map.of(),
                        "expected",
                        Map.of("status", "FAILED", "errorCode", "SCOPE_EXCEEDED"),
                        "matchMode",
                        "CONTAINS",
                        "enabled",
                        true));
        var candidate = freeze(space);
        assertEquals("VALIDATING", candidate.path("status").asText());
        String c = candidate.path("id").asText();
        var checks = data(post("/onto/candidates/" + c + "/validate"), null);
        for (var check : checks.path("checks"))
            if ("CONSISTENCY".equals(check.path("kind").asText())
                    && "SUBSET_EXCEEDED".equals(check.path("reason").asText()))
                data(
                        post("/onto/candidates/" + c + "/waivers"),
                        Map.of("kind", "SUBSET_EXCEEDED", "reason", "OWNER复核已冻结布尔属性的超EL公理"));
        var tested = data(post("/onto/candidates/" + c + "/tests"), null);
        assertTrue(tested.path("passed").asBoolean(), tested.toString());
        assertEquals(2, tested.path("results").size());
        var b =
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "candidateSha256",
                        candidate.path("sha256").asText(),
                        "reason",
                        "已核对成功与越白名单拒绝固定断言");
        data(post("/onto/candidates/" + c + "/review"), b);
        data(post("/onto/candidates/" + c + "/prepare"), b);
        var release =
                data(
                        post("/onto/candidates/" + c + "/commit"),
                        Map.of(
                                "expectedDraftRev",
                                rev(space),
                                "expectedSha256",
                                candidate.path("sha256").asText()));
        String id = release.path("id").asText(), endpoint = "";
        for (var e : data(get("/onto/releases/" + id + "/endpoints"), null))
            if ("listClasses".equals(e.path("opCode").asText())) endpoint = e.path("id").asText();
        assertFalse(endpoint.isBlank());
        data(put("/onto/endpoints/" + endpoint + "/grants/" + owner), Map.of("status", "ACTIVE"));
        var credential =
                data(
                        post("/onto/api-credentials"),
                        Map.of(
                                "spaceId",
                                space,
                                "principalId",
                                owner.toString(),
                                "name",
                                "白名单拒绝测试"));
        String code = data(get("/onto/spaces/" + space), null).path("code").asText();
        // 之前的正例真实访问PG；本边界清零后两种正式身份都必须零解密、零源连接/查询。
        clearInvocations(sources);
        var token =
                call(
                        post("/onto/internal/releases/" + id + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        403);
        assertEquals("SCOPE_EXCEEDED", token.path("code").asText());
        assertFalse(token.has("meta"));
        var external =
                mvc.perform(
                                post("/onto/r/" + code + "/releases/" + id + "/ops/listClasses")
                                        .header("X-API-Key", credential.path("key").asText())
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn()
                        .getResponse();
        assertEquals(403, external.getStatus());
        assertNull(external.getHeader("X-Ontology-Release"));
        var body = json.readTree(external.getContentAsString());
        assertEquals("SCOPE_EXCEEDED", body.path("code").asText());
        assertFalse(body.has("meta"));
        verify(sources, never()).source(anyMap());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE subject_type='RELEASE' AND"
                                + " subject_id=? AND caller IN('SERVICE','EXTERNAL') AND"
                                + " status='FAILED' AND error_code='SCOPE_EXCEEDED'",
                        Integer.class,
                        UUID.fromString(id)));
    }
}
