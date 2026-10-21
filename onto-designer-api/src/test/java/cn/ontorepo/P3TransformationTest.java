package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 转换过滤必须使用冻结码表/类型，在标签授权之后、业务LIMIT之前完成。 */
class P3TransformationTest extends P3Fixture {
    @Test
    void integerGuardScopesRemainIntegralAfterNumericScopeNormalization() throws Exception {
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
                "/spaces/" + s + "/guards",
                s,
                Map.of(
                        "guardCfg",
                        Map.of(
                                "priority",
                                Map.of(
                                        "type",
                                        "INTEGER",
                                        "filter_binding",
                                        Map.of(
                                                "table",
                                                "p3_source.tasks",
                                                "column",
                                                "priority",
                                                "operator",
                                                "IN")))));
        update(
                "/spaces/" + s + "/grants/" + owner,
                s,
                Map.of(
                        "role",
                        "OWNER",
                        "dataScope",
                        Map.of("attr", "priority", "in", List.of(7)),
                        "attributes",
                        Map.of()));
        var declaration =
                json.convertValue(
                        data(
                                get("/onto/operations/" + x.operation() + "/assistant")
                                        .param("spaceId", s),
                                null),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                LinkedHashMap<String, Object>>() {});
        declaration.remove("operationId");
        var tool = new LinkedHashMap<>((Map<String, Object>) declaration.get("assistantTool"));
        tool.put("guard_attrs", List.of("priority"));
        declaration.put("assistantTool", tool);
        update("/operations/" + x.operation() + "/assistant", s, declaration);
        String candidate = freeze(s).path("id").asText();
        var result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals(1, result.path("rows").size());
        assertTrue(result.path("rows").toString().contains("T-A"));
    }

    @Test
    void sqlFiltersUseNumericBooleanAndFrozenCodebookTransformsBeforeLimit() throws Exception {
        var x = scenario();
        String s = x.space();
        db.execute(
                "ALTER TABLE p3_source.tasks ADD COLUMN raw_priority text,ADD COLUMN raw_active"
                        + " text,ADD COLUMN raw_code text");
        db.execute(
                "UPDATE p3_source.tasks SET raw_priority=CASE WHEN task_id='T-A' THEN '02' ELSE"
                        + " '01' END,raw_active=CASE WHEN task_id='T-A' THEN 'TRUE' ELSE 'false'"
                        + " END,raw_code=CASE WHEN task_id='T-A' THEN 'low' ELSE 'high' END");
        var snapshot =
                write("/datasources/" + x.source() + "/sync", s, Map.of())
                        .path("snapshot")
                        .path("id")
                        .asText();
        update(
                "/mappings/objects/" + x.taskMap(),
                s,
                Map.of(
                        "datasourceId",
                        x.source(),
                        "sourceResource",
                        "p3_source.tasks",
                        "targetClassId",
                        x.taskClass(),
                        "objectKeyExpr",
                        "task:${task_id}",
                        "metaSnapshotId",
                        snapshot));
        write(
                "/codebooks",
                s,
                Map.of(
                        "code",
                        "buckets",
                        "name",
                        "冻结分组",
                        "kind",
                        "VALUE",
                        "entries",
                        List.of(
                                Map.of("code", "low", "label", "LOW"),
                                Map.of("code", "high", "label", "HIGH"))));
        String codebook =
                data(get("/onto/codebooks").param("spaceId", s), null).get(0).path("id").asText();
        String bucket = property(s, x.taskClass(), "分组", "STRING");
        for (var pm : data(get("/onto/mappings").param("spaceId", s), null).path("properties")) {
            String property = pm.path("targetPropertyId").asText();
            if (property.equals(x.numberProperty()) || property.equals(x.boolProperty()))
                update(
                        "/mappings/properties/" + pm.path("id").asText(),
                        s,
                        Map.of(
                                "objectMappingId",
                                x.taskMap(),
                                "sourceField",
                                property.equals(x.numberProperty()) ? "raw_priority" : "raw_active",
                                "targetPropertyId",
                                property,
                                "required",
                                true,
                                "transform",
                                Map.of(
                                        "kind",
                                        property.equals(x.numberProperty())
                                                ? "TO_NUMBER"
                                                : "TO_BOOLEAN")));
        }
        write(
                "/mappings/properties",
                s,
                Map.of(
                        "objectMappingId",
                        x.taskMap(),
                        "sourceField",
                        "raw_code",
                        "targetPropertyId",
                        bucket,
                        "required",
                        true,
                        "transform",
                        Map.of("kind", "CODEBOOK", "codebookId", codebook)));
        update(
                "/operations/" + x.operation(),
                s,
                Map.of(
                        "code",
                        "listClasses",
                        "name",
                        "转换查询",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        1,
                        "timeoutMs",
                        5000));
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
                                List.of(x.numberProperty(), x.boolProperty(), bucket),
                                "filters",
                                List.of(
                                        Map.of(
                                                "propertyId",
                                                x.numberProperty(),
                                                "operator",
                                                "GE",
                                                "inputParam",
                                                "threshold"),
                                        Map.of(
                                                "propertyId",
                                                x.boolProperty(),
                                                "operator",
                                                "EQ",
                                                "inputParam",
                                                "enabled"),
                                        Map.of(
                                                "propertyId",
                                                bucket,
                                                "operator",
                                                "EQ",
                                                "inputParam",
                                                "bucket")))));
        Map<String, Object> input = Map.of("threshold", 2, "enabled", true, "bucket", "LOW");
        var assistant =
                data(
                        get("/onto/operations/" + x.operation() + "/assistant").param("spaceId", s),
                        null);
        var declaration =
                json.convertValue(
                        assistant,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                LinkedHashMap<String, Object>>() {});
        declaration.remove("operationId");
        declaration.put(
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("threshold", "enabled", "bucket"),
                        "properties",
                        Map.of(
                                "threshold",
                                Map.of("type", "integer"),
                                "enabled",
                                Map.of("type", "boolean"),
                                "bucket",
                                Map.of("type", "string")),
                        "additionalProperties",
                        false));
        var tool = new LinkedHashMap<>((Map<String, Object>) declaration.get("assistantTool"));
        tool.put("param_examples", List.of(input));
        declaration.put("assistantTool", tool);
        update("/operations/" + x.operation() + "/assistant", s, declaration);
        String candidate = freeze(s).path("id").asText();
        var deleteCodebook =
                call(
                        delete("/onto/codebooks/" + codebook)
                                .param("spaceId", s)
                                .param("expectedDraftRev", Long.toString(rev(s))),
                        ownerToken,
                        null,
                        409);
        assertEquals("CODEBOOK_IN_USE", deleteCodebook.path("code").asText());
        var result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", input));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals(1, result.path("rows").size());
        var row = result.path("rows").get(0);
        assertEquals(2, row.path(x.numberProperty()).asInt());
        assertTrue(row.path(x.boolProperty()).asBoolean());
        assertEquals("LOW", row.path(bucket).asText());
        update(
                "/codebooks/" + codebook,
                s,
                Map.of(
                        "code",
                        "buckets",
                        "name",
                        "新草稿分组",
                        "kind",
                        "VALUE",
                        "entries",
                        List.of(
                                Map.of("code", "low", "label", "CHANGED"),
                                Map.of("code", "high", "label", "HIGH"))));
        result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", input));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals("LOW", result.path("rows").get(0).path(bucket).asText());
        db.execute("UPDATE p3_source.tasks SET raw_priority='invalid' WHERE task_id='T-A'");
        result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", input));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("TRANSFORM_FAILED", result.path("errorCode").asText());
        assertEquals(0, result.path("rows").size());
    }

    @Test
    void jdbcDateIdentityUsesIsoDateJsonWithoutWeakeningOntologyType() throws Exception {
        var x = scenario();
        String s = x.space();
        db.execute("ALTER TABLE p3_source.tasks ADD COLUMN scheduled_on date");
        db.execute("UPDATE p3_source.tasks SET scheduled_on=DATE '2026-10-21'");
        String snapshot =
                write("/datasources/" + x.source() + "/sync", s, Map.of())
                        .path("snapshot")
                        .path("id")
                        .asText();
        update(
                "/mappings/objects/" + x.taskMap(),
                s,
                Map.of(
                        "datasourceId",
                        x.source(),
                        "sourceResource",
                        "p3_source.tasks",
                        "targetClassId",
                        x.taskClass(),
                        "objectKeyExpr",
                        "task:${task_id}",
                        "metaSnapshotId",
                        snapshot));
        String scheduled = property(s, x.taskClass(), "计划日期", "DATE");
        write(
                "/mappings/properties",
                s,
                Map.of(
                        "objectMappingId",
                        x.taskMap(),
                        "sourceField",
                        "scheduled_on",
                        "targetPropertyId",
                        scheduled,
                        "required",
                        true,
                        "transform",
                        Map.of("kind", "IDENTITY")));
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
                                List.of(scheduled),
                                "filters",
                                List.of())));
        String candidate = freeze(s).path("id").asText();
        var result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertFalse(result.path("rows").isEmpty());
        assertTrue(result.path("rows").get(0).path(scheduled).isTextual());
        assertEquals("2026-10-21", result.path("rows").get(0).path(scheduled).asText());
    }

    @Test
    void toEndProjectionAndFilterDependenciesBlockDestructiveMappingRemoval() throws Exception {
        var x = scenario();
        String target = null;
        for (var p :
                data(get("/onto/mappings").param("spaceId", x.space()), null).path("properties"))
            if (p.path("targetPropertyId").asText().equals(x.algorithmProperty()))
                target = p.path("id").asText();
        assertNotNull(target);
        var impact =
                data(
                        get("/onto/mappings/impact")
                                .param("spaceId", x.space())
                                .param("kind", "properties")
                                .param("id", target),
                        null);
        assertTrue(impact.path("operations").toString().contains(x.operation()));
        call(
                delete("/onto/mappings/properties/" + target)
                        .param("spaceId", x.space())
                        .param("expectedDraftRev", Long.toString(rev(x.space()))),
                ownerToken,
                null,
                409);
        update(
                "/operations/" + x.operation() + "/data-query",
                x.space(),
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "RELATION",
                                "objectMappingId",
                                x.taskMap(),
                                "relationMappingId",
                                x.relationMap(),
                                "propertyIds",
                                List.of(x.taskProperty()),
                                "filters",
                                List.of(
                                        Map.of(
                                                "propertyId",
                                                x.algorithmProperty(),
                                                "operator",
                                                "EQ",
                                                "inputParam",
                                                "algorithm")))));
        impact =
                data(
                        get("/onto/mappings/impact")
                                .param("spaceId", x.space())
                                .param("kind", "properties")
                                .param("id", target),
                        null);
        assertTrue(impact.path("operations").toString().contains(x.operation()));
        call(
                delete("/onto/mappings/properties/" + target)
                        .param("spaceId", x.space())
                        .param("expectedDraftRev", Long.toString(rev(x.space()))),
                ownerToken,
                null,
                409);
    }
}
