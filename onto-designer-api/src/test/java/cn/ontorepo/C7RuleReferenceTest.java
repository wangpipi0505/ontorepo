package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** C7：规则被操作引用时只读可见，非成员不可跨空间读取。 */
class C7RuleReferenceTest extends WorkflowFixture {
    @Test
    void ruleReferencesListBoundOperationsAndRequireSpaceAccess() throws Exception {
        String s = createSpace(), c = createClass(s, "任务", null);
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        s,
                        "name",
                        "必须有优先级",
                        "applicableClassId",
                        c,
                        "kind",
                        "VALIDATION",
                        "condition",
                        "",
                        "severity",
                        "ERROR",
                        "action",
                        Map.of("type", "REJECT", "message", "缺少优先级"),
                        "expectedDraftRev",
                        rev(s)));
        String ruleId = data(get("/onto/rules").param("spaceId", s), null).get(0).path("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "code",
                        "checkTask",
                        "name",
                        "检查任务",
                        "kind",
                        "RULE_CHECK",
                        "queryTemplate",
                        "",
                        "maxRows",
                        10,
                        "timeoutMs",
                        1000,
                        "ruleIds",
                        java.util.List.of(ruleId),
                        "expectedDraftRev",
                        rev(s)));
        var references = data(get("/onto/rules/" + ruleId + "/references"), null);
        assertEquals(1, references.size());
        assertEquals("checkTask", references.get(0).path("code").asText());
        call(get("/onto/rules/" + ruleId + "/references"), strangerToken, null, 403);
    }
}
