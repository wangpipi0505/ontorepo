package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 六类真实格式必须经解析器；人工确认和跨空间/并发边界经HTTP与PG验证。 */
class P3IngestTest extends P3Fixture {
    @Test
    void officeDirectoriesCannotHidePayloadFromDecompressionBudget() throws Exception {
        String s = createSpace();
        for (int payloadBytes : List.of(33 * 1024 * 1024, 1)) {
            byte[] archive = officeWithDirectoryPayload(payloadBytes);
            assertTrue(archive.length < 8 * 1024 * 1024, "压缩上传大小合法，必须检查实际目录载荷而不是只信声明size");
            call(
                    multipart("/onto/data-files")
                            .file(
                                    new MockMultipartFile(
                                            "file",
                                            "directory-payload.docx",
                                            "application/octet-stream",
                                            archive))
                            .param("spaceId", s)
                            .param("expectedDraftRev", Long.toString(rev(s))),
                    ownerToken,
                    null,
                    400);
            assertEquals(0, data(get("/onto/data-files").param("spaceId", s), null).size());
        }
        var accepted = upload(s, "empty-directory.docx", officeWithDirectoryPayload(0));
        assertFalse(accepted.path("id").asText().isBlank());
        assertEquals(1, data(get("/onto/data-files").param("spaceId", s), null).size());
    }

