package cn.ontorepo.mapping.application;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.data.domain.AdapterRegistry;
import cn.ontorepo.data.domain.DataProblem;
import cn.ontorepo.data.domain.LabelScope;
import cn.ontorepo.data.domain.QueryResult;
import cn.ontorepo.data.domain.QuerySpec;
import cn.ontorepo.data.domain.SourceSpec;
import cn.ontorepo.data.domain.ValueTransform;
import cn.ontorepo.model.domain.PropertyFacets;
import cn.ontorepo.operation.domain.FrozenPolicy;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 只从冻结载荷编译实例查询；全部守卫、资源与字段检查完成后才解密及访问源。 */
@Component
public class FrozenDataExecutor {
    public record Result(
            List<String> columns,
            List<Map<String, Object>> rows,
            List<Map<String, Object>> evidence) {}

    private final DatasourceService sources;

    public FrozenDataExecutor(DatasourceService sources) {
        this.sources = sources;
    }

    private static Map<String, Object> ref(
            Map<String, Object> payload, String collection, Object id) {
        return rows(payload.get(collection)).stream()
                .filter(x -> Objects.equals(x.get("id"), id))
                .findFirst()
                .orElseThrow(
                        () ->
                                new Problem(
                                        409,
                                        "FROZEN_REFERENCE_INVALID",
                                        "冻结引用不存在: " + collection + " " + id));
    }

    private static boolean sql(Map<String, Object> source) {
        return Set.of("RELATIONAL", "WAREHOUSE").contains(source.get("kind"));
    }

    private static String column(
            Map<String, Object> source, Map<String, Object> object, String field) {
        return sql(source) ? object.get("sourceResource") + "." + field : field;
    }

    private static Map<String, Object> metadata(Map<String, Object> p, Map<String, Object> object) {
        var snapshot = ref(p, "metaSnapshots", object.get("metaSnapshotId"));
        if (!Objects.equals(snapshot.get("datasourceId"), object.get("datasourceId")))
            throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结快照与源不一致");
        return MappingService.resource(snapshot.get("resources"), object.get("sourceResource"));
    }

    private static void project(
            Set<String> projection,
            Map<String, Object> source,
            Map<String, Object> object,
            Collection<String> fields) {
        for (String f : fields) projection.add(column(source, object, f));
    }

