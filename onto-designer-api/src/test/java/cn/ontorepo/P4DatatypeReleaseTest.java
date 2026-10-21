package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 实际编辑基础数据属性，经真实两引擎、正数用例、人工复核、四制品与commit完整发布。 */
class P4DatatypeReleaseTest extends P4Fixture {
    @ParameterizedTest
    @ValueSource(strings = {"STRING", "INTEGER", "DECIMAL"})
    void minimalDatatypePublishesOnlyAfterCompleteFallback(String type) throws Exception {
        String space = createSpace(),
                cl = createClass(space, "数据对象", null),
                op = createOperation(space, 20);
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "domainClassId",
                        cl,
                        "name",
                        "类型属性",
                        "rangeType",
                        type,
                        "required",
                        true,
                        "description",
                        "完整保留域值域与SHACL约束"));
        configure(space, op);
        var old = data(get("/onto/test-cases").param("spaceId", space), null).get(0);
        var test = new LinkedHashMap<String, Object>(json.convertValue(old, Map.class));
        test.put("spaceId", space);
        test.put("expectedDraftRev", rev(space));
        test.put("expected", Map.of("status", "SUCCEEDED"));
        test.put("matchMode", "CONTAINS");
        data(put("/onto/test-cases/" + old.path("id").asText()), test);
        var c = freeze(space);
        String id = c.path("id").asText();
        var validated = data(post("/onto/candidates/" + id + "/validate"), null);
        boolean found = false;
        for (var check : validated.path("checks"))
            if ("CONSISTENCY".equals(check.path("kind").asText())) {
                found = true;
                assertEquals("PASSED", check.path("conclusion").asText(), check.toString());
                assertEquals("CONSISTENT", check.path("report").path("conclusion").asText());
                assertEquals(
                        "ELK→HermiT", check.path("report").path("details").path("engine").asText());
                assertTrue(check.path("report").path("details").path("incomplete").asBoolean());
            }
        assertTrue(found);
        assertTrue(
                data(post("/onto/candidates/" + id + "/tests"), null).path("passed").asBoolean());
        var b = bound(space, c);
        data(post("/onto/candidates/" + id + "/review"), b);
        assertEquals(
                "READY_TO_COMMIT",
                data(post("/onto/candidates/" + id + "/prepare"), b).path("status").asText());
        var release = publish(new Ready(space, op, c));
        assertEquals("READY", release.path("status").asText());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM gate_waiver", Integer.class));
    }
}
