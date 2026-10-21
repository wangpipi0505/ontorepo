package cn.ontorepo.data.domain;

import cn.ontorepo.contract.GuardAttributeName;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;

/** 全部源表和列先校验，标签参数化并在 LIMIT 前执行。 */
public final class QueryPlanner {
  public record SqlPlan(
      String sql,
      List<Object> parameters,
      int limit,
      int timeoutMs,
      List<String> projection,
      Map<String, List<String>> keys) {}

  public static void identifier(String name) {
    if (name == null
        || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,79}(\\.[A-Za-z_][A-Za-z0-9_]{0,79}){0,2}"))
      throw problem("INVALID_IDENTIFIER");
  }

  public static DataProblem problem(String code) {
    return new DataProblem(code, "数据源查询不满足访问边界或能力约束");
  }

  public static Object scalar(JsonNode n) {
    if (n == null || n.isNull() || n.isContainerNode()) throw problem("INVALID_FILTER");
    if (n.isNumber()) return n.decimalValue();
    if (n.isBoolean()) return n.booleanValue();
    return n.textValue();
  }

  public SqlPlan plan(SourceSpec source, QuerySpec q, SourceMetadata metadata) {
    boolean transformed = transformed(q);
    if (transformed && !"postgresql".equals(source.connection().path("dialect").asText()))
      throw problem("TRANSFORM_FILTER_UNSUPPORTED");
    if (!q.options().isEmpty()) throw problem("UNSUPPORTED_OPTION");
    Map<String, SourceMetadata.Resource> resources =
        metadata.resources().stream()
            .collect(Collectors.toMap(SourceMetadata.Resource::name, r -> r));
    Set<String> used = new LinkedHashSet<>();
    used.add(q.resource());
    q.joins().forEach(j -> used.add(j.resource()));
    for (String r : used) {
      identifier(r);
      if (!source.accessScope().contains(r)) throw problem("SCOPE_EXCEEDED");
      if (!resources.containsKey(r)) throw problem("RESOURCE_NOT_FOUND");
    }
    Set<String> columns = new HashSet<>();
    used.forEach(
        r ->
            resources
                .get(r)
                .fields()
                .forEach(
                    f -> {
                      identifier(f.name());
                      columns.add(r + "." + f.name());
                    }));
    java.util.function.Function<String, String> col =
        c -> {
          identifier(c);
          if (!columns.contains(c)) throw problem("UNKNOWN_COLUMN");
          return quote(c);
        };
    if (q.guardBindings().isEmpty()
        || !q.guardBindings().keySet().stream()
            .map(QueryPlanner::guardAttribute)
            .collect(Collectors.toSet())
            .containsAll(q.scope().clauses().keySet())) throw problem("GUARD_ATTR_UNRESOLVED");
    // JOIN 每一端都需分类标签；不能通过已授权任务关联出未分类算法。
    for (String resource : used)
      if (q.guardBindings().values().stream().noneMatch(c -> c.startsWith(resource + ".")))
        throw problem("GUARD_ATTR_UNRESOLVED");
    List<Object> params = new ArrayList<>();
    List<String> where = new ArrayList<>();
    for (var e : q.guardBindings().entrySet()) {
      String c = col.apply(e.getValue());
      where.add(c + " IS NOT NULL");
      var field =
          resources.get(e.getValue().substring(0, e.getValue().lastIndexOf('.'))).fields().stream()
              .filter(
                  f -> f.name().equals(e.getValue().substring(e.getValue().lastIndexOf('.') + 1)))
              .findFirst()
              .orElseThrow();
      if (field.type().toUpperCase(Locale.ROOT).matches(".*(CHAR|TEXT|STRING).*"))
        where.add(classifiedText(source, c));
      var values = q.scope().parameters().get(guardAttribute(e.getKey()));
      if (values != null) in(c, values, where, params);
    }
    for (var f : q.filters()) {
      String c = col.apply(f.column());
      if (f.operator() == null || f.values().size() > 1000) throw problem("INVALID_FILTER");
      if (f.operator() != QuerySpec.Operator.IN && f.values().size() != 1)
        throw problem("INVALID_FILTER");
      f.values().forEach(QueryPlanner::scalar);
      if (transformed) continue;
      if (f.operator() == QuerySpec.Operator.IN) in(c, f.values(), where, params);
      else {
        if (f.values().size() != 1) throw problem("INVALID_FILTER");
        where.add(
            c
                + " "
                + switch (f.operator()) {
                  case EQ -> "=";
                  case GT -> ">";
                  case GE -> ">=";
                  case LT -> "<";
                  case LE -> "<=";
                  default -> throw problem("INVALID_FILTER");
                }
                + " ?");
        params.add(scalar(f.values().getFirst()));
      }
    }
    String select = q.projection().stream().map(col).collect(Collectors.joining(","));
    Map<String, List<String>> keys = new LinkedHashMap<>();
    int keyIndex = 0;
    for (String r : used) {
      if (resources.get(r).primaryKey().isEmpty()) throw problem("SOURCE_KEY_REQUIRED");
      keys.put(r, resources.get(r).primaryKey());
      for (String key : resources.get(r).primaryKey())
        select += "," + col.apply(r + "." + key) + " AS \"__e" + (keyIndex++) + "\"";
    }
    StringBuilder from = new StringBuilder(" FROM ").append(quote(q.resource()));
    Set<String> joined = new HashSet<>();
    joined.add(q.resource());
    for (var j : q.joins()) {
      String l = j.leftColumn(), r = j.rightColumn();
      if (!r.startsWith(j.resource() + ".")
          || joined.stream().noneMatch(x -> l.startsWith(x + "."))
          || !joined.add(j.resource())) throw problem("INVALID_JOIN");
      from.append(" JOIN ")
          .append(quote(j.resource()))
          .append(" ON ")
          .append(col.apply(l))
          .append(" = ")
          .append(col.apply(r));
    }
    if (transformed)
      return PostgreSqlTransforms.plan(q, col, from.toString(), where, params, keys, resources);
    StringBuilder sql = new StringBuilder("SELECT ").append(select).append(from);
    sql.append(" WHERE ").append(String.join(" AND ", where)).append(" LIMIT ?");
    params.add(q.limit());
    return new SqlPlan(
        sql.toString(),
        List.copyOf(params),
        q.limit(),
        q.timeoutMs(),
        q.projection(),
        Collections.unmodifiableMap(new LinkedHashMap<>(keys)));
  }

