package cn.ontorepo.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;

/** PostgreSQL 12+物化阶段保证授权先于转换；内部别名、函数与类型全部由代码控制。 */
final class PostgreSqlTransforms {
  private PostgreSqlTransforms() {}

  static QueryPlanner.SqlPlan plan(
      QuerySpec q,
      Function<String, String> column,
      String from,
      List<String> guards,
      List<Object> parameters,
      Map<String, List<String>> keys,
      Map<String, SourceMetadata.Resource> resources) {
    LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
    q.projection().forEach(c -> aliases.computeIfAbsent(c, ignored -> "__c" + aliases.size()));
    keys.forEach(
        (resource, fields) ->
            fields.forEach(
                c ->
                    aliases.computeIfAbsent(
                        resource + "." + c, ignored -> "__c" + aliases.size())));
    q.filters()
        .forEach(f -> aliases.computeIfAbsent(f.column(), ignored -> "__c" + aliases.size()));
    List<String> authorized = new ArrayList<>(),
        converted = new ArrayList<>(),
        predicates = new ArrayList<>(),
        validation = new ArrayList<>(),
        validationColumns = new ArrayList<>();
    aliases.forEach((name, alias) -> authorized.add(column.apply(name) + " AS " + alias));
    // CTE参数按SQL出现顺序加入：授权、转换码表、业务值、LIMIT，不能重用表达式后错位。
    int index = 0;
    for (var f : q.filters()) {
      sourceType(f, resources);
      converted.add(
          expression(f.transform(), aliases.get(f.column()), parameters) + " AS __t" + index++);
    }
    index = 0;
    for (var f : q.filters()) {
      String alias = "__t" + index++;
      String type =
          f.values().isEmpty()
              ? null
              : f.values().getFirst().isNumber()
                  ? "number"
                  : f.values().getFirst().isBoolean() ? "boolean" : "string";
      String actual = checked(alias, f.transform().rangeType(), type);
      validation.add("count(" + actual + ") AS __check" + index);
      validationColumns.add("validated.__check" + index);
      if (f.values().isEmpty()) {
        predicates.add("FALSE");
        continue;
      }
      for (JsonNode value : f.values()) {
        if (!type.equals(value.isNumber() ? "number" : value.isBoolean() ? "boolean" : "string"))
          throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
        f.transform().checkRange(QueryPlanner.scalar(value));
      }
      String nativeType =
          type.equals("number") ? "numeric" : type.equals("boolean") ? "boolean" : "text";
      String scalar =
          "(("
              + actual
              + ") #>> '{}')::"
              + nativeType
              + (type.equals("string") ? " COLLATE \"C\"" : "");
      String op =
          switch (f.operator()) {
            case EQ -> "=";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            case IN -> "IN";
          };
      if (f.operator() == QuerySpec.Operator.IN)
        predicates.add(
            scalar + " IN (" + String.join(",", Collections.nCopies(f.values().size(), "?")) + ")");
      else predicates.add(scalar + " " + op + " ?");
      f.values().forEach(v -> parameters.add(QueryPlanner.scalar(v)));
    }
    List<String> output = new ArrayList<>();
    q.projection().forEach(c -> output.add(aliases.get(c)));
    keys.forEach(
        (resource, fields) -> fields.forEach(c -> output.add(aliases.get(resource + "." + c))));
    // MATERIALIZED本身仍可按需产出；消费聚合强制检查全部已授权值，防止LIMIT隐藏坏值。
    String sql =
        "WITH authorized AS MATERIALIZED (SELECT "
            + String.join(",", authorized)
            + from
            + " WHERE "
            + String.join(" AND ", guards)
            + "), converted AS MATERIALIZED (SELECT authorized.*,"
            + String.join(",", converted)
            + " FROM authorized), validated AS MATERIALIZED (SELECT "
            + String.join(",", validation)
            + " FROM converted) SELECT matched.*,"
            + String.join(",", validationColumns)
            + " FROM validated LEFT JOIN LATERAL (SELECT "
            + String.join(",", output)
            + " FROM converted WHERE "
            + String.join(" AND ", predicates)
            + " LIMIT ?) matched ON TRUE";
    parameters.add(q.limit());
    return new QueryPlanner.SqlPlan(
        sql,
        List.copyOf(parameters),
        q.limit(),
        q.timeoutMs(),
        q.projection(),
        Collections.unmodifiableMap(new LinkedHashMap<>(keys)));
  }

