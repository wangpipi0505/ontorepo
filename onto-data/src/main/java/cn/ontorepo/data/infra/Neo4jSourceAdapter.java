package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import java.time.Duration;
import java.util.*;
import org.neo4j.driver.*;

/** 标签严格标识符；只读会话执行自动生成的节点查询，禁止用户 Cypher。 */
public final class Neo4jSourceAdapter extends AdapterSupport {
  record GraphPlan(String cypher, Map<String, Object> parameters) {}

  GraphPlan plan(SourceSpec s, QuerySpec q) {
    flat(q, Set.of("relationship", "targetLabel"));
    if (!q.options().keySet().equals(Set.of("relationship", "targetLabel")))
      throw QueryPlanner.problem("INVALID_GRAPH_RELATIONSHIP");
    String source = label(s, q.resource()),
        target = label(s, q.options().get("targetLabel").asText()),
        type = q.options().get("relationship").asText();
    QueryPlanner.identifier(type);
    if (type.contains(".")) throw QueryPlanner.problem("INVALID_IDENTIFIER");
    List<String> where = new ArrayList<>();
    Map<String, Object> parameters = new LinkedHashMap<>();
    for (var b : q.guardBindings().entrySet())
      for (String alias : List.of("n", "m")) {
        String column = property(b.getValue()).replace("n.", alias + ".");
        where.add(column + " IS NOT NULL AND trim(toString(" + column + ")) <> ''");
        var values = q.scope().parameters().get(b.getKey());
        if (values != null) {
          String p = "p" + parameters.size();
          parameters.put(p, values.stream().map(this::nativeValue).toList());
          where.add(column + " IN $" + p);
        }
      }
    if (!q.filters().isEmpty()) throw QueryPlanner.problem("GRAPH_EDGE_FILTER_UNSUPPORTED");
    List<String> projection = new ArrayList<>();
    for (String p : q.projection())
      projection.add(
          switch (p) {
            case "_targetId" -> "elementId(m) AS `_targetId`";
            case "_relationshipId" -> "elementId(r) AS `_relationshipId`";
            default -> property(p) + " AS `" + p + "`";
          });
    parameters.put("limit", q.limit());
    return new GraphPlan(
        "MATCH (n:"
            + source
            + ")-[r:`"
            + type
            + "`]->(m:"
            + target
            + ") WHERE "
            + String.join(" AND ", where)
            + " RETURN "
            + String.join(",", projection)
            + ",elementId(n) AS __key,elementId(m) AS __target,elementId(r) AS __relation LIMIT"
            + " $limit",
        parameters);
  }

  @Override
  public Set<String> capabilities() {
    return Set.of(
        "NODE_LABEL_QUERY",
        "SINGLE_HOP_RELATIONSHIP",
        "PARAMETERIZED_FILTER",
        "READ_TRANSACTION",
        "SAMPLED_PROPERTIES",
        "ELEMENT_ID_EVIDENCE",
        "NO_JOIN");
  }

  Object nativeValue(com.fasterxml.jackson.databind.JsonNode n) {
    if (n.isIntegralNumber()) {
      if (!n.canConvertToLong()) throw QueryPlanner.problem("GRAPH_NUMBER_RANGE");
      return n.longValue();
    }
    if (n.isNumber()) {
      double v = n.doubleValue();
      if (!Double.isFinite(v) || java.math.BigDecimal.valueOf(v).compareTo(n.decimalValue()) != 0)
        throw QueryPlanner.problem("GRAPH_NUMBER_PRECISION");
      return v;
    }
    return QueryPlanner.scalar(n);
  }

