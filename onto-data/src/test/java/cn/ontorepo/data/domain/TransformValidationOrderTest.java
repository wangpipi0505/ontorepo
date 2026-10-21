package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.minio.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.*;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 坏值位于LIMIT之后或被第一业务过滤排除时，仍必须完成全部授权行的全部转换校验。 */
class TransformValidationOrderTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();
  final String good = "{\"id\":1,\"region\":\"A\",\"raw\":\"10\",\"other\":\"ok\"}";
  final String bad = "{\"id\":2,\"region\":\"A\",\"raw\":\"bad\",\"other\":\"no\"}";

  QuerySpec query(String resource, boolean precedingFilter) {
    List<QuerySpec.Filter> filters = new ArrayList<>();
    if (precedingFilter)
      filters.add(
          new QuerySpec.Filter("other", QuerySpec.Operator.EQ, List.of(json.valueToTree("ok"))));
    filters.add(
        new QuerySpec.Filter(
            "raw",
            QuerySpec.Operator.EQ,
            List.of(json.valueToTree(10)),
            new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL")));
    return new QuerySpec(
        resource,
        List.of("id"),
        filters,
        List.of(),
        precedingFilter ? 500 : 1,
        10000,
        Map.of(),
        LabelScope.parse(json.valueToTree(Map.of("attr", "region", "in", List.of("A")))),
        Map.of("region", "region"));
  }

  void assertInvalid(SourceAdapter adapter, SourceSpec source, String resource) {
    for (boolean preceding : List.of(false, true))
      assertEquals(
          "TRANSFORM_FAILED",
          assertThrows(DataProblem.class, () -> adapter.query(source, query(resource, preceding)))
              .code());
  }

  @Test
  void filesValidateAuthorizedRowsAfterLimitAndBeforeAnyBusinessPredicate() throws Exception {
    Files.writeString(root.resolve("rows.json"), "[" + good + "," + bad + "]");
    Files.writeString(root.resolve("rows.csv"), "id,region,raw,other\n1,A,10,ok\n2,A,bad,no\n");
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var sheet = workbook.createSheet("data");
      String[][] values = {
        {"id", "region", "raw", "other"}, {"1", "A", "10", "ok"}, {"2", "A", "bad", "no"}
      };
      for (int i = 0; i < values.length; i++) {
        var row = sheet.createRow(i);
        for (int j = 0; j < values[i].length; j++) row.createCell(j).setCellValue(values[i][j]);
      }
      try (var out = Files.newOutputStream(root.resolve("rows.xlsx"))) {
        workbook.write(out);
      }
    }
    for (var format :
        Map.of("json", "rows.json", "csv", "rows.csv", "excel", "rows.xlsx").entrySet())
      assertInvalid(
          new FileSourceAdapter(),
          new SourceSpec(
              "f",
              "FILE",
              json.createObjectNode().put("root", root.toString()).put("dialect", format.getKey()),
              Set.of(format.getValue())),
          format.getValue());
  }

  @Test
  void httpValidatesLaterAuthorizedPagesAfterLimit() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rows",
        exchange -> {
          String q = exchange.getRequestURI().getQuery();
          String response =
              q.contains("page=1")
                  ? "[" + good + "]"
                  : q.contains("page=2") ? "[" + bad + "]" : "[]";
          byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var config =
          json.createObjectNode()
              .put("dialect", "json")
              .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())
              .put("pageParam", "page")
              .put("pageSize", 1)
              .put("maxPages", 3);
      config.putArray("endpoints").add("/rows");
      assertInvalid(
          new HttpSourceAdapter(), new SourceSpec("h", "HTTP", config, Set.of("/rows")), "/rows");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void minioValidatesAuthorizedRowsAfterLimitAndBeforeNativeBusinessPushdown() throws Exception {
    JsonNode env = json.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    var config =
        json.createObjectNode()
            .put("dialect", "s3-parquet")
            .put("endpoint", env.path("ONTO_STORAGE_ENDPOINT").asText())
            .put("accessKey", env.path("ONTO_STORAGE_ACCESS_KEY").asText())
            .put("secretKey", env.path("ONTO_STORAGE_SECRET_KEY").asText())
            .put("bucket", env.path("ONTO_STORAGE_BUCKET").asText());
    var client =
        MinioClient.builder()
            .endpoint(config.path("endpoint").asText())
            .credentials(config.path("accessKey").asText(), config.path("secretKey").asText())
            .build();
    String prefix = "c5tv/" + UUID.randomUUID() + "/", object = prefix + "rows.parquet";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    OutputFile file =
        new OutputFile() {
          public PositionOutputStream create(long hint) {
            return new PositionOutputStream() {
              public long getPos() {
                return bytes.size();
              }

              public void write(int b) {
                bytes.write(b);
              }

              public void write(byte[] b, int off, int len) {
                bytes.write(b, off, len);
              }
            };
          }

          public PositionOutputStream createOrOverwrite(long hint) {
            return create(hint);
          }

          public boolean supportsBlockSize() {
            return false;
          }

          public long defaultBlockSize() {
            return 0;
          }
        };
    var schema =
        MessageTypeParser.parseMessageType(
            "message rows { required int32 id; optional binary region (STRING); optional binary raw"
                + " (STRING); optional binary other (STRING); }");
    try (var writer =
        ExampleParquetWriter.builder(file)
            .withConf(new Configuration(false))
            .withType(schema)
            .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
            .build()) {
      writer.write(
          new SimpleGroup(schema)
              .append("id", 1)
              .append("region", "A")
              .append("raw", "10")
              .append("other", "ok"));
      writer.write(
          new SimpleGroup(schema)
              .append("id", 2)
              .append("region", "A")
              .append("raw", "bad")
              .append("other", "no"));
    }
    byte[] body = bytes.toByteArray();
    client.putObject(
        PutObjectArgs.builder().bucket(config.path("bucket").asText()).object(object).stream(
                new ByteArrayInputStream(body), (long) body.length, -1L)
            .build());
    try {
      assertInvalid(
          new S3ParquetSourceAdapter(),
          new SourceSpec("s3", "LAKE", config, Set.of(prefix)),
          object);
    } finally {
      client.removeObject(
          RemoveObjectArgs.builder().bucket(config.path("bucket").asText()).object(object).build());
    }
  }
}
