package cn.ontorepo.model.infra;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/** SHACL映射与验证分离：导出和候选使用同一约束语义；纯导入未编辑原图保持图同构。 */
public final class ShaclSchema {
    private static final String SH = "http://www.w3.org/ns/shacl#";

    private ShaclSchema() {}

    private static Property prop(Model m, String p) {
        return m.createProperty(SH + p);
    }

    private static Resource type(Model m, String p) {
        return m.createResource(SH + p);
    }

    private static Resource node(Model m, String cl) {
        return m.createResource()
                .addProperty(RDF.type, type(m, "NodeShape"))
                .addProperty(prop(m, "targetClass"), m.createResource(cl));
    }

    @SuppressWarnings("unchecked")
    public static Model build(Map<String, Object> p, Model data, boolean includeAllTypes) {
        Model shapes = ModelFactory.createDefaultModel();
        Map<String, Object> s = (Map<String, Object>) p.get("space");
        String ns = s.get("namespaceUri").toString();
        Map<String, String> classes = new HashMap<>(),
                paths = new HashMap<>(),
                domains = new HashMap<>();
        Map<String, Resource> propertyShapes = new HashMap<>();
        for (var c : RdfCodec.rows(p, "classes"))
            classes.put(
                    c.get("id").toString(),
                    Objects.toString(c.get("sourceIri"), ns + "class/" + c.get("id")));
        for (var c : RdfCodec.rows(p, "relations")) {
            String id = c.get("id").toString();
            paths.put(id, Objects.toString(c.get("sourceIri"), ns + "relation/" + id));
            domains.put(id, c.get("fromClassId").toString());
        }
        for (var c : RdfCodec.rows(p, "properties")) {
            String id = c.get("id").toString();
            paths.put(id, Objects.toString(c.get("sourceIri"), ns + "property/" + id));
            domains.put(id, c.get("domainClassId").toString());
            Map<String, Object> authoredFacets =
                    (Map<String, Object>) c.getOrDefault("facets", Map.of());
            boolean constrained =
                    RdfCodec.rows(p, "constraints").stream()
                            .anyMatch(co -> id.equals(co.get("targetRef")));
            if (!includeAllTypes
                    && c.get("sourceIri") != null
                    && !Boolean.TRUE.equals(c.get("required"))
                    && authoredFacets.isEmpty()
                    && !constrained) continue;
            Resource shape = node(shapes, classes.get(domains.get(id))),
                    ps =
                            shapes.createResource()
                                    .addProperty(
                                            prop(shapes, "path"),
                                            shapes.createResource(paths.get(id)));
            shape.addProperty(prop(shapes, "property"), ps);
            propertyShapes.put(id, ps);
            String datatype =
                    org.apache.jena.vocabulary.XSD.NS
                            + switch (c.get("rangeType").toString()) {
                                case "INTEGER" -> "integer";
                                case "DECIMAL" -> "decimal";
                                case "BOOLEAN" -> "boolean";
                                case "DATE" -> "date";
                                default -> "string";
                            };
            if (c.get("sourceIri") != null) {
                var range =
                        data.createResource(c.get("sourceIri").toString())
                                .getProperty(org.apache.jena.vocabulary.RDFS.range);
                if (range != null && range.getObject().isURIResource())
                    datatype = range.getResource().getURI();
            }
            ps.addProperty(prop(shapes, "datatype"), shapes.createResource(datatype));
            Map<String, Object> facets = (Map<String, Object>) c.getOrDefault("facets", Map.of());
            if (Boolean.TRUE.equals(c.get("required"))) ps.addLiteral(prop(shapes, "minCount"), 1);
            if (facets.get("minimum") != null)
                ps.addLiteral(prop(shapes, "minInclusive"), facets.get("minimum"));
            if (facets.get("maximum") != null)
                ps.addLiteral(prop(shapes, "maxInclusive"), facets.get("maximum"));
            if (facets.get("enumValues") instanceof List<?> list) {
                String enumDatatype = datatype;
                RDFNode[] nodes =
                        list.stream()
                                .map(value -> enumLiteral(shapes, value, enumDatatype))
                                .toArray(RDFNode[]::new);
                ps.addProperty(prop(shapes, "in"), shapes.createList(nodes));
            }
        }
        for (var c : RdfCodec.rows(p, "constraints")) {
            String id = c.get("targetRef").toString(), kind = c.get("kind").toString();
            Map<String, Object> expr = (Map<String, Object>) c.get("expr");
            if (kind.equals("RANGE") || kind.equals("CARDINALITY")) {
                Resource ps = propertyShapes.get(id);
                if (ps == null) {
                    Resource shape = node(shapes, classes.get(domains.get(id)));
                    ps =
                            shapes.createResource()
                                    .addProperty(
                                            prop(shapes, "path"),
                                            shapes.createResource(paths.get(id)));
                    shape.addProperty(prop(shapes, "property"), ps);
                }
                ps.addLiteral(
                        prop(shapes, kind.equals("RANGE") ? "minInclusive" : "minCount"),
                        expr.get("min"));
                ps.addLiteral(
                        prop(shapes, kind.equals("RANGE") ? "maxInclusive" : "maxCount"),
                        expr.get("max"));
            } else if (kind.equals("MUTEX")) {
                Resource shape = node(shapes, classes.get(id));
                for (Object other : (List<?>) expr.get("classIds"))
                    shape.addProperty(
                            prop(shapes, "not"),
                            shapes.createResource()
                                    .addProperty(
                                            prop(shapes, "class"),
                                            shapes.createResource(
                                                    classes.get(
                                                            other.toString()
                                                                    .toLowerCase(Locale.ROOT)))));
            } else if (kind.equals("DEPENDENCY")) {
                Resource target = node(shapes, classes.get(domains.get(id)));
                for (Object other : (List<?>) expr.get("requires")) {
                    Resource empty =
                            shapes.createResource()
                                    .addProperty(
                                            prop(shapes, "property"),
                                            shapes.createResource()
                                                    .addProperty(
                                                            prop(shapes, "path"),
                                                            shapes.createResource(paths.get(id)))
                                                    .addLiteral(prop(shapes, "maxCount"), 0));
                    Resource required =
                            shapes.createResource()
                                    .addProperty(
                                            prop(shapes, "property"),
                                            shapes.createResource()
                                                    .addProperty(
                                                            prop(shapes, "path"),
                                                            shapes.createResource(
                                                                    paths.get(
                                                                            other.toString()
                                                                                    .toLowerCase(
                                                                                            Locale
                                                                                                    .ROOT))))
                                                    .addLiteral(prop(shapes, "minCount"), 1));
                    target.addProperty(
                            prop(shapes, "or"), shapes.createList(new RDFNode[] {empty, required}));
                }
            }
        }
        return shapes;
    }

    /** 枚举匹配按RDF项而非Java类型，日期字符串必须生成xsd:date，不能生成xsd:string。 */
    private static Literal enumLiteral(Model model, Object value, String datatype) {
        String lexical = value.toString();
        if (value instanceof Number)
            lexical = new java.math.BigDecimal(lexical).stripTrailingZeros().toPlainString();
        return model.createTypedLiteral(
                lexical,
                org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(datatype));
    }
}
