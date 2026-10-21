package cn.ontorepo.mapping.domain;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.contract.GuardValidator;
import cn.ontorepo.data.domain.ValueTransform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 映射DSL只解释字段占位符和白名单转换，不执行任何脚本。 */
public final class MappingValues {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]{0,127})}");

    private MappingValues() {}

    /** 在内部结构校验中保留JSON原类型。 */
    public static JsonNode node(Object o) {
        return JSON.valueToTree(o);
    }

    /** 定义必须为JSON对象，拒绝隐式标量转换。 */
    public static Map<String, Object> map(Object o) {
        if (!(o instanceof Map<?, ?>)) throw Problem.bad("定义必须为对象");
        return JSON.convertValue(
                o,
                new com.fasterxml.jackson.core.type.TypeReference<
                        LinkedHashMap<String, Object>>() {});
    }

    /** 集合定义必须为对象数组。 */
    public static List<Map<String, Object>> rows(Object o) {
        if (o == null) return List.of();
        if (!(o instanceof List<?> list)) throw Problem.bad("定义必须为数组");
        return list.stream().map(MappingValues::map).toList();
    }

    /** 对象模板只提取受限字段占位符，不执行表达式。 */
    public static Set<String> keys(String expr) {
        if (expr == null || expr.length() > 1000) throw Problem.bad("对象标识模板无效");
        Matcher m = TOKEN.matcher(expr);
        Set<String> fields = new LinkedHashSet<>();
        while (m.find()) fields.add(m.group(1));
        String fixed = TOKEN.matcher(expr).replaceAll("");
        if (fields.isEmpty() || !fixed.matches("[\\p{L}\\p{N}_:./#|~-]*"))
            throw Problem.bad("对象标识仅允许字段占位符与固定分隔符");
        return fields;
    }

    /** 空主键拒绝并提供字段定位，不生成空对象身份。 */
    public static String identity(String expr, Map<String, Object> values) {
        keys(expr);
        Matcher m = TOKEN.matcher(expr);
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            Object v = values.get(m.group(1));
            if (v == null || v.toString().isBlank())
                throw new Problem(400, "OBJECT_KEY_EMPTY", "对象主键为空: " + m.group(1));
            m.appendReplacement(result, Matcher.quoteReplacement(v.toString()));
        }
        m.appendTail(result);
        return result.toString();
    }

    /** S3按批准前缀覆盖，其余协议按原资源名精确匹配。 */
    public static boolean covered(String kind, Collection<String> scope, String resource) {
        if (!GuardValidator.resource(resource)) return false;
        return "LAKE".equals(kind)
                ? scope.stream().anyMatch(resource::startsWith)
                : scope.contains(resource);
    }

    /** 从冻结定义编译纯转换对象；码表容器和值均复制，不保留当前草稿引用。 */
    public static ValueTransform valueTransform(
            Map<String, Object> definition, List<Map<String, Object>> codebooks, String rangeType) {
        String kind =
                Objects.toString(definition == null ? null : definition.get("kind"), "IDENTITY");
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        if (kind.equals("CODEBOOK")) {
            var codebook =
                    codebooks.stream()
                            .filter(
                                    book ->
                                            Input.sameUuid(
                                                    book.get("id"), definition.get("codebookId")))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Problem(
                                                    409, "FROZEN_REFERENCE_INVALID", "冻结转换码表不存在"));
            for (var entry : rows(codebook.get("entries"))) {
                if (entry.get("code") == null
                        || entries.putIfAbsent(
                                        entry.get("code").toString(),
                                        node(
                                                entry.containsKey("value")
                                                        ? entry.get("value")
                                                        : entry.get("label")))
                                != null)
                    throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结码表条目编码为空或重复");
            }
        }
        try {
            return new ValueTransform(ValueTransform.Kind.valueOf(kind), entries, rangeType);
        } catch (IllegalArgumentException failure) {
            throw Problem.bad("转换类型无效");
        }
    }
}
