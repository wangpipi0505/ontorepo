package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAdaptersTest {
  @TempDir Path root;
  final ObjectMapper mapper = new ObjectMapper();

  QuerySpec query(String r) throws Exception {
    return new QuerySpec(
        r,
        List.of("id"),
        List.of(),
        List.of(),
        1,
        1000,
        Map.of(),
        LabelScope.parse(mapper.readTree("{\"attr\":\"region\",\"in\":[\"A\"]}")),
        Map.of("region", "region"));
  }

  @Test
  void fileFiltersBeforeLimitAndRejectsPathEscape() throws Exception {
    Files.writeString(root.resolve("records.csv"), "id,region\n1,B\n2,\n3,A\n");
    var source =
        new SourceSpec(
            "f",
            "FILE",
            mapper.createObjectNode().put("root", root.toString()).put("dialect", "csv"),
            Set.of("records.csv"));
    var adapter = new FileSourceAdapter();
    var result = adapter.query(source, query("records.csv"));
    assertEquals("3", result.rows().getFirst().get("id"));
    assertEquals(1, result.rows().size());
    assertEquals(4L, result.evidence().getFirst().key().get("line"));
    assertThrows(
        DataProblem.class,
        () ->
            adapter.query(
                new SourceSpec("f", "FILE", source.connection(), Set.of("../bad.csv")),
                query("../bad.csv")));
    Path outside = Files.createTempFile("c5-outside", ".json");
    try {
      assertThrows(
          DataProblem.class,
          () ->
              adapter.query(
                  new SourceSpec("f", "FILE", source.connection(), Set.of(outside.toString())),
                  query(outside.toString())));
    } finally {
      Files.delete(outside);
    }
  }

  @Test
  void httpPagesAndRejectsRedirectAndUndeclaredEndpoint() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/records",
        e -> {
          String body =
              e.getRequestURI().getQuery().contains("page=2")
                  ? "[{\"id\":3,\"region\":\"A\"}]"
                  : "[{\"id\":1,\"region\":\"B\"},{\"id\":2,\"region\":null}]";
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.sendResponseHeaders(200, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.createContext(
        "/redirect",
        e -> {
          e.getResponseHeaders().set("Location", "http://example.com/");
          e.sendResponseHeaders(302, -1);
          e.close();
        });
    server.start();
    try {
      var cfg =
          mapper
              .createObjectNode()
              .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())
              .put("dialect", "json")
              .put("pageParam", "page")
              .put("pageSize", 2)
              .put("maxPages", 3);
      cfg.putArray("endpoints").add("/records").add("/redirect");
      var source = new SourceSpec("h", "HTTP", cfg, Set.of("/records", "/redirect"));
      var adapter = new HttpSourceAdapter();
      var r = adapter.query(source, query("/records"));
      assertEquals(3, r.rows().getFirst().get("id"));
      assertThrows(DataProblem.class, () -> adapter.query(source, query("/other")));
      assertThrows(DataProblem.class, () -> adapter.query(source, query("/redirect")));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void jsonAndExcelPreserveTypesAndRejectFormulas() throws Exception {
    Files.writeString(
        root.resolve("rows.json"),
        "[{\"id\":1,\"region\":\"B\"},{\"id\":2,\"region\":\"A\",\"enabled\":true}]");
    var jsonSource =
        new SourceSpec(
            "f",
            "FILE",
            mapper.createObjectNode().put("root", root.toString()).put("dialect", "json"),
            Set.of("rows.json"));
    var adapter = new FileSourceAdapter();
    assertEquals(2, adapter.query(jsonSource, query("rows.json")).rows().getFirst().get("id"));
    Path path = root.resolve("rows.xlsx");
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var sheet = workbook.createSheet("data");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("id");
      header.createCell(1).setCellValue("region");
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue(7);
      row.createCell(1).setCellValue("A");
      try (var out = Files.newOutputStream(path)) {
        workbook.write(out);
      }
    }
    var excelSource =
        new SourceSpec(
            "f",
            "FILE",
            mapper.createObjectNode().put("root", root.toString()).put("dialect", "excel"),
            Set.of("rows.xlsx"));
    assertEquals(7.0, adapter.query(excelSource, query("rows.xlsx")).rows().getFirst().get("id"));
    try (var in = Files.newInputStream(path);
        var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
      workbook.getSheetAt(0).getRow(1).getCell(0).setCellFormula("1+1");
      try (var out = Files.newOutputStream(root.resolve("formula.xlsx"))) {
        workbook.write(out);
      }
    }
    var formulaSource =
        new SourceSpec("f", "FILE", excelSource.connection(), Set.of("formula.xlsx"));
    assertEquals(
        "EXCEL_FORMULA_REJECTED",
        assertThrows(DataProblem.class, () -> adapter.query(formulaSource, query("formula.xlsx")))
            .code());
  }

  @Test
  void symbolicDirectoryEscapeAndOversizeAreRejected() throws Exception {
    Path outside = Files.createTempDirectory("c5-outside-");
    Path link = root.resolve("link");
    try {
      Files.writeString(outside.resolve("rows.json"), "[{\"id\":1,\"region\":\"A\"}]");
      try {
        Files.createSymbolicLink(link, outside);
      } catch (java.nio.file.FileSystemException denied) {
        if (!System.getProperty("os.name").startsWith("Windows")) throw denied;
        // Windows目录junction同样经过toRealPath，验证无需管理员特权的重解析点逃逸。
        Process process =
            new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "junction创建必须成功，不能跳过边界验收");
      }
      var source =
          new SourceSpec(
              "f",
              "FILE",
              mapper.createObjectNode().put("root", root.toString()).put("dialect", "json"),
              Set.of("link/rows.json"));
      assertEquals(
          "FILE_PATH_OR_SIZE_REJECTED",
          assertThrows(
                  DataProblem.class,
                  () -> new FileSourceAdapter().query(source, query("link/rows.json")))
              .code());
      try (var sparse = new java.io.RandomAccessFile(root.resolve("large.json").toFile(), "rw")) {
        sparse.setLength(16L * 1024 * 1024 + 1);
      }
      var large = new SourceSpec("f", "FILE", source.connection(), Set.of("large.json"));
      assertThrows(
          DataProblem.class, () -> new FileSourceAdapter().query(large, query("large.json")));
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(outside.resolve("rows.json"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void registryRequiresExactKindAndDialect() {
    var registry = new AdapterRegistry();
    for (var pair :
        Map.of(
                "RELATIONAL",
                "postgresql",
                "WAREHOUSE",
                "clickhouse",
                "LAKE",
                "s3-parquet",
                "FILE",
                "csv",
                "HTTP",
                "json",
                "DOCUMENT",
                "mongodb",
                "KEY_VALUE",
                "redis",
                "WIDE_COLUMN",
                "cassandra",
                "GRAPH",
                "neo4j")
            .entrySet())
      assertNotNull(
          registry.adapter(
              new SourceSpec(
                  "s",
                  pair.getKey(),
                  mapper.createObjectNode().put("dialect", pair.getValue()),
                  Set.of())));
    assertThrows(
        DataProblem.class,
        () ->
            registry.adapter(
                new SourceSpec(
                    "s",
                    "WAREHOUSE",
                    mapper.createObjectNode().put("dialect", "postgresql"),
                    Set.of())));
    assertThrows(
        DataProblem.class,
        () -> registry.adapter(new SourceSpec("s", "FILE", mapper.createObjectNode(), Set.of())));
  }

  @Test
  void httpTimeoutCoversSlowResponseBody() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/slow",
        e -> {
          try {
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write('[');
            e.getResponseBody().flush();
            Thread.sleep(700);
            e.getResponseBody().write(']');
          } catch (Exception ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
    try {
      var cfg =
          mapper
              .createObjectNode()
              .put("dialect", "json")
              .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
      cfg.putArray("endpoints").add("/slow");
      var source = new SourceSpec("h", "HTTP", cfg, Set.of("/slow"));
      var q =
          new QuerySpec(
              "/slow",
              List.of("id"),
              List.of(),
              List.of(),
              1,
              100,
              Map.of(),
              LabelScope.unrestricted(),
              Map.of("region", "region"));
      long start = System.nanoTime();
      assertThrows(DataProblem.class, () -> new HttpSourceAdapter().query(source, q));
      assertTrue((System.nanoTime() - start) / 1_000_000 < 500, "响应体必须受同一截止时间限制");
    } finally {
      server.stop(0);
    }
  }
}
