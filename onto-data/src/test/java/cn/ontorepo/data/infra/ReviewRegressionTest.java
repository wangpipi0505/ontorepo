package cn.ontorepo.data.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 独立评审精确反例：错误列绑定、origin变化、纯空白未分类。 */
public class ReviewRegressionTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();

  @Test
  public void sparseExcelHeaderKeepsPhysicalColumnIndexAndEvidence() throws Exception {
    Path file = root.resolve("sparse.xlsx");
    try (var workbook = new XSSFWorkbook()) {
      var sheet = workbook.createSheet("data");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("id");
      header.createCell(2).setCellValue("region");
      var denied = sheet.createRow(1);
      denied.createCell(0).setCellValue(7);
      denied.createCell(1).setCellValue("A");
      denied.createCell(2).setCellValue("B");
      var allowed = sheet.createRow(2);
      allowed.createCell(0).setCellValue(8);
      allowed.createCell(1).setCellValue("B");
      allowed.createCell(2).setCellValue("A");
      try (var out = Files.newOutputStream(file)) {
        workbook.write(out);
      }
    }
    var config = json.createObjectNode().put("root", root.toString()).put("dialect", "excel");
    var source = new SourceSpec("f", "FILE", config, Set.of("sparse.xlsx"));
    var q =
        new QuerySpec(
            "sparse.xlsx",
            List.of("id"),
            List.of(),
            List.of(),
            1,
            1000,
            Map.of(),
            new LabelScope(Map.of("region", Set.of(TextNode.valueOf("A")))),
            Map.of("region", "region"));
    var result = new FileSourceAdapter().query(source, q);
    assertEquals(8.0, result.rows().getFirst().get("id"));
    assertEquals(3L, result.evidence().getFirst().key().get("line"));
  }

  @Test
  public void networkPathEndpointNeverResolvesOutsideApprovedOrigin() {
    var config =
        json.createObjectNode().put("baseUrl", "https://trusted.example").put("dialect", "json");
    config.putArray("endpoints").add("//attacker/rows").add("/rows");
    config.putObject("headers").put("Authorization", "test-secret");
    var source = new SourceSpec("h", "HTTP", config, Set.of("//attacker/rows", "/rows"));
    var adapter = new HttpSourceAdapter();
    assertEquals(
        "HTTP_ENDPOINT_REJECTED",
        assertThrows(DataProblem.class, () -> adapter.endpoint(source, "//attacker/rows", 1))
            .code());
    var safe = adapter.endpoint(source, "/rows", 1);
    assertEquals("https", safe.getScheme());
    assertEquals("trusted.example", safe.getHost());
  }

  @Test
  public void realPostgresUnrestrictedScopeRejectsEveryJavaBlankBeforeLimit() throws Exception {
    JsonNode env = json.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    String schema = "c5_blank_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        json.createObjectNode()
            .put("dialect", "postgresql")
            .put("url", env.path("ONTO_DB_URL").asText())
            .put("user", env.path("ONTO_DB_USER").asText())
            .put("password", env.path("ONTO_DB_PASSWORD").asText());
    try (Connection c =
            DriverManager.getConnection(
                config.path("url").asText(),
                config.path("user").asText(),
                config.path("password").asText());
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA " + schema);
      try {
        st.execute("CREATE TABLE " + schema + ".rows(id integer primary key,region text)");
        int id = 0;
        try (var insert = c.prepareStatement("INSERT INTO " + schema + ".rows VALUES(?,?)")) {
          for (int point = 0; point <= Character.MAX_CODE_POINT; point++)
            if (Character.isWhitespace(point)) {
              insert.setInt(1, ++id);
              insert.setString(2, new String(Character.toChars(point)));
              insert.executeUpdate();
            }
          insert.setInt(1, ++id);
          insert.setString(2, " \t\n\r\u3000");
          insert.executeUpdate();
          insert.setInt(1, ++id);
          insert.setString(2, null);
          insert.executeUpdate();
          insert.setInt(1, ++id);
          insert.setString(2, "");
          insert.executeUpdate();
          insert.setInt(1, ++id);
          insert.setString(2, "A");
          insert.executeUpdate();
          insert.setInt(1, ++id);
          insert.setString(2, "\u00a0");
          insert.executeUpdate();
        }
        var source = new SourceSpec("pg", "RELATIONAL", config, Set.of(schema + ".rows"));
        var q =
            new QuerySpec(
                schema + ".rows",
                List.of(schema + ".rows.id", schema + ".rows.region"),
                List.of(),
                List.of(),
                500,
                10000,
                Map.of(),
                LabelScope.unrestricted(),
                Map.of("region", schema + ".rows.region"));
        var result = new JdbcSourceAdapter().query(source, q);
        assertEquals(2, result.rows().size());
        for (var row : result.rows()) {
          assertTrue(
              LabelScope.unrestricted()
                  .allows("region", json.valueToTree(row.get(schema + ".rows.region"))));
          assertInstanceOf(Integer.class, row.get(schema + ".rows.id"));
        }
        assertEquals(2, result.evidence().size());
      } finally {
        st.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  @Test
  public void clickHousePlanUsesExplicitJavaWhitespaceSetWithoutClaimingServerExecution() {
    var config = json.createObjectNode().put("dialect", "clickhouse");
    var source = new SourceSpec("ch", "WAREHOUSE", config, Set.of("approved.rows"));
    var metadata =
        new SourceMetadata(
            List.of(
                new SourceMetadata.Resource(
                    "approved.rows",
                    List.of(
                        new SourceMetadata.Field("id", "Int32", false),
                        new SourceMetadata.Field("region", "String", true)),
                    List.of("id"),
                    List.of(),
                    List.of())));
    var q =
        new QuerySpec(
            "approved.rows",
            List.of("approved.rows.id"),
            List.of(),
            List.of(),
            1,
            1000,
            Map.of(),
            LabelScope.unrestricted(),
            Map.of("region", "approved.rows.region"));
    String sql = new QueryPlanner().plan(source, q, metadata).sql();
    assertTrue(sql.contains("replaceRegexpAll("));
    assertFalse(sql.contains("BTRIM("));
    int start = sql.indexOf("'[") + 1, end = sql.indexOf("'", start);
    String regex = sql.substring(start, end).replace("\\\\", "\\");
    var pattern = java.util.regex.Pattern.compile(regex);
    for (int point = 0; point <= Character.MAX_CODE_POINT; point++)
      if (Character.isWhitespace(point))
        assertTrue(pattern.matcher(new String(Character.toChars(point))).matches());
    assertFalse(pattern.matcher("\u00a0").matches());
    assertFalse(pattern.matcher("A").matches());
  }
}
