package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** 管理API到真实文件/HTTP/MinIO适配器的贯通，分类过滤必须早于LIMIT=1。 */
class P3ProtocolTest extends P3Fixture {
    void query(
            String kind,
            String dialect,
            Map<String, Object> connection,
            String resource,
            String scope)
            throws Exception {
        String s = createSpace(),
                cl = createClass(s, "任务源", null),
                property = property(s, cl, "任务编码", "STRING");
        write(
                "/datasources",
                s,
                Map.of(
                        "code",
                        "native_source",
                        "name",
                        "真实协议测试",
                        "kind",
                        kind,
                        "connection",
                        connection,
                        "accessScope",
                        List.of(scope)));
        String source =
                data(get("/onto/datasources").param("spaceId", s), null).get(0).path("id").asText();
        var probe = data(post("/onto/datasources/" + source + "/probe"), Map.of("spaceId", s));
        for (var stage : probe.path("stages"))
            assertEquals("PASSED", stage.path("status").asText(), probe.toString());
        var sync = write("/datasources/" + source + "/sync", s, Map.of());
        String snapshot = sync.path("snapshot").path("id").asText();
        write(
                "/mappings/objects",
                s,
                Map.of(
                        "datasourceId",
                        source,
                        "sourceResource",
                        resource,
                        "targetClassId",
                        cl,
                        "objectKeyExpr",
                        "task:${task_id}",
                        "metaSnapshotId",
                        snapshot));
        String mapping =
                data(get("/onto/mappings").param("spaceId", s), null)
                        .path("objects")
                        .get(0)
                        .path("id")
                        .asText();
        write(
                "/mappings/properties",
                s,
                Map.of(
                        "objectMappingId",
                        mapping,
                        "sourceField",
                        "task_id",
                        "targetPropertyId",
                        property,
                        "transform",
                        Map.of("kind", "IDENTITY"),
                        "required",
                        true));
        String op = createOperation(s, 1);
        update(
                "/operations/" + op + "/data-query",
                s,
                Map.of(
                        "dataQuery",
                        Map.of(
                                "mode",
                                "OBJECT",
                                "objectMappingId",
                                mapping,
                                "propertyIds",
                                List.of(property),
                                "filters",
                                List.of())));
        guards(s, List.of(resource));
        update(
                "/spaces/" + s + "/grants/" + owner,
                s,
                Map.of(
                        "role",
                        "OWNER",
                        "dataScope",
                        Map.of("attr", "region", "in", List.of("A")),
                        "attributes",
                        Map.of()));
        assistant(s, op);
        String candidate = freeze(s).path("id").asText();
        var trial =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", op, "input", Map.of()));
        assertEquals("SUCCEEDED", trial.path("status").asText(), trial.toString());
        assertEquals(1, trial.path("rows").size());
        assertEquals("T-A", trial.path("rows").get(0).path(property).asText());
        assertTrue(trial.path("evidence").toString().contains("SOURCE_RECORD"));
    }

    @Test
    void controlledHttpPostPaginationIsAuthorizedBeforeResultLimit() throws Exception {
        query(
                "HTTP",
                "json",
                Map.of(
                        "dialect",
                        "json",
                        "baseUrl",
                        "http://127.0.0.1:19180",
                        "headers",
                        Map.of(),
                        "endpoints",
                        List.of("/tasks"),
                        "method",
                        "POST",
                        "body",
                        Map.of(),
                        "pageParam",
                        "page",
                        "pageSize",
                        2,
                        "maxPages",
                        3),
                "/tasks",
                "/tasks");
    }

