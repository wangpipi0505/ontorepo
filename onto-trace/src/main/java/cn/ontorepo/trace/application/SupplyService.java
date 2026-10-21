package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.contract.*;
import cn.ontorepo.data.domain.LabelScope;
import cn.ontorepo.operation.domain.FrozenPolicy;
import cn.ontorepo.space.application.GuardService;

import com.fasterxml.jackson.databind.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** 供给编译与统一冻结守卫复核；不依赖任何草稿操作或客户端主体属性。 */
@Service
public class SupplyService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final GuardService guards;
    private final String baseUrl;

    public SupplyService(
            GuardService guards,
            @Value("${onto.service-base-url:http://127.0.0.1:18182}") String baseUrl) {
        this.guards = guards;
        this.baseUrl = baseUrl;
    }

    public JsonNode operation(Map<String, Object> p, Map<String, Object> op) {
        var node = JSON.createObjectNode();
        node.put("code", op.get("code").toString());
        node.set("input_schema", inputSchema(op));
        node.set("output_schema", JSON.valueToTree(op.get("outputSchema")));
        node.set("assistant_tool", JSON.valueToTree(op.get("assistantTool")));
        var policy = FrozenPolicy.of(p, op);
        node.set(
                "policy",
                JSON.valueToTree(
                        Map.of("max_rows", policy.maxRows(), "timeout_ms", policy.timeoutMs())));
        return node;
    }

    @SuppressWarnings("unchecked")
    private JsonNode cfg(Map<String, Object> p) {
        return JSON.valueToTree(
                ((Map<String, Object>) p.get("space")).getOrDefault("guardCfg", Map.of()));
    }

    /** MODEL_ONLY 不生成供给契约，但可作为纯模型发布（DEC-1=A）；缺能力标记的旧候选仍不默认通过。 */
    public List<JsonNode> manifests(Map<String, Object> p, String candidate) {
        if ("MODEL_ONLY".equals(p.get("capability"))) return List.of();
        if (!"CONTRACT".equals(p.get("capability")))
            throw new Problem(409, "CONTRACT_NOT_AVAILABLE", "该候选仅具备MODEL_ONLY本地模型能力");
        List<JsonNode> result = new ArrayList<>();
        for (var op : cn.ontorepo.knowledge.application.FrozenKnowledge.rows(p, "operations"))
            result.add(preview(p, op, candidate));
        return result;
    }

    private JsonNode preview(Map<String, Object> p, Map<String, Object> op, String candidate) {
        // 契约成功必须意味着实例过滤可被翻译；规则-only不能推迟到源执行时才暴露缺绑定。
        GuardValidator.validate(operation(p, op), cfg(p));
        cn.ontorepo.mapping.application.FrozenGuardBindings.validate(p, op);
        return new ContractGenerator().preview(operation(p, op), cfg(p), baseUrl, "", candidate);
    }

    /** 六步：入口会话、角色、冻结守卫完整性、当前主体授权、空间/策略范围、执行绑定适用性。 */
    public LabelScope authorize(
            UUID u,
            UUID s,
            Map<String, Object> p,
            Map<String, Object> op,
            String caller,
            String candidate) {
        return authorize(u, s, p, op, caller, candidate, null);
    }

    /** 端点范围只能由受信服务入口传入；试执行/本地测试取同源空间范围作为等价入口层。 */
    public LabelScope authorize(
            UUID u,
            UUID s,
            Map<String, Object> p,
            Map<String, Object> op,
            String caller,
            String candidate,
            LabelScope endpointScope) {
        return authorize(u, s, p, op, caller, candidate, endpointScope, null);
    }

    /** 授权事实由服务端逐步追加，守卫复核必须发生在空间范围读取之前。 */
    public LabelScope authorize(
            UUID u,
            UUID s,
            Map<String, Object> p,
            Map<String, Object> op,
            String caller,
            String candidate,
            LabelScope endpointScope,
            List<Map<String, Object>> authorization) {
        JsonNode node = operation(p, op);
        boolean declared = op.get("assistantTool") != null;
        boolean instance = op.get("dataQuery") != null;
        if (("SERVICE".equals(caller) || "EXTERNAL".equals(caller))
                && !"CONTRACT".equals(p.get("capability")))
            throw new Problem(409, "CONTRACT_NOT_AVAILABLE", "MODEL_ONLY候选不可作为内部供给服务调用");
        if (!declared) {
            if (instance) throw new Problem(409, "ASSISTANT_REQUIRED", "实例操作必须具备完整助手声明与守卫");
            if ("SERVICE".equals(caller)
                    || "EXTERNAL".equals(caller)
                    || !Set.of("OBJECT_QUERY", "RELATION_QUERY", "RULE_CHECK", "SCORING")
                            .contains(op.getOrDefault("kind", "OBJECT_QUERY").toString()))
                throw new Problem(409, "ASSISTANT_REQUIRED", "外部操作必须具备完整助手声明与守卫");
            var grant = guards.grant(u, s);
            if (authorization != null) {
                authorization.add(
                        Map.of(
                                "order",
                                3,
                                "kind",
                                "FROZEN_GUARD_INTEGRITY",
                                "passed",
                                true,
                                "detail",
                                "旧MODEL_ONLY仅供本地模型兼容"));
                authorization.add(
                        Map.of(
                                "order",
                                4,
                                "kind",
                                "SPACE_READ",
                                "passed",
                                true,
                                "detail",
                                "当前空间授权已核验"));
            }
            // 纯P1本地兼容仍不能绕过明确授权范围。
            if (!LabelScope.parse(JSON.valueToTree(grant.get("dataScope"))).clauses().isEmpty())
                throw new Problem(403, "GUARD_BINDING_NOT_APPLICABLE", "模型操作不能证明当前数据范围");
            return LabelScope.unrestricted();
        }
        GuardValidator.validate(node, cfg(p));
        preview(p, op, candidate);
        if (authorization != null)
            authorization.add(
                    Map.of(
                            "order",
                            3,
                            "kind",
                            "FROZEN_GUARD_INTEGRITY",
                            "passed",
                            true,
                            "detail",
                            "冻结守卫定义、类型及执行绑定完整"));
        Map<String, Object> grant;
        try {
            grant = endpointScope == null ? guards.grant(u, s) : guards.readGrant(u, s);
        } catch (Problem e) {
            if (endpointScope != null && e.status == 403)
                throw new Problem(403, "NO_PERMISSION", "没有该版本空间的读取授权");
            throw e;
        }
        if (authorization != null)
            authorization.add(
                    Map.of(
                            "order",
                            4,
                            "kind",
                            "SPACE_READ",
                            "passed",
                            true,
                            "detail",
                            "当前空间读取授权存在"));
        JsonNode attrs = JSON.valueToTree(grant.get("attributes"));
        LabelScope scope = LabelScope.parse(JSON.valueToTree(grant.get("dataScope")));
        scope = LabelScope.intersect(scope, endpointScope == null ? scope : endpointScope);
        for (Object extra : FrozenPolicy.of(p, op).scopes())
            scope = LabelScope.intersect(scope, LabelScope.parse(JSON.valueToTree(extra)));
        Set<String> checked = new HashSet<>();
        for (JsonNode attr : node.path("assistant_tool").path("guard_attrs")) {
            String name = attr.asText();
            JsonNode def = cfg(p).path(name), rule = def.path("authorization_rule");
            // 即使同时存在主体授权规则，模型路径也不能静默丢掉声明的源列过滤。
            if (instance) {
                for (JsonNode value : scope.clauses().getOrDefault(name, Set.of())) {
                    // LabelScope为数值集合去重而规范化为DecimalNode；整数语义不能依赖原JSON节点类。
                    boolean integral =
                            "INTEGER".equals(def.path("type").asText())
                                    && value.isNumber()
                                    && value.decimalValue().stripTrailingZeros().scale() <= 0;
                    if (!integral) GuardService.typed(def.path("type").asText(), value);
                }
                // 实例源守卫由冻结翻译器逐资源验证；主体规则若同时声明仍必须满足。
                if (rule.isObject()) {
                    JsonNode value = attrs.path(rule.path("attribute").asText());
                    GuardService.typed(def.path("type").asText(), value);
                    if (!scope.allows(name, value))
                        throw new Problem(403, "DATA_SCOPE_DENIED", "当前主体属性超出授权范围");
                }
                checked.add(name);
                continue;
            }
            if (def.has("filter_binding")
                    || def.has("filter_bindings")
                    || !"PRINCIPAL_ATTRIBUTE".equals(rule.path("kind").asText()))
                throw new Problem(403, "GUARD_BINDING_NOT_APPLICABLE", "模型查询不支持数据源列过滤守卫");
            JsonNode value = attrs.path(rule.path("attribute").asText());
            GuardService.typed(def.path("type").asText(), value);
            if (!scope.allows(name, value))
                throw new Problem(403, "DATA_SCOPE_DENIED", "当前主体属性超出授权范围");
            checked.add(name);
        }
        if (!checked.containsAll(scope.clauses().keySet()))
            throw new Problem(403, "GUARD_BINDING_NOT_APPLICABLE", "模型查询不能证明未绑定的数据范围");
        return scope;
    }

    public void input(Map<String, Object> op, Object value) {
        if (op.get("assistantTool") != null)
            JsonSchemaAssertions.validate(inputSchema(op), JSON.valueToTree(value));
    }

    /** 规则/评分只消费属性对象；在原Schema根追加合取约束，保留原类型和所有本地引用路径。 */
    private JsonNode inputSchema(Map<String, Object> op) {
        JsonNode schema = JSON.valueToTree(op.get("inputSchema"));
        if (Set.of("RULE_CHECK", "SCORING").contains(op.getOrDefault("kind", "OBJECT_QUERY"))
                && schema instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            JsonSchemaAssertions.validateSchema(schema);
            object.withArray("allOf").addObject().put("type", "object");
        }
        return schema;
    }

    public void output(Map<String, Object> op, Object value) {
        if (op.get("assistantTool") != null)
            JsonSchemaAssertions.validate(
                    JSON.valueToTree(op.get("outputSchema")), JSON.valueToTree(value));
    }
}