  QueryResult executeRelationship(SourceSpec s, QuerySpec q) {
    var plan = plan(s, q);
    try (Driver d = open(s);
        Session session = session(d, s)) {
      return session.executeRead(
          tx -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            List<QueryResult.SourceRecord> evidence = new ArrayList<>();
            var cursor = tx.run(plan.cypher(), plan.parameters());
            while (cursor.hasNext() && rows.size() < q.limit()) {
              var record = cursor.next();
              Map<String, Object> row = new LinkedHashMap<>();
              q.projection().forEach(p -> row.put(p, record.get(p).asObject()));
              rows.add(row);
              evidence.add(
                  new QueryResult.SourceRecord(
                      s.id(),
                      q.resource(),
                      Map.of(
                          "elementId",
                          record.get("__key").asString(),
                          "targetElementId",
                          record.get("__target").asString(),
                          "relationshipElementId",
                          record.get("__relation").asString())));
            }
            return new QueryResult(inferred(q.resource(), rows).fields(), rows, evidence);
          },
          TransactionConfig.builder().withTimeout(Duration.ofMillis(q.timeoutMs())).build());
    }
  }

  Driver open(SourceSpec s) {
    return GraphDatabase.driver(
        text(s, "uri"),
        AuthTokens.basic(text(s, "user"), s.connection().path("password").asText("")),
        Config.builder()
            .withConnectionTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .withConnectionAcquisitionTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .withMaxConnectionPoolSize(2)
            .build());
  }

  Session session(Driver d, SourceSpec s) {
    return d.session(
        SessionConfig.builder()
            .withDatabase(text(s, "database"))
            .withDefaultAccessMode(AccessMode.READ)
            .build());
  }

  String label(SourceSpec s, String r) {
    exact(s, r);
    QueryPlanner.identifier(r);
    if (r.contains(".")) throw QueryPlanner.problem("INVALID_IDENTIFIER");
    return "`" + r + "`";
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    uriSocket(text(s, "uri"), 7687);
  }

  @Override
  void authenticate(SourceSpec s) {
    try (Driver d = open(s)) {
      d.verifyConnectivity();
    }
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) {
    try (Driver d = open(s);
        Session session = session(d, s)) {
      List<SourceMetadata.Resource> out = new ArrayList<>();
      for (String r : s.accessScope()) {
        String label = label(s, r);
        Map<String, Object> row =
            session.executeRead(
                tx -> {
                  var result = tx.run("MATCH (n:" + label + ") RETURN properties(n) AS p LIMIT 1");
                  if (!result.hasNext()) throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
                  return result.next().get("p").asMap();
                },
                TransactionConfig.builder().withTimeout(Duration.ofSeconds(3)).build());
        var inf = inferred(r, List.of(row));
        out.add(
            new SourceMetadata.Resource(
                r, inf.fields(), List.of("elementId"), List.of(), List.of()));
      }
      return new SourceMetadata(out);
    }
  }

  String property(String field) {
    QueryPlanner.identifier(field);
    if (field.contains(".")) throw QueryPlanner.problem("INVALID_IDENTIFIER");
    return "n.`" + field + "`";
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) {
    if (!q.options().isEmpty()) return executeRelationship(s, q);
    flat(q);
    String label = label(s, q.resource());
    List<String> predicates = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    for (var b : q.guardBindings().entrySet()) {
      String c = property(b.getValue());
      predicates.add(c + " IS NOT NULL AND trim(toString(" + c + ")) <> ''");
      var allowed = q.scope().parameters().get(b.getKey());
      if (allowed != null) {
        String p = "p" + params.size();
        params.put(p, allowed.stream().map(this::nativeValue).toList());
        predicates.add(c + " IN $" + p);
      }
    }
    for (var f : q.filters()) {
      String p = "p" + params.size();
      Object value =
          f.operator() == QuerySpec.Operator.IN
              ? f.values().stream().map(this::nativeValue).toList()
              : nativeValue(f.values().getFirst());
      if (value instanceof java.math.BigDecimal decimal) value = decimal.doubleValue();
      params.put(p, value);
      predicates.add(
          property(f.column())
              + " "
              + switch (f.operator()) {
                case EQ -> "=";
                case IN -> "IN";
                case GT -> ">";
                case GE -> ">=";
                case LT -> "<";
                case LE -> "<=";
              }
              + " $"
              + p);
    }
    params.put("limit", q.limit());
    String projection =
        q.projection().stream()
            .map(p -> property(p) + " AS `" + p + "`")
            .collect(java.util.stream.Collectors.joining(","));
    String cypher =
        "MATCH (n:"
            + label
            + ") WHERE "
            + String.join(" AND ", predicates)
            + " RETURN "
            + projection
            + ", elementId(n) AS __key LIMIT $limit";
    try (Driver d = open(s);
        Session session = session(d, s)) {
      return session.executeRead(
          tx -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            List<QueryResult.SourceRecord> evidence = new ArrayList<>();
            var cursor = tx.run(cypher, params);
            while (cursor.hasNext() && rows.size() < q.limit()) {
              var record = cursor.next();
              Map<String, Object> row = new LinkedHashMap<>();
              q.projection().forEach(p -> row.put(p, record.get(p).asObject()));
              rows.add(row);
              evidence.add(
                  new QueryResult.SourceRecord(
                      s.id(), q.resource(), Map.of("elementId", record.get("__key").asString())));
            }
            return new QueryResult(inferred(q.resource(), rows).fields(), rows, evidence);
          },
          TransactionConfig.builder().withTimeout(Duration.ofMillis(q.timeoutMs())).build());
    }
  }
}
