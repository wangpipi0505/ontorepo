package cn.ontorepo.mapping.application;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.UUID;

/** 三层映射与操作模板的草稿应用服务；所有引用在空间锁内完整校验。 */
@Service
public class MappingService {
    private static final Set<String> MODEL_TABLES =
            Set.of("ont_class", "ont_property", "ont_codebook", "ont_relation", "ont_operation");
    private final Db db;
    private final SpaceService spaces;
    private final DatasourceService sources;

    public MappingService(Db db, SpaceService spaces, DatasourceService sources) {
        this.db = db;
        this.spaces = spaces;
        this.sources = sources;
    }

    private static String kind(String category) {
        return switch (category) {
            case "objects" -> "OBJECT";
            case "properties" -> "PROPERTY";
            case "relations" -> "RELATION";
            default -> throw Problem.bad("映射类别无效");
        };
    }

    /** 读取指定层映射并展开稳定ID，调用方负责已建立的空间授权。 */
    public List<Map<String, Object>> all(UUID s, String category) {
        return db
                .list(
                        "SELECT id,definition FROM data_mapping WHERE space_id=? AND kind=? ORDER"
                                + " BY id",
                        s,
                        kind(category))
                .stream()
                .map(
                        x -> {
                            var d = map(x.get("definition"));
                            d.put("id", x.get("id"));
                            return d;
                        })
                .toList();
    }

    /** 读取同空间三层定义，供工作台统一展示。 */
    public Map<String, Object> list(UUID u, UUID s) {
        spaces.role(u, s);
        return Map.of(
                "objects",
                all(s, "objects"),
                "properties",
                all(s, "properties"),
                "relations",
                all(s, "relations"));
    }

    /** 三层引用必须属于当前空间和指定实体类别。 */
    public Map<String, Object> owned(UUID s, Object id, String category) {
        return all(s, category).stream()
                .filter(x -> Objects.equals(x.get("id"), Objects.toString(id)))
                .findFirst()
                .orElseThrow(
                        () -> new Problem(400, "CROSS_SPACE_REFERENCE", "映射不存在或不属于当前空间: " + id));
    }

    /** 仅允许服务端固定表名，核对本体实体的同空间归属。 */
    public Map<String, Object> model(UUID s, String table, Object id) {
        if (table == null || !MODEL_TABLES.contains(table)) throw Problem.bad("本体实体类别无效");
        var r = db.list("SELECT * FROM " + table + " WHERE id=? AND space_id=?", Input.uuid(id), s);
        if (r.isEmpty()) throw new Problem(400, "CROSS_SPACE_REFERENCE", "本体引用不属于当前空间");
        return r.getFirst();
    }

    /** 只从对应不可变快照读取精确资源，不从当前源重新推断。 */
    public Map<String, Object> resource(UUID s, Map<String, Object> object) {
        sources.owned(s, object.get("datasourceId"));
        var snap = sources.snapshot(s, object.get("datasourceId"), object.get("metaSnapshotId"));
        return resource(snap.get("resources"), object.get("sourceResource"));
    }

    /** 只从对应不可变快照读取精确资源，不从当前源重新推断。 */
    public static Map<String, Object> resource(Object resources, Object name) {
        return rows(resources).stream()
                .filter(r -> Objects.equals(r.get("name"), name))
                .findFirst()
                .orElseThrow(
                        () -> new Problem(400, "RESOURCE_NOT_IN_SNAPSHOT", "资源不在冻结元数据: " + name));
    }

    /** 字段白名单来自快照，不允许任意SQL或表达式作为字段。 */
    public static void fields(Map<String, Object> r, Collection<String> required) {
        Set<String> names = new HashSet<>();
        for (var f : rows(r.get("fields"))) names.add(f.get("name").toString());
        if (!names.containsAll(required))
            throw new Problem(
                    400, "FIELD_NOT_IN_SNAPSHOT", "字段不在元数据资源: " + r.get("name") + " " + required);
    }

