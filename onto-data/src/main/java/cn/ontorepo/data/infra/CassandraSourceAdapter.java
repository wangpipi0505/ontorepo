package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.config.*;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;

/** CQL 仅完整分区键 EQ；守卫也必须绑定已确定的分区键，禁止 ALLOW FILTERING。 */
public final class CassandraSourceAdapter extends AdapterSupport {
  record TableShape(
      Map<String, String> columns,
      List<String> partition,
      List<String> primary,
      List<SourceMetadata.Index> indexes) {}

  @Override
  public Set<String> capabilities() {
    return Set.of(
        "TABLE_METADATA",
        "PARTITION_KEY_EQ_ONLY",
        "GUARD_PARTITION_KEY_ONLY",
        "NO_ALLOW_FILTERING",
        "NO_JOIN");
  }

  CqlSession open(SourceSpec s) {
    var b =
        CqlSession.builder()
            .withLocalDatacenter(text(s, "datacenter"))
            .withConfigLoader(
                DriverConfigLoader.programmaticBuilder()
                    .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
                    .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
                    .withDuration(
                        DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(2))
                    .build());
    for (var host : s.connection().path("contactPoints"))
      b.addContactPoint(new InetSocketAddress(host.asText(), number(s, "port", 9042)));
    String user = s.connection().path("user").asText("");
    if (!user.isEmpty()) b.withAuthCredentials(user, s.connection().path("password").asText(""));
    return b.build();
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    var hosts = s.connection().path("contactPoints");
    if (!hosts.isArray() || hosts.isEmpty()) throw QueryPlanner.problem("INVALID_CONNECTION");
    socket(hosts.get(0).asText(), number(s, "port", 9042));
  }

  @Override
  void authenticate(SourceSpec s) {
    try (CqlSession c = open(s)) {
      c.execute("SELECT release_version FROM system.local");
    }
  }

