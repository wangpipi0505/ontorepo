package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class TransformProtocolBoundaryTest {
  @Test
  void typedIdentityFiltersAreRejectedConsistentlyBeforeAnyConnection() {
    var json = new ObjectMapper();
    for (var pair :
        Map.of(
                "RELATIONAL",
                "postgresql",
                "WAREHOUSE",
                "clickhouse",
                "DOCUMENT",
                "mongodb",
                "WIDE_COLUMN",
                "cassandra",
                "GRAPH",
                "neo4j",
                "KEY_VALUE",
                "redis",
                "FILE",
                "json",
                "HTTP",
                "json",
                "LAKE",
                "s3-parquet")
            .entrySet()) {
      var source =
          new SourceSpec(
              "s",
              pair.getKey(),
              json.createObjectNode().put("dialect", pair.getValue()),
              Set.of("items"));
      var filter =
          new QuerySpec.Filter(
              "amount",
              QuerySpec.Operator.EQ,
              List.of(json.valueToTree(10)),
              new ValueTransform(ValueTransform.Kind.IDENTITY, Map.of(), "INTEGER"));
      var query =
          new QuerySpec(
              "items",
              List.of("id"),
              List.of(filter),
              List.of(),
              1,
              1000,
              Map.of(),
              LabelScope.unrestricted(),
              Map.of("region", "region"));
      assertEquals(
          "INVALID_TRANSFORM",
          assertThrows(
                  DataProblem.class,
                  () -> new AdapterRegistry().adapter(source).query(source, query))
              .code(),
          pair.getKey());
    }
  }

  @Test
  void clickHousePlainIdentityKeepsExistingSqlPlan() {
    var json = new ObjectMapper();
    var source =
        new SourceSpec(
            "ch",
            "WAREHOUSE",
            json.createObjectNode().put("dialect", "clickhouse"),
            Set.of("public.rows"));
    var query =
        new QuerySpec(
            "public.rows",
            List.of("public.rows.id"),
            List.of(
                new QuerySpec.Filter(
                    "public.rows.id", QuerySpec.Operator.EQ, List.of(json.valueToTree(1)))),
            List.of(),
            1,
            1000,
            Map.of(),
            LabelScope.unrestricted(),
            Map.of("region", "public.rows.region"));
    var metadata =
        new SourceMetadata(
            List.of(
                new SourceMetadata.Resource(
                    "public.rows",
                    List.of(
                        new SourceMetadata.Field("id", "Int32", false),
                        new SourceMetadata.Field("region", "String", false)),
                    List.of("id"),
                    List.of(),
                    List.of())));
    assertFalse(new QueryPlanner().plan(source, query, metadata).sql().contains("MATERIALIZED"));
  }

  @Test
  void unsupportedNativeTransformsFailBeforeConnection() {
    var json = new ObjectMapper();
    for (var pair :
        Map.of(
                "WAREHOUSE",
                "clickhouse",
                "DOCUMENT",
                "mongodb",
                "WIDE_COLUMN",
                "cassandra",
                "GRAPH",
                "neo4j")
            .entrySet()) {
      // 只有dialect而无任何地址：必须先报告能力边界，不能访问网络或先解密连接参数。
      var source =
          new SourceSpec(
              "s",
              pair.getKey(),
              json.createObjectNode().put("dialect", pair.getValue()),
              Set.of("items"));
      var query =
          new QuerySpec(
              "items",
              List.of("id"),
              List.of(
                  new QuerySpec.Filter(
                      "amount",
                      QuerySpec.Operator.EQ,
                      List.of(json.valueToTree(10)),
                      new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL"))),
              List.of(),
              1,
              1000,
              Map.of(),
              LabelScope.unrestricted(),
              Map.of("region", "region"));
      assertEquals(
          "TRANSFORM_FILTER_UNSUPPORTED",
          assertThrows(
                  DataProblem.class,
                  () -> new AdapterRegistry().adapter(source).query(source, query))
              .code(),
          pair.getKey());
    }
  }
}
