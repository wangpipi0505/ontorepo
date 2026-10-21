package cn.ontorepo.model.domain;

import cn.ontorepo.common.domain.Problem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 分面只接受真实 JSON 标量类型；默认值、枚举和边界使用同一类型规则。 */
public final class PropertyFacets {
    private PropertyFacets() {}

    public static void validate(String type, boolean required, Map<String, Object> f) {
        if (!Set.of(
                        "unitCodebookId",
                        "unitCode",
                        "codebookId",
                        "defaultValue",
                        "enumValues",
                        "minimum",
                        "maximum",
                        "primaryKey")
                .containsAll(f.keySet())) throw Problem.bad("存在不支持的属性分面");
        for (String k : List.of("minimum", "maximum"))
            if (f.containsKey(k)
                    && (!(f.get(k) instanceof Number)
                            || !Set.of("INTEGER", "DECIMAL").contains(type)))
                throw Problem.bad("数值范围仅适用于数值属性");
        if (f.containsKey("minimum")
                && f.containsKey("maximum")
                && decimal(f.get("minimum")).compareTo(decimal(f.get("maximum"))) > 0)
            throw Problem.bad("最小值不能大于最大值");
        if (f.containsKey("primaryKey") && !(f.get("primaryKey") instanceof Boolean))
            throw Problem.bad("primaryKey 必须为布尔值");
        if (Boolean.TRUE.equals(f.get("primaryKey")) && !required) throw Problem.bad("主键属性必须必填");
        if (f.containsKey("enumValues")) {
            if (!(f.get("enumValues") instanceof List<?> values) || values.isEmpty())
                throw Problem.bad("枚举不能为空");
            Set<String> seen = new HashSet<>();
            for (Object v : (List<?>) f.get("enumValues")) {
                scalar(type, v);
                range(v, f);
                if (!seen.add(v.toString())) throw Problem.bad("枚举值重复");
            }
        }
        if (f.containsKey("defaultValue")) {
            Object v = f.get("defaultValue");
            scalar(type, v);
            range(v, f);
            if (f.containsKey("enumValues")
                    && ((List<?>) f.get("enumValues")).stream().noneMatch(x -> equal(x, v)))
                throw Problem.bad("默认值不在枚举中");
        }
        if ((f.get("unitCodebookId") == null) != (f.get("unitCode") == null))
            throw Problem.bad("单位码表与单位条目必须同时指定");
    }

    public static boolean equal(Object a, Object b) {
        return a instanceof Number && b instanceof Number
                ? decimal(a).compareTo(decimal(b)) == 0
                : Objects.equals(a, b);
    }

    public static BigDecimal decimal(Object v) {
        return new BigDecimal(v.toString());
    }

    public static void scalar(String type, Object value) {
        boolean ok =
                switch (type) {
                    case "STRING" -> value instanceof String;
                    case "BOOLEAN" -> value instanceof Boolean;
                    case "INTEGER" ->
                            value instanceof Number
                                    && decimal(value).stripTrailingZeros().scale() <= 0;
                    case "DECIMAL" -> value instanceof Number;
                    case "DATE" -> {
                        try {
                            LocalDate.parse((String) value);
                            yield true;
                        } catch (Exception e) {
                            yield false;
                        }
                    }
                    default -> false;
                };
        if (!ok) throw new Problem(400, "TYPE_MISMATCH", "分面值与属性类型不匹配");
    }

    private static void range(Object v, Map<String, Object> f) {
        if (v instanceof Number) {
            if (f.containsKey("minimum") && decimal(v).compareTo(decimal(f.get("minimum"))) < 0
                    || f.containsKey("maximum")
                            && decimal(v).compareTo(decimal(f.get("maximum"))) > 0)
                throw Problem.bad("值超出属性范围");
        }
    }
}
