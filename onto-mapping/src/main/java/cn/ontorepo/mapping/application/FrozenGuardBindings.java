package cn.ontorepo.mapping.application;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.contract.ContractProblem;
import cn.ontorepo.contract.GuardValidator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 冻结契约与实例执行共用的资源绑定证明；只读冻结映射及元数据，绝不访问数据源。 */
public final class FrozenGuardBindings {
    private FrozenGuardBindings() {}

    /** 主体授权规则适用于纯模型；实例操作还必须逐资源具备真实列过滤，二者不能相互替代。 */
    public static void validate(Map<String, Object> payload, Map<String, Object> operation) {
        if (operation.get("dataQuery") == null) return;
        var query = map(operation.get("dataQuery"));
        var from = reference(payload, "objectMappings", query.get("objectMappingId"));
        var objects = new ArrayList<>(List.of(from));
        if ("RELATION".equals(query.get("mode"))) {
            var relation = reference(payload, "relationMappings", query.get("relationMappingId"));
            if (!Objects.equals(relation.get("fromObjectMappingId"), from.get("id")))
                throw new Problem(409, "FROZEN_REFERENCE_INVALID", "关系from端不一致");
            objects.add(reference(payload, "objectMappings", relation.get("toObjectMappingId")));
        }
        var cfg = node(map(payload.get("space")).get("guardCfg"));
        for (var attr : node(operation.get("assistantTool")).path("guard_attrs")) {
            String name = attr.asText();
            for (var object : objects) {
                var bound =
                        binding(
                                cfg.path(name),
                                object.get("sourceResource").toString(),
                                operation.get("code").toString(),
                                name);
                var snapshot = reference(payload, "metaSnapshots", object.get("metaSnapshotId"));
                if (!Objects.equals(snapshot.get("datasourceId"), object.get("datasourceId")))
                    throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结快照与源不一致");
                var resource =
                        MappingService.resource(
                                snapshot.get("resources"), object.get("sourceResource"));
                String field = bound.path("column").asText();
                if (rows(resource.get("fields")).stream()
                        .noneMatch(column -> field.equals(column.get("name"))))
                    throw new ContractProblem(
                            "GUARD_ATTR_UNRESOLVED",
                            operation.get("code") + "." + name,
                            "FIELD_NOT_IN_SNAPSHOT:" + object.get("sourceResource") + "." + field);
            }
        }
    }

    /** 返回该资源唯一合法绑定；所有入口沿用同一错误码和明细。 */
    public static JsonNode binding(
            JsonNode definition, String resource, String operation, String name) {
        if (!GuardValidator.validFilterBindings(definition))
            throw new ContractProblem(
                    "GUARD_ATTR_UNRESOLVED", operation + "." + name, "NO_FILTER_BINDING");
        var bindings =
                definition.has("filter_bindings")
                        ? definition.get("filter_bindings")
                        : JSON.createArrayNode().add(definition.get("filter_binding"));
        for (var binding : bindings)
            if (resource.equals(binding.path("table").asText())) return binding;
        throw new ContractProblem(
                "GUARD_ATTR_UNRESOLVED",
                operation + "." + name,
                "RESOURCE_BINDING_MISSING:" + resource);
    }

    private static Map<String, Object> reference(
            Map<String, Object> payload, String collection, Object id) {
        return rows(payload.get(collection)).stream()
                .filter(row -> Objects.equals(row.get("id"), id))
                .findFirst()
                .orElseThrow(
                        () ->
                                new Problem(
                                        409, "FROZEN_REFERENCE_INVALID", "冻结引用不存在: " + collection));
    }
}
