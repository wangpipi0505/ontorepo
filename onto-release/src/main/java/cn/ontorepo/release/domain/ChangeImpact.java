package cn.ontorepo.release.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * R10：版本变更影响分级（纯函数）。
 *
 * <p>原理：变更的风险不取决于"改了什么"，而取决于"下游谁在依赖"——把依赖方向
 * 编码进 diff 规则即可自动分级。分级口径（与优化方案-本体侧 R10 约定一致）：
 *
 * <ul>
 *   <li>CRITICAL breaking：删除类/属性/操作/规则/知识/码表——下游映射、技能契约、
 *       冻结实例按稳定标识引用，删除即悬挂；删除操作同时移除服务端点。</li>
 *   <li>HIGH breaking：domain/range 收紧——属性可用输入面变窄，已有映射可能
 *       不再兼容；操作输入/输出 schema 收紧同理。</li>
 *   <li>MEDIUM potentially breaking：关系 cardinality 变化、类层级移动（parent
 *       变更）、关系端点变更——语义面变化，对既有引用多数兼容但需回归。</li>
 *   <li>SAFE：新增、注解/描述/名称展示面修改、策略与测试用例变更。</li>
 * </ul>
 *
 * <p>只读投影函数：输入 compare 产出的变更行（kind/key/before/after），输出
 * severity 与 reason，不触碰存储，供 REVIEW 门禁材料与前端变更行着色复用。
 */
public final class ChangeImpact {
    public enum Level {
        CRITICAL,
        HIGH,
        MEDIUM,
        SAFE
    }

    /** 任何删除都断引用；操作删除额外移除服务端点，同为 CRITICAL。 */
    private static final Set<String> BREAKING_KINDS =
            Set.of("CLASS", "PROPERTY", "RELATION", "OPERATION", "RULE", "KNOWLEDGE", "CODEBOOK");

    /** 规模字段：变更这些键视为"收紧"判定输入。 */
    private static final Set<String> TIGHTENING_KEYS =
            Set.of("rangeType", "inputSchema", "outputSchema", "minCount", "maxCount", "required");

    private ChangeImpact() {}

    /** 单条变更的影响分级。change 为 compare 产出的 {kind,key,before?,after?} 行。 */
    public static Map<String, Object> classify(Map<String, Object> change) {
        String kind = String.valueOf(change.get("kind"));
        boolean removed = !change.containsKey("after");
        boolean added = !change.containsKey("before");
        Level level;
        String reason;
        if (removed) {
            level = BREAKING_KINDS.contains(kind) ? Level.CRITICAL : Level.SAFE;
            reason =
                    BREAKING_KINDS.contains(kind)
                            ? "删除" + kind + "会使映射/契约/实例的按标识引用悬挂"
                            : "删除" + kind + "不影响下游引用";
        } else if (added) {
            level = Level.SAFE;
            reason = "新增" + kind + "，不改动既有引用";
        } else {
            JsonNode before = node(change.get("before")), after = node(change.get("after"));
            var tightened = tightened(before, after);
            if (!tightened.isEmpty()) {
                level = Level.HIGH;
                reason = "约束收紧：" + String.join("、", tightened) + "，既有映射/契约可能不再兼容";
            } else if (changed(before, after, "parentId") || changed(before, after, "fromClassId")
                    || changed(before, after, "toClassId") || changed(before, after, "cardinality")) {
                level = Level.MEDIUM;
                reason = kind + " 的层级/端点/基数变更，语义面变化需回归确认";
            } else {
                level = Level.SAFE;
                reason = kind + " 的展示面/注解变更";
            }
        }
        return Map.of("severity", level.name(), "reason", reason);
    }

    /** 找出"收紧"的字段：required true→保持、数值上限变小、枚举变小、rangeType 变化。 */
    private static java.util.List<String> tightened(JsonNode before, JsonNode after) {
        var result = new java.util.ArrayList<String>();
        for (String key : TIGHTENING_KEYS) {
            var b = before.get(key);
            var a = after.get(key);
            if (b == null && a == null) continue;
            if (b == null && a != null) {
                result.add(key + " 新增约束");
                continue;
            }
            if (a == null) continue; // 移除约束是放宽
            if (b.isNumber() && a.isNumber() && a.decimalValue().compareTo(b.decimalValue()) < 0
                    || (key.equals("required") && a.asBoolean() && !b.asBoolean())
                    || (b.isTextual() && a.isTextual() && !a.asText().equals(b.asText())
                            && (key.equals("rangeType") || key.equals("required"))))
                result.add(key + " " + b.asText() + "→" + a.asText());
        }
        return result;
    }

    private static boolean changed(JsonNode before, JsonNode after, String key) {
        var b = before.get(key);
        var a = after.get(key);
        return b != null && a != null && !b.equals(a)
                || b == null && a != null
                || b != null && a == null;
    }

    private static JsonNode node(Object value) {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(value);
    }
}
