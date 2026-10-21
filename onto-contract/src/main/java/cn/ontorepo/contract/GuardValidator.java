package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** 逐项复核完整守卫集合；缺定义或缺执行绑定均阻断，不存在仅声明安全豁免。 */
public final class GuardValidator {
    private static final Set<String> TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN");

    private GuardValidator() {}

    public static void validate(JsonNode operation, JsonNode guardCfg) {
        String code = ContractFields.text(operation, "code");
        JsonNode guards = operation.path("assistant_tool").path("guard_attrs");
        if (guards.isMissingNode() || guards.isNull() || (guards.isArray() && guards.isEmpty()))
            throw new ContractProblem("MISSING_GUARD_ATTRS", code, "MISSING_GUARD_ATTRS");
        var names = ContractFields.strings(guards, "guard_attrs", true);
        for (String name : names) {
            JsonNode definition = guardCfg.path(name);
            if (!definition.isObject()) unresolved(code, name, "UNDEFINED");
            if (!TYPES.contains(definition.path("type").asText()))
                unresolved(code, name, "UNSUPPORTED_TYPE");
            if ((definition.has("filter_binding") || definition.has("filter_bindings"))
                    && !validFilterBindings(definition))
                unresolved(code, name, "NO_FILTER_BINDING");
            if (!validFilterBindings(definition)
                    && !authorizationRule(definition.path("authorization_rule")))
                unresolved(code, name, "NO_FILTER_BINDING");
        }
    }

    /** 仅接受可由查询翻译器参数化执行的表列和运算符，任意字符串不算绑定。 */
    private static boolean filterBinding(JsonNode binding) {
        return binding.isObject()
                && binding.path("table").isTextual()
                && resource(binding.path("table").textValue())
                && textualIdentifier(binding.path("column"))
                && binding.path("operator").isTextual()
                && Set.of("IN", "EQ").contains(binding.path("operator").textValue());
    }

    /** 多表必须显式、互斥、去重；结构层不把文件或HTTP资源当SQL名称。 */
    public static boolean validFilterBindings(JsonNode definition) {
        if (definition.has("filter_binding") && definition.has("filter_bindings")) return false;
        if (definition.has("filter_binding"))
            return filterBinding(definition.get("filter_binding"));
        JsonNode bindings = definition.path("filter_bindings");
        if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 16) return false;
        java.util.Set<String> resources = new java.util.HashSet<>();
        for (JsonNode binding : bindings)
            if (!filterBinding(binding) || !resources.add(binding.path("table").textValue()))
                return false;
        return true;
    }

    /** 协议原始资源标识；真正SQL名称仍由QueryPlanner独立验证。 */
    public static boolean resource(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** 授权规则必须指向受认证身份属性，不能让调用参数替代主体身份。 */
    private static boolean authorizationRule(JsonNode rule) {
        return rule.isObject()
                && "PRINCIPAL_ATTRIBUTE".equals(rule.path("kind").textValue())
                && rule.path("attribute").isTextual()
                && GuardAttributeName.valid(rule.path("attribute").textValue());
    }

    /** 标识符必须来自 JSON 字符串，禁止 asText 将布尔值伪装成合法名称。 */
    private static boolean textualIdentifier(JsonNode value) {
        return value.isTextual() && identifier(value.textValue());
    }

    /** 数据源表列标识符规则；属性命名独立由GuardAttributeName维护，不能顺带扩张SQL名称。 */
    public static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }

    private static void unresolved(String op, String attr, String reason) {
        throw new ContractProblem("GUARD_ATTR_UNRESOLVED", op + "." + attr, reason);
    }
}
