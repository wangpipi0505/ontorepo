package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.function.BiConsumer;

/** 只访问 Draft 2020-12 定义的 Schema 位置；属性映射的键和实例/注释内容均保持不透明。 */
final class SchemaTraversal {
    private static final Set<String> SINGLE = Set.of(
            "additionalProperties", "unevaluatedProperties", "propertyNames",
            "items", "unevaluatedItems", "contains", "not", "if", "then", "else", "contentSchema");
    private static final Set<String> MAP = Set.of(
            "$defs", "definitions", "properties", "patternProperties", "dependentSchemas");
    private static final Set<String> ARRAY = Set.of("allOf", "anyOf", "oneOf", "prefixItems");

    private SchemaTraversal() {}

    static void walk(JsonNode root, BiConsumer<JsonNode, String> visitor) {
        walk(root, "", 0, visitor);
    }

    private static void walk(JsonNode node, String pointer, int depth, BiConsumer<JsonNode, String> visitor) {
        if (depth > 64) {
            throw ContractFields.problem("SCHEMA_TOO_DEEP", "schema");
        }
        visitor.accept(node, pointer);
        children(node, (child, suffix) -> walk(child, pointer + suffix, depth + 1, visitor));
    }

    static void children(JsonNode node, BiConsumer<JsonNode, String> visitor) {
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String keyword = entry.getKey();
            JsonNode value = entry.getValue();
            String prefix = "/" + keyword;
            if (SINGLE.contains(keyword)) {
                visitor.accept(value, prefix);
            } else if (MAP.contains(keyword) && value.isObject()) {
                value.fields().forEachRemaining(child ->
                        visitor.accept(child.getValue(), prefix + "/" + escape(child.getKey())));
            } else if (keyword.equals("dependencies") && value.isObject()) {
                // 2020-12 元 Schema 仍保留此兼容关键字；只有对象/布尔值是 Schema，字符串数组是数据。
                value.fields().forEachRemaining(child -> {
                    if (child.getValue().isObject() || child.getValue().isBoolean()) {
                        visitor.accept(child.getValue(), prefix + "/" + escape(child.getKey()));
                    }
                });
            } else if (ARRAY.contains(keyword) && value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    visitor.accept(value.get(i), prefix + "/" + i);
                }
            }
        });
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
