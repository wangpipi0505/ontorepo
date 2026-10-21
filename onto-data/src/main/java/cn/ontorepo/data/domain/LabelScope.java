package cn.ontorepo.data.domain;

import cn.ontorepo.contract.GuardAttributeName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权标签范围的不可变交集，不负责判断授权记录是否存在。
 *
 * <p>调用者必须先验证入口授权记录；记录存在且范围 null 才代表不限。 策略、端点、空间按属性逐项求交，任何层不能扩大另一层；同一空间范围应用两次保持等价。
 * 缺少分类标签始终拒绝，即使所有范围均为不限，也不能把未分类对象当作公共数据。
 */
public record LabelScope(Map<String, Set<JsonNode>> clauses) {
  public LabelScope {
    if (clauses == null || clauses.size() > 64) throw invalid();
    Map<String, Set<JsonNode>> copy = new LinkedHashMap<>();
    clauses.forEach(
        (attribute, values) -> {
          if (attribute == null
              || !GuardAttributeName.valid(attribute)
              || values == null
              || values.size() > 1000) throw invalid();
          Set<JsonNode> normalized = new LinkedHashSet<>();
          for (JsonNode value : values) {
            if (!classified(value)) throw invalid();
            normalized.add(normalize(value));
          }
          copy.put(attribute, Set.copyOf(normalized));
        });
    clauses = Map.copyOf(copy);
  }

  public static LabelScope unrestricted() {
    return new LabelScope(Map.of());
  }

  /** 仅允许明确的集合或集合合取，不接受原始 SQL、通配符和任意表达式。 */
  public static LabelScope parse(JsonNode source) {
    return parse(source, 0);
  }

  private static LabelScope parse(JsonNode source, int depth) {
    if (source == null || source.isNull()) return unrestricted();
    if (!source.isObject() || depth > 16) throw invalid();
    if (source.size() == 1 && source.has("all")) {
      JsonNode all = source.get("all");
      if (!all.isArray() || all.isEmpty() || all.size() > 64) throw invalid();
      LabelScope combined = unrestricted();
      for (JsonNode child : all) {
        if (!child.isObject()) throw invalid();
        combined = intersect(combined, parse(child, depth + 1));
      }
      return combined;
    }
    JsonNode attr = source.get("attr"), values = source.get("in");
    if (source.size() != 2
        || attr == null
        || !attr.isTextual()
        || !GuardAttributeName.valid(attr.textValue())
        || values == null
        || !values.isArray()
        || values.size() > 1000) throw invalid();
    Set<JsonNode> allowed = new LinkedHashSet<>();
    for (JsonNode value : values) {
      if (!classified(value)) throw invalid();
      allowed.add(normalize(value));
    }
    return new LabelScope(Map.of(attr.textValue(), allowed));
  }

  public static LabelScope intersect(LabelScope... scopes) {
    Map<String, Set<JsonNode>> result = new LinkedHashMap<>();
    for (LabelScope scope : scopes) {
      if (scope == null) throw invalid();
      for (var entry : scope.clauses.entrySet()) {
        Set<JsonNode> existing = result.get(entry.getKey());
        if (existing == null) result.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        else existing.retainAll(entry.getValue());
      }
    }
    return new LabelScope(result);
  }

  /** SQL 计划器将空交集编译为恒假谓词，不能在空列表时省略 WHERE。 */
  public boolean deniesAll() {
    return clauses.values().stream().anyMatch(Set::isEmpty);
  }

  /** 只判断当前属性；整条记录必须对全部必需 guard_attrs 逐项调用。 */
  public boolean allows(String attribute, JsonNode label) {
    if (deniesAll() || !classified(label)) return false;
    Set<JsonNode> allowed = clauses.get(attribute);
    return allowed == null || allowed.contains(normalize(label));
  }

  /** 返回参数化计划所需的只读集合；不得将其中字符串拼接到 SQL。 */
  public Map<String, List<JsonNode>> parameters() {
    Map<String, List<JsonNode>> result = new LinkedHashMap<>();
    clauses.forEach((attribute, values) -> result.put(attribute, List.copyOf(values)));
    return Map.copyOf(result);
  }

  private static JsonNode normalize(JsonNode value) {
    return value.isNumber()
        ? DecimalNode.valueOf(value.decimalValue().stripTrailingZeros())
        : value;
  }

  private static boolean classified(JsonNode value) {
    return value != null
        && (value.isBoolean()
            || value.isNumber()
            || value.isTextual()
                && !value.textValue().isBlank()
                && value.textValue().length() <= 1000);
  }

  private static DataProblem invalid() {
    return new DataProblem("INVALID_DATA_SCOPE", "数据范围必须为明确的属性值集合或集合合取");
  }
}
