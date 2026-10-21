package cn.ontorepo.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 结构化只读计划；guardBindings 来自已验证的模型快照，不能来自客户端授权参数。 */
public record QuerySpec(
    String resource,
    List<String> projection,
    List<Filter> filters,
    List<Join> joins,
    int limit,
    int timeoutMs,
    Map<String, JsonNode> options,
    LabelScope scope,
    Map<String, String> guardBindings) {
  public enum Operator {
    EQ,
    IN,
    GT,
    GE,
    LT,
    LE
  }

  /** 属性过滤携带冻结转换，旧调用保持原值语义；转换不是调用者提供的SQL片段。 */
  public record Filter(
      String column, Operator operator, List<JsonNode> values, ValueTransform transform) {
    public Filter(String column, Operator operator, List<JsonNode> values) {
      this(column, operator, values, ValueTransform.identity());
    }

    public Filter {
      values = List.copyOf(values);
      transform = transform == null ? ValueTransform.identity() : transform;
    }
  }

  public record Join(String resource, String leftColumn, String rightColumn) {}

  public QuerySpec {
    if (resource == null
        || projection == null
        || projection.isEmpty()
        || projection.size() > 128
        || filters == null
        || filters.size() > 64
        || joins == null
        || joins.size() > 8
        || options == null
        || scope == null
        || guardBindings == null) throw new DataProblem("INVALID_QUERY", "查询计划无效");
    projection = List.copyOf(projection);
    filters = List.copyOf(filters);
    joins = List.copyOf(joins);
    options = Map.copyOf(options);
    guardBindings = Map.copyOf(guardBindings);
    limit = limit <= 0 ? 500 : Math.min(limit, 500);
    timeoutMs = timeoutMs <= 0 ? 10000 : Math.min(timeoutMs, 10000);
  }
}
