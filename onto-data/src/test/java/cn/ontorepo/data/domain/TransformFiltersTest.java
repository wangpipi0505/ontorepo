package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.FileSourceAdapter;
import cn.ontorepo.data.infra.HttpSourceAdapter;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 从结构化计划验证业务语义，避免只检查生成SQL而漏掉LIMIT与授权顺序。 */
class TransformFiltersTest {
  @TempDir Path root;
  final ObjectMapper json =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  QuerySpec.Filter filter(
      String column,
      String operator,
      Object value,
      String kind,
      String range,
      Map<String, Object> codebook) {
    return json.convertValue(
        Map.of(
            "column",
            column,
            "operator",
            operator,
            "values",
            List.of(value),
            "transform",
            Map.of("kind", kind, "codebook", codebook, "rangeType", range)),
        QuerySpec.Filter.class);
  }

  QuerySpec query(String resource, QuerySpec.Filter filter) {
    return new QuerySpec(
        resource,
        List.of("id"),
        List.of(filter),
        List.of(),
        1,
        10000,
        Map.of(),
        LabelScope.parse(json.valueToTree(Map.of("attr", "region", "in", List.of("A")))),
        Map.of("region", "region"));
  }

  @Test
  void numericTextFilterSkipsUnauthorizedBadValuesAndFiltersBeforeLimit() throws Exception {
    Files.writeString(root.resolve("rows.csv"), "id,region,amount\n1,B,bad\n2,A,2\n3,A,10\n");
    var source =
        new SourceSpec(
            "f",
            "FILE",
            json.createObjectNode().put("dialect", "csv").put("root", root.toString()),
            Set.of("rows.csv"));
    var result =
        assertDoesNotThrow(
            () ->
                new FileSourceAdapter()
                    .query(
                        source,
                        query(
                            "rows.csv",
                            filter("amount", "GT", 5, "TO_NUMBER", "DECIMAL", Map.of()))));
    assertEquals("3", result.rows().getFirst().get("id"));
    assertEquals(4L, result.evidence().getFirst().key().get("line"));
  }

  @Test
  void jsonAndExcelApplyBooleanCodebookAndNumericFiltersWithOriginalEvidence() throws Exception {
    Files.writeString(
        root.resolve("rows.json"),
        "[{\"id\":1,\"region\":\"B\",\"amount\":\"bad\",\"enabled\":\"bad\",\"code\":\"missing\"},{\"id\":2,\"region\":\"A\",\"amount\":\"2\",\"enabled\":\"0\",\"code\":\"low\"},{\"id\":3,\"region\":\"A\",\"amount\":\"10\",\"enabled\":\"1\",\"code\":\"high\"}]");
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var sheet = workbook.createSheet("data");
      String[][] rows = {
        {"id", "region", "amount", "enabled", "code"},
        {"1", "B", "bad", "bad", "missing"},
        {"2", "A", "2", "0", "low"},
        {"3", "A", "10", "true", "high"}
      };
      for (int i = 0; i < rows.length; i++) {
        var row = sheet.createRow(i);
        for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
      }
      try (var out = Files.newOutputStream(root.resolve("rows.xlsx"))) {
        workbook.write(out);
      }
    }
    for (var format : Map.of("json", "rows.json", "excel", "rows.xlsx").entrySet()) {
      var source =
          new SourceSpec(
              "f",
              "FILE",
              json.createObjectNode().put("dialect", format.getKey()).put("root", root.toString()),
              Set.of(format.getValue()));
      for (var filter :
          List.of(
              filter("amount", "GT", 5, "TO_NUMBER", "DECIMAL", Map.of()),
              filter("enabled", "EQ", true, "TO_BOOLEAN", "BOOLEAN", Map.of()),
              filter("code", "IN", 10, "CODEBOOK", "INTEGER", Map.of("low", 2, "high", 10)))) {
        var result = new FileSourceAdapter().query(source, query(format.getValue(), filter));
        assertEquals("3", result.rows().getFirst().get("id").toString());
        assertEquals(
            format.getKey().equals("json") ? 3L : 4L,
            result.evidence().getFirst().key().get("line"));
      }
    }
  }

  @Test
  void authorizedInvalidValueFailsWhileNullAndUnclassifiedRowsDoNotConvert() throws Exception {
    Files.writeString(
        root.resolve("rows.json"),
        "[{\"id\":1,\"region\":null,\"amount\":\"bad\"},{\"id\":2,\"region\":\"A\",\"amount\":null},{\"id\":3,\"region\":\"A\",\"amount\":\"bad\"}]");
    var source =
        new SourceSpec(
            "f",
            "FILE",
            json.createObjectNode().put("dialect", "json").put("root", root.toString()),
            Set.of("rows.json"));
    var query = query("rows.json", filter("amount", "GT", 5, "TO_NUMBER", "DECIMAL", Map.of()));
    assertEquals(
        "TRANSFORM_FAILED",
        assertThrows(DataProblem.class, () -> new FileSourceAdapter().query(source, query)).code());
  }

  @Test
  void httpContinuesPastNonmatchingPageBeforeApplyingLimit() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rows",
        exchange -> {
          String body =
              exchange.getRequestURI().getQuery().contains("page=2")
                  ? "[{\"id\":3,\"region\":\"A\",\"amount\":\"10\",\"enabled\":\"1\",\"code\":\"high\"}]"
                  : "[{\"id\":1,\"region\":\"B\",\"amount\":\"bad\",\"enabled\":\"bad\",\"code\":\"missing\"},{\"id\":2,\"region\":\"A\",\"amount\":\"2\",\"enabled\":\"0\",\"code\":\"low\"}]";
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var config =
          json.createObjectNode()
              .put("dialect", "json")
              .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())
              .put("pageParam", "page")
              .put("pageSize", 2)
              .put("maxPages", 3);
      config.putArray("endpoints").add("/rows");
      var source = new SourceSpec("h", "HTTP", config, Set.of("/rows"));
      for (var filter :
          List.of(
              filter("amount", "GT", 5, "TO_NUMBER", "DECIMAL", Map.of()),
              filter("enabled", "EQ", true, "TO_BOOLEAN", "BOOLEAN", Map.of()),
              filter("code", "EQ", 10, "CODEBOOK", "INTEGER", Map.of("low", 2, "high", 10)))) {
        var result = new HttpSourceAdapter().query(source, query("/rows", filter));
        assertEquals(3, result.rows().getFirst().get("id"));
        assertEquals(Map.of("page", 2, "index", 0), result.evidence().getFirst().key());
      }
    } finally {
      server.stop(0);
    }
  }
}
