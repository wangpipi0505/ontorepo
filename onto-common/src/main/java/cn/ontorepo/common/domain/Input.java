package cn.ontorepo.common.domain;

import java.util.Map;
import java.util.UUID;

/** 在入口集中校验类型、长度和稳定 UUID，禁止依赖数据库异常充当输入提示。 */
public final class Input {
    private Input() {}

    public static String text(Map<String, Object> b, String k, int max) {
        Object v = b.get(k);
        if (!(v instanceof String s) || s.isBlank() || s.length() > max)
            throw Problem.bad(k + " 不能为空且长度不能超过 " + max);
        return s.trim();
    }

    /** 可选说明字段仍限制长度；缺省或 JSON null 均规范为空串。 */
    public static String optionalText(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > max)
            throw Problem.bad(key + " 长度不能超过 " + max);
        return text.trim();
    }

    public static String description(Map<String, Object> b) {
        Object v = b.getOrDefault("description", "");
        if (!(v instanceof String s) || s.length() > 4000) throw Problem.bad("说明不能超过4000字符");
        return s;
    }

    public static UUID uuid(Object v) {
        try {
            String raw = String.valueOf(v);
            if (!raw.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException();
            return UUID.fromString(raw);
        } catch (Exception e) {
            throw Problem.bad("资源标识格式错误");
        }
    }

    /** JSON引用集合入库前规范化，UUID大小写不应改变身份、去重或资源上限。 */
    public static java.util.List<String> uuids(Object raw) {
        if (!(raw instanceof java.util.List<?> values)) throw Problem.bad("引用集合必须为数组");
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object value : values) {
            String id = uuid(value).toString();
            if (!seen.add(id)) throw Problem.bad("引用集合重复");
            out.add(id);
        }
        return java.util.List.copyOf(out);
    }

    /** 旧候选仅在读取时等价比较，绝不为了规范化而重写冻结字节。 */
    public static boolean sameUuid(Object left, Object right) {
        return left != null && right != null && left.toString().equalsIgnoreCase(right.toString());
    }

    public static long number(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (!(v instanceof Number n) || n.doubleValue() != n.longValue())
            throw Problem.bad(k + " 必须为整数");
        return n.longValue();
    }

    public static boolean bool(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (!(v instanceof Boolean x)) throw Problem.bad(k + " 必须为布尔值");
        return x;
    }
}
