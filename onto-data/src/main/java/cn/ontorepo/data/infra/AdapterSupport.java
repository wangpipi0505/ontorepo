package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.util.*;

/** 协议失败统一脱敏；没有权限或没有完成的阶段不能报告通过。 */
abstract class AdapterSupport implements SourceAdapter {
  static final ObjectMapper JSON = new ObjectMapper();
  static final int MAX_BYTES = 16 * 1024 * 1024, MAX_SCAN = 100000;

  abstract void connectivity(SourceSpec s) throws Exception;

  abstract void authenticate(SourceSpec s) throws Exception;

  abstract SourceMetadata readMetadata(SourceSpec s) throws Exception;

  abstract QueryResult execute(SourceSpec s, QuerySpec q) throws Exception;

  void verifyScope(SourceSpec s, SourceMetadata m) {
    if (!m.resources().stream()
        .map(SourceMetadata.Resource::name)
        .collect(java.util.stream.Collectors.toSet())
        .containsAll(s.accessScope())) throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
  }

  @Override
  public final ProbeResult probe(SourceSpec s) {
    int phase = 0;
    try {
      connectivity(s);
      phase = 1;
      authenticate(s);
      phase = 2;
      if (s.accessScope().isEmpty()) throw QueryPlanner.problem("SCOPE_EXCEEDED");
      SourceMetadata m = readMetadata(s);
      if (m.resources().isEmpty()) throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
      verifyScope(s, m);
      return ProbeResult.passed();
    } catch (Exception e) {
      return ProbeResult.failed(
          phase,
          e instanceof DataProblem p
              ? p.code()
              : phase == 0
                  ? "CONNECTIVITY_FAILED"
                  : phase == 1 ? "AUTHENTICATION_FAILED" : "SCOPE_UNAVAILABLE");
    }
  }

  @Override
  public final SourceMetadata metadata(SourceSpec s) {
    try {
      if (s.accessScope().isEmpty()) return new SourceMetadata(List.of());
      return readMetadata(s);
    } catch (Exception e) {
      throw safe(e);
    }
  }

  @Override
  public final QueryResult query(SourceSpec s, QuerySpec q) {
    boolean transformed = QueryPlanner.transformed(q);
    // 运行期兜底必须在任何协议元数据或连接访问前完成。
    if (q.guardBindings().isEmpty()
        || !q.guardBindings().keySet().stream()
            .map(QueryPlanner::guardAttribute)
            .collect(java.util.stream.Collectors.toSet())
            .containsAll(q.scope().clauses().keySet()))
      throw QueryPlanner.problem("GUARD_ATTR_UNRESOLVED");
    q.guardBindings().values().forEach(QueryPlanner::identifier);
    // 原生下推协议尚无等价转换模板；连接前明确拒绝，绝不能继续按源原值比较。
    if (Set.of("WAREHOUSE", "DOCUMENT", "WIDE_COLUMN", "GRAPH").contains(s.kind()) && transformed)
      throw QueryPlanner.problem("TRANSFORM_FILTER_UNSUPPORTED");
    try {
      return execute(s, q);
    } catch (Exception e) {
      if (e instanceof java.sql.SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().startsWith("22")
          && transformed) throw QueryPlanner.problem("TRANSFORM_FAILED");
      throw safe(e);
    }
  }

  static DataProblem safe(Exception e) {
    return e instanceof DataProblem p ? p : QueryPlanner.problem("SOURCE_OPERATION_FAILED");
  }

  static String text(SourceSpec s, String key) {
    JsonNode n = s.connection().get(key);
    if (n == null || !n.isTextual() || n.textValue().isBlank())
      throw QueryPlanner.problem("INVALID_CONNECTION");
    return n.textValue();
  }

  static int number(SourceSpec s, String key, int fallback) {
    return s.connection().path(key).asInt(fallback);
  }

