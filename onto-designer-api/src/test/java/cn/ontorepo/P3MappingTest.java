package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 真PG端到端覆盖关系JOIN、双表标签、冻结隔离、元数据与证据。 */
class P3MappingTest extends P3Fixture {
    @Test
    void nonPrimaryObjectKeysCannotHideDuplicatesBehindBusinessLimit() throws Exception {
        var x = scenario();
        String s = x.space();
        db.execute("UPDATE p3_source.tasks SET priority=7 WHERE task_id='T-CROSS'");
        var object = new LinkedHashMap<String, Object>();
        object.put("datasourceId", x.source());
        object.put("sourceResource", "p3_source.tasks");
        object.put("targetClassId", x.taskClass());
        object.put("objectKeyExpr", "task:${priority}");
        object.put("metaSnapshotId", x.snapshot());
        update("/mappings/objects/" + x.taskMap(), s, object);
        update(
                "/operations/" + x.operation(),
                s,
                Map.of(
                        "code",
                        "listClasses",
                        "name",
                        "单行对象查询",
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
                                List.of(x.taskProperty()),
                                "filters",
                                List.of())));
        String candidate = freeze(s).path("id").asText();
        var result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("FAILED", result.path("status").asText(), result.toString());
        assertEquals("OBJECT_KEY_DUPLICATE", result.path("errorCode").asText());
        db.execute(
                "INSERT INTO p3_source.tasks SELECT 'T-LIMIT-'||i,'ALG-A','A',i,true FROM"
                    + " generate_series(1,500) i");
        object.put("objectKeyExpr", "task:${task_id}");
        update("/mappings/objects/" + x.taskMap(), s, object);
        candidate = freeze(s).path("id").asText();
        result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals(1, result.path("rows").size());
        assertEquals(
                1,
                java.util.stream.StreamSupport.stream(result.path("evidence").spliterator(), false)
                        .filter(e -> e.path("kind").asText().equals("SOURCE_RECORD"))
                        .count());
        object.put("objectKeyExpr", "task:${priority}");
        update("/mappings/objects/" + x.taskMap(), s, object);
        candidate = freeze(s).path("id").asText();
        result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("OBJECT_KEY_CHECK_INCOMPLETE", result.path("errorCode").asText());
    }

    @Test
    void cardinalityValidationSeesConflictsBeforeBusinessLimitAndBoundsItsScan() throws Exception {
        var x = scenario();
        String s = x.space();
        db.execute("INSERT INTO p3_source.tasks VALUES('T-A2','ALG-A','A',8,true)");
        String relationId =
                data(get("/onto/mappings").param("spaceId", s), null)
                        .path("relations")
                        .get(0)
                        .path("relationId")
                        .asText();
        update(
                "/relations/" + relationId,
                s,
                Map.of(
                        "name",
                        "使用算法",
                        "fromClassId",
                        x.taskClass(),
                        "toClassId",
                        x.algorithmClass(),
                        "cardinality",
                        "ONE_TO_ONE",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        "全匹配集核验"));
        update(
                "/operations/" + x.operation(),
                s,
                Map.of(
                        "code",
                        "listClasses",
                        "name",
                        "列出对象",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        1,
                        "timeoutMs",
                        5000));
        String candidate = freeze(s).path("id").asText();
        var failed =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("CARDINALITY_VIOLATION", failed.path("errorCode").asText());
        update(
                "/relations/" + relationId,
                s,
                Map.of(
                        "name",
                        "使用算法",
                        "fromClassId",
                        x.taskClass(),
                        "toClassId",
                        x.algorithmClass(),
                        "cardinality",
                        "MANY_TO_ONE",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        "完整性预算"));
        candidate = freeze(s).path("id").asText();
        var bounded =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", bounded.path("status").asText(), bounded.toString());
        assertEquals(1, bounded.path("rows").size());
        assertEquals(
                2,
                java.util.stream.StreamSupport.stream(bounded.path("evidence").spliterator(), false)
                        .filter(e -> e.path("kind").asText().equals("SOURCE_RECORD"))
                        .count());
        db.execute(
                "INSERT INTO p3_source.tasks SELECT 'T-'||i,'ALG-A','A',i,true FROM"
                        + " generate_series(1,500) i");
        failed =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("CARDINALITY_CHECK_INCOMPLETE", failed.path("errorCode").asText());
    }

    @Test
    void propertyMappingCanUpdateItsOwnFieldButDuplicateCreateAlwaysConflicts() throws Exception {
        var x = scenario();
        String s = x.space();
        String id = null;
        for (var pm : data(get("/onto/mappings").param("spaceId", s), null).path("properties"))
            if (pm.path("targetPropertyId").asText().equals(x.taskProperty()))
                id = pm.path("id").asText();
        assertNotNull(id);
        var definition =
                Map.<String, Object>of(
                        "objectMappingId",
                        x.taskMap(),
                        "sourceField",
                        "algo_code",
                        "targetPropertyId",
                        x.taskProperty(),
                        "transform",
                        Map.of("kind", "IDENTITY"),
                        "required",
                        true);
        update("/mappings/properties/" + id, s, definition);
        var body = new LinkedHashMap<>(definition);
        body.put("spaceId", s);
        body.put("expectedDraftRev", rev(s));
        var duplicate = call(post("/onto/mappings/properties"), ownerToken, body, 409);
        assertEquals("PROPERTY_MAPPING_DUPLICATE", duplicate.path("code").asText());
        assertTrue(
                data(get("/onto/mappings/validate").param("spaceId", s), null)
                        .path("valid")
                        .asBoolean());
    }

    @Test
    void everyObjectMappingNeedsItsOwnRequiredPropertyBindings() throws Exception {
        var x = scenario();
        write(
                "/mappings/objects",
                x.space(),
                Map.of(
                        "datasourceId",
                        x.source(),
                        "sourceResource",
                        "p3_source.tasks",
                        "targetClassId",
                        x.taskClass(),
                        "objectKeyExpr",
                        "alternate:${task_id}",
                        "metaSnapshotId",
                        x.snapshot()));
        var check = data(get("/onto/mappings/validate").param("spaceId", x.space()), null);
        assertFalse(check.path("valid").asBoolean());
        for (var item : check.path("checks"))
            if (item.path("kind").asText().equals("REQUIRED_PROPERTY")) {
                assertFalse(item.path("passed").asBoolean());
                assertTrue(item.path("issues").size() >= 3);
            }
        assertTrue(check.path("coverage").path("pending").asInt() >= 3);
    }

    @Test
    void catalogLocatesOperationOnBothRelationEndsAndCapabilitiesExposeRealLimits()
            throws Exception {
        var x = scenario();
        var catalog = data(get("/onto/data-catalog").param("spaceId", x.space()), null);
        for (var binding : catalog.path("bindings"))
            assertTrue(
                    binding.path("operationIds").toString().contains(x.operation()),
                    binding.toString());
        var capabilities =
                data(get("/onto/datasources/capabilities").param("spaceId", x.space()), null);
        for (var capability : capabilities) {
            assertTrue(capability.path("limits").size() > 0);
            if (capability.path("kind").asText().equals("LAKE"))
                assertTrue(capability.path("capabilities").toString().contains("PARQUET"));
        }
    }

    @Test
    void identicalMetadataSyncHasNoAffectedMappings() throws Exception {
        var x = scenario();
        var sync = write("/datasources/" + x.source() + "/sync", x.space(), Map.of());
        assertEquals(0, sync.path("diff").path("added").size());
        assertEquals(0, sync.path("diff").path("removed").size());
        assertEquals(0, sync.path("diff").path("changed").size());
        assertEquals(0, sync.path("diff").path("affectedMappings").size());
    }

    @Test
    void mappedOntologyPropertyCannotBeDeletedAndOptionalPropertiesRemainInCoverage()
            throws Exception {
        var x = scenario();
        var denied =
                call(
                        delete("/onto/properties/" + x.numberProperty())
                                .param("spaceId", x.space())
                                .param("expectedDraftRev", Long.toString(rev(x.space()))),
                        ownerToken,
                        null,
                        409);
        assertEquals("PROPERTY_IN_USE", denied.path("code").asText());
        write(
                "/properties",
                x.space(),
                Map.of(
                        "domainClassId",
                        x.taskClass(),
                        "name",
                        "可选说明",
                        "rangeType",
                        "STRING",
                        "required",
                        false,
                        "description",
                        "尚未映射"));
        var validation = data(get("/onto/mappings/validate").param("spaceId", x.space()), null);
        assertEquals(1, validation.path("coverage").path("pending").asInt());
    }

    @Test
    void rawSourceQueryIsRejectedAtTypedManagementBoundary() throws Exception {
        String s = createSpace();
        var response =
                call(
                        post("/onto/datasources"),
                        ownerToken,
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "code",
                                "raw",
                                "name",
                                "不受信定义",
                                "kind",
                                "HTTP",
                                "connection",
                                Map.of(
                                        "dialect",
                                        "json",
                                        "baseUrl",
                                        "http://127.0.0.1:19180",
                                        "endpoints",
                                        List.of("/tasks")),
                                "accessScope",
                                List.of("/tasks"),
                                "src_query",
                                "SELECT secret FROM other_space"),
                        400);
        assertEquals("INVALID_ARGUMENT", response.path("code").asText());
    }

    @Test
    void duplicateObjectIdentityFromDifferentPrimaryKeysFailsInsteadOfSilentlyMerging()
            throws Exception {
        var x = scenario();
        db.execute("INSERT INTO p3_source.tasks VALUES('T-A2','ALG-A','A',7,true)");
        update(
                "/mappings/objects/" + x.taskMap(),
                x.space(),
                Map.of(
                        "datasourceId",
                        x.source(),
                        "sourceResource",
                        "p3_source.tasks",
                        "targetClassId",
                        x.taskClass(),
                        "objectKeyExpr",
                        "task:${algo_code}",
                        "metaSnapshotId",
                        x.snapshot()));
        update(
                "/operations/" + x.operation() + "/data-query",
                x.space(),
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "OBJECT",
                                "objectMappingId",
                                x.taskMap(),
                                "propertyIds",
                                List.of(x.numberProperty()),
                                "filters",
                                List.of())));
        var c = freeze(x.space()).path("id").asText();
        var result =
                data(
                        post("/onto/candidates/" + c + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("OBJECT_KEY_DUPLICATE", result.path("errorCode").asText());
    }

    @Test
    void relationQueryFiltersBothTablesAndPersistsRealTypedEvidenceAcrossEntrypoints()
            throws Exception {
        var x = scenario();
        String s = x.space(), candidate = freeze(s).path("id").asText();
        var validation = data(get("/onto/mappings/validate").param("spaceId", s), null);
        assertTrue(validation.path("valid").asBoolean(), validation.toString());
        assertEquals(4, validation.path("checks").size());
        var trial =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", trial.path("status").asText(), trial.toString());
        assertEquals(1, trial.path("rows").size());
        var row = trial.path("rows").get(0);
        assertEquals("T-A", row.path(x.taskProperty()).asText());
        assertEquals("ALG-A", row.path(x.algorithmProperty()).asText());
        assertTrue(row.path(x.numberProperty()).isIntegralNumber());
        assertTrue(row.path(x.boolProperty()).isBoolean());
        assertEquals("task:T-A", row.path("fromObjectId").asText());
        assertEquals("algorithm:ALG-A", row.path("toObjectId").asText());
        var evidence = trial.path("evidence");
        assertEquals(
                2,
                java.util.stream.StreamSupport.stream(evidence.spliterator(), false)
                        .filter(e -> e.path("kind").asText().equals("SOURCE_RECORD"))
                        .count());
        assertTrue(evidence.toString().contains("task_id"));
        assertTrue(evidence.toString().contains("std_code"));
        for (String kind : List.of("META_SNAPSHOT", "MAPPING_SNAPSHOT", "ONTOLOGY_VERSION"))
            assertTrue(evidence.toString().contains(kind));
        var service =
                call(
                        post("/onto/internal/candidates/" + candidate + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        200);
        assertEquals(trial.path("rows"), service.path("data").path("rows"));
        write(
                "/test-cases",
                s,
                Map.of(
                        "name",
                        "实例NL查询",
                        "question",
                        "任务使用算法查询",
                        "operationId",
                        x.operation(),
                        "input",
                        Map.of(),
                        "expected",
                        Map.of("rows", json.convertValue(trial.path("rows"), Object.class)),
                        "matchMode",
                        "EXACT",
                        "enabled",
                        true,
                        "mode",
                        "NL"));
        var tested = freeze(s).path("id").asText();
        var tests = data(post("/onto/candidates/" + tested + "/tests"), null);
        assertTrue(tests.path("passed").asBoolean(), tests.toString());
        long count =
                db.queryForObject(
                        "SELECT count(*) FROM evidence_ref WHERE execution_id=?",
                        Long.class,
                        UUID.fromString(trial.path("id").asText()));
        assertTrue(count >= 5);
    }

    @Test
    void missingJoinGuardRejectsBeforeAnySourceAccessAndKeepsFailureFact() throws Exception {
        var x = scenario();
        guards(x.space(), List.of("p3_source.tasks"));
        String candidate = freeze(x.space()).path("id").asText();
        db.execute("ALTER TABLE p3_source.algorithms RENAME TO unavailable_algorithms");
        var denied =
                call(
                        post("/onto/candidates/" + candidate + "/trial"),
                        ownerToken,
                        Map.of("operationId", x.operation(), "input", Map.of()),
                        400);
        assertEquals("GUARD_ATTR_UNRESOLVED", denied.path("code").asText());
        assertTrue(denied.path("reason").asText().startsWith("RESOURCE_BINDING_MISSING"));
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "SELECT status FROM op_execution WHERE subject_id=?",
                        String.class,
                        UUID.fromString(candidate)));
    }

    @Test
    void frozenSourceMappingAndMetadataDoNotFollowDraftUpdates() throws Exception {
        var x = scenario();
        String s = x.space();
        var frozen = freeze(s);
        String id = frozen.path("id").asText();
        byte[] bytes =
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(id));
        update(
                "/datasources/" + x.source(),
                s,
                Map.of(
                        "code",
                        "p3",
                        "name",
                        "失效新草稿",
                        "kind",
                        "RELATIONAL",
                        "connection",
                        Map.of(
                                "dialect",
                                "postgresql",
                                "url",
                                "jdbc:postgresql://127.0.0.1:1/unreachable",
                                "user",
                                "x",
                                "password",
                                "new-secret"),
                        "accessScope",
                        List.of("p3_source.tasks")));
        var body = new LinkedHashMap<String, Object>();
        body.put("datasourceId", x.source());
        body.put("sourceResource", "p3_source.tasks");
        body.put("targetClassId", x.taskClass());
        body.put("objectKeyExpr", "changed:${task_id}");
        body.put("metaSnapshotId", x.snapshot());
        update("/mappings/objects/" + x.taskMap(), s, body);
        var trial =
                data(
                        post("/onto/candidates/" + id + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", trial.path("status").asText(), trial.toString());
        assertEquals("task:T-A", trial.path("rows").get(0).path("objectId").asText());
        assertArrayEquals(
                bytes,
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(id)));
    }

    @Test
    void metadataDiffReportsChangedFieldsAndAffectedMappingsAndSourceDeleteIsBlocked()
            throws Exception {
        var x = scenario();
        db.execute("ALTER TABLE p3_source.tasks ADD COLUMN title text");
        var sync = write("/datasources/" + x.source() + "/sync", x.space(), Map.of());
        assertTrue(sync.path("diff").path("added").toString().contains("p3_source.tasks.title"));
        assertTrue(sync.path("diff").path("affectedMappings").toString().contains(x.taskMap()));
        var old =
                data(
                        get("/onto/datasources/" + x.source() + "/snapshots/" + x.snapshot())
                                .param("spaceId", x.space()),
                        null);
        assertFalse(old.path("resources").toString().contains("title"));
        var denied =
                call(
                        delete("/onto/datasources/" + x.source())
                                .param("spaceId", x.space())
                                .param("expectedDraftRev", Long.toString(rev(x.space()))),
                        ownerToken,
                        null,
                        409);
        assertTrue(denied.path("message").asText().contains(x.taskMap()));
        assertEquals(
                2,
                data(get("/onto/data-catalog").param("spaceId", x.space()), null)
                        .path("bindings")
                        .size());
    }

    @Test
    void unclassifiedAndCrossLabelRecordsNeverPassEvenUnrestrictedScope() throws Exception {
        var x = scenario();
        update(
                "/spaces/" + x.space() + "/grants/" + owner,
                x.space(),
                Map.of("role", "OWNER", "attributes", Map.of()));
        String id = freeze(x.space()).path("id").asText();
        var result =
                data(
                        post("/onto/candidates/" + id + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals(3, result.path("rows").size());
        assertFalse(result.path("rows").toString().contains("T-NULL"));
        update(
                "/spaces/" + x.space() + "/grants/" + owner,
                x.space(),
                Map.of(
                        "role",
                        "OWNER",
                        "attributes",
                        Map.of(),
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("B"))));
        result =
                data(
                        post("/onto/candidates/" + id + "/trial"),
                        Map.of("operationId", x.operation(), "input", Map.of()));
        assertEquals(1, result.path("rows").size());
        assertTrue(result.path("rows").toString().contains("T-B"));
    }
}
