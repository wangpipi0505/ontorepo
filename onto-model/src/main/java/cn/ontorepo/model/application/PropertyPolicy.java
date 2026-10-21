package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.model.domain.PropertyFacets;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 属性引用影响和码表校验集中处理，避免更新与删除出现不同权限或引用口径。 */
@Service
public class PropertyPolicy {
    private final Db db;
    private final SpaceService spaces;

    public PropertyPolicy(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public Map<String, Object> owned(String table, UUID id, UUID space) {
        return db.list("SELECT * FROM " + table + " WHERE id=? AND space_id=?", id, space).stream()
                .findFirst()
                .orElseThrow(() -> new Problem(400, "CROSS_SPACE_REFERENCE", "资源不存在或不属于当前空间"));
    }

    @SuppressWarnings("unchecked")
    public void facets(
            UUID space, UUID id, UUID cl, String type, boolean required, Map<String, Object> f) {
        PropertyFacets.validate(type, required, f);
        if (Boolean.TRUE.equals(f.get("primaryKey"))
                && !db.list(
                                "SELECT id FROM ont_property WHERE space_id=? AND domain_class_id=?"
                                        + " AND facets->>'primaryKey'='true' AND id<>?",
                                space,
                                cl,
                                id == null ? new UUID(0, 0) : id)
                        .isEmpty()) throw Problem.bad("单类最多一个主键");
        for (String key : List.of("unitCodebookId", "codebookId"))
            if (f.get(key) != null) {
                var cb = owned("ont_codebook", Input.uuid(f.get(key)), space);
                String expected = key.equals("unitCodebookId") ? "UNIT" : "VALUE";
                if (!expected.equals(cb.get("kind"))) throw Problem.bad("码表种类不匹配");
                var entries = (List<Map<String, Object>>) cb.get("entries");
                if (key.equals("unitCodebookId")
                        && entries.stream()
                                .noneMatch(e -> Objects.equals(e.get("code"), f.get("unitCode"))))
                    throw Problem.bad("单位条目不存在");
                if (key.equals("codebookId")) {
                    if (!type.equals("STRING")) throw Problem.bad("值码表适用于文本属性");
                    List<Object> values = new ArrayList<>();
                    if (f.containsKey("defaultValue")) values.add(f.get("defaultValue"));
                    if (f.get("enumValues") instanceof List<?> list) values.addAll(list);
                    for (Object value : values)
                        if (entries.stream().noneMatch(e -> Objects.equals(e.get("code"), value)))
                            throw Problem.bad("分面值不在码表中");
                }
            }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> references(UUID space, UUID id) {
        List<Map<String, Object>> refs = new ArrayList<>();
        String raw = id.toString();
        for (var mapping :
                db.list(
                        "SELECT id,kind FROM data_mapping WHERE space_id=? AND"
                            + " (definition->>'targetPropertyId'=? OR"
                            + " definition->'transform'->>'codebookId'=?)",
                        space,
                        raw,
                        raw))
            refs.add(
                    Map.of(
                            "kind",
                            "MAPPING",
                            "id",
                            mapping.get("id"),
                            "name",
                            mapping.get("kind")));
        for (var c : db.list("SELECT * FROM ont_constraint WHERE space_id=?", space))
            if (raw.equals(c.get("targetRef"))
                    || c.get("expr").toString().toLowerCase(Locale.ROOT).contains(raw))
                refs.add(Map.of("kind", "CONSTRAINT", "id", c.get("id"), "name", c.get("name")));
        for (var r : db.list("SELECT * FROM ont_rule WHERE space_id=?", space))
            if (r.get("condition").toString().toLowerCase(Locale.ROOT).contains(raw)
                    || Objects.toString(r.get("scoring"), "")
                            .toLowerCase(Locale.ROOT)
                            .contains(raw))
                refs.add(Map.of("kind", "RULE", "id", r.get("id"), "name", r.get("name")));
        for (var p : db.list("SELECT * FROM ont_property WHERE space_id=? AND id<>?", space, id))
            if (p.get("facets").toString().toLowerCase(Locale.ROOT).contains(raw))
                refs.add(Map.of("kind", "PROPERTY", "id", p.get("id"), "name", p.get("name")));
        return refs;
    }

    public Map<String, Object> impact(UUID user, UUID space, UUID id) {
        spaces.role(user, space);
        owned("ont_property", id, space);
        return Map.of("references", references(space, id));
    }

    public void prevent(UUID space, UUID id) {
        var refs = references(space, id);
        if (!refs.isEmpty()) throw new Problem(409, "PROPERTY_IN_USE", "属性仍被引用：" + refs);
    }

    /** 删除导入实体时同步清理原图；已有RDF实例或限制引用必须先人工解除，不能留下幽灵实体。 */
    public void sourceDelete(String table, UUID space, UUID id) {
        var row = owned(table, id, space);
        if (!db.list(
                        "SELECT id FROM data_mapping WHERE space_id=? AND"
                            + " (definition->>'targetClassId'=? OR definition->>'relationId'=?)",
                        space,
                        id.toString(),
                        id.toString())
                .isEmpty()) throw new Problem(409, "MODEL_IN_USE", "本体实体仍被映射引用: " + id);
        Object iri = row.get("sourceIri");
        if (iri == null) return;
        for (var doc : db.list("SELECT id,content FROM ont_rdf_document WHERE space_id=?", space)) {
            try (var handle =
                    new cn.ontorepo.model.infra.RdfCodec.Handle(
                            doc.get("content").toString(), "TTL")) {
                var model = handle.model;
                var resource = model.createResource(iri.toString());
                if (model.contains(null, null, resource)
                        || model.contains(
                                null,
                                model.createProperty(iri.toString()),
                                (org.apache.jena.rdf.model.RDFNode) null))
                    throw new Problem(409, "RDF_ENTITY_IN_USE", "导入实体仍被原始RDF引用，请先处理引用");
                model.removeAll(resource, null, null);
                db.update(
                        "UPDATE ont_rdf_document SET content=? WHERE id=?",
                        cn.ontorepo.model.infra.RdfCodec.ttl(model),
                        Input.uuid(doc.get("id")));
            }
        }
    }

    /** 导入批量更新后复核整棵层级，不能利用多条UPSERT绕过普通编辑的防环规则。 */
    public void validateHierarchy(UUID space) {
        Map<String, String> parents = new HashMap<>();
        for (var row : db.list("SELECT id,parent_id FROM ont_class WHERE space_id=?", space))
            parents.put(
                    row.get("id").toString(),
                    row.get("parentId") == null ? null : row.get("parentId").toString());
        for (String start : parents.keySet()) {
            Set<String> seen = new HashSet<>();
            String cur = start;
            while (cur != null) {
                if (!seen.add(cur)) throw new Problem(400, "HIERARCHY_CYCLE", "导入会形成类层级环");
                cur = parents.get(cur);
            }
        }
    }
}
