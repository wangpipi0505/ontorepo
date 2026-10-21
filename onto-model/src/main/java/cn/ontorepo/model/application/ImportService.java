package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.model.infra.RdfCodec;
import cn.ontorepo.space.application.SpaceService;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.vocabulary.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 预览仅保存导入作业，确认时以原修订重校验并将原图与可编辑实体原子写入。 */
@Service
public class ImportService {
    private final Db db;
    private final SpaceService spaces;
    private final PropertyPolicy propertyPolicy;

    public ImportService(Db db, SpaceService spaces, PropertyPolicy propertyPolicy) {
        this.db = db;
        this.spaces = spaces;
        this.propertyPolicy = propertyPolicy;
    }

    @Transactional
    public Map<String, Object> preview(UUID u, UUID s, Map<String, Object> b) {
        long rev = Input.number(b, "expectedDraftRev");
        spaces.lock(u, s, rev, true);
        String kind = Input.text(b, "kind", 5);
        Input.text(b, "content", 2000000);
        String content = (String) b.get("content");
        if (!Set.of("TTL", "OWL").contains(kind)) throw Problem.bad("仅支持TTL/OWL导入");
        UUID id = UUID.randomUUID();
        List<Map<String, Object>> errors = new ArrayList<>(),
                unsupported = new ArrayList<>(),
                conflicts = new ArrayList<>();
        int added = 0, updated = 0;
        try (var handle = new RdfCodec.Handle(content, kind)) {
            Model m = handle.model;
            unsupported = RdfCodec.unsupported(content, m);
            var entities = entities(m);
            Set<String> names = new HashSet<>();
            for (Resource r : entities) {
                if (r.hasProperty(RDF.type, OWL.DatatypeProperty)) {
                    // 先检查已有属性变化，再决定是否能投影；未知类型不能跳过引用保护。
                    try {
                        validatePropertyReplacement(s, r);
                    } catch (Problem problem) {
                        conflicts.add(
                                Map.of(
                                        "line",
                                        RdfCodec.line(content, r.getURI()),
                                        "message",
                                        problem.getMessage()));
                    }
                    Statement range = r.getProperty(RDFS.range);
                    if (range == null
                            || !range.getObject().isURIResource()
                            || rangeType(range.getResource()) == null) continue;
                }

                String table =
                        r.hasProperty(RDF.type, OWL.DatatypeProperty)
                                ? "ont_property"
                                : r.hasProperty(RDF.type, OWL.ObjectProperty)
                                        ? "ont_relation"
                                        : "ont_class";
                Statement scopeStatement =
                        r.getProperty(table.equals("ont_class") ? RDFS.subClassOf : RDFS.domain);
                UUID scope =
                        scopeStatement != null && scopeStatement.getObject().isURIResource()
                                ? entityId(s, scopeStatement.getResource().getURI())
                                : null;
                String key = table + ":" + Objects.toString(scope, "") + ":" + label(r);
                String scoped =
                        table.equals("ont_class")
                                ? " AND parent_id IS NOT DISTINCT FROM ?"
                                : table.equals("ont_property") ? " AND domain_class_id=?" : "";
                List<Map<String, Object>> sameName =
                        scoped.isEmpty()
                                ? db.list(
                                        "SELECT id FROM "
                                                + table
                                                + " WHERE space_id=? AND name=? AND id<>?",
                                        s,
                                        label(r),
                                        entityId(s, r.getURI()))
                                : db.list(
                                        "SELECT id FROM "
                                                + table
                                                + " WHERE space_id=? AND name=? AND id<>?"
                                                + scoped,
                                        s,
                                        label(r),
                                        entityId(s, r.getURI()),
                                        scope);
                if (!names.add(key) || !sameName.isEmpty())
                    conflicts.add(
                            Map.of(
                                    "line",
                                    RdfCodec.line(content, r.getURI()),
                                    "message",
                                    "导入名称与已有实体冲突：" + label(r)));
                UUID eid = entityId(s, r.getURI());
                boolean exists =
                        !db.list(
                                        "SELECT id FROM ont_class WHERE space_id=? AND source_iri=?"
                                                + " UNION ALL SELECT id FROM ont_property WHERE"
                                                + " space_id=? AND source_iri=? UNION ALL SELECT id"
                                                + " FROM ont_relation WHERE space_id=? AND"
                                                + " source_iri=?",
                                        s,
                                        r.getURI(),
                                        s,
                                        r.getURI(),
                                        s,
                                        r.getURI())
                                .isEmpty();
                if (exists) updated++;
                else added++;
            }
        } catch (RiotParseException e) {
            errors.add(
                    Map.of(
                            "line",
                            Math.max(1, e.getLine()),
                            "message",
                            "RDF语法错误：" + e.getOriginalMessage()));
        } catch (Exception e) {
            errors.add(Map.of("line", 1, "message", "RDF内容无法解析"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id.toString());
        report.put("status", errors.isEmpty() ? "PREVIEW" : "FAILED");
        report.put("targetDraftRev", rev);
        report.put("added", added);
        report.put("updated", updated);
        report.put("conflicts", conflicts);
        report.put("unsupported", unsupported);
        report.put("errors", errors);
        db.update(
                "INSERT INTO ont_import(id,space_id,kind,content,target_draft_rev,status,report)"
                        + " VALUES(?,?,?,?,?,?,?::jsonb)",
                id,
                s,
                kind,
                content,
                rev,
                report.get("status"),
                db.json(report));
        spaces.audit(u, s, "IMPORT_PREVIEW", rev);
        return report;
    }

    private UUID entityId(UUID s, String iri) {
        return UUID.nameUUIDFromBytes((s + ":" + iri).getBytes(StandardCharsets.UTF_8));
    }

    private Set<Resource> entities(Model m) {
        Set<Resource> all = new LinkedHashSet<>();
        for (Resource type :
                List.of(OWL.Class, RDFS.Class, OWL.DatatypeProperty, OWL.ObjectProperty))
            m.listSubjectsWithProperty(RDF.type, type)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(all::add);
        return all;
    }

    /** 预览和确认共用同一保护；确认在空间写锁内重算，不能信任历史预览的冲突列表。 */
    private void validatePropertyReplacement(UUID space, Resource incoming) {
        UUID id = entityId(space, incoming.getURI());
        var rows = db.list("SELECT * FROM ont_property WHERE id=? AND space_id=?", id, space);
        if (rows.isEmpty()) return;
        var old = rows.getFirst();
        Statement range = incoming.getProperty(RDFS.range),
                domain = incoming.getProperty(RDFS.domain);
        String nextType =
                range != null && range.getObject().isURIResource()
                        ? rangeType(range.getResource())
                        : null;
        String nextDomain =
                domain != null && domain.getObject().isURIResource()
                        ? entityId(space, domain.getResource().getURI()).toString()
                        : null;
        if (Objects.equals(nextType, old.get("rangeType"))
                && Objects.equals(nextDomain, old.get("domainClassId"))) return;
        propertyPolicy.prevent(space, id);
        // 没有引用也不能把旧基础投影和新未知类型静默并存；新IRI仍可完整、合法地导入原图。
        if (nextType == null || nextDomain == null)
            throw new Problem(409, "IMPORT_TYPE_CONFLICT", "已有基础属性无法自动替换为不支持的类型或结构，请先人工处理原属性");
    }

    private String rangeType(Resource range) {
        return RdfCodec.editableRange(range);
    }

    private String label(Resource r) {
        Statement label = r.getProperty(RDFS.label);
        return label != null && label.getObject().isLiteral()
                ? label.getString()
                : r.getLocalName() == null ? r.getURI() : r.getLocalName();
    }

    private String comment(Resource r) {
        Statement st = r.getProperty(RDFS.comment);
        return st != null && st.getObject().isLiteral() ? st.getString() : "";
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(UUID u, UUID id, long rev) {
        var job = db.one("SELECT * FROM ont_import WHERE id=?", id);
        UUID s = Input.uuid(job.get("spaceId"));
        return spaces.write(
                u,
                s,
                rev,
                "IMPORT_APPLY",
                () -> {
                    var current = db.one("SELECT * FROM ont_import WHERE id=? FOR UPDATE", id);
                    if (Boolean.TRUE.equals(current.get("applied")))
                        throw new Problem(409, "IMPORT_ALREADY_APPLIED", "导入已确认");
                    if (!"PREVIEW".equals(current.get("status"))) throw Problem.bad("语法错误的导入不可应用");
                    if (!((List<?>) ((Map<String, Object>) current.get("report")).get("conflicts"))
                            .isEmpty())
                        throw new Problem(409, "IMPORT_CONFLICT", "导入存在冲突，请修复后重新预览");
                    if (((Number) current.get("targetDraftRev")).longValue() != rev)
                        throw new Problem(409, "DRAFT_REV_CONFLICT", "导入预览已过期，请重新预览");
                    try (var handle =
                            new RdfCodec.Handle(
                                    current.get("content").toString(),
                                    current.get("kind").toString())) {
                        Model m = handle.model;
                        // 即使预览来自旧版本且没有冲突，也必须在任何草稿写入前复核类型与引用。
                        for (Resource resource : entities(m))
                            if (resource.hasProperty(RDF.type, OWL.DatatypeProperty))
                                validatePropertyReplacement(s, resource);
                        Set<Resource> classes = new LinkedHashSet<>();
                        m.listSubjectsWithProperty(RDF.type, OWL.Class)
                                .filterKeep(Resource::isURIResource)
                                .forEachRemaining(classes::add);
                        m.listSubjectsWithProperty(RDF.type, RDFS.Class)
                                .filterKeep(Resource::isURIResource)
                                .forEachRemaining(classes::add);
                        for (Resource r : entities(m))
                            for (var pred : List.of(RDFS.domain, RDFS.range)) {
                                if (pred.equals(RDFS.range)
                                        && r.hasProperty(RDF.type, OWL.DatatypeProperty)) continue;
                                Statement st = r.getProperty(pred);
                                if (st != null
                                        && st.getObject().isURIResource()
                                        && !st.getResource().getURI().startsWith(XSD.NS))
                                    classes.add(st.getResource());
                            }
                        m.listStatements(null, RDFS.subClassOf, (RDFNode) null)
                                .forEachRemaining(
                                        st -> {
                                            if (st.getSubject().isURIResource())
                                                classes.add(st.getSubject());
                                            if (st.getObject().isURIResource())
                                                classes.add(st.getResource());
                                        });
                        for (Resource r : classes)
                            db.update(
                                    "INSERT INTO"
                                        + " ont_class(id,space_id,name,parent_id,description,deprecated,source_iri)"
                                        + " VALUES(?,?,?,null,?,false,?) ON CONFLICT(id) DO UPDATE"
                                        + " SET name=excluded.name,description=excluded.description",
                                    entityId(s, r.getURI()),
                                    s,
                                    label(r),
                                    comment(r),
                                    r.getURI());
                        for (Resource r : classes) {
                            Statement parent = r.getProperty(RDFS.subClassOf);
                            if (parent != null && parent.getObject().isURIResource())
                                db.update(
                                        "UPDATE ont_class SET parent_id=? WHERE id=?",
                                        entityId(s, parent.getResource().getURI()),
                                        entityId(s, r.getURI()));
                        }
                        for (Resource r : entities(m)) {
                            Statement dom = r.getProperty(RDFS.domain),
                                    range = r.getProperty(RDFS.range);
                            if (dom == null
                                    || range == null
                                    || !dom.getObject().isURIResource()
                                    || !range.getObject().isURIResource()) continue;
                            UUID eid = entityId(s, r.getURI()),
                                    domain = entityId(s, dom.getResource().getURI());
                            if (r.hasProperty(RDF.type, OWL.DatatypeProperty)) {
                                String type = rangeType(range.getResource());
                                if (type == null) continue;
                                var prior =
                                        db.list(
                                                "SELECT * FROM ont_property WHERE id=? AND"
                                                        + " space_id=?",
                                                eid,
                                                s);
                                if (!prior.isEmpty()) {
                                    var old = prior.getFirst();
                                    if (!type.equals(old.get("rangeType"))
                                            || !domain.toString().equals(old.get("domainClassId")))
                                        propertyPolicy.prevent(s, eid);
                                    propertyPolicy.facets(
                                            s,
                                            eid,
                                            domain,
                                            type,
                                            Boolean.TRUE.equals(old.get("required")),
                                            (Map<String, Object>) old.get("facets"));
                                }
                                db.update(
                                        "INSERT INTO"
                                            + " ont_property(id,space_id,domain_class_id,name,range_type,required,description,source_iri)"
                                            + " VALUES(?,?,?,?,?,false,?,?) ON CONFLICT(id) DO"
                                            + " UPDATE SET"
                                            + " name=excluded.name,domain_class_id=excluded.domain_class_id,range_type=excluded.range_type,description=excluded.description",
                                        eid,
                                        s,
                                        domain,
                                        label(r),
                                        type,
                                        comment(r),
                                        r.getURI());
                            } else if (r.hasProperty(RDF.type, OWL.ObjectProperty))
                                db.update(
                                        "INSERT INTO"
                                            + " ont_relation(id,space_id,name,from_class_id,to_class_id,cardinality,inverse_of,transitive,\"symmetric\",description,source_iri)"
                                            + " VALUES(?,?,?,?,?,'MANY_TO_MANY',null,?,?,?,?) ON"
                                            + " CONFLICT(id) DO UPDATE SET"
                                            + " name=excluded.name,from_class_id=excluded.from_class_id,to_class_id=excluded.to_class_id,transitive=excluded.transitive,\"symmetric\"=excluded.\"symmetric\",description=excluded.description",
                                        eid,
                                        s,
                                        label(r),
                                        domain,
                                        entityId(s, range.getResource().getURI()),
                                        r.hasProperty(RDF.type, OWL.TransitiveProperty),
                                        r.hasProperty(RDF.type, OWL.SymmetricProperty),
                                        comment(r),
                                        r.getURI());
                        }
                        propertyPolicy.validateHierarchy(s);
                        // 同一原图的重复导入替换对应文档，避免空白节点重复导致往返图增大。
                        db.update(
                                "DELETE FROM ont_rdf_document WHERE space_id=? AND content=?",
                                s,
                                RdfCodec.ttl(m));
                        db.update(
                                "INSERT INTO"
                                    + " ont_rdf_document(id,space_id,content,format,unsupported,original_content,original_format)"
                                    + " VALUES(?,?,?,'TTL',?::jsonb,?,?)",
                                UUID.randomUUID(),
                                s,
                                RdfCodec.ttl(m),
                                db.json(
                                        ((Map<String, Object>) current.get("report"))
                                                .get("unsupported")),
                                current.get("content"),
                                current.get("kind"));
                        db.update("UPDATE ont_import SET applied=true WHERE id=?", id);
                    }
                });
    }

    public Map<String, Object> export(UUID u, UUID s) {
        spaces.role(u, s);
        Map<String, Object> payload = snapshot(s);
        try (var handle = new RdfCodec.Handle(payload)) {
            Model m = handle.model;
            Model shapes = cn.ontorepo.model.infra.ShaclSchema.build(payload, m, false);
            try {
                m.add(shapes);
            } finally {
                shapes.close();
            }
            return Map.of("content", RdfCodec.ttl(m), "format", "TTL");
        }
    }

    public Map<String, Object> snapshot(UUID s) {
        Map<String, Object> p = new HashMap<>();
        p.put("space", db.one("SELECT * FROM ontology_space WHERE id=?", s));
        for (String[] pair :
                new String[][] {
                    {"classes", "ont_class"},
                    {"properties", "ont_property"},
                    {"relations", "ont_relation"},
                    {"constraints", "ont_constraint"},
                    {"rdfDocuments", "ont_rdf_document"}
                })
            p.put(
                    pair[0],
                    db.list("SELECT * FROM " + pair[1] + " WHERE space_id=? ORDER BY id", s));
        return p;
    }
}
