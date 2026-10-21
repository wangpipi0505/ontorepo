package cn.ontorepo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试源与业务权威使用专属P3库的不同schema；不触及人工验收样例。 */
abstract class P3Fixture extends WorkflowFixture {
    record Scenario(
            String space,
            String source,
            String snapshot,
            String taskClass,
            String algorithmClass,
            String taskMap,
            String algorithmMap,
            String relationMap,
            String taskProperty,
            String algorithmProperty,
            String numberProperty,
            String boolProperty,
            String operation) {}

    JsonNode write(String path, String s, Map<String, Object> b) throws Exception {
        var body = new LinkedHashMap<>(b);
        body.put("spaceId", s);
        body.put("expectedDraftRev", rev(s));
        return data(post("/onto" + path), body);
    }

    JsonNode update(String path, String s, Map<String, Object> b) throws Exception {
        var body = new LinkedHashMap<>(b);
        body.put("spaceId", s);
        body.put("expectedDraftRev", rev(s));
        return data(put("/onto" + path), body);
    }

    String property(String s, String cl, String name, String type) throws Exception {
        write(
                "/properties",
                s,
                Map.of(
                        "domainClassId",
                        cl,
                        "name",
                        name,
                        "rangeType",
                        type,
                        "required",
                        true,
                        "description",
                        name));
        for (var p : data(get("/onto/spaces/" + s + "/properties"), null))
            if (p.path("name").asText().equals(name)) return p.path("id").asText();
        throw new AssertionError();
    }