  /** 浮点/时间戳的PG文本与JDBC Java.toString不等价时必须拒绝，不能悄悄改变码表键。 */
  private static void sourceType(
      QuerySpec.Filter f, Map<String, SourceMetadata.Resource> resources) {
    if (f.transform().kind() == ValueTransform.Kind.IDENTITY) return;
    String name = f.column().substring(f.column().lastIndexOf('.') + 1);
    String resource = f.column().substring(0, f.column().lastIndexOf('.'));
    String type =
        resources.get(resource).fields().stream()
            .filter(v -> v.name().equals(name))
            .findFirst()
            .orElseThrow()
            .type()
            .toLowerCase(Locale.ROOT);
    // numeric的JDBC BigDecimal文本会使用科学计数法；PG ::text保留定点形式，不能冒充等价。
    if (Set.of("numeric", "decimal").contains(type)
        && Set.of(ValueTransform.Kind.TO_STRING, ValueTransform.Kind.CODEBOOK)
            .contains(f.transform().kind()))
      throw QueryPlanner.problem("TRANSFORM_SOURCE_TYPE_UNSUPPORTED");
    if (!Set.of(
            "text",
            "varchar",
            "int2",
            "int4",
            "int8",
            "smallint",
            "integer",
            "bigint",
            "numeric",
            "decimal",
            "bool",
            "boolean",
            "date")
        .contains(type)) throw QueryPlanner.problem("TRANSFORM_SOURCE_TYPE_UNSUPPORTED");
  }

  private static String expression(
      ValueTransform transform, String source, List<Object> parameters) {
    String text = "(" + source + ")::text";
    String numericText = normalizeDigits(text);
    String failure = "('ontorepo_transform_invalid_' || " + text + ")::jsonb";
    String expression =
        switch (transform.kind()) {
          case IDENTITY -> "to_jsonb(" + source + ")";
          case TO_STRING -> "to_jsonb(" + text + ")";
          case TO_NUMBER ->
              "CASE WHEN "
                  + numericText
                  + " ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$' THEN to_jsonb(("
                  + numericText
                  + ")::numeric) ELSE "
                  + failure
                  + " END";
          case TO_BOOLEAN ->
              "CASE WHEN lower("
                  + text
                  + ") IN ('true','1') THEN 'true'::jsonb WHEN lower("
                  + text
                  + ") IN ('false','0') THEN 'false'::jsonb ELSE "
                  + failure
                  + " END";
          case CODEBOOK -> {
            StringBuilder cases = new StringBuilder("CASE ").append(text);
            transform
                .codebook()
                .forEach(
                    (code, value) -> {
                      cases.append(" WHEN ? THEN ?::jsonb");
                      parameters.add(code);
                      parameters.add(value.toString());
                    });
            // 空码表仍须对非null源值报错，不能产生非法的CASE语法。
            yield transform.codebook().isEmpty()
                ? failure
                : cases.append(" ELSE ").append(failure).append(" END").toString();
          }
        };
    return "CASE WHEN " + source + " IS NULL THEN NULL::jsonb ELSE " + expression + " END";
  }

  private static String checked(String alias, String range, String comparisonType) {
    String valid =
        comparisonType == null ? "TRUE" : "jsonb_typeof(" + alias + ") = '" + comparisonType + "'";
    if (range != null)
      valid +=
          " AND "
              + switch (range) {
                case "STRING" -> "jsonb_typeof(" + alias + ") = 'string'";
                case "BOOLEAN" -> "jsonb_typeof(" + alias + ") = 'boolean'";
                case "DECIMAL" -> "jsonb_typeof(" + alias + ") = 'number'";
                case "INTEGER" ->
                    "jsonb_typeof("
                        + alias
                        + ") = 'number' AND ("
                        + alias
                        + " #>> '{}')::numeric = trunc(("
                        + alias
                        + " #>> '{}')::numeric)";
                case "DATE" ->
                    "jsonb_typeof("
                        + alias
                        + ") = 'string' AND ("
                        + alias
                        + " #>> '{}') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND (("
                        + alias
                        + " #>> '{}')::date)::text = ("
                        + alias
                        + " #>> '{}')";
                default -> throw QueryPlanner.problem("INVALID_TRANSFORM");
              };
    return "CASE WHEN "
        + alias
        + " IS NULL OR "
        + alias
        + " = 'null'::jsonb THEN NULL::jsonb WHEN "
        + valid
        + " THEN "
        + alias
        + " ELSE ('ontorepo_transform_invalid_' || "
        + alias
        + "::text)::jsonb END";
  }

  /** BigDecimal接受BMP Unicode十进制数字；常量字符表来自JDK，不含任何查询或码表输入。 */
  private static String normalizeDigits(String text) {
    return "translate(" + text + ", '" + Digits.SOURCE + "', '" + Digits.TARGET + "')";
  }

  private static final class Digits {
    static final String SOURCE;
    static final String TARGET;

    static {
      StringBuilder source = new StringBuilder(), target = new StringBuilder();
      for (int point = 0; point <= Character.MAX_VALUE; point++) {
        char value = (char) point;
        int digit = Character.digit(value, 10);
        if (digit >= 0 && (value < '0' || value > '9')) {
          source.append(value);
          target.append((char) ('0' + digit));
        }
      }
      SOURCE = source.toString();
      TARGET = target.toString();
    }
  }
}
