package cn.ontorepo.model.infra;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.vocabulary.*;

import java.util.*;

/** 草稿和候选使用同一 RDF 投影；导入原图保留空白节点与未支持构造，不跟随 owl:imports。 */
public final class RdfCodec {
    private RdfCodec() {}

    public static final class Handle implements AutoCloseable {
        public final Model model;

        public Handle(String content, String kind) {
            model = parse(content, kind);
        }

        public Handle(Map<String, Object> payload) {
            model = RdfCodec.model(payload);
        }

        public void close() {
            model.close();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Map<String, Object> p, String key) {
        return (List<Map<String, Object>>) p.getOrDefault(key, List.of());
    }

    @SuppressWarnings("unchecked")
    public static Model model(Map<String, Object> p) {
        Model m = ModelFactory.createDefaultModel();
        try {
            populate(m, p);
            return m;
        } catch (RuntimeException | Error failure) {
            // 只有成功构建的模型才转交调用方；导入或投影中途失败时释放累积图。
            m.close();
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static void populate(Model m, Map<String, Object> p) {
        Map<String, Object> s = (Map<String, Object>) p.get("space");
        String ns = s.get("namespaceUri").toString();
        for (var doc : rows(p, "rdfDocuments")) {
            Model part = ModelFactory.createDefaultModel();
            try {
                RDFParser.fromString(doc.get("content").toString()).lang(Lang.TURTLE).parse(part);
                m.add(part);
            } finally {
                part.close();
            }
        }
        Map<String, String> classIris = new HashMap<>(),
                propertyIris = new HashMap<>(),
                relationIris = new HashMap<>();
        for (var c : rows(p, "classes"))
            classIris.put(c.get("id").toString(), iri(c, ns, "class/"));
        for (var c : rows(p, "properties"))
            propertyIris.put(c.get("id").toString(), iri(c, ns, "property/"));
        for (var c : rows(p, "relations"))
            relationIris.put(c.get("id").toString(), iri(c, ns, "relation/"));
        for (var c : rows(p, "classes")) {
            if (c.get("sourceIri") != null) {
                Resource raw = m.createResource(c.get("sourceIri").toString());
                syncLabels(m, raw, c);
                String parent =
                        c.get("parentId") == null
                                ? null
                                : classIris.get(c.get("parentId").toString());
                List<Statement> old =
                        m.listStatements(raw, RDFS.subClassOf, (RDFNode) null)
                                .filterKeep(st -> st.getObject().isURIResource())
                                .toList();
                if (!Objects.equals(
                        old.isEmpty() ? null : old.getFirst().getResource().getURI(), parent)) {
                    m.remove(old);
                    if (parent != null) raw.addProperty(RDFS.subClassOf, m.createResource(parent));
                }
                continue;
            }
            Resource r = m.createResource(classIris.get(c.get("id").toString()));
            r.addProperty(RDF.type, OWL.Class)
                    .addProperty(RDFS.label, c.get("name").toString())
                    .addProperty(RDFS.comment, Objects.toString(c.get("description"), ""));
            if (c.get("parentId") != null)
                r.addProperty(
                        RDFS.subClassOf,
                        m.createResource(classIris.get(c.get("parentId").toString())));
        }
        for (var a : rows(p, "properties")) {
            if (a.get("sourceIri") != null) {
                Resource raw = m.createResource(a.get("sourceIri").toString());
                syncLabels(m, raw, a);
                syncResource(m, raw, RDFS.domain, classIris.get(a.get("domainClassId").toString()));
                Statement old = raw.getProperty(RDFS.range);
                // 未识别的原始数据类型没有可靠的基础类型投影；旧候选中的STRING占位不能代表用户修改。
                if (old != null
                        && old.getObject().isURIResource()
                        && editableRange(old.getResource()) == null) continue;
                String sourceType =
                        old != null && old.getObject().isURIResource()
                                ? editableRange(old.getResource())
                                : null;
                if (!Objects.equals(sourceType, a.get("rangeType")))
                    syncResource(m, raw, RDFS.range, datatype(a.get("rangeType").toString()));
                continue;
            }
            Resource r = m.createResource(propertyIris.get(a.get("id").toString()));
            r.addProperty(RDF.type, OWL.DatatypeProperty)
                    .addProperty(RDFS.label, a.get("name").toString())
                    .addProperty(
                            RDFS.domain,
                            m.createResource(classIris.get(a.get("domainClassId").toString())))
                    .addProperty(
                            RDFS.range,
                            m.createResource(
                                    XSD.NS
                                            + switch (a.get("rangeType").toString()) {
                                                case "INTEGER" -> "integer";
                                                case "DECIMAL" -> "decimal";
                                                case "BOOLEAN" -> "boolean";
                                                case "DATE" -> "date";
                                                default -> "string";
                                            }));
        }
        for (var a : rows(p, "relations")) {
            if (a.get("sourceIri") != null) {
                Resource raw = m.createResource(a.get("sourceIri").toString());
                syncLabels(m, raw, a);
                syncResource(m, raw, RDFS.domain, classIris.get(a.get("fromClassId").toString()));
                syncResource(m, raw, RDFS.range, classIris.get(a.get("toClassId").toString()));
                if (Boolean.TRUE.equals(a.get("transitive")))
                    raw.addProperty(RDF.type, OWL.TransitiveProperty);
                else m.removeAll(raw, RDF.type, OWL.TransitiveProperty);
                if (Boolean.TRUE.equals(a.get("symmetric")))
                    raw.addProperty(RDF.type, OWL.SymmetricProperty);
                else m.removeAll(raw, RDF.type, OWL.SymmetricProperty);
                // 原图中未进入可编辑投影的其他逆关系仍保留；手工维护的逆边追加完整公理。
                if (a.get("inverseOf") != null
                        && relationIris.containsKey(a.get("inverseOf").toString()))
                    raw.addProperty(
                            OWL.inverseOf,
                            m.createResource(relationIris.get(a.get("inverseOf").toString())));
                continue;
            }
            Resource r = m.createResource(relationIris.get(a.get("id").toString()));
            r.addProperty(RDF.type, OWL.ObjectProperty)
                    .addProperty(RDFS.label, a.get("name").toString())
                    .addProperty(
                            RDFS.domain,
                            m.createResource(classIris.get(a.get("fromClassId").toString())))
                    .addProperty(
                            RDFS.range,
                            m.createResource(classIris.get(a.get("toClassId").toString())));
            if (Boolean.TRUE.equals(a.get("transitive")))
                r.addProperty(RDF.type, OWL.TransitiveProperty);
            if (Boolean.TRUE.equals(a.get("symmetric")))
                r.addProperty(RDF.type, OWL.SymmetricProperty);
            if (a.get("inverseOf") != null
                    && relationIris.containsKey(a.get("inverseOf").toString()))
                r.addProperty(
                        OWL.inverseOf,
                        m.createResource(relationIris.get(a.get("inverseOf").toString())));
        }
        for (var c : rows(p, "constraints"))
            if ("MUTEX".equals(c.get("kind"))) {
                Map<String, Object> expr = (Map<String, Object>) c.get("expr");
                for (Object other : (List<?>) expr.get("classIds"))
                    m.createResource(classIris.get(c.get("targetRef").toString()))
                            .addProperty(
                                    OWL.disjointWith,
                                    m.createResource(
                                            classIris.get(
                                                    other.toString().toLowerCase(Locale.ROOT))));
            }
    }

    private static void syncLabels(Model m, Resource raw, Map<String, Object> row) {
        Statement label = raw.getProperty(RDFS.label);
        String inferred = raw.getLocalName() == null ? raw.getURI() : raw.getLocalName();
        if (!Objects.equals(
                label != null && label.getObject().isLiteral() ? label.getString() : inferred,
                row.get("name"))) {
            m.removeAll(raw, RDFS.label, null);
            raw.addProperty(RDFS.label, row.get("name").toString());
        }
        Statement desc = raw.getProperty(RDFS.comment);
        String old = desc != null && desc.getObject().isLiteral() ? desc.getString() : "";
        if (!Objects.equals(old, Objects.toString(row.get("description"), ""))) {
            m.removeAll(raw, RDFS.comment, null);
            if (!Objects.toString(row.get("description"), "").isEmpty())
                raw.addProperty(RDFS.comment, row.get("description").toString());
        }
    }

    private static void syncResource(Model m, Resource raw, Property property, String value) {
        if (value == null) return;
        Statement old = raw.getProperty(property);
        if (old != null
                && old.getObject().isURIResource()
                && old.getResource().getURI().equals(value)) return;
        m.removeAll(raw, property, null);
        raw.addProperty(property, m.createResource(value));
    }

    /** 仅完整IRI命中受支持XSD基础类型；自定义同名类型也不能冒充XSD。 */
    public static String editableRange(Resource range) {
        if (range == null || !range.isURIResource() || !range.getURI().startsWith(XSD.NS))
            return null;
        return switch (range.getURI().substring(XSD.NS.length())) {
            case "string" -> "STRING";
            case "integer", "int", "long" -> "INTEGER";
            case "decimal", "double", "float" -> "DECIMAL";
            case "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            default -> null;
        };
    }

    private static String datatype(String range) {
        return XSD.NS
                + switch (range) {
                    case "INTEGER" -> "integer";
                    case "DECIMAL" -> "decimal";
                    case "BOOLEAN" -> "boolean";
                    case "DATE" -> "date";
                    default -> "string";
                };
    }

    private static String iri(Map<String, Object> r, String ns, String part) {
        return r.get("sourceIri") == null ? ns + part + r.get("id") : r.get("sourceIri").toString();
    }

    public static String ttl(Model m) {
        java.io.StringWriter out = new java.io.StringWriter();
        RDFDataMgr.write(out, m, Lang.TURTLE);
        return out.toString();
    }

    public static Model parse(String content, String kind) {
        Model m = ModelFactory.createDefaultModel();
        try {
            Lang lang =
                    kind.equals("TTL")
                            ? Lang.TURTLE
                            : content.stripLeading().startsWith("<")
                                            && (content.contains("rdf:RDF")
                                                    || content.contains("<?xml"))
                                    ? Lang.RDFXML
                                    : Lang.TURTLE;
            RDFParser.fromString(content).lang(lang).checking(true).parse(m);
            return m;
        } catch (RuntimeException e) {
            m.close();
            throw e;
        }
    }

    /** 超范围的原始构造逐条定位；profile 检测由 ELK 门禁的 OWL API 再校验。 */
    public static List<Map<String, Object>> unsupported(String content, Model m) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> forbidden =
                Set.of(
                        "allValuesFrom",
                        "complementOf",
                        "unionOf",
                        "cardinality",
                        "minCardinality",
                        "maxCardinality",
                        "qualifiedCardinality",
                        "minQualifiedCardinality",
                        "maxQualifiedCardinality",
                        "imports",
                        "inverseOf",
                        "oneOf",
                        "hasSelf");
        m.listStatements()
                .forEachRemaining(
                        st -> {
                            String local = st.getPredicate().getLocalName();
                            if (st.getPredicate().equals(RDFS.range)
                                    && st.getSubject().hasProperty(RDF.type, OWL.DatatypeProperty)
                                    && (!st.getObject().isURIResource()
                                            || editableRange(st.getResource()) == null))
                                out.add(
                                        Map.of(
                                                "line",
                                                line(
                                                        content,
                                                        st.getObject().isURIResource()
                                                                ? st.getResource().getURI()
                                                                : local),
                                                "construct",
                                                "DATATYPE",
                                                "message",
                                                "此数据类型保留完整原图，不进入五种基础类型的可编辑属性投影"));
                            if (st.getPredicate().equals(RDF.type)
                                    && st.getObject().isURIResource()) {
                                String type = st.getResource().getURI();
                                if (type.startsWith("http://www.w3.org/2003/11/swrl")
                                        || Set.of(
                                                        OWL.SymmetricProperty.getURI(),
                                                        OWL.FunctionalProperty.getURI(),
                                                        OWL.InverseFunctionalProperty.getURI())
                                                .contains(type))
                                    out.add(
                                            Map.of(
                                                    "line",
                                                    line(content, st.getResource().getLocalName()),
                                                    "construct",
                                                    st.getResource().getLocalName(),
                                                    "message",
                                                    "此OWL类型超出OWL 2 EL支持范围，原图保留"));
                            }
                            if (forbidden.contains(local)
                                    || st.getPredicate()
                                            .getURI()
                                            .startsWith("http://www.w3.org/2003/11/swrl")) {
                                int line = line(content, local);
                                out.add(
                                        Map.of(
                                                "line",
                                                line,
                                                "construct",
                                                local,
                                                "message",
                                                "构造超出 OWL 2 EL 支持范围，原图保留待人工处理"));
                            }
                        });
        return out;
    }

    public static int line(String content, String marker) {
        int pos = content.indexOf(marker);
        return pos < 0
                ? 1
                : 1 + (int) content.substring(0, pos).chars().filter(c -> c == '\n').count();
    }
}
