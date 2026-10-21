package cn.ontorepo.mapping.domain;

import static cn.ontorepo.mapping.domain.MappingValues.map;
import static cn.ontorepo.mapping.domain.MappingValues.rows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 操作对三层映射的统一依赖遍历；影响、删除、目录和元数据diff不能各自猜测关联端。 */
public final class MappingDependencies {
    private MappingDependencies() {}

    /** 输入集合已由服务端按空间限定；只返回安全操作定位，不参与任何查询执行。 */
    public static List<Map<String, Object>> operations(
            List<Map<String, Object>> mappings,
            List<Map<String, Object>> operations,
            String mappingId) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var row : mappings) {
            var definition = map(row.get("definition"));
            definition.put("kind", row.get("kind"));
            byId.put(row.get("id").toString(), definition);
        }
        var target = byId.get(mappingId);
        if (target == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var operation : operations) {
            if (operation.get("dataQuery") == null) continue;
            var query = map(operation.get("dataQuery"));
            Set<String> objects = new HashSet<>();
            objects.add(Objects.toString(query.get("objectMappingId"), ""));
            var relation = byId.get(Objects.toString(query.get("relationMappingId"), ""));
            if (relation != null) {
                objects.add(Objects.toString(relation.get("fromObjectMappingId"), ""));
                objects.add(Objects.toString(relation.get("toObjectMappingId"), ""));
            }
            boolean depends =
                    switch (Objects.toString(target.get("kind"))) {
                        case "OBJECT" -> objects.contains(mappingId);
                        case "RELATION" -> mappingId.equals(query.get("relationMappingId"));
                        case "PROPERTY" ->
                                objects.contains(target.get("objectMappingId"))
                                        && propertyUsed(query, target.get("targetPropertyId"));
                        default -> false;
                    };
            if (depends)
                result.add(
                        Map.of(
                                "id",
                                operation.get("id"),
                                "code",
                                operation.get("code"),
                                "name",
                                operation.get("name")));
        }
        return result;
    }

    private static boolean propertyUsed(Map<String, Object> query, Object property) {
        if (query.get("propertyIds") instanceof List<?> ids && ids.contains(property)) return true;
        return rows(query.get("filters")).stream()
                .anyMatch(filter -> Objects.equals(filter.get("propertyId"), property));
    }
}