    void assistant(String s, String op) throws Exception {
        update(
                "/operations/" + op + "/assistant",
                s,
                Map.of(
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(),
                                "additionalProperties",
                                false),
                        "outputSchema",
                        Map.of(
                                "type",
                                "object",
                                "required",
                                List.of("columns", "rows"),
                                "properties",
                                Map.of(
                                        "rows",
                                        Map.of("type", "array", "items", Map.of("type", "object")),
                                        "columns",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "string")))),
                        "assistantTool",
                        Map.of(
                                "name",
                                "任务算法",
                                "description",
                                "任务使用算法查询",
                                "guard_attrs",
                                List.of("region"),
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

    void guards(String s, List<String> resources) throws Exception {
        update(
                "/spaces/" + s + "/guards",
                s,
                Map.of(
                        "guardCfg",
                        Map.of(
                                "region",
                                Map.of(
                                        "type",
                                        "STRING",
                                        "filter_bindings",
                                        resources.stream()
                                                .map(
                                                        r ->
                                                                Map.of(
                                                                        "table",
                                                                        r,
                                                                        "column",
                                                                        "region",
                                                                        "operator",
                                                                        "IN"))
                                                .toList()))));
    }

    Scenario scenario() throws Exception {
        db.execute("DROP SCHEMA IF EXISTS p3_source CASCADE");
        db.execute("CREATE SCHEMA p3_source");
        db.execute("CREATE TABLE p3_source.algorithms(std_code text PRIMARY KEY,region text)");
        db.execute(
                "CREATE TABLE p3_source.tasks(task_id text PRIMARY KEY,algo_code text REFERENCES"
                        + " p3_source.algorithms(std_code),region text,priority integer,active"
                        + " boolean)");
        db.execute(
                "INSERT INTO p3_source.algorithms"
                        + " VALUES('ALG-A','A'),('ALG-B','B'),('ALG-NULL',null)");
        db.execute(
                "INSERT INTO p3_source.tasks"
                    + " VALUES('T-A','ALG-A','A',7,true),('T-B','ALG-B','B',8,false),('T-NULL','ALG-NULL',null,9,true),('T-CROSS','ALG-B','A',10,true)");
        String s = createSpace(),
                task = createClass(s, "任务", null),
                algorithm = createClass(s, "算法", null);
        String taskId = property(s, task, "任务编号", "STRING"),
                std = property(s, algorithm, "标准码", "STRING"),
                num = property(s, task, "优先级", "INTEGER"),
                bool = property(s, task, "启用", "BOOLEAN");
        write(
                "/relations",
                s,
                Map.of(
                        "name",
                        "使用算法",
                        "fromClassId",
                        task,
                        "toClassId",
                        algorithm,
                        "cardinality",
                        "MANY_TO_ONE",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        "真实关联"));
        String relation =
                data(get("/onto/relations").param("spaceId", s), null).get(0).path("id").asText();
        write(
                "/datasources",
                s,
                Map.of(
                        "code",
                        "p3",
                        "name",
                        "P3隔离测试源",
                        "kind",
                        "RELATIONAL",
                        "connection",
                        Map.of(
                                "dialect",
                                "postgresql",
                                "url",
                                System.getenv("ONTO_TEST_DB_URL"),
                                "user",
                                System.getenv("ONTO_TEST_DB_USER"),
                                "password",
                                System.getenv("ONTO_TEST_DB_PASSWORD")),
                        "accessScope",
                        List.of("p3_source.tasks", "p3_source.algorithms")));
        String source =
                data(get("/onto/datasources").param("spaceId", s), null).get(0).path("id").asText();
        var sync = write("/datasources/" + source + "/sync", s, Map.of());
        String snapshot = sync.path("snapshot").path("id").asText();
        write(
                "/mappings/objects",
                s,
                Map.of(
                        "datasourceId",
                        source,
                        "sourceResource",
                        "p3_source.tasks",
                        "targetClassId",
                        task,
                        "objectKeyExpr",
                        "task:${task_id}",
                        "metaSnapshotId",
                        snapshot));
        write(
                "/mappings/objects",
                s,
                Map.of(
                        "datasourceId",
                        source,
                        "sourceResource",
                        "p3_source.algorithms",
                        "targetClassId",
                        algorithm,
                        "objectKeyExpr",
                        "algorithm:${std_code}",
                        "metaSnapshotId",
                        snapshot));
        String tm = null, am = null;
        for (var o : data(get("/onto/mappings").param("spaceId", s), null).path("objects"))
            if (o.path("targetClassId").asText().equals(task)) tm = o.path("id").asText();
            else am = o.path("id").asText();
        for (var item :
                List.of(
                        List.of(tm, "task_id", taskId),
                        List.of(am, "std_code", std),
                        List.of(tm, "priority", num),
                        List.of(tm, "active", bool)))
            write(
                    "/mappings/properties",
                    s,
                    Map.of(
                            "objectMappingId",
                            item.get(0),
                            "sourceField",
                            item.get(1),
                            "targetPropertyId",
                            item.get(2),
                            "transform",
                            Map.of("kind", "IDENTITY"),
                            "required",
                            true));
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("datasourceId", source);
        rel.put("relationId", relation);
        rel.put("fromObjectMappingId", tm);
        rel.put("toObjectMappingId", am);
        rel.put("fromKeyExpr", "task:${task_id}");
        rel.put("toKeyExpr", "algorithm:${std_code}");
        rel.put("join", Map.of("fromField", "algo_code", "toField", "std_code"));
        rel.put("cardinalityCheck", true);
        rel.put("metaSnapshotId", snapshot);
        write("/mappings/relations", s, rel);
        String rm =
                data(get("/onto/mappings").param("spaceId", s), null)
                        .path("relations")
                        .get(0)
                        .path("id")
                        .asText();
        String op = createOperation(s, 20);
        update(
                "/operations/" + op + "/data-query",
                s,
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "RELATION",
                                "objectMappingId",
                                tm,
                                "relationMappingId",
                                rm,
                                "propertyIds",
                                List.of(taskId, std, num, bool),
                                "filters",
                                List.of())));
        guards(s, List.of("p3_source.tasks", "p3_source.algorithms"));
        update(
                "/spaces/" + s + "/grants/" + owner,
                s,
                Map.of(
                        "role",
                        "OWNER",
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("A")),
                        "attributes",
                        Map.of("region", "A")));
        assistant(s, op);
        return new Scenario(
                s, source, snapshot, task, algorithm, tm, am, rm, taskId, std, num, bool, op);
    }
}