  static void socket(String host, int port) throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 2000);
    }
  }

  static void uriSocket(String value, int fallback) throws Exception {
    URI u = URI.create(value);
    if (u.getHost() == null) throw QueryPlanner.problem("INVALID_CONNECTION");
    socket(u.getHost(), u.getPort() < 0 ? fallback : u.getPort());
  }

  static void exact(SourceSpec s, String r) {
    if (!s.accessScope().contains(r)) throw QueryPlanner.problem("SCOPE_EXCEEDED");
  }

  static void flat(QuerySpec q) {
    flat(q, Set.of());
  }

  static void flat(QuerySpec q, Set<String> options) {
    if (!q.joins().isEmpty()) throw QueryPlanner.problem("JOIN_UNSUPPORTED");
    if (!options.containsAll(q.options().keySet()))
      throw QueryPlanner.problem("UNSUPPORTED_OPTION");
    if (q.guardBindings().isEmpty()
        || !q.guardBindings().keySet().containsAll(q.scope().clauses().keySet()))
      throw QueryPlanner.problem("GUARD_ATTR_UNRESOLVED");
    q.projection().forEach(QueryPlanner::identifier);
    q.guardBindings().values().forEach(QueryPlanner::identifier);
    for (var f : q.filters()) {
      QueryPlanner.identifier(f.column());
      if (f.operator() == null
          || f.values().size() > 1000
          || f.operator() != QuerySpec.Operator.IN && f.values().size() != 1)
        throw QueryPlanner.problem("INVALID_FILTER");
      f.values().forEach(QueryPlanner::scalar);
    }
  }

  static boolean matches(QuerySpec q, Map<String, Object> row) {
    for (var b : q.guardBindings().entrySet())
      if (!q.scope().allows(b.getKey(), JSON.valueToTree(row.get(b.getValue())))) return false;
    // 全部转换先完成，再允许业务谓词短路；否则第一项不匹配会隐藏第二项的授权坏值。
    List<JsonNode> actuals =
        q.filters().stream()
            .map(f -> JSON.<JsonNode>valueToTree(f.transform().apply(row.get(f.column()))))
            .toList();
    int filterIndex = 0;
    for (var f : q.filters()) {
      JsonNode actual = actuals.get(filterIndex++);
      if (actual == null || actual.isNull()) return false;
      if (f.operator() == QuerySpec.Operator.IN) {
        if (f.values().stream().noneMatch(v -> compare(actual, v) == 0)) return false;
      } else {
        int c = compare(actual, f.values().getFirst());
        if (!switch (f.operator()) {
          case EQ -> c == 0;
          case GT -> c > 0;
          case GE -> c >= 0;
          case LT -> c < 0;
          case LE -> c <= 0;
          default -> false;
        }) return false;
      }
    }
    return true;
  }

  static int compare(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
    if (a.isTextual() && b.isTextual()) return a.textValue().compareTo(b.textValue());
    if (a.isBoolean() && b.isBoolean()) return Boolean.compare(a.booleanValue(), b.booleanValue());
    throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
  }

  static void fields(QuerySpec q, Set<String> fields) {
    Set<String> required = new HashSet<>(q.projection());
    required.addAll(q.guardBindings().values());
    q.filters().forEach(f -> required.add(f.column()));
    if (!fields.containsAll(required)) throw QueryPlanner.problem("UNKNOWN_COLUMN");
  }

  static Map<String, Object> project(QuerySpec q, Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    q.projection().forEach(c -> out.put(c, row.get(c)));
    return out;
  }

  static SourceMetadata.Resource inferred(String name, List<Map<String, Object>> rows) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (var r : rows)
      r.forEach(
          (k, v) ->
              fields.putIfAbsent(
                  k,
                  v == null
                      ? "UNKNOWN"
                      : v instanceof Number
                          ? "NUMBER"
                          : v instanceof Boolean ? "BOOLEAN" : "STRING"));
    return new SourceMetadata.Resource(
        name,
        fields.entrySet().stream()
            .map(e -> new SourceMetadata.Field(e.getKey(), e.getValue(), true))
            .toList(),
        List.of(),
        List.of(),
        List.of());
  }

  static void deadline(long started, QuerySpec q) {
    if (System.nanoTime() - started > q.timeoutMs() * 1_000_000L)
      throw QueryPlanner.problem("QUERY_TIMEOUT");
  }
}