  TableShape table(CqlSession c, SourceSpec s, String r) {
    exact(s, r);
    QueryPlanner.identifier(r);
    String[] parts = r.split("\\.");
    if (parts.length != 2 || !parts[0].equals(text(s, "keyspace")))
      throw QueryPlanner.problem("SCOPE_EXCEEDED");
    // 关闭驱动全量Schema刷新，只读取已批准keyspace/table的系统元数据。
    Map<String, String> columns = new LinkedHashMap<>();
    Map<Integer, String> partition = new TreeMap<>(), clustering = new TreeMap<>();
    var rows =
        c.execute(
            SimpleStatement.newInstance(
                    "SELECT column_name,type,kind,position FROM system_schema.columns WHERE"
                        + " keyspace_name=? AND table_name=?",
                    parts[0],
                    parts[1])
                .setTimeout(Duration.ofSeconds(3)));
    for (var row : rows) {
      String name = row.getString("column_name"),
          type = row.getString("type"),
          kind = row.getString("kind");
      columns.put(name, type);
      if ("partition_key".equals(kind)) partition.put(row.getInt("position"), name);
      if ("clustering".equals(kind)) clustering.put(row.getInt("position"), name);
    }
    if (columns.isEmpty()) throw QueryPlanner.problem("RESOURCE_NOT_FOUND");
    List<String> primary = new ArrayList<>(partition.values());
    primary.addAll(clustering.values());
    List<SourceMetadata.Index> indexes = new ArrayList<>();
    var indexRows =
        c.execute(
            SimpleStatement.newInstance(
                    "SELECT index_name,options FROM system_schema.indexes WHERE keyspace_name=? AND"
                        + " table_name=?",
                    parts[0],
                    parts[1])
                .setTimeout(Duration.ofSeconds(3)));
    for (var row : indexRows) {
      String target = row.getMap("options", String.class, String.class).get("target");
      indexes.add(
          new SourceMetadata.Index(
              row.getString("index_name"), target == null ? List.of() : List.of(target), false));
    }
    return new TableShape(columns, new ArrayList<>(partition.values()), primary, indexes);
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) {
    try (CqlSession c = open(s)) {
      List<SourceMetadata.Resource> out = new ArrayList<>();
      for (String r : s.accessScope()) {
        var t = table(c, s, r);
        List<SourceMetadata.Field> fs =
            t.columns().entrySet().stream()
                .map(
                    f ->
                        new SourceMetadata.Field(
                            f.getKey(), f.getValue(), !t.primary().contains(f.getKey())))
                .toList();
        List<SourceMetadata.Index> indexes = t.indexes();
        out.add(new SourceMetadata.Resource(r, fs, t.primary(), List.of(), indexes));
      }
      return new SourceMetadata(out);
    }
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) {
    flat(q);
    exact(s, q.resource());
    QueryPlanner.identifier(q.resource());
    try (CqlSession c = open(s)) {
      var table = table(c, s, q.resource());
      Set<String> all = new HashSet<>();
      all.addAll(table.columns().keySet());
      fields(q, all);
      Set<String> partition = new LinkedHashSet<>();
      partition.addAll(table.partition());
      Map<String, com.fasterxml.jackson.databind.JsonNode> equal = new LinkedHashMap<>();
      for (var f : q.filters()) {
        if (f.operator() != QuerySpec.Operator.EQ
            || !partition.contains(f.column())
            || equal.putIfAbsent(f.column(), f.values().getFirst()) != null)
          throw QueryPlanner.problem("CASSANDRA_PARTITION_EQ_REQUIRED");
      }
      if (!equal.keySet().equals(partition))
        throw QueryPlanner.problem("CASSANDRA_PARTITION_EQ_REQUIRED");
      for (var guard : q.guardBindings().entrySet()) {
        if (!partition.contains(guard.getValue()))
          throw QueryPlanner.problem("CASSANDRA_GUARD_PARTITION_REQUIRED");
        if (!q.scope().allows(guard.getKey(), equal.get(guard.getValue())))
          return new QueryResult(List.of(), List.of(), List.of());
      }
      LinkedHashSet<String> projection = new LinkedHashSet<>(q.projection());
      projection.addAll(table.primary());
      String select =
          projection.stream()
              .map(QueryPlanner::quote)
              .collect(java.util.stream.Collectors.joining(","));
      String where =
          partition.stream()
              .map(k -> QueryPlanner.quote(k) + "=?")
              .collect(java.util.stream.Collectors.joining(" AND "));
      var prepared =
          c.prepare(
              "SELECT "
                  + select
                  + " FROM "
                  + QueryPlanner.quote(q.resource())
                  + " WHERE "
                  + where
                  + " LIMIT "
                  + q.limit());
      Object[] values =
          partition.stream()
              .map(
                  k -> {
                    var n = equal.get(k);
                    var type = table.columns().get(k);
                    return switch (type) {
                      case "int" -> {
                        if (!n.isIntegralNumber() || !n.canConvertToInt())
                          throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
                        yield n.intValue();
                      }
                      case "bigint" -> {
                        if (!n.isIntegralNumber() || !n.canConvertToLong())
                          throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
                        yield n.longValue();
                      }
                      case "uuid" -> {
                        if (!n.isTextual()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
                        yield UUID.fromString(n.textValue());
                      }
                      case "boolean" -> {
                        if (!n.isBoolean()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
                        yield n.booleanValue();
                      }
                      case "text", "varchar", "ascii" -> {
                        if (!n.isTextual()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
                        yield n.textValue();
                      }
                      default -> throw QueryPlanner.problem("CASSANDRA_PARTITION_TYPE_UNSUPPORTED");
                    };
                  })
              .toArray();
      var result =
          c.execute(
              prepared
                  .bind(values)
                  .setTimeout(Duration.ofMillis(q.timeoutMs()))
                  .setPageSize(q.limit()));
      List<Map<String, Object>> out = new ArrayList<>();
      List<QueryResult.SourceRecord> evidence = new ArrayList<>();
      for (var row : result) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String p : q.projection()) data.put(p, row.getObject(p));
        out.add(data);
        Map<String, Object> key = new LinkedHashMap<>();
        table.primary().forEach(k -> key.put(k, row.getObject(k)));
        evidence.add(new QueryResult.SourceRecord(s.id(), q.resource(), key));
        if (out.size() >= q.limit()) break;
      }
      return new QueryResult(inferred(q.resource(), out).fields(), out, evidence);
    }
  }
}
