package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 新候选严格编译就绪屏障与历史format2拒绝事实兼容分别验证，不能为旧用例推进失败新候选。 */
class P4CompilationTest extends P4Fixture {
    @ParameterizedTest
    @ValueSource(strings = {"guard", "mixed"})
    void invalidFrozenDeclarationVoidsAllocationAndNeverStartsLocalTest(String fault)
            throws Exception {
        String space = createSpace(), operation = createOperation(space, 20);
        configure(space, operation);
        if (fault.equals("guard")) {
            var declaration =
                    new LinkedHashMap<String, Object>(
                            json.convertValue(
                                    data(
                                            get("/onto/operations/" + operation + "/assistant")
                                                    .param("spaceId", space),
                                            null),
                                    Map.class));
            var tool =
                    new LinkedHashMap<String, Object>(
                            (Map<String, Object>) declaration.get("assistantTool"));
            tool.put("guard_attrs", List.of("undefined_guard"));
            declaration.put("assistantTool", tool);
            declaration.put("spaceId", space);
            declaration.put("expectedDraftRev", rev(space));
            data(put("/onto/operations/" + operation + "/assistant"), declaration);
        } else {
            data(
                    post("/onto/operations"),
                    Map.of(
                            "spaceId",
                            space,
                            "expectedDraftRev",
                            rev(space),
                            "code",
                            "undeclared",
                            "name",
                            "缺助手声明的操作",
                            "queryTemplate",
                            QUERY,
                            "maxRows",
                            20,
                            "timeoutMs",
                            1000));
        }
        var failed = freeze(space);
        String id = failed.path("id").asText();
        UUID candidate = UUID.fromString(id);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals(
                "VOID",
                db.queryForObject(
                        "SELECT allocation_status FROM publish_candidate WHERE id=?",
                        String.class,
                        candidate));
        assertNotNull(
                db.queryForObject(
                        "SELECT failure_code FROM publish_candidate WHERE id=?",
                        String.class,
                        candidate));
        // bundle已真实归档，失败来自冻结契约编译；仍不能建立任何TEST执行或晋级发布。
        assertEquals(
                "BUNDLE",
                data(get("/onto/candidates/" + id + "/publication"), null)
                        .path("artifacts")
                        .get(0)
                        .path("kind")
                        .asText());
        call(post("/onto/candidates/" + id + "/tests"), ownerToken, null, 409);
        call(post("/onto/candidates/" + id + "/prepare"), ownerToken, bound(space, failed), 409);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM test_run", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE caller='LOCAL_TEST'",
                        Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ontology_release", Integer.class));
        assertEquals(
                "FAILED",
                data(get("/onto/candidates/" + id + "/publication"), null).path("status").asText());
        assertTrue(
                freeze(space).path("preVersionNo").asLong() > failed.path("preVersionNo").asLong());
    }
}
