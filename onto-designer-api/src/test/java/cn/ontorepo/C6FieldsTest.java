package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** C6：操作业务说明/执行条件与属性显示名称在真实 PG 中持久化。 */
class C6FieldsTest extends WorkflowFixture {
    @Test
    void operationAndPropertyBusinessFieldsPersist() throws Exception {
        String s = createSpace();
        String c = createClass(s, "任务", null);
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        s,
                        "domainClassId",
                        c,
                        "name",
                        "priority",
                        "displayName",
                        "优先级",
                        "rangeType",
                        "INTEGER",
                        "required",
                        false,
                        "description",
                        "",
                        "facets",
                        Map.of(),
                        "expectedDraftRev",
                        rev(s)));
        var properties =
                data(get("/onto/properties").param("spaceId", s).param("classId", c), null);
        assertEquals(1, properties.size());
        assertEquals("优先级", properties.get(0).path("displayName").asText());

        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "code",
                        "listClasses",
                        "name",
                        "列出对象",
                        "description",
                        "列出当前模型的全部对象",
                        "precondition",
                        "调用者需具备 model.read",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        100,
                        "timeoutMs",
                        1000,
                        "expectedDraftRev",
                        rev(s)));
        var operations = data(get("/onto/operations").param("spaceId", s), null);
        assertEquals(1, operations.size());
        assertEquals("列出当前模型的全部对象", operations.get(0).path("description").asText());
        assertEquals("调用者需具备 model.read", operations.get(0).path("precondition").asText());
        assertTrue(operations.get(0).path("ruleIds").isArray());
        assertEquals(0, operations.get(0).path("ruleIds").size());
    }
}