    /** 从冻结三层映射编译计划，逐资源守卫检查完成后才允许解密和访问源。 */
    public Result execute(
            Map<String, Object> p, Map<String, Object> op, Object input, LabelScope effective) {
        if (op.get("assistantTool") == null)
            throw new Problem(409, "ASSISTANT_REQUIRED", "实例操作必须具备完整助手声明");
        FrozenGuardBindings.validate(p, op);
        var q = map(op.get("dataQuery"));
        var from = ref(p, "objectMappings", q.get("objectMappingId"));
        var source = ref(p, "datasources", from.get("datasourceId"));
        Map<String, Object> rel = null, to = null;
        List<Map<String, Object>> objects = new ArrayList<>(List.of(from));
        List<QuerySpec.Join> joins = new ArrayList<>();
        if ("RELATION".equals(q.get("mode"))) {
            rel = ref(p, "relationMappings", q.get("relationMappingId"));
            if (!Objects.equals(rel.get("fromObjectMappingId"), from.get("id")))
                throw new Problem(409, "FROZEN_REFERENCE_INVALID", "关系from端不一致");
            to = ref(p, "objectMappings", rel.get("toObjectMappingId"));
            if (!sql(source)
                    || !Objects.equals(from.get("datasourceId"), to.get("datasourceId"))
                    || !Objects.equals(from.get("metaSnapshotId"), to.get("metaSnapshotId"))
                    || !Objects.equals(rel.get("metaSnapshotId"), from.get("metaSnapshotId"))
                    || !Objects.equals(rel.get("datasourceId"), from.get("datasourceId")))
                throw new Problem(400, "JOIN_UNSUPPORTED", "关系查询必须来自同源同快照且协议支持JOIN");
            var join = map(rel.get("join"));
            joins.add(
                    new QuerySpec.Join(
                            to.get("sourceResource").toString(),
                            column(source, from, join.get("fromField").toString()),
                            column(source, to, join.get("toField").toString())));
            MappingService.fields(metadata(p, from), List.of(join.get("fromField").toString()));
            MappingService.fields(metadata(p, to), List.of(join.get("toField").toString()));
            objects.add(to);
        }
        Set<String> access = new HashSet<>();
        node(source.get("accessScope")).forEach(n -> access.add(n.asText()));
        Map<String, Map<String, Object>> byObject = new HashMap<>();
        for (var o : objects) {
            byObject.put(o.get("id").toString(), o);
            if (!covered(source.get("kind").toString(), access, o.get("sourceResource").toString()))
                throw new Problem(403, "SCOPE_EXCEEDED", "冻结映射超出源批准范围");
            metadata(p, o);
            ref(p, "classes", o.get("targetClassId"));
        }
        // 每个JOIN资源都必须由OWNER明确绑定同一必需守卫；不按列名推断其他表的授权。
        var cfg = node(map(p.get("space")).get("guardCfg"));
        var declared = node(op.get("assistantTool")).path("guard_attrs");
        Map<String, String> bindings = new LinkedHashMap<>();
        Set<String> checked = new HashSet<>();
        for (var attr : declared) {
            String name = attr.asText();
            checked.add(name);
            var definition = cfg.path(name);
            for (var object : objects) {
                String resource = object.get("sourceResource").toString();
                JsonNode matched =
                        FrozenGuardBindings.binding(
                                definition, resource, op.get("code").toString(), name);
                String field = matched.path("column").asText();
                MappingService.fields(metadata(p, object), List.of(field));
                if ("EQ".equals(matched.path("operator").asText())
                        && effective.clauses().containsKey(name)
                        && effective.clauses().get(name).size() > 1)
                    throw new Problem(403, "GUARD_SCOPE_OPERATOR_INVALID", "EQ守卫只能绑定至多一个授权值");
                bindings.put(
                        sql(source) && objects.size() > 1 ? name + "@" + resource : name,
                        column(source, object, field));
            }
        }
        if (!checked.containsAll(effective.clauses().keySet()))
            throw new Problem(403, "GUARD_ATTR_UNRESOLVED", "有效授权范围存在未绑定的属性");
        Map<String, List<Map<String, Object>>> candidates = new HashMap<>();
        for (var prop : rows(p.get("propertyMappings")))
            if (byObject.containsKey(prop.get("objectMappingId")))
                candidates
                        .computeIfAbsent(
                                prop.get("targetPropertyId").toString(), x -> new ArrayList<>())
                        .add(prop);
        List<String> propertyIds = new ArrayList<>();
        for (var id : node(q.get("propertyIds"))) propertyIds.add(id.asText());
        Map<String, Map<String, Object>> propertyMappings = new LinkedHashMap<>();
        Map<String, ValueTransform> transformations = new LinkedHashMap<>();
        Set<String> projection = new LinkedHashSet<>();
        for (String id : propertyIds) {
            var pm = unique(candidates, id);
            propertyMappings.put(id, pm);
            transformations.put(id, propertyTransform(p, pm));
            var object = byObject.get(pm.get("objectMappingId"));
            MappingService.fields(metadata(p, object), List.of(pm.get("sourceField").toString()));
            project(projection, source, object, List.of(pm.get("sourceField").toString()));
        }
        for (var o : objects) {
            MappingService.fields(metadata(p, o), keys(o.get("objectKeyExpr").toString()));
            project(projection, source, o, keys(o.get("objectKeyExpr").toString()));
        }
        if (rel != null) {
            MappingService.fields(metadata(p, from), keys(rel.get("fromKeyExpr").toString()));
            MappingService.fields(metadata(p, to), keys(rel.get("toKeyExpr").toString()));
            project(projection, source, from, keys(rel.get("fromKeyExpr").toString()));
            project(projection, source, to, keys(rel.get("toKeyExpr").toString()));
        }
        List<QuerySpec.Filter> filters = new ArrayList<>();
        for (var f : rows(q.get("filters"))) {
            var pm = unique(candidates, f.get("propertyId").toString());
            var object = byObject.get(pm.get("objectMappingId"));
            MappingService.fields(metadata(p, object), List.of(pm.get("sourceField").toString()));
            if (!(input instanceof Map<?, ?> attrs) || !attrs.containsKey(f.get("inputParam")))
                throw new Problem(400, "QUERY_INPUT_MISSING", "查询参数缺失: " + f.get("inputParam"));
            Object value = attrs.get(f.get("inputParam"));
            String operator = f.get("operator").toString();
            List<JsonNode> values = new ArrayList<>();
            if (operator.equals("IN")) {
                if (!(value instanceof List<?> list)) throw Problem.bad("IN参数必须为数组");
                for (Object v : list) values.add(node(v));
            } else values.add(node(value));
            filters.add(
                    new QuerySpec.Filter(
                            column(source, object, pm.get("sourceField").toString()),
                            QuerySpec.Operator.valueOf(operator),
                            values,
                            filterTransform(
                                    transformations.computeIfAbsent(
                                            f.get("propertyId").toString(),
                                            id -> propertyTransform(p, pm)))));
        }
        for (var object : objects)
            if (object.get("filter") != null) {
                var f = map(object.get("filter"));
                MappingService.validateFilter(metadata(p, object), f);
                String operator = f.get("operator").toString();
                List<JsonNode> values = new ArrayList<>();
                if (operator.equals("IN")) {
                    if (!(f.get("value") instanceof List<?> list)) throw Problem.bad("对象IN过滤值须为数组");
                    for (Object v : list) values.add(node(v));
                } else values.add(node(f.get("value")));
                filters.add(
                        new QuerySpec.Filter(
                                column(source, object, f.get("field").toString()),
                                QuerySpec.Operator.valueOf(operator),
                                values));
            }
        var policy = FrozenPolicy.of(p, op);
        // 基数检查必须看同一授权/业务谓词的完整匹配集，不能让业务LIMIT隐藏另一条关联。
        // 受适配器500行硬上限约束；命中预算时无法证明穷尽，明确失败而不是伪造检查通过。
        boolean checkCardinality = rel != null && Boolean.TRUE.equals(rel.get("cardinalityCheck"));
        Map<String, String> primaryIdentities = new HashMap<>();
        for (var object : objects) {
            String primary = provenPrimaryIdentity(p, source, object);
            if (primary != null) primaryIdentities.put(object.get("id").toString(), primary);
        }
        // 普通非主键/复合模板可能有拼接碰撞；同样先核验有界匹配集再应用业务LIMIT。
        // 只有PG单列真实主键的单变量模板可直接借用源约束证明唯一，避免惩罚常规大表查询。
        boolean checkIdentity = primaryIdentities.size() != objects.size();
        var spec =
                new QuerySpec(
                        from.get("sourceResource").toString(),
                        new ArrayList<>(projection),
                        filters,
                        joins,
                        checkCardinality || checkIdentity ? 500 : policy.maxRows(),
                        policy.timeoutMs(),
                        Map.of(),
                        effective,
                        bindings);
        // 此行之前不解密、不访问任何元数据/网络/文件源，历史损坏bundle同样零访问拒绝。
        SourceSpec src = sources.source(source);
        QueryResult queried;
        try {
            queried = new AdapterRegistry().adapter(src).query(src, spec);
        } catch (DataProblem failure) {
            if (failure.code().equals("TRANSFORM_FAILED"))
                throw new Problem(
                        400,
                        "TRANSFORM_FAILED",
                        "冻结过滤属性转换失败: "
                                + rows(q.get("filters")).stream()
                                        .map(filter -> filter.get("propertyId"))
                                        .toList());
            throw failure;
        }
        if (checkCardinality && queried.rows().size() >= 500)
            throw new Problem(
                    400, "CARDINALITY_CHECK_INCOMPLETE", "关系匹配集达到500行完整性预算，无法证明基数检查完整；请缩小业务过滤范围");
        if (checkIdentity && queried.rows().size() >= 500)
            throw new Problem(
                    400,
                    "OBJECT_KEY_CHECK_INCOMPLETE",
                    "对象匹配集达到500行完整性预算，无法证明非主键模板没有重复身份；请缩小业务过滤范围");
        List<Map<String, Object>> output = new ArrayList<>();
        Map<String, Map<String, Object>> seen = new HashMap<>();
        int rowIndex = 0;
        Map<String, Set<String>> fromTargets = new HashMap<>(), toSources = new HashMap<>();
        for (var record : queried.rows()) {
            int evidenceOffset = rowIndex++ * objects.size();
            if (queried.evidence().size() < evidenceOffset + objects.size())
                throw new Problem(409, "SOURCE_EVIDENCE_MISSING", "源记录缺少定位主键证据");
            Map<String, Map<String, Object>> locals = new HashMap<>();
            for (var o : objects) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (var f : rows(metadata(p, o).get("fields")))
                    values.put(
                            f.get("name").toString(),
                            record.get(column(source, o, f.get("name").toString())));
                locals.put(o.get("id").toString(), values);
            }
            String objectId =
                    identity(from.get("objectKeyExpr").toString(), locals.get(from.get("id")));
            verifyIdentity(
                    seen,
                    from,
                    objectId,
                    queried.evidence().get(evidenceOffset).key(),
                    primaryIdentities.get(from.get("id").toString()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("objectId", objectId);
            row.put("classId", from.get("targetClassId"));
            if (rel != null) {
                String target =
                        identity(to.get("objectKeyExpr").toString(), locals.get(to.get("id")));
                verifyIdentity(
                        seen,
                        to,
                        target,
                        queried.evidence().get(evidenceOffset + 1).key(),
                        primaryIdentities.get(to.get("id").toString()));
                String
                        fromId =
                                identity(
                                        rel.get("fromKeyExpr").toString(),
                                        locals.get(from.get("id"))),
                        toId = identity(rel.get("toKeyExpr").toString(), locals.get(to.get("id")));
                if (!fromId.equals(objectId) || !toId.equals(target))
                    throw new Problem(400, "RELATION_KEY_MISMATCH", "关系标识模板与对象身份不一致");
                row.put("relationId", rel.get("relationId"));
                row.put("fromObjectId", fromId);
                row.put("toObjectId", toId);
                fromTargets.computeIfAbsent(fromId, x -> new HashSet<>()).add(toId);
                toSources.computeIfAbsent(toId, x -> new HashSet<>()).add(fromId);
            }
            for (var entry : propertyMappings.entrySet()) {
                String id = entry.getKey();
                var pm = entry.getValue();
                Object value;
                try {
                    value =
                            transformations
                                    .get(id)
                                    .apply(
                                            sourceScalar(
                                                    transformations.get(id),
                                                    locals.get(pm.get("objectMappingId"))
                                                            .get(pm.get("sourceField"))));
                } catch (DataProblem failure) {
                    throw new Problem(400, "TRANSFORM_FAILED", "属性转换失败: " + id);
                }
                var property = ref(p, "properties", id);
                if (value == null
                        && (Boolean.TRUE.equals(pm.get("required"))
                                || Boolean.TRUE.equals(property.get("required"))))
                    throw new Problem(400, "REQUIRED_VALUE_MISSING", "必填属性取值为空: " + id);
                if (value != null)
                    PropertyFacets.scalar(property.get("rangeType").toString(), value);
                row.put(id, value);
            }
            output.add(row);
        }
        if (checkCardinality) {
            var ontology = ref(p, "relations", rel.get("relationId"));
            String card = ontology.get("cardinality").toString();
            if ((Set.of("ONE_TO_ONE", "MANY_TO_ONE").contains(card)
                            && fromTargets.values().stream().anyMatch(v -> v.size() > 1))
                    || (Set.of("ONE_TO_ONE", "ONE_TO_MANY").contains(card)
                            && toSources.values().stream().anyMatch(v -> v.size() > 1)))
                throw new Problem(400, "CARDINALITY_VIOLATION", "实际关系结果不满足冻结基数约束");
        }
        if (output.size() > policy.maxRows())
            output = new ArrayList<>(output.subList(0, policy.maxRows()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        // 截断业务结果时同步截断源证据，不能把完整性检查多读的行误报为实际返回数据。
        for (var e : queried.evidence().subList(0, output.size() * objects.size()))
            evidence.add(
                    Map.of(
                            "kind",
                            "SOURCE_RECORD",
                            "ref",
                            Map.of(
                                    "sourceId",
                                    e.sourceId(),
                                    "resource",
                                    e.resource(),
                                    "key",
                                    e.key())));
        Set<Object> snapshots = new LinkedHashSet<>();
        for (var o : objects) snapshots.add(o.get("metaSnapshotId"));
        for (var id : snapshots) {
            var snap = ref(p, "metaSnapshots", id);
            evidence.add(
                    Map.of(
                            "kind",
                            "META_SNAPSHOT",
                            "ref",
                            Map.of("id", id, "sha256", snap.get("sha256"))));
        }
        evidence.add(
                Map.of(
                        "kind",
                        "MAPPING_SNAPSHOT",
                        "ref",
                        Map.of(
                                "sha256",
                                p.get("mappingSha256"),
                                "objectMappingIds",
                                objects.stream().map(o -> o.get("id")).toList())));
        List<String> columns = new ArrayList<>(List.of("objectId", "classId"));
        if (rel != null) columns.addAll(List.of("relationId", "fromObjectId", "toObjectId"));
        columns.addAll(propertyIds);
        return new Result(columns, output, evidence);
    }

    /** 只证明同一原生标量列的单变量模板；复合拼接、物理定位键及非唯一排序键均不推断唯一。 */
    private static String provenPrimaryIdentity(
            Map<String, Object> payload, Map<String, Object> source, Map<String, Object> object) {
        if (!"RELATIONAL".equals(source.get("kind"))) return null;
        var resource = metadata(payload, object);
        var primary = node(resource.get("primaryKey"));
        if (!primary.isArray() || primary.size() != 1) return null;
        String field = primary.get(0).asText();
        if (!keys(object.get("objectKeyExpr").toString()).equals(Set.of(field))) return null;
        // 二进制、时间戳和扩展对象的Java字符串表示不作为可证明的稳定身份编码。
        Set<String> scalarTypes =
                Set.of(
                        "text", "varchar", "bpchar", "int2", "int4", "int8", "numeric", "float4",
                        "float8", "uuid", "date", "bool");
        return rows(resource.get("fields")).stream()
                        .anyMatch(
                                f ->
                                        field.equals(f.get("name"))
                                                && scalarTypes.contains(
                                                        Objects.toString(f.get("type"))
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT)))
                ? field
                : null;
    }

    /** 输出与过滤共用相同冻结转换，避免码表或严格类型口径在两条路径漂移。 */
    private static ValueTransform propertyTransform(
            Map<String, Object> payload, Map<String, Object> mapping) {
        var property = ref(payload, "properties", mapping.get("targetPropertyId"));
        return valueTransform(
                mapping.get("transform") == null ? null : map(mapping.get("transform")),
                rows(payload.get("codebooks")),
                property.get("rangeType").toString());
    }

    /** 原生DATE只在JSON输出边界编码为ISO日期；不把任意字符串或时间戳放宽为日期。 */
    private static Object sourceScalar(ValueTransform transformation, Object value) {
        if (transformation.kind() == ValueTransform.Kind.IDENTITY
                && "DATE".equals(transformation.rangeType())) {
            if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
            if (value instanceof java.time.LocalDate date) return date.toString();
        }
        return value;
    }

    /** IDENTITY过滤保持原值谓词兼容；目标类型在实际投影输出严格验证，不伪装为源端转换。 */
    private static ValueTransform filterTransform(ValueTransform transform) {
        return transform.kind() == ValueTransform.Kind.IDENTITY
                ? ValueTransform.identity()
                : transform;
    }

    private static Map<String, Object> unique(
            Map<String, List<Map<String, Object>>> mappings, String id) {
        var candidates = mappings.getOrDefault(id, List.of());
        if (candidates.size() != 1)
            throw new Problem(400, "AMBIGUOUS_PROPERTY_MAPPING", "冻结属性映射不存在或不唯一: " + id);
        return candidates.getFirst();
    }

    private static void verifyIdentity(
            Map<String, Map<String, Object>> seen,
            Map<String, Object> object,
            String id,
            Map<String, Object> values,
            String provenPrimaryField) {
        // 适配器证据使用当前真实主键；冻后主键声明改变时，不能继续沿用旧证明。
        if (provenPrimaryField != null && !values.keySet().equals(Set.of(provenPrimaryField)))
            throw new Problem(409, "SOURCE_KEY_DRIFT", "源实际主键与冻结身份约束不一致");
        String key = object.get("id") + ":" + id;
        var previous = seen.putIfAbsent(key, values);
        if (previous != null && !previous.equals(values))
            throw new Problem(400, "OBJECT_KEY_DUPLICATE", "不同源记录构造重复对象标识: " + id);
    }
}
