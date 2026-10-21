package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** P4真实HTTP/PG/MinIO主线共享夹具，期望仍由用例固定声明。 */
abstract class P4Fixture extends WorkflowFixture {
    record Ready(String space, String operation, JsonNode candidate) {}

    Ready ready() throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        var c = freeze(space);
        String id = c.path("id").asText();
        data(post("/onto/candidates/" + id + "/validate"), null);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var b = bound(space, c);
        data(post("/onto/candidates/" + id + "/review"), b);
        data(post("/onto/candidates/" + id + "/prepare"), b);
        return new Ready(space, operation, c);
    }

    Map<String, Object> bound(String space, JsonNode c) throws Exception {
        return Map.of(
                "expectedDraftRev",
                rev(space),
                "candidateSha256",
                c.path("sha256").asText(),
                "reason",
                "已人工核对冻结差异与所有门禁");
    }

    Map<String, Object> commitBody(Ready r) throws Exception {
        return Map.of(
                "expectedDraftRev",
                rev(r.space()),
                "expectedSha256",
                r.candidate().path("sha256").asText());
    }

    JsonNode publish(Ready r) throws Exception {
        return data(
                post("/onto/candidates/" + r.candidate().path("id").asText() + "/commit"),
                commitBody(r));
    }

    void configure(String space, String operation) throws Exception {
        data(
                put("/onto/spaces/" + space + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(space),
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
                put("/onto/spaces/" + space + "/grants/" + owner),
                Map.of(
                        "expectedDraftRev",
                        rev(space),
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of("region", "A")));
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", "列出对象");
        tool.put("description", "列出对象模型");
        tool.put("guard_attrs", List.of("region"));
        tool.put("info_attrs", List.of());
        tool.put("param_examples", List.of(Map.of()));
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
                        Map.of("mode", "TEMPLATE", "template", "查询结果"),
                        "structuredPath",
                        "$.data",
                        "fileRefPaths",
                        List.of(),
                        "cardTemplate",
                        "查询结果"));
        data(
                put("/onto/operations/" + operation + "/assistant"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "inputSchema",
                        Map.of("type", "object"),
                        "outputSchema",
                        Map.of("type", "object"),
                        "assistantTool",
                        tool));
        data(
                post("/onto/test-cases"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "name",
                        "空模型确定性断言",
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
}
