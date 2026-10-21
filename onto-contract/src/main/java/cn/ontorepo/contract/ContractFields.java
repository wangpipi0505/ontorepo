package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/** 消费契约的严格字段读取；缺失和类型错误分开，禁止 Jackson 宽松转换掩盖错误。 */
final class ContractFields {
    private ContractFields() {}

    static JsonNode required(JsonNode object, String key) {
        JsonNode value = object.path(key);
        if (value.isMissingNode() || value.isNull()) throw problem("MISSING_FIELD", key);
        return value;
    }

    static String text(JsonNode object, String key) {
        JsonNode value = required(object, key);
        if (!value.isTextual() || value.textValue().isBlank()) throw problem("TYPE_MISMATCH", key);
        return value.textValue();
    }

    static JsonNode object(JsonNode parent, String key) {
        JsonNode value = required(parent, key);
        if (!value.isObject()) throw problem("TYPE_MISMATCH", key);
        return value;
    }

    static boolean bool(JsonNode parent, String key) {
        JsonNode value = required(parent, key);
        if (!value.isBoolean()) throw problem("TYPE_MISMATCH", key);
        return value.booleanValue();
    }

    static int positiveInt(JsonNode parent, String key) {
        JsonNode value = required(parent, key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
            throw problem("TYPE_MISMATCH", key);
        return value.intValue();
    }

    static Set<String> strings(JsonNode value, String field, boolean nonEmpty) {
        if (!value.isArray() || (nonEmpty && value.isEmpty())) throw problem("TYPE_MISMATCH", field);
        Set<String> found = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank() || !found.add(item.textValue()))
                throw problem("TYPE_MISMATCH", field);
        }
        return found;
    }

    static String enumeration(JsonNode parent, String field, Set<String> values) {
        String value = text(parent, field);
        if (!values.contains(value)) throw new ContractProblem("INVALID_ENUM", field, value);
        return value;
    }

    static String httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null)
                throw problem("INVALID_URL", "endpoint");
            return value;
        } catch (IllegalArgumentException e) {
            throw problem("INVALID_URL", "endpoint");
        }
    }

    static ContractProblem problem(String code, String field) {
        return new ContractProblem(code, field, code);
    }
}