    @Test
    void minioParquetPrefixScopeAndPhysicalRowEvidenceSurviveManagementFreeze() throws Exception {
        var fixture =
                json.readTree(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of(
                                        "../docs/evidence/continuation/p3-lake-fixture.json")));
        query(
                "LAKE",
                "s3-parquet",
                Map.of(
                        "dialect",
                        "s3-parquet",
                        "endpoint",
                        System.getenv("ONTO_STORAGE_ENDPOINT"),
                        "accessKey",
                        System.getenv("ONTO_STORAGE_ACCESS_KEY"),
                        "secretKey",
                        System.getenv("ONTO_STORAGE_SECRET_KEY"),
                        "bucket",
                        System.getenv("ONTO_STORAGE_BUCKET")),
                fixture.path("resource").asText(),
                fixture.path("prefix").asText());
    }

    @Test
    void csvJsonAndExcelFileReferencesUseServerRootAndProtocolResourceBindings() throws Exception {
        for (String ext : List.of("csv", "json", "xlsx")) {
            String s = createSpace();
            byte[] bytes;
            if (ext.equals("csv"))
                bytes =
                        "task_id,region\nT-B,B\nT-NULL,\nT-A,A\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else if (ext.equals("json"))
                bytes =
                        "[{\"task_id\":\"T-B\",\"region\":\"B\"},{\"task_id\":\"T-NULL\",\"region\":null},{\"task_id\":\"T-A\",\"region\":\"A\"}]"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else
                try (var wb = new XSSFWorkbook();
                        var out = new ByteArrayOutputStream()) {
                    var sheet = wb.createSheet("任务");
                    sheet.createRow(0).createCell(0).setCellValue("task_id");
                    sheet.getRow(0).createCell(1).setCellValue("region");
                    for (int i = 1; i <= 3; i++) {
                        sheet.createRow(i)
                                .createCell(0)
                                .setCellValue(i == 1 ? "T-B" : i == 2 ? "T-NULL" : "T-A");
                        if (i != 2) sheet.getRow(i).createCell(1).setCellValue(i == 1 ? "B" : "A");
                    }
                    wb.write(out);
                    bytes = out.toByteArray();
                }
            var file =
                    data(
                                    multipart("/onto/data-files")
                                            .file(
                                                    new MockMultipartFile(
                                                            "file",
                                                            "tasks." + ext,
                                                            "application/octet-stream",
                                                            bytes))
                                            .param("spaceId", s)
                                            .param("expectedDraftRev", Long.toString(rev(s))),
                                    null)
                            .path("file");
            // FILE引用必须与所属空间一致，因此复用下方同空间链路。
            String cl = createClass(s, "任务文件", null), prop = property(s, cl, "编码", "STRING");
            write(
                    "/datasources",
                    s,
                    Map.of(
                            "code",
                            "files",
                            "name",
                            "真实文件",
                            "kind",
                            "FILE",
                            "connection",
                            Map.of(
                                    "dialect",
                                    ext.equals("xlsx") ? "excel" : ext,
                                    "fileId",
                                    file.path("id").asText()),
                            "accessScope",
                            List.of(file.path("resource").asText())));
            String src =
                    data(get("/onto/datasources").param("spaceId", s), null)
                            .get(0)
                            .path("id")
                            .asText();
            var sync = write("/datasources/" + src + "/sync", s, Map.of());
            write(
                    "/mappings/objects",
                    s,
                    Map.of(
                            "datasourceId",
                            src,
                            "sourceResource",
                            file.path("resource").asText(),
                            "targetClassId",
                            cl,
                            "objectKeyExpr",
                            "task:${task_id}",
                            "metaSnapshotId",
                            sync.path("snapshot").path("id").asText()));
            String mapping =
                    data(get("/onto/mappings").param("spaceId", s), null)
                            .path("objects")
                            .get(0)
                            .path("id")
                            .asText();
            write(
                    "/mappings/properties",
                    s,
                    Map.of(
                            "objectMappingId",
                            mapping,
                            "sourceField",
                            "task_id",
                            "targetPropertyId",
                            prop,
                            "required",
                            true));
            String op = createOperation(s, 1);
            update(
                    "/operations/" + op + "/data-query",
                    s,
                    Map.of(
                            "dataQuery",
                            Map.of(
                                    "mode",
                                    "OBJECT",
                                    "objectMappingId",
                                    mapping,
                                    "propertyIds",
                                    List.of(prop),
                                    "filters",
                                    List.of())));
            guards(s, List.of(file.path("resource").asText()));
            update(
                    "/spaces/" + s + "/grants/" + owner,
                    s,
                    Map.of(
                            "role",
                            "OWNER",
                            "dataScope",
                            Map.of("attr", "region", "in", List.of("A")),
                            "attributes",
                            Map.of()));
            assistant(s, op);
            String candidate = freeze(s).path("id").asText();
            var trial =
                    data(
                            post("/onto/candidates/" + candidate + "/trial"),
                            Map.of("operationId", op, "input", Map.of()));
            assertEquals("SUCCEEDED", trial.path("status").asText(), trial.toString());
            assertEquals("T-A", trial.path("rows").get(0).path(prop).asText());
            assertEquals(1, trial.path("rows").size());
            assertTrue(trial.path("evidence").get(1).path("ref").path("key").has("line"));
            if (ext.equals("csv")) {
                var path =
                        java.nio.file.Path.of(System.getenv("ONTO_FILES_ROOT"))
                                .resolve(file.path("resource").asText());
                try {
                    java.nio.file.Files.writeString(path, "task_id,region\nT-TAMPERED,A\n");
                    var rejected =
                            data(
                                    post("/onto/candidates/" + candidate + "/trial"),
                                    Map.of("operationId", op, "input", Map.of()));
                    assertEquals("FAILED", rejected.path("status").asText());
                    assertEquals("FILE_INTEGRITY_FAILED", rejected.path("errorCode").asText());
                } finally {
                    java.nio.file.Files.write(path, bytes);
                }
            }
        }
    }
}