  /** IDENTITY只承诺原值谓词；带类型校验的组合不得被某个协议静默忽略。 */
  public static boolean transformed(QuerySpec q) {
    for (var filter : q.filters())
      if (filter.transform().kind() == ValueTransform.Kind.IDENTITY
          && filter.transform().rangeType() != null) throw problem("INVALID_TRANSFORM");
    return q.filters().stream().anyMatch(f -> f.transform().kind() != ValueTransform.Kind.IDENTITY);
  }

  private static void in(
      String col, List<JsonNode> values, List<String> where, List<Object> params) {
    if (values.isEmpty()) {
      where.add("1=0");
      return;
    }
    where.add(col + " IN (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")");
    values.forEach(v -> params.add(scalar(v)));
  }

  public static String quote(String value) {
    identifier(value);
    return Arrays.stream(value.split("\\."))
        .map(x -> "\"" + x + "\"")
        .collect(Collectors.joining("."));
  }

  /** 空白集合从当前JDK规则取得，避免SQL默认TRIM仅删除普通空格。没有调用者输入进入字面量。 */
  private static String classifiedText(SourceSpec source, String column) {
    String dialect = source.connection().path("dialect").asText();
    StringBuilder escaped = new StringBuilder(), pattern = new StringBuilder("[");
    for (int point : BlankCharacters.POINTS) {
      escaped
          .append('\\')
          .append(
              point <= 0xffff
                  ? String.format(Locale.ROOT, "%04x", point)
                  : String.format(Locale.ROOT, "+%06x", point));
      pattern.append("\\\\x{").append(Integer.toHexString(point)).append('}');
    }
    pattern.append(']');
    return switch (dialect) {
      case "postgresql" -> "BTRIM(" + column + ", U&'" + escaped + "') <> ''";
      case "clickhouse" -> "replaceRegexpAll(" + column + ", '" + pattern + "', '') <> ''";
      default -> throw problem("UNSUPPORTED_DIALECT");
    };
  }

  private static final class BlankCharacters {
    static final int[] POINTS =
        java.util.stream.IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace)
            .toArray();
  }

  /** 同一属性在 JOIN 多表绑定时使用 attr@resource，仍应用同一属性范围。 */
  public static String guardAttribute(String binding) {
    String[] parts = binding.split("@", -1);
    if (parts.length > 2 || !GuardAttributeName.valid(parts[0]))
      throw problem("GUARD_ATTR_UNRESOLVED");
    if (parts.length == 2) identifier(parts[1]);
    return parts[0];
  }
}
