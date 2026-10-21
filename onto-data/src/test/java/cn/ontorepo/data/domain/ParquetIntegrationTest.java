package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.S3ParquetSourceAdapter;
import com.fasterxml.jackson.databind.*;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.*;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;

class ParquetIntegrationTest {
  @Test
  void realMinioParquetFiltersProjectsAndLocatesPhysicalRow() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode env = mapper.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    var config =
        mapper
            .createObjectNode()
            .put("dialect", "s3-parquet")
            .put("endpoint", env.path("ONTO_STORAGE_ENDPOINT").asText())
            .put("accessKey", env.path("ONTO_STORAGE_ACCESS_KEY").asText())
            .put("secretKey", env.path("ONTO_STORAGE_SECRET_KEY").asText())
            .put("bucket", env.path("ONTO_STORAGE_BUCKET").asText());
    MinioClient client =
        MinioClient.builder()
            .endpoint(config.path("endpoint").asText())
            .credentials(config.path("accessKey").asText(), config.path("secretKey").asText())
            .build();
    String prefix = "c5/" + UUID.randomUUID() + "/", object = prefix + "rows.parquet";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    OutputFile output =
        new OutputFile() {
          public PositionOutputStream create(long hint) {
            return new PositionOutputStream() {
              public long getPos() {
                return bytes.size();
              }

              public void write(int b) {
                bytes.write(b);
              }

              public void write(byte[] b, int o, int n) {
                bytes.write(b, o, n);
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
            "message rows { required int32 id; optional binary region (STRING); optional int64"
                + " amount; optional binary hidden (STRING); optional binary raw (STRING);"
                + " optional binary code (STRING); optional binary enabled (STRING);"
                + " optional float ratio; optional double measure; optional boolean active; }");
    try (var writer =
        ExampleParquetWriter.builder(output)
            .withConf(new Configuration(false))
            .withType(schema)
            .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
            .build()) {
      writer.write(
          new SimpleGroup(schema)
              .append("id", 1)
              .append("region", "B")
              .append("amount", 10L)
              .append("raw", "bad")
              .append("code", "missing")
              .append("enabled", "bad"));
      writer.write(new SimpleGroup(schema).append("id", 2).append("amount", 20L));
      writer.write(
          new SimpleGroup(schema)
              .append("id", 3)
              .append("region", "A")
              .append("amount", 30L)
              .append("raw", "2")
              .append("code", "low")
              .append("enabled", "0")
              .append("ratio", 0.5f)
              .append("measure", 2.5d)
              .append("active", true)
              .append("hidden", "private"));
      writer.write(
          new SimpleGroup(schema)
              .append("id", 4)
              .append("region", "A")
              .append("amount", 40L)
              .append("raw", "10")
              .append("code", "high")
              .append("enabled", "1"));
    }
    byte[] body = bytes.toByteArray();
    assertEquals("PAR1", new String(body, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    client.putObject(
        PutObjectArgs.builder().bucket(config.path("bucket").asText()).object(object).stream(
                new ByteArrayInputStream(body), (long) body.length, -1L)
            .build());
    try {
      var source = new SourceSpec("lake", "LAKE", config, Set.of(prefix));
      var adapter = new S3ParquetSourceAdapter();
      assertEquals(1, adapter.metadata(source).resources().size());
      var q =
          new QuerySpec(
              object,
              List.of("id", "amount"),
              List.of(),
              List.of(),
              1,
              10000,
              Map.of(),
              LabelScope.parse(mapper.readTree("{\"attr\":\"region\",\"in\":[\"A\"]}")),
              Map.of("region", "region"));
      var result = adapter.query(source, q);
      assertEquals(1, result.rows().size());
      assertEquals(3, result.rows().getFirst().get("id"));
      assertEquals(30L, result.rows().getFirst().get("amount"));
      assertFalse(result.rows().getFirst().containsKey("hidden"));
      assertEquals(2L, result.evidence().getFirst().key().get("rowIndex"));
      // LabelScope 会把整数标签规范成十进制节点，精确等价的整数仍须可用。
      var numericGuard =
          new QuerySpec(
              q.resource(),
              q.projection(),
              List.of(),
              q.joins(),
              1,
              10000,
              q.options(),
              new LabelScope(Map.of("region", Set.of(mapper.valueToTree(3)))),
              Map.of("region", "id"));
      assertEquals(3, adapter.query(source, numericGuard).rows().getFirst().get("id"));
      for (var entry :
          Map.<String, JsonNode>of(
                  "ratio",
                  mapper.valueToTree(0.5),
                  "measure",
                  mapper.valueToTree(2.5),
                  "active",
                  mapper.valueToTree(true),
                  "amount",
                  mapper.valueToTree(30L),
                  "id",
                  mapper.valueToTree(new java.math.BigDecimal("3.0")))
              .entrySet()) {
        var validQuery =
            new QuerySpec(
                q.resource(),
                q.projection(),
                List.of(
                    new QuerySpec.Filter(
                        entry.getKey(), QuerySpec.Operator.EQ, List.of(entry.getValue()))),
                q.joins(),
                1,
                10000,
                q.options(),
                q.scope(),
                q.guardBindings());
        assertEquals(3, adapter.query(source, validQuery).rows().getFirst().get("id"));
      }
      // 无匹配行也必须报告无效过滤类型，不能由下推将错误静默变成空结果。
      Map<String, List<JsonNode>> invalid = new LinkedHashMap<>();
      invalid.put(
          "id",
          List.of(
              mapper.valueToTree("abc"),
              mapper.valueToTree(true),
              mapper.valueToTree(1.5),
              mapper.valueToTree(2147483648L),
              mapper.valueToTree(-2147483649L)));
      invalid.put(
          "amount",
          List.of(
              mapper.valueToTree("30"),
              mapper.valueToTree(false),
              mapper.valueToTree(1.5),
              mapper.valueToTree(new java.math.BigInteger("9223372036854775808")),
              mapper.valueToTree(new java.math.BigInteger("-9223372036854775809"))));
      invalid.put(
          "ratio",
          List.of(
              mapper.valueToTree("0"),
              mapper.valueToTree(false),
              mapper.valueToTree(new java.math.BigDecimal("1e100"))));
      invalid.put(
          "measure",
          List.of(
              mapper.valueToTree("0"),
              mapper.valueToTree(true),
              mapper.valueToTree(new java.math.BigDecimal("1e400")),
              com.fasterxml.jackson.databind.node.DoubleNode.valueOf(Double.NaN),
              com.fasterxml.jackson.databind.node.DoubleNode.valueOf(Double.POSITIVE_INFINITY)));
      invalid.put("active", List.of(mapper.valueToTree("false"), mapper.valueToTree(0)));
      invalid.put("region", List.of(mapper.valueToTree(false), mapper.valueToTree(0)));
      for (var column : invalid.entrySet())
        for (var value : column.getValue()) {
          var invalidQuery =
              new QuerySpec(
                  q.resource(),
                  q.projection(),
                  List.of(
                      new QuerySpec.Filter(column.getKey(), QuerySpec.Operator.EQ, List.of(value))),
                  q.joins(),
                  1,
                  10000,
                  q.options(),
                  q.scope(),
                  q.guardBindings());
          assertEquals(
              "FILTER_TYPE_MISMATCH",
              assertThrows(
                      DataProblem.class,
                      () -> adapter.query(source, invalidQuery),
                      column.getKey() + "=" + value)
                  .code());
        }
      // 分类标签同样进入严格谓词，不允许字符串被静默强制为数值。
      var wrongGuard =
          new QuerySpec(
              q.resource(),
              q.projection(),
              List.of(),
              q.joins(),
              1,
              10000,
              q.options(),
              new LabelScope(Map.of("region", Set.of(mapper.valueToTree("abc")))),
              Map.of("region", "id"));
      assertEquals(
          "FILTER_TYPE_MISMATCH",
          assertThrows(DataProblem.class, () -> adapter.query(source, wrongGuard)).code());
      for (var filter :
          List.of(
              new QuerySpec.Filter(
                  "raw",
                  QuerySpec.Operator.GT,
                  List.of(mapper.valueToTree(5)),
                  new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL")),
              new QuerySpec.Filter(
                  "code",
                  QuerySpec.Operator.EQ,
                  List.of(mapper.valueToTree(10)),
                  new ValueTransform(
                      ValueTransform.Kind.CODEBOOK,
                      Map.of("low", mapper.valueToTree(2), "high", mapper.valueToTree(10)),
                      "INTEGER")),
              new QuerySpec.Filter(
                  "enabled",
                  QuerySpec.Operator.EQ,
                  List.of(mapper.valueToTree(true)),
                  new ValueTransform(ValueTransform.Kind.TO_BOOLEAN, Map.of(), "BOOLEAN")))) {
        var transformed =
            new QuerySpec(
                q.resource(),
                q.projection(),
                List.of(filter),
                q.joins(),
                1,
                10000,
                q.options(),
                q.scope(),
                q.guardBindings());
        var transformedResult = assertDoesNotThrow(() -> adapter.query(source, transformed));
        assertEquals(4, transformedResult.rows().getFirst().get("id"));
        assertEquals(3L, transformedResult.evidence().getFirst().key().get("rowIndex"));
      }
      assertThrows(
          DataProblem.class,
          () -> adapter.query(new SourceSpec("lake", "LAKE", config, Set.of("other/")), q));
    } finally {
      client.removeObject(
          RemoveObjectArgs.builder().bucket(config.path("bucket").asText()).object(object).build());
    }
  }
}