    /** 实际生成合法DOCX，再附加DEFLATED目录载荷；ZIP目录大小声明可以是未知值。 */
    private byte[] officeWithDirectoryPayload(int payloadBytes) throws Exception {
        byte[] docx;
        try (var document = new XWPFDocument();
                var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("任务使用算法");
            document.write(bytes);
            docx = bytes.toByteArray();
        }
        var bytes = new ByteArrayOutputStream();
        try (var original = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx));
                var zip = new java.util.zip.ZipOutputStream(bytes)) {
            java.util.zip.ZipEntry entry;
            while ((entry = original.getNextEntry()) != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                original.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("directory-with-payload/"));
            byte[] chunk = new byte[8192];
            for (int remaining = payloadBytes;
                    remaining > 0;
                    remaining -= Math.min(remaining, chunk.length))
                zip.write(chunk, 0, Math.min(remaining, chunk.length));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void officeEntryBudgetCountsDirectoriesAsWellAsFileEntries() throws Exception {
        String s = createSpace();
        byte[] docx;
        try (var document = new XWPFDocument();
                var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("任务使用算法");
            document.write(out);
            docx = out.toByteArray();
        }
        var expanded = new ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx));
                var zip = new java.util.zip.ZipOutputStream(expanded)) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                input.transferTo(zip);
                zip.closeEntry();
            }
            for (int i = 0; i < 300; i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry("empty-" + i + "/"));
                zip.closeEntry();
            }
        }
        call(
                multipart("/onto/data-files")
                        .file(
                                new MockMultipartFile(
                                        "file",
                                        "too-many.docx",
                                        "application/octet-stream",
                                        expanded.toByteArray()))
                        .param("spaceId", s)
                        .param("expectedDraftRev", Long.toString(rev(s))),
                ownerToken,
                null,
                400);
        assertEquals(0, data(get("/onto/data-files").param("spaceId", s), null).size());
    }

    JsonNode upload(String s, String name, byte[] bytes) throws Exception {
        return data(
                        multipart("/onto/data-files")
                                .file(
                                        new MockMultipartFile(
                                                "file", name, "application/octet-stream", bytes))
                                .param("spaceId", s)
                                .param("expectedDraftRev", Long.toString(rev(s))),
                        null)
                .path("file");
    }

    JsonNode extract(String s, String kind, String file) throws Exception {
        var result = write("/suggestions/extract", s, Map.of("sourceKind", kind, "fileId", file));
        return data(
                get("/onto/suggestions")
                        .param("spaceId", s)
                        .param("batchId", result.path("batchId").asText()),
                null);
    }

    byte[] utf(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void allSixRealFormatsProduceLocatedPendingSuggestionsWithoutWritingModels() throws Exception {
        String s = createSpace();
        Map<String, byte[]> formats = new LinkedHashMap<>();
        formats.put("equipment.csv", utf("name,priority,active\n任务,7,true\n"));
        formats.put("nested.json", utf("[{\"name\":\"任务\",\"details\":{\"priority\":3}}]"));
        formats.put("domain.txt", utf("任务使用算法。\n\n算法用于分类。"));
        try (var wb = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("设备");
            sheet.createRow(0).createCell(0).setCellValue("编号");
            sheet.createRow(1).createCell(0).setCellValue("EQ-1");
            wb.write(out);
            formats.put("equipment.xlsx", out.toByteArray());
        }
        try (var doc = new XWPFDocument();
                var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("任务使用算法，算法用于分类。");
            doc.write(out);
            formats.put("domain.docx", out.toByteArray());
        }
        try (var pdf = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Task Algorithm Classification");
                stream.endText();
            }
            pdf.save(out);
            formats.put("domain.pdf", out.toByteArray());
        }
        for (var entry : formats.entrySet()) {
            var file = upload(s, entry.getKey(), entry.getValue());
            String kind = entry.getKey().matches(".*\\.(csv|json|xlsx)") ? "FILE" : "DOCUMENT";
            var result = extract(s, kind, file.path("id").asText());
            assertTrue(result.size() > 0, entry.getKey());
            for (var suggestion : result) {
                assertEquals("PENDING", suggestion.path("status").asText());
                assertEquals(
                        entry.getKey(), suggestion.path("location").path("sourceName").asText());
                assertEquals(
                        "RULE_PRIOR", suggestion.path("location").path("confidenceKind").asText());
                assertTrue(
                        suggestion
                                .path("location")
                                .path("confidenceMeaning")
                                .asText()
                                .contains("未经样本校准"));
                if (entry.getKey().endsWith("pdf"))
                    assertTrue(suggestion.path("location").has("page"));
                else if (entry.getKey().endsWith("txt") || entry.getKey().endsWith("docx"))
                    assertFalse(suggestion.path("location").has("page"));
            }
        }
        assertEquals(0, data(get("/onto/classes").param("spaceId", s), null).size());
        assertEquals(6, data(get("/onto/data-files").param("spaceId", s), null).size());
    }

    @Test
    void acceptsSameBatchDependenciesAtomicallyAndRejectsRepeatedDecision() throws Exception {
        String s = createSpace();
        var file = upload(s, "assets.csv", utf("code,count\nA,4\n"));
        var suggestions = extract(s, "FILE", file.path("id").asText());
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (var c : suggestions)
            decisions.add(Map.of("id", c.path("id").asText(), "decision", "ACCEPT"));
        Collections.reverse(decisions);
        write("/suggestions/decide", s, Map.of("decisions", decisions));
        assertEquals(1, data(get("/onto/classes").param("spaceId", s), null).size());
        assertEquals(2, data(get("/onto/spaces/" + s + "/properties"), null).size());
        var repeat =
                call(
                        post("/onto/suggestions/decide"),
                        ownerToken,
                        Map.of("spaceId", s, "expectedDraftRev", rev(s), "decisions", decisions),
                        409);
        assertEquals("SUGGESTION_ALREADY_DECIDED", repeat.path("code").asText());
    }

    @Test
    void rejectionLeavesAuditAndDoesNotBuildEntities() throws Exception {
        String s = createSpace();
        var f = upload(s, "rules.txt", utf("任务使用算法。"));
        var suggestions = extract(s, "DOCUMENT", f.path("id").asText());
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (var c : suggestions)
            decisions.add(Map.of("id", c.path("id").asText(), "decision", "REJECT"));
        long before = rev(s);
        write("/suggestions/decide", s, Map.of("decisions", decisions));
        assertEquals(before + 1, rev(s));
        assertEquals(0, data(get("/onto/classes").param("spaceId", s), null).size());
        assertTrue(
                data(get("/onto/suggestions").param("spaceId", s), null)
                        .toString()
                        .contains("REJECTED"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE space_id=? AND"
                                + " action='SUGGESTIONS_DECIDE'",
                        Integer.class,
                        UUID.fromString(s)));
    }

    @Test
    void missingDependencyAndNameConflictRollBackWholeBatchAndLocateCandidate() throws Exception {
        String s = createSpace();
        var f = upload(s, "assets.csv", utf("code\nA\n"));
        var suggestions = extract(s, "FILE", f.path("id").asText());
        JsonNode clazz = null, property = null;
        for (var c : suggestions)
            if (c.path("kind").asText().equals("CLASS")) clazz = c;
            else property = c;
        assertNotNull(clazz);
        assertNotNull(property);
        long before = rev(s);
        var missing =
                call(
                        post("/onto/suggestions/decide"),
                        ownerToken,
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                before,
                                "decisions",
                                List.of(
                                        Map.of(
                                                "id",
                                                property.path("id").asText(),
                                                "decision",
                                                "ACCEPT"))),
                        400);
        assertEquals("SUGGESTION_DEPENDENCY_UNRESOLVED", missing.path("code").asText());
        assertEquals(before, rev(s));
        createClass(s, "assets", null);
        before = rev(s);
        var conflict =
                call(
                        post("/onto/suggestions/decide"),
                        ownerToken,
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                before,
                                "decisions",
                                List.of(
                                        Map.of(
                                                "id",
                                                clazz.path("id").asText(),
                                                "decision",
                                                "ACCEPT"),
                                        Map.of(
                                                "id",
                                                property.path("id").asText(),
                                                "decision",
                                                "ACCEPT"))),
                        409);
        assertEquals("SUGGESTION_NAME_CONFLICT", conflict.path("code").asText());
        assertTrue(conflict.path("message").asText().contains(clazz.path("id").asText()));
        assertEquals(before, rev(s));
    }

    @Test
    void filePathsBinaryImpersonationForeignSpaceAndStaleRevisionAreRejected() throws Exception {
        String s = createSpace(), other = createSpace();
        for (String name : List.of("bad.pdf", "bad.docx", "bad.xlsx", "bad.exe"))
            call(
                    multipart("/onto/data-files")
                            .file(
                                    new MockMultipartFile(
                                            "file", name, "text/plain", utf("pure text")))
                            .param("spaceId", s)
                            .param("expectedDraftRev", Long.toString(rev(s))),
                    ownerToken,
                    null,
                    400);
        var file = upload(s, "safe.csv", utf("code,region\nA,A\n"));
        var foreign =
                call(
                        post("/onto/suggestions/extract"),
                        ownerToken,
                        Map.of(
                                "spaceId",
                                other,
                                "expectedDraftRev",
                                rev(other),
                                "sourceKind",
                                "FILE",
                                "fileId",
                                file.path("id").asText()),
                        400);
        assertEquals("CROSS_SPACE_REFERENCE", foreign.path("code").asText());
        call(
                post("/onto/datasources"),
                ownerToken,
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "unsafe",
                        "name",
                        "非法路径",
                        "kind",
                        "FILE",
                        "connection",
                        Map.of(
                                "dialect",
                                "csv",
                                "root",
                                "C:/",
                                "path",
                                "secret.csv",
                                "fileId",
                                file.path("id").asText()),
                        "accessScope",
                        List.of(file.path("resource").asText())),
                400);
        grant(s, viewer, "VIEWER");
        call(
                multipart("/onto/data-files")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", utf("id\n1\n")))
                        .param("spaceId", s)
                        .param("expectedDraftRev", Long.toString(rev(s))),
                viewerToken,
                null,
                403);
        call(
                post("/onto/suggestions/extract"),
                ownerToken,
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        0,
                        "sourceKind",
                        "FILE",
                        "fileId",
                        file.path("id").asText()),
                409);
    }

    @Test
    void tableSnapshotExtractionIncludesRealForeignKeyAndRequiresHumanConfirmation()
            throws Exception {
        var x = scenario();
        int before = data(get("/onto/classes").param("spaceId", x.space()), null).size();
        var extracted =
                write(
                        "/suggestions/extract",
                        x.space(),
                        Map.of(
                                "sourceKind",
                                "TABLE",
                                "datasourceId",
                                x.source(),
                                "metaSnapshotId",
                                x.snapshot(),
                                "resource",
                                "p3_source.tasks"));
        var suggestions =
                data(
                        get("/onto/suggestions")
                                .param("spaceId", x.space())
                                .param("batchId", extracted.path("batchId").asText()),
                        null);
        assertTrue(suggestions.toString().contains("foreignKey"));
        assertTrue(suggestions.toString().contains("MANY_TO_ONE"));
        for (var suggestion : suggestions) {
            var location = suggestion.path("location");
            assertEquals("RULE_PRIOR", location.path("confidenceKind").asText());
            assertEquals("METADATA_ONLY", location.path("evidenceScope").asText());
            assertTrue(location.has("samplesIncluded"));
            assertFalse(location.path("samplesIncluded").asBoolean());
            assertFalse(location.path("sampleOmissionReason").asText().isBlank());
            assertFalse(location.has("sample"));
        }
        assertEquals(before, data(get("/onto/classes").param("spaceId", x.space()), null).size());
    }
}
