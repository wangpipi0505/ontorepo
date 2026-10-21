package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import java.util.*;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/** Redis 仅白名单前缀内 STRING(JSON对象) 键；扫描有步数上限，无关系连接。 */
public final class RedisSourceAdapter extends AdapterSupport {
  @Override
  public Set<String> capabilities() {
    return Set.of(
        "JSON_STRING_KEY", "PREFIX_SCAN", "MAX_1000_SCAN_STEPS", "NO_JOIN", "KEY_EVIDENCE");
  }

  Jedis open(SourceSpec s) {
    Jedis j = new Jedis(text(s, "host"), number(s, "port", 6379), 2000, 10000);
    String pass = s.connection().path("password").asText("");
    if (!pass.isEmpty()) j.auth(pass);
    j.select(number(s, "database", 0));
    return j;
  }

  void prefix(SourceSpec s, String r) {
    exact(s, r);
    if (!r.matches("[A-Za-z0-9:_-]{1,200}")) throw QueryPlanner.problem("INVALID_KEY_PREFIX");
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    socket(text(s, "host"), number(s, "port", 6379));
  }

  @Override
  void authenticate(SourceSpec s) {
    try (Jedis j = open(s)) {
      j.ping();
    }
  }

  Map<String, Object> row(Jedis j, String key) throws Exception {
    if (!j.type(key).equals("string")) throw QueryPlanner.problem("REDIS_VALUE_TYPE_UNSUPPORTED");
    String value = j.get(key);
    if (value == null) return null;
    if (value.length() > MAX_BYTES) throw QueryPlanner.problem("RESPONSE_TOO_LARGE");
    var n = JSON.readTree(value);
    if (!n.isObject()) throw QueryPlanner.problem("REDIS_JSON_OBJECT_REQUIRED");
    Map<String, Object> row =
        JSON.convertValue(
            n, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    row.put("_key", key);
    return row;
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) throws Exception {
    try (Jedis j = open(s)) {
      List<SourceMetadata.Resource> out = new ArrayList<>();
      for (String r : s.accessScope()) {
        prefix(s, r);
        String cursor = "0";
        boolean found = false;
        for (int step = 0; step < 1000; step++) {
          var scan = j.scan(cursor, new ScanParams().match(r + "*").count(100));
          cursor = scan.getCursor();
          for (String key : scan.getResult()) {
            if (!key.startsWith(r)) throw QueryPlanner.problem("SCOPE_EXCEEDED");
            var row = row(j, key);
            if (row != null) {
              var inferred = inferred(r, List.of(row));
              out.add(
                  new SourceMetadata.Resource(
                      r, inferred.fields(), List.of("_key"), List.of(), List.of()));
              found = true;
              break;
            }
          }
          if (found || cursor.equals("0")) break;
          if (step == 999) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
        }
        if (!found) throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
      }
      return new SourceMetadata(out);
    }
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) throws Exception {
    flat(q);
    boolean transformed = QueryPlanner.transformed(q);
    prefix(s, q.resource());
    long started = System.nanoTime();
    List<Map<String, Object>> out = new ArrayList<>();
    List<QueryResult.SourceRecord> evidence = new ArrayList<>();
    String exact = null;
    for (var f : q.filters())
      if (f.column().equals("_key") && f.operator() == QuerySpec.Operator.EQ && !transformed) {
        exact = f.values().getFirst().asText();
        if (!exact.startsWith(q.resource())) throw QueryPlanner.problem("SCOPE_EXCEEDED");
      }
    try (Jedis j = open(s)) {
      String cursor = "0";
      int scanned = 0;
      for (int step = 0; step < 1000; step++) {
        deadline(started, q);
        ScanResult<String> scan =
            exact == null
                ? j.scan(cursor, new ScanParams().match(q.resource() + "*").count(100))
                : new ScanResult<>("0", List.of(exact));
        cursor = scan.getCursor();
        for (String key : scan.getResult()) {
          deadline(started, q);
          if (++scanned > MAX_SCAN) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
          if (!key.startsWith(q.resource())) throw QueryPlanner.problem("SCOPE_EXCEEDED");
          if (!j.exists(key)) continue;
          var row = row(j, key);
          if (row != null) {
            fields(q, row.keySet());
            if (matches(q, row) && out.size() < q.limit()) {
              out.add(project(q, row));
              evidence.add(new QueryResult.SourceRecord(s.id(), q.resource(), Map.of("key", key)));
              if (out.size() >= q.limit() && !transformed)
                return new QueryResult(inferred(q.resource(), out).fields(), out, evidence);
            }
          }
        }
        if (cursor.equals("0"))
          return new QueryResult(inferred(q.resource(), out).fields(), out, evidence);
      }
      throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
    }
  }
}
