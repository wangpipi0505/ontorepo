package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.JdbcSourceAdapter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 真实PG隔离schema；转换错误、授权顺序和证据都不能仅靠SQL字符串断言替代。 */
class TransformJdbcTest {
  final ObjectMapper json = new ObjectMapper();

  @Test
  void unicodeDecimalDigitsAndEmptyInHonorStrictJavaAndValidationSemantics() throws Exception {
    JsonNode env = json.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    String url =
        System.getenv()
            .getOrDefault("ONTO_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:15472/onto_test_p3");
    String schema = "c5tu_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        json.createObjectNode()
            .put("dialect", "postgresql")
            .put("url", url)
            .put("user", env.path("ONTO_DB_USER").asText())
            .put("password", env.path("ONTO_DB_PASSWORD").asText());
    try (Connection c =
            DriverManager.getConnection(
                url, config.path("user").asText(), config.path("password").asText());
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA " + schema);
      try {
        String table = schema + ".rows";
        st.execute(
            "CREATE TABLE "
                + table
                + "(id integer primary key,region text,raw text,small numeric)");
        st.execute("INSERT INTO " + table + " VALUES(1,'A','١٠',0.0000001)");
        var adapter = new JdbcSourceAdapter();
        var source = new SourceSpec("p", "RELATIONAL", config, Set.of(table));
        var number = new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL");
        assertEquals(new java.math.BigDecimal("10"), number.apply("١٠"));
        assertEquals(
            1,
            adapter
                .query(
                    source, query(table, "raw", number, QuerySpec.Operator.EQ, IntNode.valueOf(10)))
                .rows()
                .getFirst()
                .get(table + ".id"));
        for (var transform :
            List.of(
                new ValueTransform(ValueTransform.Kind.TO_STRING, Map.of(), "STRING"),
                new ValueTransform(
                    ValueTransform.Kind.CODEBOOK,
                    Map.of("1E-7", TextNode.valueOf("small")),
                    "STRING")))
          assertEquals(
              "TRANSFORM_SOURCE_TYPE_UNSUPPORTED",
              assertThrows(
                      DataProblem.class,
                      () ->
                          adapter.query(
                              source,
                              query(
                                  table,
                                  "small",
                                  transform,
                                  QuerySpec.Operator.EQ,
                                  TextNode.valueOf("small"))))
                  .code());
        st.execute("UPDATE " + table + " SET raw='bad'");
        var base = query(table, "raw", number, QuerySpec.Operator.EQ, IntNode.valueOf(10));
        var empty =
            new QuerySpec(
                table,
                base.projection(),
                List.of(
                    new QuerySpec.Filter(table + ".raw", QuerySpec.Operator.IN, List.of(), number)),
                List.of(),
                1,
                10000,
                Map.of(),
                base.scope(),
                base.guardBindings());
        assertEquals(
            "TRANSFORM_FAILED",
            assertThrows(DataProblem.class, () -> adapter.query(source, empty)).code());
        st.execute("UPDATE " + table + " SET raw='10'");
        assertTrue(adapter.query(source, empty).rows().isEmpty());
      } finally {
        st.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  QuerySpec query(
      String table,
      String column,
      ValueTransform transform,
      QuerySpec.Operator op,
      JsonNode value) {
    return new QuerySpec(
        table,
        List.of(table + ".id"),
        List.of(new QuerySpec.Filter(table + "." + column, op, List.of(value), transform)),
        List.of(),
        1,
        10000,
        Map.of(),
        LabelScope.parse(json.valueToTree(Map.of("attr", "region", "in", List.of("A")))),
        Map.of("region", table + ".region"));
  }

  @Test
  void postgresConvertsBeforeBusinessFilterAndLimitAfterAuthorization() throws Exception {
    JsonNode env = json.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    String url =
        System.getenv()
            .getOrDefault("ONTO_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:15472/onto_test_p3");
    String schema = "c5t_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        json.createObjectNode()
            .put("dialect", "postgresql")
            .put("url", url)
            .put("user", env.path("ONTO_DB_USER").asText())
            .put("password", env.path("ONTO_DB_PASSWORD").asText());
    try (Connection c =
            DriverManager.getConnection(
                url, config.path("user").asText(), config.path("password").asText());
        Statement st = c.createStatement()) {
      int serverMajor = c.getMetaData().getDatabaseMajorVersion();
      assertTrue(serverMajor >= 12, "转换计划要求PostgreSQL 12+物化CTE");
      System.out.println("TRANSFORM_POSTGRES_MAJOR=" + serverMajor);
      st.execute("CREATE SCHEMA " + schema);
      try {
        String table = schema + ".rows";
        st.execute(
            "CREATE TABLE "
                + table
                + "(id integer primary key,region text,amount text,enabled text,code text)");
        st.execute(
            "INSERT INTO "
                + table
                + " VALUES(1,'B','bad','bad','missing'),(2,'A','2','0','low'),(3,'A','10','TrUe','high')");
        var source = new SourceSpec("pg", "RELATIONAL", config, Set.of(table));
        var adapter = new JdbcSourceAdapter();
        var numeric = new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL");
        var result =
            assertDoesNotThrow(
                () ->
                    adapter.query(
                        source,
                        query(
                            table, "amount", numeric, QuerySpec.Operator.GT, IntNode.valueOf(5))));
        assertEquals(3, result.rows().getFirst().get(table + ".id"));
        assertEquals(3, result.evidence().getFirst().key().get("id"));
        var booleanTransform =
            new ValueTransform(ValueTransform.Kind.TO_BOOLEAN, Map.of(), "BOOLEAN");
        assertEquals(
            3,
            adapter
                .query(
                    source,
                    query(
                        table,
                        "enabled",
                        booleanTransform,
                        QuerySpec.Operator.EQ,
                        BooleanNode.TRUE))
                .rows()
                .getFirst()
                .get(table + ".id"));
        st.execute("UPDATE " + table + " SET enabled='1' WHERE id=3");
        assertEquals(
            3,
            adapter
                .query(
                    source,
                    query(
                        table,
                        "enabled",
                        booleanTransform,
                        QuerySpec.Operator.EQ,
                        BooleanNode.TRUE))
                .rows()
                .getFirst()
                .get(table + ".id"));
        var codebook =
            new ValueTransform(
                ValueTransform.Kind.CODEBOOK,
                Map.of("low", IntNode.valueOf(2), "high", IntNode.valueOf(10)),
                "INTEGER");
        assertEquals(
            3,
            adapter
                .query(
                    source,
                    query(table, "code", codebook, QuerySpec.Operator.GE, IntNode.valueOf(10)))
                .rows()
                .getFirst()
                .get(table + ".id"));
        var stringTransform = new ValueTransform(ValueTransform.Kind.TO_STRING, Map.of(), "STRING");
        assertEquals(
            3,
            adapter
                .query(
                    source,
                    query(
                        table, "id", stringTransform, QuerySpec.Operator.EQ, TextNode.valueOf("3")))
                .rows()
                .getFirst()
                .get(table + ".id"));
        var injection =
            new ValueTransform(
                ValueTransform.Kind.CODEBOOK,
                Map.of("high", TextNode.valueOf("x' OR 1=1 --"), "low", TextNode.valueOf("small")),
                "STRING");
        var injected =
            query(
                table, "code", injection, QuerySpec.Operator.EQ, TextNode.valueOf("x' OR 1=1 --"));
        var plan = new QueryPlanner().plan(source, injected, adapter.metadata(source));
        assertFalse(plan.sql().contains("OR 1=1"));
        assertTrue(plan.sql().contains("MATERIALIZED"));
        assertEquals(3, adapter.query(source, injected).rows().getFirst().get(table + ".id"));
        var multiple =
            new QuerySpec(
                table,
                List.of(table + ".id"),
                List.of(
                    new QuerySpec.Filter(
                        table + ".code",
                        QuerySpec.Operator.EQ,
                        List.of(TextNode.valueOf("x' OR 1=1 --")),
                        injection),
                    new QuerySpec.Filter(
                        table + ".amount",
                        QuerySpec.Operator.IN,
                        List.of(IntNode.valueOf(10)),
                        numeric)),
                List.of(),
                1,
                10000,
                Map.of(),
                injected.scope(),
                injected.guardBindings());
        assertEquals(3, adapter.query(source, multiple).rows().getFirst().get(table + ".id"));
        st.execute(
            "CREATE TABLE " + schema + ".algorithms(id integer primary key,region text,raw text)");
        st.execute("INSERT INTO " + schema + ".algorithms VALUES(10,'B','bad')");
        st.execute("ALTER TABLE " + table + " ADD COLUMN algo integer DEFAULT 10");
        String algorithms = schema + ".algorithms";
        var joined =
            new QuerySpec(
                table,
                List.of(table + ".id", algorithms + ".id"),
                List.of(
                    new QuerySpec.Filter(
                        algorithms + ".raw",
                        QuerySpec.Operator.GT,
                        List.of(IntNode.valueOf(5)),
                        numeric)),
                List.of(new QuerySpec.Join(algorithms, table + ".algo", algorithms + ".id")),
                1,
                10000,
                Map.of(),
                injected.scope(),
                Map.of(
                    "region", table + ".region", "region@" + algorithms, algorithms + ".region"));
        var joinedSource = new SourceSpec("pg", "RELATIONAL", config, Set.of(table, algorithms));
        assertTrue(adapter.query(joinedSource, joined).rows().isEmpty());
        st.execute("UPDATE " + algorithms + " SET region='A',raw='10'");
        var joinedResult = adapter.query(joinedSource, joined);
        assertEquals(10, joinedResult.rows().getFirst().get(algorithms + ".id"));
        assertEquals(2, joinedResult.evidence().size());
        assertEquals(10, joinedResult.evidence().get(1).key().get("id"));
        st.execute("ALTER TABLE " + table + " ADD COLUMN fractional real DEFAULT 2.0");
        assertEquals(
            "TRANSFORM_SOURCE_TYPE_UNSUPPORTED",
            assertThrows(
                    DataProblem.class,
                    () ->
                        adapter.query(
                            source,
                            query(
                                table,
                                "fractional",
                                stringTransform,
                                QuerySpec.Operator.EQ,
                                TextNode.valueOf("2.0"))))
                .code());
        assertEquals(
            "INVALID_IDENTIFIER",
            assertThrows(
                    DataProblem.class,
                    () ->
                        adapter.query(
                            source,
                            query(
                                table,
                                "amount;DROP",
                                numeric,
                                QuerySpec.Operator.EQ,
                                IntNode.valueOf(1))))
                .code());
        st.execute("UPDATE " + table + " SET amount='bad' WHERE id=2");
        assertEquals(
            "TRANSFORM_FAILED",
            assertThrows(
                    DataProblem.class,
                    () ->
                        adapter.query(
                            source,
                            query(
                                table,
                                "amount",
                                numeric,
                                QuerySpec.Operator.GT,
                                IntNode.valueOf(5))))
                .code());
        st.execute("UPDATE " + table + " SET amount='' WHERE id=2");
        assertEquals(
            "TRANSFORM_FAILED",
            assertThrows(
                    DataProblem.class,
                    () ->
                        adapter.query(
                            source,
                            query(
                                table,
                                "amount",
                                numeric,
                                QuerySpec.Operator.GT,
                                IntNode.valueOf(5))))
                .code());
        var deniedBase = query(table, "amount", numeric, QuerySpec.Operator.GT, IntNode.valueOf(5));
        var denied =
            new QuerySpec(
                table,
                deniedBase.projection(),
                deniedBase.filters(),
                List.of(),
                1,
                10000,
                Map.of(),
                new LabelScope(Map.of("region", Set.of())),
                deniedBase.guardBindings());
        assertTrue(adapter.query(source, denied).rows().isEmpty());
        st.execute("UPDATE " + table + " SET amount=null WHERE id=2");
        assertEquals(
            3,
            adapter
                .query(
                    source,
                    query(table, "amount", numeric, QuerySpec.Operator.GT, IntNode.valueOf(5)))
                .rows()
                .getFirst()
                .get(table + ".id"));
        st.execute("UPDATE " + table + " SET code='missing' WHERE id=2");
        assertEquals(
            "TRANSFORM_FAILED",
            assertThrows(
                    DataProblem.class,
                    () ->
                        adapter.query(
                            source,
                            query(
                                table,
                                "code",
                                codebook,
                                QuerySpec.Operator.EQ,
                                IntNode.valueOf(10))))
                .code());
      } finally {
        st.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
