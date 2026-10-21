package cn.ontorepo.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 冻结属性映射的受限转换；只有服务端解析冻结载荷后才能构造，绝不接收表达式或SQL。 */
public record ValueTransform(Kind kind, Map<String, JsonNode> codebook, String rangeType) {
  public enum Kind {
    IDENTITY,
    TO_STRING,
    TO_NUMBER,
    TO_BOOLEAN,
    CODEBOOK
  }

  public ValueTransform {
    if (kind == null
        || codebook == null
        || codebook.size() > 1000
        || rangeType != null
            && !Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE").contains(rangeType)
        || kind != Kind.CODEBOOK && !codebook.isEmpty())
      throw QueryPlanner.problem("INVALID_TRANSFORM");
    long bytes = 0;
    Map<String, JsonNode> copy = new LinkedHashMap<>();
    for (var entry : codebook.entrySet()) {
      JsonNode value = entry.getValue();
      if (entry.getKey() == null
          || value == null
          || !(value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean()))
        throw QueryPlanner.problem("INVALID_TRANSFORM");
      bytes += entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      bytes += value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (bytes > 1024 * 1024) throw QueryPlanner.problem("INVALID_TRANSFORM");
      // 标准JSON标量节点不可变；容器已拒绝，不会保留可被草稿写入的引用。
      copy.put(entry.getKey(), value.deepCopy());
    }
    codebook = Collections.unmodifiableMap(copy);
  }

  public static ValueTransform identity() {
    return new ValueTransform(Kind.IDENTITY, Map.of(), null);
  }

  /** null保持null；所有非空失败必须显式报错，不能作为“不匹配”悄悄丢行。 */
  public Object apply(Object value) {
    if (value == null) return null;
    try {
      Object transformed =
          switch (kind) {
            case IDENTITY -> value;
            case TO_STRING -> value.toString();
            case TO_NUMBER -> new BigDecimal(value.toString());
            case TO_BOOLEAN -> {
              String text = value.toString();
              if (value instanceof Boolean) yield value;
              if (text.equalsIgnoreCase("true") || text.equals("1")) yield true;
              if (text.equalsIgnoreCase("false") || text.equals("0")) yield false;
              throw new IllegalArgumentException();
            }
            case CODEBOOK -> {
              JsonNode mapped = codebook.get(value.toString());
              if (mapped == null) throw new IllegalArgumentException();
              yield mapped.isNull()
                  ? null
                  : mapped.isNumber()
                      ? mapped.decimalValue()
                      : mapped.isBoolean() ? mapped.booleanValue() : mapped.textValue();
            }
          };
      checkRange(transformed);
      return transformed;
    } catch (Exception failure) {
      throw QueryPlanner.problem("TRANSFORM_FAILED");
    }
  }

  /** 与本体分面使用相同严格标量规则，转换不授予隐式字符串到数值的类型放宽。 */
  public void checkRange(Object value) {
    if (rangeType == null || value == null) return;
    boolean valid =
        switch (rangeType) {
          case "STRING" -> value instanceof String;
          case "BOOLEAN" -> value instanceof Boolean;
          case "DECIMAL" -> value instanceof Number;
          case "INTEGER" ->
              value instanceof Number
                  && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
          case "DATE" -> {
            try {
              LocalDate.parse((String) value);
              yield true;
            } catch (Exception failure) {
              yield false;
            }
          }
          default -> false;
        };
    if (!valid) throw QueryPlanner.problem("TRANSFORM_FAILED");
  }
}