    @Transactional
    /** 空间行锁内完整验证映射DSL及所有跨层引用，再写修订与审计。 */
    public Map<String, Object> save(UUID u, UUID id, String category, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        UUID key = id == null ? UUID.randomUUID() : id;
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "MAPPING_" + kind(category) + "_SAVE",
                new cn.ontorepo.common.domain.AuditDetail(
                        "MAPPING",
                        key.toString(),
                        Map.of("kind", kind(category)),
                        null,
                        null,
                        id == null ? "CREATED" : "UPDATED"),
                () -> {
                    if (id != null) owned(s, id, category);
                    var d = new LinkedHashMap<>(b);
                    d.remove("spaceId");
                    d.remove("expectedDraftRev");
                    d.remove("id");
                    validate(s, category, d, id);
                    db.update(
                            "INSERT INTO data_mapping(id,space_id,kind,definition)"
                                    + " VALUES(?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET"
                                    + " definition=excluded.definition",
                            key,
                            s,
                            kind(category),
                            db.json(d));
                });
    }

    private void validate(UUID s, String category, Map<String, Object> d, UUID currentId) {
        if (category.equals("objects")) {
            only(
                    d,
                    Set.of(
                            "datasourceId",
                            "sourceResource",
                            "targetClassId",
                            "objectKeyExpr",
                            "filter",
                            "metaSnapshotId"));
            model(s, "ont_class", d.get("targetClassId"));
            var src = sources.owned(s, d.get("datasourceId"));
            var r = resource(s, d);
            Set<String> access = new HashSet<>();
            node(src.get("accessScope")).forEach(x -> access.add(x.asText()));
            if (!covered(src.get("kind").toString(), access, r.get("name").toString()))
                throw new Problem(403, "SCOPE_EXCEEDED", "映射资源超出批准范围");
            fields(r, keys(Input.text(d, "objectKeyExpr", 1000)));
            if (d.get("filter") != null) validateFilter(r, map(d.get("filter")));
        } else if (category.equals("properties")) {
            only(
                    d,
                    Set.of(
                            "objectMappingId",
                            "sourceField",
                            "targetPropertyId",
                            "transform",
                            "required"));
            var o = owned(s, d.get("objectMappingId"), "objects");
            var p = model(s, "ont_property", d.get("targetPropertyId"));
            if (!Objects.equals(o.get("targetClassId"), p.get("domainClassId")))
                throw Problem.bad("属性不属于对象映射的本体类");
            String sourceField = Input.text(d, "sourceField", 128);
            fields(resource(s, o), List.of(sourceField));
            if (!(d.get("required") instanceof Boolean)) throw Problem.bad("required必须为布尔值");
            Map<String, Object> transform =
                    d.get("transform") == null ? null : map(d.get("transform"));
            if (transform != null) {
                only(transform, Set.of("kind", "codebookId"));
                if (!Set.of("IDENTITY", "TO_STRING", "TO_NUMBER", "TO_BOOLEAN", "CODEBOOK")
                        .contains(transform.get("kind")))
                    throw Problem.bad("不支持任意映射表达式");
                if ("CODEBOOK".equals(transform.get("kind")))
                    model(s, "ont_codebook", transform.get("codebookId"));
            }
            requireTypeCompatible(resource(s, o), sourceField, p, transform);
            for (var pmap : all(s, "properties"))
                if (!Objects.equals(pmap.get("id"), currentId == null ? null : currentId.toString())
                        && Objects.equals(pmap.get("objectMappingId"), d.get("objectMappingId"))
                        && Objects.equals(pmap.get("targetPropertyId"), d.get("targetPropertyId")))
                    throw new Problem(
                            409,
                            "PROPERTY_MAPPING_DUPLICATE",
                            "同一对象映射与本体属性只允许一个属性映射: " + pmap.get("id"));
        } else {
            only(
                    d,
                    Set.of(
                            "datasourceId",
                            "relationId",
                            "fromObjectMappingId",
                            "toObjectMappingId",
                            "fromKeyExpr",
                            "toKeyExpr",
                            "join",
                            "cardinalityCheck",
                            "metaSnapshotId"));
            var from = owned(s, d.get("fromObjectMappingId"), "objects");
            var to = owned(s, d.get("toObjectMappingId"), "objects");
            var r = model(s, "ont_relation", d.get("relationId"));
            if (!Objects.equals(from.get("targetClassId"), r.get("fromClassId"))
                    || !Objects.equals(to.get("targetClassId"), r.get("toClassId")))
                throw Problem.bad("关系两端与本体关系不一致");
            if (!Objects.equals(d.get("datasourceId"), from.get("datasourceId"))
                    || !Objects.equals(d.get("datasourceId"), to.get("datasourceId"))
                    || !Objects.equals(d.get("metaSnapshotId"), from.get("metaSnapshotId"))
                    || !Objects.equals(d.get("metaSnapshotId"), to.get("metaSnapshotId")))
                throw Problem.bad("关系两端必须来自同一源与快照");
            if (Objects.equals(from.get("sourceResource"), to.get("sourceResource")))
                throw Problem.bad("当前适配器不支持同表别名自连接");
            var src = sources.owned(s, d.get("datasourceId"));
            if (!Set.of("RELATIONAL", "WAREHOUSE").contains(src.get("kind")))
                throw new Problem(400, "JOIN_UNSUPPORTED", "当前协议不支持三层映射的等值JOIN");
            fields(resource(s, from), keys(Input.text(d, "fromKeyExpr", 1000)));
            fields(resource(s, to), keys(Input.text(d, "toKeyExpr", 1000)));
            var join = map(d.get("join"));
            only(join, Set.of("fromField", "toField"));
            fields(resource(s, from), List.of(Input.text(join, "fromField", 128)));
            fields(resource(s, to), List.of(Input.text(join, "toField", 128)));
            if (!(d.get("cardinalityCheck") instanceof Boolean))
                throw Problem.bad("cardinalityCheck必须为布尔值");
        }
    }

    /** 源字段类型与本体属性类型在保存期即校验；快照缺类型时保留运行期判定，不伪造兼容。 */
    private void requireTypeCompatible(
            Map<String, Object> resource,
            String sourceField,
            Map<String, Object> property,
            Map<String, Object> transform) {
        String source = sourceType(resource, sourceField);
        String target = Objects.toString(property.get("rangeType"), "");
        if (source == null || target.isBlank()) return;
        String kind = transform == null ? "IDENTITY" : Objects.toString(transform.get("kind"), "");
        boolean compatible =
                switch (kind) {
                    case "TO_STRING" -> target.equals("STRING");
                    case "TO_NUMBER" ->
                            Set.of("INTEGER", "DECIMAL").contains(target)
                                    && Set.of("STRING", "INTEGER", "DECIMAL").contains(source);
                    case "TO_BOOLEAN" ->
                            target.equals("BOOLEAN") && Set.of("STRING", "BOOLEAN").contains(source);
                    case "CODEBOOK" -> target.equals("STRING") && source.equals("STRING");
                    default ->
                            source.equals(target)
                                    || (source.equals("INTEGER") && target.equals("DECIMAL"));
                };
        if (!compatible)
            throw new Problem(
                    409,
                    "MAPPING_TYPE_INCOMPATIBLE",
                    "源字段 " + sourceField + "(" + source + ") 与属性类型 " + target + " 不兼容");
    }

    private static String sourceType(Map<String, Object> resource, String sourceField) {
        var fields = node(resource.get("fields"));
        if (!fields.isArray()) return null;
        for (var field : fields)
            if (sourceField.equals(field.path("name").asText()))
                return normalizeType(field.path("type").asText());
        return null;
    }

    private static String normalizeType(String raw) {
        String type = raw.toUpperCase(java.util.Locale.ROOT);
        if (type.contains("CHAR")
                || type.contains("TEXT")
                || type.contains("UUID")
                || type.contains("JSON")
                || type.contains("ENUM")) return "STRING";
        if (type.contains("BOOL")) return "BOOLEAN";
        if (type.contains("DATE") || type.contains("TIME")) return "DATE";
        if (type.contains("INT") || type.contains("SERIAL")) return "INTEGER";
        if (type.contains("NUM")
                || type.contains("DEC")
                || type.contains("REAL")
                || type.contains("DOUBLE")
                || type.contains("FLOAT")
                || type.contains("MONEY")) return "DECIMAL";
        return null;
    }

    private static void only(Map<String, Object> d, Set<String> allowed) {
        if (!allowed.containsAll(d.keySet())) throw Problem.bad("定义包含未声明字段");
    }

    /** 对象固定过滤只允许结构化标量谓词。 */
    public static void validateFilter(Map<String, Object> r, Map<String, Object> f) {
        only(f, Set.of("field", "operator", "value"));
        fields(r, List.of(Input.text(f, "field", 128)));
        if (!Set.of("EQ", "IN", "GT", "GE", "LT", "LE").contains(f.get("operator")))
            throw Problem.bad("对象过滤运算符无效");
        if (!node(f.get("value")).isValueNode() && !node(f.get("value")).isArray())
            throw Problem.bad("对象过滤值必须为标量或数组");
    }

    @Transactional
    /** 阻断被属性、关系或操作继续引用的映射。 */
    public Map<String, Object> delete(UUID u, UUID id, String category, UUID s, long rev) {
        return spaces.write(
                u,
                s,
                rev,
                "MAPPING_DELETE",
                new cn.ontorepo.common.domain.AuditDetail(
                        "MAPPING",
                        id.toString(),
                        Map.of("kind", kind(category)),
                        null,
                        null,
                        "DELETED"),
                () -> {
                    owned(s, id, category);
                    var dependent =
                            db.list(
                                    "SELECT id FROM data_mapping WHERE space_id=? AND"
                                            + " (definition->>'objectMappingId'=? OR"
                                            + " definition->>'fromObjectMappingId'=? OR"
                                            + " definition->>'toObjectMappingId'=?)",
                                    s,
                                    id.toString(),
                                    id.toString(),
                                    id.toString());
                    if (!dependent.isEmpty()
                            || !impact(u, s, category, id).get("operations").equals(List.of()))
                        throw new Problem(409, "MAPPING_IN_USE", "映射仍被引用: " + id);
                    db.update("DELETE FROM data_mapping WHERE id=? AND space_id=?", id, s);
                });
    }

    /** 独立读取操作实例查询定义，保留纯模型查询兼容。 */
    public Map<String, Object> dataQuery(UUID u, UUID s, UUID op) {
        spaces.role(u, s);
        var o = model(s, "ont_operation", op);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operationId", op.toString());
        out.put("dataQuery", o.get("dataQuery"));
        return out;
    }

    @Transactional
    /** 实例模板仅保存本体属性与映射ID，不接受原始查询字符串。 */
    public Map<String, Object> saveDataQuery(UUID u, UUID op, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "DATA_QUERY_SAVE",
                new cn.ontorepo.common.domain.AuditDetail(
                        "OPERATION", op.toString(), null, null, null, "UPDATED"),
                () -> {
                    model(s, "ont_operation", op);
                    Object value = b.get("dataQuery");
                    if (value != null) validateQuery(s, map(value));
                    db.update(
                            "UPDATE ont_operation SET data_query=?::jsonb WHERE id=? AND"
                                    + " space_id=?",
                            value == null ? null : db.json(value),
                            op,
                            s);
                });
    }

    /** RELATION两端同属性ID有歧义时明确拒绝，不能任选一端。 */
    public void validateQuery(UUID s, Map<String, Object> q) {
        only(q, Set.of("mode", "objectMappingId", "relationMappingId", "propertyIds", "filters"));
        if (!Set.of("OBJECT", "RELATION").contains(q.get("mode"))) throw Problem.bad("实例查询模式无效");
        var from = owned(s, q.get("objectMappingId"), "objects");
        Set<String> objects = new HashSet<>(Set.of(from.get("id").toString()));
        if (q.get("mode").equals("RELATION")) {
            var rel = owned(s, q.get("relationMappingId"), "relations");
            if (!Objects.equals(rel.get("fromObjectMappingId"), from.get("id")))
                throw Problem.bad("objectMappingId必须为关系from端");
            objects.add(rel.get("toObjectMappingId").toString());
        } else if (q.get("relationMappingId") != null) throw Problem.bad("OBJECT模式不可绑定关系");
        Map<String, Integer> counts = new HashMap<>();
        for (var p : all(s, "properties"))
            if (objects.contains(p.get("objectMappingId")))
                counts.merge(p.get("targetPropertyId").toString(), 1, Integer::sum);
        var ids = node(q.get("propertyIds"));
        if (!ids.isArray() || ids.isEmpty() || ids.size() > 100) throw Problem.bad("至少选择一个本体属性");
        Set<String> selected = new HashSet<>();
        for (var id : ids) {
            String key = Input.uuid(id.asText()).toString();
            if (!selected.add(key)) throw Problem.bad("重复投影属性");
            unique(counts, key);
        }
        var filters = node(q.getOrDefault("filters", List.of()));
        if (!filters.isArray() || filters.size() > 64) throw Problem.bad("过滤项无效");
        for (var item : filters) {
            var f = map(JSON.convertValue(item, Object.class));
            only(f, Set.of("propertyId", "operator", "inputParam"));
            unique(counts, Input.uuid(f.get("propertyId")).toString());
            if (!Set.of("EQ", "IN", "GT", "GE", "LT", "LE").contains(f.get("operator")))
                throw Problem.bad("过滤运算符无效");
            Input.text(f, "inputParam", 200);
        }
    }

    private void unique(Map<String, Integer> counts, String id) {
        if (counts.getOrDefault(id, 0) != 1)
            throw new Problem(400, "AMBIGUOUS_PROPERTY_MAPPING", "本体属性未映射或跨关系两端不唯一，请拆分操作: " + id);
    }

    /** 变更前按对象、属性与关系依赖定位受影响操作。 */
    public Map<String, Object> impact(UUID u, UUID s, String category, UUID id) {
        spaces.role(u, s);
        owned(s, id, category);
        var operations =
                cn.ontorepo.mapping.domain.MappingDependencies.operations(
                        db.list("SELECT id,kind,definition FROM data_mapping WHERE space_id=?", s),
                        db.list(
                                "SELECT id,code,name,data_query FROM ont_operation WHERE space_id=?"
                                        + " AND data_query IS NOT NULL",
                                s),
                        id.toString());
        return Map.of("operations", operations, "issues", List.of());
    }

    /** 分母为活动类、其全部属性和关系；可选属性也计入覆盖率，必填缺失另行阻断。 */
    public Map<String, Object> validate(UUID u, UUID s) {
        spaces.role(u, s);
        var objects = all(s, "objects");
        var properties = all(s, "properties");
        var relations = all(s, "relations");
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> classIssues = new ArrayList<>(),
                propIssues = new ArrayList<>(),
                keyIssues = new ArrayList<>(),
                refIssues = new ArrayList<>();
        int mapped = 0, pending = 0, unmappable = 0;
        for (var cl : db.list("SELECT id FROM ont_class WHERE space_id=? AND NOT deprecated", s)) {
            if (objects.stream()
                    .anyMatch(o -> Objects.equals(o.get("targetClassId"), cl.get("id")))) mapped++;
            else {
                pending++;
                classIssues.add(issue("CLASS_OBJECT_MISSING", "活动类没有对象映射", cl.get("id")));
            }
        }
        for (var p :
                db.list(
                        "SELECT p.id,p.domain_class_id,p.required FROM ont_property p JOIN"
                            + " ont_class c ON c.id=p.domain_class_id WHERE p.space_id=? AND NOT"
                            + " c.deprecated",
                        s)) {
            var owners =
                    objects.stream()
                            .filter(
                                    o ->
                                            Objects.equals(
                                                    o.get("targetClassId"), p.get("domainClassId")))
                            .toList();
            if (owners.isEmpty()) {
                pending++;
                if (Boolean.TRUE.equals(p.get("required")))
                    propIssues.add(issue("REQUIRED_PROPERTY_MISSING", "必填属性没有映射", p.get("id")));
                continue;
            }
            for (var owner : owners) {
                long count =
                        properties.stream()
                                .filter(
                                        pm ->
                                                Objects.equals(
                                                                pm.get("objectMappingId"),
                                                                owner.get("id"))
                                                        && Objects.equals(
                                                                pm.get("targetPropertyId"),
                                                                p.get("id")))
                                .count();
                if (count == 1) mapped++;
                else {
                    pending++;
                    if (Boolean.TRUE.equals(p.get("required")))
                        propIssues.add(
                                Map.of(
                                        "code",
                                        count == 0
                                                ? "REQUIRED_PROPERTY_MISSING"
                                                : "REQUIRED_PROPERTY_AMBIGUOUS",
                                        "message",
                                        "对象映射的必填属性缺失或不唯一",
                                        "entityId",
                                        owner.get("id"),
                                        "field",
                                        p.get("id")));
                }
            }
        }
        for (var r : db.list("SELECT id FROM ont_relation WHERE space_id=?", s)) {
            if (relations.stream().anyMatch(x -> Objects.equals(x.get("relationId"), r.get("id"))))
                mapped++;
            else {
                pending++;
                keyIssues.add(issue("RELATION_MAPPING_MISSING", "本体关系没有双端映射", r.get("id")));
            }
        }
        for (String category : List.of("objects", "properties", "relations"))
            for (var original : all(s, category)) {
                var d = new LinkedHashMap<>(original);
                d.remove("id");
                try {
                    validate(s, category, d, Input.uuid(original.get("id")));
                } catch (RuntimeException e) {
                    unmappable++;
                    if (category.equals("relations"))
                        keyIssues.add(
                                issue(
                                        "RELATION_KEYS_INVALID",
                                        "关系映射的双端引用或标识字段不可解析",
                                        original.get("id")));
                    refIssues.add(
                            issue("MAPPING_REFERENCE_INVALID", "映射引用或字段不可用", original.get("id")));
                }
            }
        checks.add(check("CLASS_OBJECT", classIssues));
        checks.add(check("REQUIRED_PROPERTY", propIssues));
        checks.add(check("RELATION_KEYS", keyIssues));
        checks.add(check("REACHABLE_REFERENCE", refIssues));
        Set<Object> affected = new LinkedHashSet<>();
        for (var op :
                db.list(
                        "SELECT id,code,name,data_query FROM ont_operation WHERE space_id=? AND"
                                + " data_query IS NOT NULL",
                        s))
            try {
                validateQuery(s, map(op.get("dataQuery")));
            } catch (RuntimeException e) {
                affected.add(
                        Map.of("id", op.get("id"), "code", op.get("code"), "name", op.get("name")));
            }
        return Map.of(
                "valid",
                checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed"))),
                "checks",
                checks,
                "coverage",
                Map.of("mapped", mapped, "pending", pending, "unmappable", unmappable),
                "coverageBasis",
                "当前活动类 + 各对象映射的全部本体属性（无对象映射时计一份待映射属性）+ 本体关系；不可用映射单独计入unmappable",
                "affectedOperations",
                affected);
    }

    private static Map<String, Object> issue(String code, String message, Object id) {
        return Map.of("code", code, "message", message, "entityId", id);
    }

    private static Map<String, Object> check(String kind, List<Map<String, Object>> issues) {
        return Map.of("kind", kind, "passed", issues.isEmpty(), "issues", issues);
    }

    /** 以只读视图汇总源、快照、模型绑定和操作定位。 */
    public Map<String, Object> catalog(UUID u, UUID s) {
        spaces.role(u, s);
        List<Map<String, Object>> srcs = new ArrayList<>(), bindings = new ArrayList<>();
        for (var source : sources.list(u, s)) {
            var sourceId = source.get("id");
            var os =
                    all(s, "objects").stream()
                            .filter(o -> Objects.equals(o.get("datasourceId"), sourceId))
                            .toList();
            var sn = sources.snapshots(u, s, Input.uuid(sourceId));
            source.put("latestSnapshotId", sn.isEmpty() ? null : sn.getFirst().get("id"));
            source.put(
                    "resources",
                    sn.isEmpty()
                            ? List.of()
                            : catalogResources(s, sourceId, sn.getFirst().get("id")));
            Set<Object> objectIds = new HashSet<>();
            os.forEach(object -> objectIds.add(object.get("id")));
            source.put(
                    "mappingCount",
                    os.size()
                            + all(s, "properties").stream()
                                    .filter(
                                            property ->
                                                    objectIds.contains(
                                                            property.get("objectMappingId")))
                                    .count()
                            + all(s, "relations").stream()
                                    .filter(
                                            relation ->
                                                    Objects.equals(
                                                            relation.get("datasourceId"), sourceId))
                                    .count());
            source.put(
                    "classIds", os.stream().map(o -> o.get("targetClassId")).distinct().toList());
            Set<String> allOps = new LinkedHashSet<>();
            for (var o : os) {
                var ids = sources.operationIds(s, o.get("id").toString());
                allOps.addAll(ids);
                bindings.add(
                        Map.of(
                                "datasourceId",
                                sourceId,
                                "objectMappingId",
                                o.get("id"),
                                "resource",
                                o.get("sourceResource"),
                                "classId",
                                o.get("targetClassId"),
                                "className",
                                model(s, "ont_class", o.get("targetClassId")).get("name"),
                                "propertyCount",
                                all(s, "properties").stream()
                                        .filter(
                                                p ->
                                                        Objects.equals(
                                                                p.get("objectMappingId"),
                                                                o.get("id")))
                                        .count(),
                                "relationCount",
                                all(s, "relations").stream()
                                        .filter(
                                                r ->
                                                        Objects.equals(
                                                                        r.get(
                                                                                "fromObjectMappingId"),
                                                                        o.get("id"))
                                                                || Objects.equals(
                                                                        r.get("toObjectMappingId"),
                                                                        o.get("id")))
                                        .count(),
                                "operationIds",
                                ids));
            }
            source.put("operationIds", allOps);
            srcs.add(source);
        }
        return Map.of("datasources", srcs, "bindings", bindings);
    }

    /** 数据目录下钻：资源 → 字段 → 已绑定本体属性；未绑定字段保留空数组，不伪造映射。 */
    private List<Map<String, Object>> catalogResources(UUID s, Object sourceId, Object snapshotId) {
        var snapshot = sources.snapshot(s, sourceId, snapshotId);
        var resources = node(snapshot.get("resources"));
        if (!resources.isArray()) return List.of();
        var objects = all(s, "objects");
        var properties = all(s, "properties");
        var out = new ArrayList<Map<String, Object>>();
        for (var resource : resources) {
            String resourceName = resource.path("name").asText();
            var view = new LinkedHashMap<String, Object>();
            view.put("name", resourceName);
            var fields = new ArrayList<Map<String, Object>>();
            for (var field : resource.path("fields")) {
                String fieldName = field.path("name").asText();
                var fieldView = new LinkedHashMap<String, Object>();
                fieldView.put("name", fieldName);
                fieldView.put("type", field.path("type").asText());
                var bound = new ArrayList<Map<String, Object>>();
                for (var object : objects)
                    if (resourceName.equals(object.get("sourceResource")))
                        for (var property : properties)
                            if (Objects.equals(property.get("objectMappingId"), object.get("id"))
                                    && fieldName.equals(property.get("sourceField"))) {
                                var binding = new LinkedHashMap<String, Object>();
                                binding.put("objectMappingId", object.get("id"));
                                binding.put("classId", object.get("targetClassId"));
                                binding.put(
                                        "className",
                                        model(s, "ont_class", object.get("targetClassId"))
                                                .get("name"));
                                binding.put("propertyId", property.get("targetPropertyId"));
                                binding.put(
                                        "propertyName",
                                        model(s, "ont_property", property.get("targetPropertyId"))
                                                .get("name"));
                                bound.add(binding);
                            }
                fieldView.put("bindings", bound);
                fields.add(fieldView);
            }
            view.put("fields", fields);
            out.add(view);
        }
        return out;
    }
}
