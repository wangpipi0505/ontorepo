package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.model.infra.RdfCodec;
import cn.ontorepo.operation.application.ConflictService;
import cn.ontorepo.trace.application.ShaclChecker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.jena.vocabulary.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/** C1独立复审的五项真实边界回归，业务路径全部连接本工程PG。 */
class C1ReviewRegressionTest extends WorkflowFixture {
    @Autowired ConflictService conflicts;
    static final String PREFIX =
            "@prefix owl:<http://www.w3.org/2002/07/owl#>. @prefix"
                + " rdfs:<http://www.w3.org/2000/01/rdf-schema#>. @prefix"
                + " xsd:<http://www.w3.org/2001/XMLSchema#>. @prefix ex:<https://example.org/>. ";

    String imported(String ttl) throws Exception {
        String s = createSpace();
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "TTL", "content", PREFIX + ttl, "expectedDraftRev", rev(s)));
        assertEquals("PREVIEW", preview.path("status").asText());
        data(
                post("/onto/imports/" + preview.path("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        return s;
    }

    String exported(String s) throws Exception {
        return data(get("/onto/spaces/" + s + "/export"), null).path("content").asText();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> snapshot(String candidate) throws Exception {
        return json.readValue(
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(candidate)),
                Map.class);
    }

    UUID legacy(String s, String candidate, java.util.function.Consumer<ObjectNode> edit)
            throws Exception {
        ObjectNode node = (ObjectNode) json.valueToTree(snapshot(candidate));
        edit.accept(node);
        byte[] bytes = json.writeValueAsBytes(node);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO publish_candidate(id,space_id,draft_rev,sha256,payload)"
                        + " VALUES(?,?,?,?,?)",
                id,
                UUID.fromString(s),
                rev(s),
                cn.ontorepo.space.application.AuthService.digest(bytes),
                bytes);
        return id;
    }

    @Test
    void importedRelationEditsSynchronizeSymmetryAndInverseWithoutDroppingOtherAxioms()
            throws Exception {
        String s =
                imported(
                        "ex:A a owl:Class. ex:B a owl:Class. ex:r a owl:ObjectProperty; rdfs:domain"
                                + " ex:A; rdfs:range ex:B; rdfs:comment 'keep me'.");
        JsonNode r = data(get("/onto/relations").param("spaceId", s), null).get(0);
        String id = r.path("id").asText();
        Map<String, Object> b =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "r",
                                "fromClassId",
                                r.path("fromClassId").asText(),
                                "toClassId",
                                r.path("toClassId").asText(),
                                "cardinality",
                                "MANY_TO_MANY",
                                "transitive",
                                false,
                                "symmetric",
                                true,
                                "description",
                                "keep me"));
        data(put("/onto/relations/" + id), b);
        try (var graph = new RdfCodec.Handle(exported(s), "TTL")) {
            var rr = graph.model.createResource("https://example.org/r");
            assertTrue(graph.model.contains(rr, RDF.type, OWL.SymmetricProperty));
            assertTrue(graph.model.contains(rr, RDFS.comment, "keep me"));
        }
        String c = freeze(s).path("id").asText();
        assertTrue(
                data(post("/onto/candidates/" + c + "/checks"), null)
                        .toString()
                        .contains("SUBSET_EXCEEDED"));
        b.put("expectedDraftRev", rev(s));
        b.put("symmetric", false);
        data(put("/onto/relations/" + id), b);
        try (var graph = new RdfCodec.Handle(exported(s), "TTL")) {
            assertFalse(
                    graph.model.contains(
                            graph.model.createResource("https://example.org/r"),
                            RDF.type,
                            OWL.SymmetricProperty));
        }
        b.put("expectedDraftRev", rev(s));
        b.put("inverseName", "reverse");
        data(put("/onto/relations/" + id), b);
        try (var graph = new RdfCodec.Handle(exported(s), "TTL")) {
            var rr = graph.model.createResource("https://example.org/r");
            assertTrue(
                    graph.model.contains(
                            rr, OWL.inverseOf, (org.apache.jena.rdf.model.RDFNode) null));
            var inverse = rr.getProperty(OWL.inverseOf).getResource();
            assertTrue(graph.model.contains(inverse, OWL.inverseOf, rr));
        }
    }

    @Test
    void uneditableDateTimeAndCustomDatatypesRetainUntouchedOriginalGraphs() throws Exception {
        for (String range : List.of("xsd:dateTime", "ex:CustomDatatype")) {
            String ttl =
                    PREFIX
                            + "ex:A a owl:Class. ex:p a owl:DatatypeProperty; rdfs:domain ex:A;"
                            + " rdfs:range "
                            + range
                            + ". ";
            String s = createSpace();
            var preview =
                    data(
                            post("/onto/spaces/" + s + "/imports/preview"),
                            Map.of("kind", "TTL", "content", ttl, "expectedDraftRev", rev(s)));
            assertEquals("PREVIEW", preview.path("status").asText());
            data(
                    post("/onto/imports/" + preview.path("id").asText() + "/apply"),
                    Map.of("expectedDraftRev", rev(s)));
            try (var before = new RdfCodec.Handle(ttl, "TTL");
                    var after = new RdfCodec.Handle(exported(s), "TTL")) {
                assertTrue(
                        before.model.isIsomorphicWith(after.model),
                        range + " must remain graph-isomorphic");
            }
            assertTrue(preview.path("unsupported").size() > 0, preview.toString());
            assertEquals(0, data(get("/onto/spaces/" + s + "/properties"), null).size());
            String c = freeze(s).path("id").asText();
            Map<String, Object> p = snapshot(c);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) p.get("classes");
            p.put(
                    "properties",
                    List.of(
                            Map.of(
                                    "id",
                                    UUID.randomUUID().toString(),
                                    "spaceId",
                                    s,
                                    "sourceIri",
                                    "https://example.org/p",
                                    "domainClassId",
                                    classes.stream()
                                            .filter(
                                                    cl ->
                                                            "https://example.org/A"
                                                                    .equals(cl.get("sourceIri")))
                                            .findFirst()
                                            .orElseThrow()
                                            .get("id"),
                                    "name",
                                    "p",
                                    "rangeType",
                                    "STRING",
                                    "required",
                                    false,
                                    "description",
                                    "")));
            try (var before = new RdfCodec.Handle(ttl, "TTL");
                    var oldProjection = new RdfCodec.Handle(p)) {
                assertTrue(
                        before.model.isIsomorphicWith(oldProjection.model),
                        "旧候选的STRING占位值也不能改写原始类型");
            }
        }
    }

    @Test
    void uppercaseStrategyBindingsAreCanonicalAndOldCandidateLimitsRemainEnforced()
            throws Exception {
        String s = createSpace();
        String op = createOperation(s, 50);
        data(
                post("/onto/strategies"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "限制",
                        "policy",
                        Map.of(
                                "maxRows",
                                1,
                                "resultLimit",
                                1,
                                "timeoutMs",
                                1000,
                                "failureMode",
                                "FAIL"),
                        "boundOps",
                        List.of(op.toUpperCase(Locale.ROOT))));
        assertEquals(
                op,
                data(get("/onto/strategies").param("spaceId", s), null)
                        .get(0)
                        .path("boundOps")
                        .get(0)
                        .asText());
        createClass(s, "A", null);
        createClass(s, "B", null);
        createClass(s, "C", null);
        String c = freeze(s).path("id").asText();
        assertEquals(
                1,
                data(post("/onto/candidates/" + c + "/trial"), Map.of("operationId", op))
                        .path("rows")
                        .size());
        byte[] original =
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(c));
        UUID old =
                legacy(
                        s,
                        c,
                        n ->
                                ((ObjectNode) n.path("strategies").get(0))
                                        .putArray("boundOps")
                                        .add(op.toUpperCase(Locale.ROOT)));
        assertEquals(
                1,
                data(post("/onto/candidates/" + old + "/trial"), Map.of("operationId", op))
                        .path("rows")
                        .size());
        UUID scoped =
                legacy(
                        s,
                        c,
                        n -> {
                            var st = (ObjectNode) n.path("strategies").get(0);
                            st.putArray("boundOps").add(op.toUpperCase(Locale.ROOT));
                            ((ObjectNode) st.path("policy"))
                                    .putObject("readScope")
                                    .put("unclassified", false);
                        });
        assertEquals(
                "READ_SCOPE_UNSUPPORTED",
                data(post("/onto/candidates/" + scoped + "/trial"), Map.of("operationId", op))
                        .path("errorCode")
                        .asText());
        assertArrayEquals(
                original,
                db.queryForObject(
                        "SELECT payload FROM publish_candidate WHERE id=?",
                        byte[].class,
                        UUID.fromString(c)));
        db.update(
                "UPDATE ont_strategy SET bound_ops=?::jsonb WHERE space_id=?",
                json.writeValueAsString(List.of(op.toUpperCase(Locale.ROOT))),
                UUID.fromString(s));
        call(
                delete("/onto/operations/" + op)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
    }

    @Test
    void dateEnumsUseDateRdfTermsForValidAndRejectedInstances() throws Exception {
        for (String value : List.of("2026-10-20", "2026-10-21")) {
            String s =
                    imported(
                            "ex:A a owl:Class. ex:p a owl:DatatypeProperty; rdfs:domain ex:A;"
                                    + " rdfs:range xsd:date. ex:i a ex:A; ex:p '"
                                    + value
                                    + "'^^xsd:date.");
            var prop = data(get("/onto/spaces/" + s + "/properties"), null).get(0);
            data(
                    put("/onto/properties/" + prop.path("id").asText()),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "domainClassId",
                            prop.path("domainClassId").asText(),
                            "name",
                            "p",
                            "rangeType",
                            "DATE",
                            "required",
                            false,
                            "description",
                            "",
                            "facets",
                            Map.of("enumValues", List.of("2026-10-20"))));
            String c = freeze(s).path("id").asText();
            var result = new ShaclChecker().check(snapshot(c));
            assertEquals(value.equals("2026-10-20"), result.get("conforms"), result.toString());
        }
    }

    @Test
    void draftConflictSnapshotIncludesNormalAndMissingStrategyReferences() throws Exception {
        String s = createSpace();
        String op = createOperation(s, 50);
        data(
                post("/onto/strategies"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "策略",
                        "policy",
                        Map.of(
                                "maxRows",
                                1,
                                "resultLimit",
                                1,
                                "timeoutMs",
                                1000,
                                "failureMode",
                                "FAIL"),
                        "boundOps",
                        List.of()));
        String strategy =
                data(get("/onto/strategies").param("spaceId", s), null).get(0).path("id").asText();
        data(
                put("/onto/operations/" + op),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "listClasses",
                        "name",
                        "列出对象",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        50,
                        "timeoutMs",
                        1000,
                        "strategyRef",
                        strategy));
        assertEquals(0, data(get("/onto/spaces/" + s + "/conflicts"), null).path("errors").size());
        String c = freeze(s).path("id").asText();
        var p = snapshot(c);
        p.put("strategies", List.of());
        assertTrue(conflicts.inspect(p).get("errors").toString().contains("REFERENCE"));
    }

    @Test
    void otherUuidRuleReferencesNormalizeAndLegacyArraysStillProtectBindings() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null);
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "domainClassId",
                        a,
                        "name",
                        "p",
                        "rangeType",
                        "INTEGER",
                        "required",
                        false,
                        "description",
                        ""));
        String p = data(get("/onto/spaces/" + s + "/properties"), null).get(0).path("id").asText();
        Map<String, Object> rule =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "规则",
                                "applicableClassId",
                                a,
                                "kind",
                                "VALIDATION",
                                "condition",
                                "${" + p.toUpperCase(Locale.ROOT) + "} > 0",
                                "action",
                                Map.of("type", "ACCEPT", "message", "ok"),
                                "severity",
                                "ERROR"));
        data(post("/onto/rules"), rule);
        String rid = data(get("/onto/rules").param("spaceId", s), null).get(0).path("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "check",
                        "name",
                        "检查",
                        "kind",
                        "RULE_CHECK",
                        "maxRows",
                        10,
                        "timeoutMs",
                        1000,
                        "ruleIds",
                        List.of(rid.toUpperCase(Locale.ROOT))));
        var op = data(get("/onto/operations").param("spaceId", s), null).get(0);
        assertEquals(rid, op.path("ruleIds").get(0).asText());
        String c = freeze(s).path("id").asText();
        assertEquals(
                "true",
                data(
                                post("/onto/candidates/" + c + "/trial"),
                                Map.of(
                                        "operationId",
                                        op.path("id").asText(),
                                        "input",
                                        Map.of(p.toUpperCase(Locale.ROOT), 1)))
                        .path("rows")
                        .get(0)
                        .path("passed")
                        .asText());
        UUID old =
                legacy(
                        s,
                        c,
                        n ->
                                ((ObjectNode) n.path("operations").get(0))
                                        .putArray("ruleIds")
                                        .add(rid.toUpperCase(Locale.ROOT)));
        assertEquals(
                "SUCCEEDED",
                data(
                                post("/onto/candidates/" + old + "/trial"),
                                Map.of(
                                        "operationId",
                                        op.path("id").asText(),
                                        "input",
                                        Map.of(p, 1)))
                        .path("status")
                        .asText());
        db.update(
                "UPDATE ont_operation SET rule_ids=?::jsonb WHERE id=?",
                json.writeValueAsString(List.of(rid.toUpperCase(Locale.ROOT))),
                UUID.fromString(op.path("id").asText()));
        call(
                delete("/onto/rules/" + rid)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
        Map<String, Object> scoring =
                Map.of(
                        "metrics",
                        List.of(
                                Map.of(
                                        "propertyId",
                                        p.toUpperCase(Locale.ROOT),
                                        "weight",
                                        1,
                                        "bands",
                                        List.of(Map.of("min", 0, "max", 10, "score", 5)))),
                        "missingValue",
                        "REJECT",
                        "tieBreak",
                        "STABLE_ID",
                        "forbiddenProperties",
                        List.of(),
                        "explain",
                        true);
        rule.put("expectedDraftRev", rev(s));
        rule.put("name", "评分");
        rule.put("kind", "SCORING");
        rule.put("condition", "");
        rule.put("action", Map.of());
        rule.put("scoring", scoring);
        data(post("/onto/rules"), rule);
        for (var saved : data(get("/onto/rules").param("spaceId", s), null))
            if (saved.path("kind").asText().equals("SCORING"))
                assertEquals(
                        p,
                        saved.path("scoring").path("metrics").get(0).path("propertyId").asText());
    }

    @Test
    void uuidConstraintAndCodebookCollectionsCannotHideExistingReferences() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null);
        for (String name : List.of("p", "q"))
            data(
                    post("/onto/properties"),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "domainClassId",
                            a,
                            "name",
                            name,
                            "rangeType",
                            "INTEGER",
                            "required",
                            false,
                            "description",
                            ""));
        var props = data(get("/onto/spaces/" + s + "/properties"), null);
        String p = props.get(0).path("id").asText(), q = props.get(1).path("id").asText();
        data(
                post("/onto/constraints"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "依赖",
                        "kind",
                        "DEPENDENCY",
                        "targetType",
                        "PROPERTY",
                        "targetRef",
                        p,
                        "expr",
                        Map.of("requires", List.of(q.toUpperCase(Locale.ROOT)))));
        assertEquals(
                q,
                data(get("/onto/constraints").param("spaceId", s), null)
                        .get(0)
                        .path("expr")
                        .path("requires")
                        .get(0)
                        .asText());
        db.update(
                "UPDATE ont_constraint SET expr=?::jsonb WHERE space_id=?",
                json.writeValueAsString(Map.of("requires", List.of(q.toUpperCase(Locale.ROOT)))),
                UUID.fromString(s));
        call(
                delete("/onto/properties/" + q)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
        data(
                post("/onto/codebooks"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "units",
                        "name",
                        "单位",
                        "kind",
                        "UNIT",
                        "entries",
                        List.of(Map.of("code", "m", "label", "米"))));
        String cb =
                data(get("/onto/codebooks").param("spaceId", s), null).get(0).path("id").asText();
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "domainClassId",
                        a,
                        "name",
                        "距离",
                        "rangeType",
                        "DECIMAL",
                        "required",
                        false,
                        "description",
                        "",
                        "facets",
                        Map.of("unitCodebookId", cb.toUpperCase(Locale.ROOT), "unitCode", "m")));
        String distance = null;
        for (var prop : data(get("/onto/spaces/" + s + "/properties"), null))
            if (prop.path("name").asText().equals("距离")) {
                distance = prop.path("id").asText();
                assertEquals(cb, prop.path("facets").path("unitCodebookId").asText());
            }
        db.update(
                "UPDATE ont_property SET facets=?::jsonb WHERE id=?",
                json.writeValueAsString(
                        Map.of("unitCodebookId", cb.toUpperCase(Locale.ROOT), "unitCode", "m")),
                UUID.fromString(distance));
        call(
                delete("/onto/codebooks/" + cb)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
    }

    @Test
    void clearingImportedSymmetryRechecksActualCandidateGraphInsteadOfStalePreviewFlags()
            throws Exception {
        String s =
                imported(
                        "ex:A a owl:Class. ex:B a owl:Class. ex:r a"
                                + " owl:ObjectProperty,owl:SymmetricProperty; rdfs:domain ex:A;"
                                + " rdfs:range ex:B.");
        String before = freeze(s).path("id").asText();
        assertTrue(
                data(post("/onto/candidates/" + before + "/checks"), null)
                        .toString()
                        .contains("SUBSET_EXCEEDED"));
        var relation = data(get("/onto/relations").param("spaceId", s), null).get(0);
        data(
                put("/onto/relations/" + relation.path("id").asText()),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "r",
                        "fromClassId",
                        relation.path("fromClassId").asText(),
                        "toClassId",
                        relation.path("toClassId").asText(),
                        "cardinality",
                        "MANY_TO_MANY",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        ""));
        String after = freeze(s).path("id").asText();
        var checks = data(post("/onto/candidates/" + after + "/checks"), null);
        assertTrue(checks.path("eligible").asBoolean(), checks.toString());
        assertTrue(checks.toString().contains("CONSISTENT"), checks.toString());
        assertTrue(
                data(post("/onto/candidates/" + before + "/checks"), null)
                        .toString()
                        .contains("SUBSET_EXCEEDED"));
    }

    private String referencedIntegerImport() throws Exception {
        String s =
                imported(
                        "ex:A a owl:Class. ex:p a owl:DatatypeProperty; rdfs:domain ex:A;"
                            + " rdfs:range xsd:integer.");
        var p = data(get("/onto/spaces/" + s + "/properties"), null).get(0);
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "数值校验",
                        "applicableClassId",
                        p.path("domainClassId").asText(),
                        "kind",
                        "VALIDATION",
                        "condition",
                        "${" + p.path("id").asText() + "} > 0",
                        "action",
                        Map.of("type", "ACCEPT", "message", "ok"),
                        "severity",
                        "ERROR"));
        return s;
    }

    private String unsupportedReplacement(String datatype) {
        return PREFIX
                + "ex:A a owl:Class. ex:p a owl:DatatypeProperty; rdfs:domain ex:A; rdfs:range "
                + datatype
                + ".";
    }

    @Test
    void referencedPropertyUnknownDatatypeIsReportedBeforeProjectionSkip() throws Exception {
        for (String datatype : List.of("xsd:dateTime", "ex:CustomDatatype")) {
            String s = referencedIntegerImport();
            long before = rev(s);
            var preview =
                    data(
                            post("/onto/spaces/" + s + "/imports/preview"),
                            Map.of(
                                    "kind",
                                    "TTL",
                                    "content",
                                    unsupportedReplacement(datatype),
                                    "expectedDraftRev",
                                    before));
            assertEquals("PREVIEW", preview.path("status").asText());
            assertEquals(0, preview.path("errors").size());
            assertTrue(preview.path("conflicts").size() > 0, preview.toString());
            assertEquals(before, rev(s));
            call(
                    post("/onto/imports/" + preview.path("id").asText() + "/apply"),
                    ownerToken,
                    Map.of("expectedDraftRev", before),
                    409);
            assertEquals(before, rev(s));
        }
    }

    @Test
    void applyRechecksReferencedUnknownDatatypeEvenForLegacyCleanPreview() throws Exception {
        String s = referencedIntegerImport();
        long before = rev(s);
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                unsupportedReplacement("xsd:dateTime"),
                                "expectedDraftRev",
                                before));
        String job = preview.path("id").asText();
        // 模拟修复前已持久化的“无冲突”预览；确认必须重新检查当前引用，不能只信报告。
        db.update(
                "UPDATE ont_import SET report=jsonb_set(report,'{conflicts}','[]'::jsonb) WHERE"
                    + " id=?",
                UUID.fromString(job));
        int documents =
                db.queryForObject(
                        "SELECT count(*) FROM ont_rdf_document WHERE space_id=?",
                        Integer.class,
                        UUID.fromString(s));
        int audits =
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE space_id=? AND action='IMPORT_APPLY'",
                        Integer.class,
                        UUID.fromString(s));
        var response =
                call(
                        post("/onto/imports/" + job + "/apply"),
                        ownerToken,
                        Map.of("expectedDraftRev", before),
                        409);
        assertEquals("PROPERTY_IN_USE", response.path("code").asText());
        assertEquals(before, rev(s));
        assertEquals(
                documents,
                db.queryForObject(
                        "SELECT count(*) FROM ont_rdf_document WHERE space_id=?",
                        Integer.class,
                        UUID.fromString(s)));
        assertEquals(
                audits,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE space_id=? AND action='IMPORT_APPLY'",
                        Integer.class,
                        UUID.fromString(s)));
        assertEquals(
                false,
                db.queryForObject(
                        "SELECT applied FROM ont_import WHERE id=?",
                        Boolean.class,
                        UUID.fromString(job)));
        assertEquals(
                "INTEGER",
                data(get("/onto/spaces/" + s + "/properties"), null)
                        .get(0)
                        .path("rangeType")
                        .asText());
        try (var graph = new RdfCodec.Handle(exported(s), "TTL")) {
            var ranges =
                    graph.model
                            .createResource("https://example.org/p")
                            .listProperties(RDFS.range)
                            .toList();
            assertEquals(1, ranges.size());
            assertEquals(XSD.NS + "integer", ranges.get(0).getResource().getURI());
        }
    }
}
