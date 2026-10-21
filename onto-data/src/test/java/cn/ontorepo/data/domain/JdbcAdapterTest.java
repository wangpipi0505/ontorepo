package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.JdbcSourceAdapter;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JdbcAdapterTest {
  @Test
  void realPostgresAppliesScopeBeforeLimitAndReturnsKeys() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode env = mapper.readTree(Files.readString(Path.of("../.runtime/local-env.json")));
    String url = env.path("ONTO_DB_URL").asText();
    // 本工程唯一测试 schema；不读取或清理业务表。
    String schema = "c5_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        mapper
            .createObjectNode()
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
        st.execute(
            "CREATE TABLE "
                + schema
                + ".tasks(id integer primary key, region text, amount integer)");
        st.execute(
            "INSERT INTO " + schema + ".tasks VALUES(1,'B',5),(2,null,6),(3,'',7),(4,'A',8)");
        var source = new SourceSpec("pg", "RELATIONAL", config, Set.of(schema + ".tasks"));
        var adapter = new JdbcSourceAdapter();
        assertEquals(ProbeResult.Status.PASSED, adapter.probe(source).stages().get(2).status());
        assertEquals(1, adapter.metadata(source).resources().size());
        assertEquals(
            ProbeResult.Status.FAILED,
            adapter
                .probe(
                    new SourceSpec(
                        "pg", "RELATIONAL", config, Set.of(schema + ".tasks", schema + ".missing")))
                .stages()
                .get(2)
                .status());
        var q =
            new QuerySpec(
                schema + ".tasks",
                List.of(schema + ".tasks.id", schema + ".tasks.amount"),
                List.of(),
                List.of(),
                1,
                10000,
                Map.of(),
                LabelScope.parse(mapper.readTree("{\"attr\":\"region\",\"in\":[\"A\"]}")),
                Map.of("region", schema + ".tasks.region"));
        var result = adapter.query(source, q);
        assertEquals(1, result.rows().size());
        assertEquals(4, result.rows().getFirst().get(schema + ".tasks.id"));
        assertEquals(4, result.evidence().getFirst().key().get("id"));
        assertInstanceOf(Integer.class, result.rows().getFirst().get(schema + ".tasks.amount"));
        st.execute("CREATE TABLE " + schema + ".algorithms(id integer primary key,region text)");
        st.execute("INSERT INTO " + schema + ".algorithms VALUES(10,'A'),(11,'B'),(12,null)");
        st.execute(
            "ALTER TABLE "
                + schema
                + ".tasks ADD COLUMN algo integer REFERENCES "
                + schema
                + ".algorithms(id) DEFAULT 10");
        var joinedSource =
            new SourceSpec(
                "pg", "RELATIONAL", config, Set.of(schema + ".tasks", schema + ".algorithms"));
        var joined =
            new QuerySpec(
                schema + ".tasks",
                List.of(schema + ".tasks.id", schema + ".algorithms.id"),
                List.of(),
                List.of(
                    new QuerySpec.Join(
                        schema + ".algorithms", schema + ".tasks.algo", schema + ".algorithms.id")),
                1,
                10000,
                Map.of(),
                q.scope(),
                Map.of(
                    "region",
                    schema + ".tasks.region",
                    "region@" + schema + ".algorithms",
                    schema + ".algorithms.region"));
        var joinResult = adapter.query(joinedSource, joined);
        assertEquals(10, joinResult.rows().getFirst().get(schema + ".algorithms.id"));
        assertEquals(2, joinResult.evidence().size());
        assertEquals(4, joinResult.evidence().getFirst().key().get("id"));
        assertEquals(10, joinResult.evidence().get(1).key().get("id"));
        st.execute("UPDATE " + schema + ".algorithms SET region=null WHERE id=10");
        assertTrue(adapter.query(joinedSource, joined).rows().isEmpty());
        var invalid =
            new SourceSpec(
                "pg",
                "RELATIONAL",
                config.deepCopy().put("password", "secret-not-in-error"),
                Set.of(schema + ".tasks"));
        assertFalse(adapter.probe(invalid).toString().contains("secret-not-in-error"));
      } finally {
        st.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
