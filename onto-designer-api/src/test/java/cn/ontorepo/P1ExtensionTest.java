package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** P1 扩展走真实 HTTP 边界及 PostgreSQL，验证失败回滚和冻结隔离。 */
class P1ExtensionTest extends WorkflowFixture {
    @Test
    void codebookFacetsAndInverseRelationsArePersistentAndAtomic() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), b = createClass(s, "B", null);
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
        String units =
                data(get("/onto/codebooks").param("spaceId", s), null).get(0).get("id").asText();
        Map<String, Object> p =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "domainClassId",
                                a,
                                "name",
                                "长度",
                                "rangeType",
                                "DECIMAL",
                                "required",
                                true,
                                "description",
                                "",
                                "facets",
                                Map.of("minimum", 0, "maximum", 10, "defaultValue", 11)));
        long r = rev(s);
        call(post("/onto/properties"), ownerToken, p, 400);
        assertEquals(r, rev(s));
        p.put(
                "facets",
                Map.of(
                        "minimum",
                        0,
                        "maximum",
                        10,
                        "defaultValue",
                        2,
                        "unitCodebookId",
                        units,
                        "unitCode",
                        "m"));
        data(post("/onto/properties"), p);
        assertEquals(
                2,
                data(get("/onto/properties").param("spaceId", s).param("classId", a), null)
                        .get(0)
                        .path("facets")
                        .path("defaultValue")
                        .asInt());
        data(
                post("/onto/relations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "使用",
                        "fromClassId",
                        a,
                        "toClassId",
                        b,
                        "cardinality",
                        "ONE_TO_MANY",
                        "inverseName",
                        "被使用",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        ""));
        var rs = data(get("/onto/relations").param("spaceId", s), null);
        assertEquals(2, rs.size());
        assertTrue(rs.toString().contains("MANY_TO_ONE"));
        data(
                delete("/onto/relations/" + rs.get(0).get("id").asText())
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
        assertEquals(0, data(get("/onto/relations").param("spaceId", s), null).size());
    }

    @Test
    void rdfPreviewPreservesUnsupportedGraphAndRequiresConfirmation() throws Exception {
        String s = createSpace();
        String ttl =
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . @prefix rdfs:"
                        + " <http://www.w3.org/2000/01/rdf-schema#> . <urn:A> a owl:Class;"
                        + " rdfs:subClassOf [ a owl:Restriction; owl:onProperty <urn:p>;"
                        + " owl:allValuesFrom <urn:B> ] .";
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "TTL", "content", ttl, "expectedDraftRev", rev(s)));
        assertEquals(0, rev(s));
        assertTrue(preview.path("unsupported").size() > 0);
        data(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        String out = data(get("/onto/spaces/" + s + "/export"), null).get("content").asText();
        var m1 = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        var m2 = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        org.apache.jena.riot.RDFParser.fromString(ttl)
                .lang(org.apache.jena.riot.Lang.TURTLE)
                .parse(m1);
        org.apache.jena.riot.RDFParser.fromString(out)
                .lang(org.apache.jena.riot.Lang.TURTLE)
                .parse(m2);
        assertTrue(m1.isIsomorphicWith(m2));
        String c = freeze(s).get("id").asText();
        var checks = data(post("/onto/candidates/" + c + "/checks"), null);
        assertFalse(checks.get("eligible").asBoolean());
        assertTrue(checks.toString().contains("SUBSET_EXCEEDED"));
        call(
                post("/onto/candidates/" + c + "/waivers"),
                viewerToken,
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "审查"),
                403);
        data(
                post("/onto/candidates/" + c + "/waivers"),
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "已评审扩展构造"));
        assertTrue(
                data(post("/onto/candidates/" + c + "/checks"), null).path("eligible").asBoolean());
        createClass(s, "草稿变更", null);
        assertFalse(
                data(post("/onto/candidates/" + c + "/checks"), null).path("eligible").asBoolean());
        call(
                post("/onto/candidates/" + c + "/waivers"),
                ownerToken,
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "过期审批"),
                409);
    }

    @Test
    void testCasesAreFrozenAndExecutionsUseLocalTestCaller() throws Exception {
        String s = createSpace();
        createClass(s, "A", null);
        String op = createOperation(s, 50);
        data(
                post("/onto/test-cases"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "用例",
                        "question",
                        "列出类",
                        "operationId",
                        op,
                        "input",
                        Map.of(),
                        "expected",
                        Map.of("status", "SUCCEEDED"),
                        "matchMode",
                        "CONTAINS",
                        "enabled",
                        true));
        String c = freeze(s).get("id").asText();
        var tests = data(post("/onto/candidates/" + c + "/tests"), null);
        assertTrue(tests.get("passed").asBoolean());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE caller='LOCAL_TEST'",
                        Integer.class));
    }

    String property(String space, String cl, String name, String type) throws Exception {
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        space,
                        "expectedDraftRev",
                        rev(space),
                        "domainClassId",
                        cl,
                        "name",
                        name,
                        "rangeType",
                        type,
                        "required",
                        false,
                        "description",
                        ""));
        for (var p : data(get("/onto/spaces/" + space + "/properties"), null))
            if (name.equals(p.get("name").asText())) return p.get("id").asText();
        throw new AssertionError();
    }

    Map<String, Object> rule(String s, String cl, String name, String condition, String action)
            throws Exception {
        return new HashMap<>(
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        name,
                        "applicableClassId",
                        cl,
                        "kind",
                        "VALIDATION",
                        "condition",
                        condition,
                        "action",
                        Map.of("type", action, "message", name),
                        "severity",
                        "ERROR"));
    }

    @Test
    void constraintsRejectCrossSpaceAndPropertyReferencesBlockDestructiveChanges()
            throws Exception {
        String s = createSpace(),
                other = createSpace(),
                a = createClass(s, "A", null),
                b = createClass(other, "B", null),
                p = property(s, a, "p", "INTEGER"),
                foreign = property(other, b, "q", "INTEGER");
        Map<String, Object> c =
                new HashMap<>(
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
                                Map.of("requires", List.of(foreign))));
        call(post("/onto/constraints"), ownerToken, c, 400);
        c.put("expr", Map.of("requires", List.of(p)));
        call(post("/onto/constraints"), ownerToken, c, 400);
        c.put("kind", "RANGE");
        c.put("expr", Map.of("min", 10, "max", 1));
        call(post("/onto/constraints"), ownerToken, c, 400);
        c.put("expr", Map.of("min", 1, "max", 10));
        data(post("/onto/constraints"), c);
        assertEquals(
                1,
                data(get("/onto/properties/" + p + "/impact").param("spaceId", s), null)
                        .get("references")
                        .size());
        call(
                delete("/onto/properties/" + p)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
        call(get("/onto/spaces/" + s + "/properties"), strangerToken, null, 403);
    }

    @Test
    void conditionsAreTypedAndAllFourConflictKindsAreDetected() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), p = property(s, a, "p", "INTEGER");
        String x = "${" + p + "}";
        call(post("/onto/rules"), ownerToken, rule(s, a, "非法", x + " > '2'", "REJECT"), 400);
        data(post("/onto/rules"), rule(s, a, "低区间", x + " >= 0 && " + x + " <= 10", "ACCEPT"));
        data(post("/onto/rules"), rule(s, a, "高区间", x + " >= 5 && " + x + " <= 20", "REJECT"));
        assertTrue(
                data(get("/onto/spaces/" + s + "/conflicts"), null)
                        .path("warnings")
                        .toString()
                        .contains("LOGIC"));
        data(post("/onto/rules"), rule(s, a, "包含", x + " == 6", "REJECT"));
        assertTrue(
                data(get("/onto/spaces/" + s + "/conflicts"), null)
                        .path("errors")
                        .toString()
                        .contains("CONSTRAINT"));
        db.update(
                "UPDATE ont_rule SET condition=? WHERE name=? AND space_id=?",
                x + " > 'x'",
                "包含",
                UUID.fromString(s));
        assertTrue(
                data(get("/onto/spaces/" + s + "/conflicts"), null)
                        .path("errors")
                        .toString()
                        .contains("TYPE"));
        db.update(
                "UPDATE ont_rule SET condition=? WHERE name=? AND space_id=?",
                "${" + UUID.randomUUID() + "} > 0",
                "包含",
                UUID.fromString(s));
        assertTrue(
                data(get("/onto/spaces/" + s + "/conflicts"), null)
                        .path("errors")
                        .toString()
                        .contains("REFERENCE"));
    }

    @Test
    void frozenRulesScoringAndPolicyLimitsDoNotReadNewDraft() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), p = property(s, a, "p", "DECIMAL");
        Map<String, Object> scoring =
                Map.of(
                        "metrics",
                        List.of(
                                Map.of(
                                        "propertyId",
                                        p,
                                        "weight",
                                        1,
                                        "bands",
                                        List.of(Map.of("min", 0, "max", 10, "score", 75)))),
                        "missingValue",
                        "REJECT",
                        "tieBreak",
                        "STABLE_ID",
                        "forbiddenProperties",
                        List.of(),
                        "explain",
                        true);
        data(
                post("/onto/rules"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "评分",
                        "applicableClassId",
                        a,
                        "kind",
                        "SCORING",
                        "condition",
                        "",
                        "action",
                        Map.of(),
                        "scoring",
                        scoring,
                        "severity",
                        "ERROR"));
        String rule = data(get("/onto/rules").param("spaceId", s), null).get(0).get("id").asText();
        Map<String, Object> op =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "评分操作",
                                "code",
                                "score",
                                "kind",
                                "SCORING",
                                "ruleIds",
                                List.of(rule),
                                "maxRows",
                                20,
                                "timeoutMs",
                                1000));
        data(post("/onto/operations"), op);
        String
                oid =
                        data(get("/onto/operations").param("spaceId", s), null)
                                .get(0)
                                .get("id")
                                .asText(),
                candidate = freeze(s).get("id").asText();
        db.update("UPDATE ont_rule SET scoring='{}'::jsonb WHERE id=?", UUID.fromString(rule));
        var result =
                data(
                        post("/onto/candidates/" + candidate + "/trial"),
                        Map.of("operationId", oid, "input", Map.of(p, 5)));
        assertEquals("75", result.path("rows").get(0).path("score").asText());
        String queryOp = createOperation(s, 50);
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
                                "timeoutMs",
                                1000,
                                "resultLimit",
                                1,
                                "failureMode",
                                "FAIL"),
                        "boundOps",
                        List.of(queryOp)));
        createClass(s, "B", null);
        String next = freeze(s).get("id").asText();
        assertEquals(
                1,
                data(post("/onto/candidates/" + next + "/trial"), Map.of("operationId", queryOp))
                        .path("rows")
                        .size());
    }

    @Test
    void elkChecksConsistentUnsatisfiableAndActualTimeout() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null);
        String c = freeze(s).get("id").asText();
        var consistent = data(post("/onto/candidates/" + c + "/checks"), null);
        assertTrue(consistent.toString().contains("CONSISTENT"), consistent.toString());
        assertTrue(consistent.get("eligible").asBoolean(), consistent.toString());
        call(
                post("/onto/candidates/" + c + "/waivers"),
                ownerToken,
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "不允许"),
                409);
        String b = createClass(s, "B", a);
        data(
                post("/onto/constraints"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "互斥",
                        "kind",
                        "MUTEX",
                        "targetType",
                        "CLASS",
                        "targetRef",
                        b,
                        "expr",
                        Map.of("classIds", List.of(a))));
        String bad = freeze(s).get("id").asText();
        var inconsistent = data(post("/onto/candidates/" + bad + "/checks"), null);
        assertTrue(inconsistent.toString().contains("INCONSISTENT"), inconsistent.toString());
        assertFalse(inconsistent.path("eligible").asBoolean());
        var checker = new cn.ontorepo.trace.application.ConsistencyChecker(0);
        assertEquals("TIMEOUT", checker.check(Map.of()).get("reason"));
    }

    @Test
    void frozenTestSetIgnoresDraftChangesAndExactMismatchFails() throws Exception {
        String s = createSpace();
        createClass(s, "A", null);
        String op = createOperation(s, 50);
        Map<String, Object> test =
                new HashMap<>(
                        Map.of(
                                "spaceId",
                                s,
                                "expectedDraftRev",
                                rev(s),
                                "name",
                                "断言",
                                "question",
                                "对象",
                                "operationId",
                                op,
                                "input",
                                Map.of(),
                                "expected",
                                Map.of("rows", List.of()),
                                "matchMode",
                                "EXACT",
                                "enabled",
                                true));
        data(post("/onto/test-cases"), test);
        String c = freeze(s).get("id").asText();
        String tid =
                data(get("/onto/test-cases").param("spaceId", s), null).get(0).get("id").asText();
        test.put("expectedDraftRev", rev(s));
        test.put("enabled", false);
        data(put("/onto/test-cases/" + tid), test);
        var result = data(post("/onto/candidates/" + c + "/tests"), null);
        assertFalse(result.get("passed").asBoolean());
        assertEquals(1, result.get("results").size());
    }

    @Test
    void importSyntaxStaleRevisionAndOwnerWaiverAreEnforced() throws Exception {
        String s = createSpace();
        var broken =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                "this is not turtle",
                                "expectedDraftRev",
                                rev(s)));
        assertEquals("FAILED", broken.get("status").asText());
        call(
                post("/onto/imports/" + broken.get("id").asText() + "/apply"),
                ownerToken,
                Map.of("expectedDraftRev", rev(s)),
                400);
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                "<urn:A> a <http://www.w3.org/2002/07/owl#Class> .",
                                "expectedDraftRev",
                                rev(s)));
        createClass(s, "B", null);
        call(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                ownerToken,
                Map.of("expectedDraftRev", rev(s)),
                409);
    }

    @Test
    void importedClassEditsAreReflectedInExportAndDuplicateNamePreviewShowsConflicts()
            throws Exception {
        String s = createSpace();
        String ttl =
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . @prefix rdfs:"
                        + " <http://www.w3.org/2000/01/rdf-schema#> . <https://example.org/A> a"
                        + " owl:Class; rdfs:label \"Original\" .";
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "TTL", "content", ttl, "expectedDraftRev", rev(s)));
        data(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        String a = data(get("/onto/classes").param("spaceId", s), null).get(0).get("id").asText();
        data(put("/onto/classes/" + a), cl(s, "Edited", null, rev(s)));
        String out = data(get("/onto/spaces/" + s + "/export"), null).path("content").asText();
        assertTrue(out.contains("Edited"), out);
        assertFalse(out.contains("Original"), out);
        var duplicate =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                ttl.replace("example.org/A", "example.org/Another")
                                        .replace("Original", "Edited"),
                                "expectedDraftRev",
                                rev(s)));
        assertTrue(duplicate.path("conflicts").size() > 0, duplicate.toString());
        data(
                delete("/onto/classes/" + a)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                null);
        assertFalse(
                data(get("/onto/spaces/" + s + "/export"), null)
                        .path("content")
                        .asText()
                        .contains("https://example.org/A"));
    }

    @Test
    void shaclValidatesActualCandidateInstancesAndExternalImportsAreFlagged() throws Exception {
        String s = createSpace();
        String ttl =
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . @prefix rdfs:"
                    + " <http://www.w3.org/2000/01/rdf-schema#> . @prefix xsd:"
                    + " <http://www.w3.org/2001/XMLSchema#> . <https://example.org/A> a owl:Class ."
                    + " <https://example.org/p> a owl:DatatypeProperty; rdfs:domain"
                    + " <https://example.org/A>; rdfs:range xsd:decimal . <https://example.org/x> a"
                    + " <https://example.org/A>; <https://example.org/p> 20.0 .";
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "TTL", "content", ttl, "expectedDraftRev", rev(s)));
        data(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        String prop =
                data(get("/onto/spaces/" + s + "/properties"), null).get(0).get("id").asText();
        data(
                post("/onto/constraints"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "限制",
                        "kind",
                        "RANGE",
                        "targetType",
                        "PROPERTY",
                        "targetRef",
                        prop,
                        "expr",
                        Map.of("min", 0, "max", 10)));
        String c = freeze(s).get("id").asText();
        var checks = data(post("/onto/candidates/" + c + "/checks"), null);
        assertFalse(checks.path("eligible").asBoolean());
        assertTrue(
                checks.toString().contains("MaxInclusiveConstraintComponent"), checks.toString());
        var imports =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                "<https://example.org/ont> <http://www.w3.org/2002/07/owl#imports>"
                                        + " <https://127.0.0.1:9/external> .",
                                "expectedDraftRev",
                                rev(s)));
        assertTrue(imports.path("unsupported").size() > 0, imports.toString());
    }

    @Test
    void mutexReferencesAndEmptyAssertionsCannotBeBroken() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), b = createClass(s, "B", null);
        data(
                post("/onto/constraints"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "互斥",
                        "kind",
                        "MUTEX",
                        "targetType",
                        "CLASS",
                        "targetRef",
                        a,
                        "expr",
                        Map.of("classIds", List.of(b))));
        call(
                delete("/onto/classes/" + b)
                        .param("spaceId", s)
                        .param("expectedDraftRev", "" + rev(s)),
                ownerToken,
                null,
                409);
        String op = createOperation(s, 50);
        call(
                post("/onto/test-cases"),
                ownerToken,
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "空断言",
                        "question",
                        "类",
                        "operationId",
                        op,
                        "input",
                        Map.of(),
                        "expected",
                        Map.of(),
                        "enabled",
                        true),
                400);
    }

    @Test
    void runtimeRuleFailuresAreRecordedWithExecutionFacts() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), p = property(s, a, "p", "INTEGER");
        data(post("/onto/rules"), rule(s, a, "除零", "${" + p + "} / 0 > 1", "REJECT"));
        String rid = data(get("/onto/rules").param("spaceId", s), null).get(0).get("id").asText();
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "校验",
                        "code",
                        "check",
                        "kind",
                        "RULE_CHECK",
                        "ruleIds",
                        List.of(rid),
                        "maxRows",
                        50,
                        "timeoutMs",
                        1000));
        String
                op =
                        data(get("/onto/operations").param("spaceId", s), null)
                                .get(0)
                                .get("id")
                                .asText(),
                c = freeze(s).get("id").asText();
        var execution =
                data(
                        post("/onto/candidates/" + c + "/trial"),
                        Map.of("operationId", op, "input", Map.of(p, 1)));
        assertEquals("FAILED", execution.get("status").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE status='FAILED' AND input_digest"
                                + " IS NOT NULL",
                        Integer.class));
    }

    @Test
    void reimportCannotBreakReferencedPropertyAndBoundRuleKindIsProtected() throws Exception {
        String s = createSpace();
        String ttl =
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . @prefix rdfs:"
                    + " <http://www.w3.org/2000/01/rdf-schema#> . @prefix xsd:"
                    + " <http://www.w3.org/2001/XMLSchema#> . <https://example.org/A> a owl:Class ."
                    + " <https://example.org/p> a owl:DatatypeProperty; rdfs:domain"
                    + " <https://example.org/A>; rdfs:range xsd:integer .";
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "TTL", "content", ttl, "expectedDraftRev", rev(s)));
        data(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        String a = data(get("/onto/classes").param("spaceId", s), null).get(0).get("id").asText(),
                p = data(get("/onto/spaces/" + s + "/properties"), null).get(0).get("id").asText();
        data(post("/onto/rules"), rule(s, a, "校验", "${" + p + "} > 0", "ACCEPT"));
        String rid = data(get("/onto/rules").param("spaceId", s), null).get(0).get("id").asText();
        var broken =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of(
                                "kind",
                                "TTL",
                                "content",
                                ttl.replace("xsd:integer", "xsd:string"),
                                "expectedDraftRev",
                                rev(s)));
        assertTrue(broken.path("conflicts").size() > 0, broken.toString());
        call(
                post("/onto/imports/" + broken.get("id").asText() + "/apply"),
                ownerToken,
                Map.of("expectedDraftRev", rev(s)),
                409);
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "检查",
                        "code",
                        "check",
                        "kind",
                        "RULE_CHECK",
                        "ruleIds",
                        List.of(rid),
                        "maxRows",
                        10,
                        "timeoutMs",
                        1000));
        Map<String, Object> modified = rule(s, a, "校验", "", "ACCEPT");
        modified.put("kind", "SCORING");
        modified.put("action", Map.of());
        modified.put(
                "scoring",
                Map.of(
                        "metrics",
                        List.of(
                                Map.of(
                                        "propertyId",
                                        p,
                                        "weight",
                                        1,
                                        "bands",
                                        List.of(Map.of("min", 0, "max", 10, "score", 1)))),
                        "missingValue",
                        "ZERO",
                        "tieBreak",
                        "STABLE_ID",
                        "forbiddenProperties",
                        List.of(),
                        "explain",
                        true));
        call(put("/onto/rules/" + rid), ownerToken, modified, 409);
    }

    @Test
    void owlXmlRoundTripAndLegacyCandidateRemainCompatible() throws Exception {
        String s = createSpace();
        String owl =
                "<?xml version=\"1.0\"?><rdf:RDF"
                        + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                        + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\"><owl:Class"
                        + " rdf:about=\"https://example.org/XmlClass\"/></rdf:RDF>";
        var preview =
                data(
                        post("/onto/spaces/" + s + "/imports/preview"),
                        Map.of("kind", "OWL", "content", owl, "expectedDraftRev", rev(s)));
        assertEquals("PREVIEW", preview.path("status").asText());
        data(
                post("/onto/imports/" + preview.get("id").asText() + "/apply"),
                Map.of("expectedDraftRev", rev(s)));
        String ttl = data(get("/onto/spaces/" + s + "/export"), null).path("content").asText();
        var left = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        var right = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        try {
            org.apache.jena.riot.RDFParser.fromString(owl)
                    .lang(org.apache.jena.riot.Lang.RDFXML)
                    .parse(left);
            org.apache.jena.riot.RDFParser.fromString(ttl)
                    .lang(org.apache.jena.riot.Lang.TURTLE)
                    .parse(right);
            assertTrue(left.isIsomorphicWith(right));
        } finally {
            left.close();
            right.close();
        }
        String legacySpace = createSpace();
        createClass(legacySpace, "Legacy", null);
        String op = createOperation(legacySpace, 50),
                fresh = freeze(legacySpace).get("id").asText();
        var node =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                db.queryForObject(
                                        "SELECT payload FROM publish_candidate WHERE id=?",
                                        byte[].class,
                                        UUID.fromString(fresh)));
        assertTrue(
                node.has("codebooks")
                        && node.has("relations")
                        && node.has("constraints")
                        && node.has("rules")
                        && node.has("strategies")
                        && node.has("testCases")
                        && node.has("rdfDocuments"));
        node.remove(
                List.of(
                        "codebooks",
                        "relations",
                        "constraints",
                        "rules",
                        "strategies",
                        "testCases",
                        "rdfDocuments"));
        for (var operation : node.path("operations")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) operation)
                    .remove(List.of("ruleIds", "strategyRef"));
        }
        byte[] bytes = json.writeValueAsBytes(node);
        UUID legacy = UUID.randomUUID();
        db.update(
                "INSERT INTO publish_candidate(id,space_id,draft_rev,sha256,payload)"
                        + " VALUES(?,?,?,?,?)",
                legacy,
                UUID.fromString(legacySpace),
                rev(legacySpace),
                cn.ontorepo.space.application.AuthService.digest(bytes),
                bytes);
        assertEquals(
                "SUCCEEDED",
                data(post("/onto/candidates/" + legacy + "/trial"), Map.of("operationId", op))
                        .path("status")
                        .asText());
        var checker = new cn.ontorepo.trace.application.ConsistencyChecker(120000);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.convertValue(node, Map.class);
        assertEquals("TIMEOUT", checker.check(payload, 1).get("reason"));
        assertEquals("ENGINE_ERROR", checker.check(Map.of(), 1000).get("reason"));
        assertEquals("CONSISTENT", checker.check(payload, 120000).get("conclusion"));
    }

    @Test
    void tenCompetencyQuestionsAreAnsweredFromFrozenModel() throws Exception {
        String s = createSpace(),
                task = createClass(s, "任务", null),
                algorithm = createClass(s, "算法", null);
        createClass(s, "侦察任务", task);
        property(s, task, "名称", "STRING");
        property(s, task, "优先级", "INTEGER");
        property(s, algorithm, "评分", "DECIMAL");
        data(
                post("/onto/relations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "使用算法",
                        "fromClassId",
                        task,
                        "toClassId",
                        algorithm,
                        "cardinality",
                        "ONE_TO_MANY",
                        "transitive",
                        false,
                        "symmetric",
                        false,
                        "description",
                        ""));
        String ns = data(get("/onto/spaces/" + s), null).path("namespaceUri").asText(),
                taskIri = "<" + ns + "class/" + task + ">",
                algorithmIri = "<" + ns + "class/" + algorithm + ">";
        String prefix =
                "PREFIX owl:<http://www.w3.org/2002/07/owl#> PREFIX"
                        + " rdfs:<http://www.w3.org/2000/01/rdf-schema#> PREFIX"
                        + " xsd:<http://www.w3.org/2001/XMLSchema#> ";
        List<String> questions =
                List.of(
                        "本体有哪些对象",
                        "哪些对象是根类型",
                        "任务的直接子类有哪些",
                        "哪些对象继承其他类型",
                        "任务有哪些数据属性",
                        "哪些属性是整数",
                        "算法的数值评分字段是什么",
                        "任务到算法有什么关系",
                        "哪些关系从任务发出",
                        "哪个对象同时有名称和优先级");
        List<String> queries =
                List.of(
                        "SELECT ?name WHERE {?c a owl:Class; rdfs:label ?name} ORDER BY ?name",
                        "SELECT ?name WHERE {?c a owl:Class; rdfs:label ?name OPTIONAL {?c"
                                + " rdfs:subClassOf ?p} FILTER(!BOUND(?p))} ORDER BY ?name",
                        "SELECT ?name WHERE {?c rdfs:subClassOf " + taskIri + "; rdfs:label ?name}",
                        "SELECT ?name WHERE {?c rdfs:subClassOf ?parent; rdfs:label ?name}",
                        "SELECT ?name WHERE {?p a owl:DatatypeProperty; rdfs:domain "
                                + taskIri
                                + "; rdfs:label ?name} ORDER BY ?name",
                        "SELECT ?name WHERE {?p a owl:DatatypeProperty; rdfs:range xsd:integer;"
                                + " rdfs:label ?name}",
                        "SELECT ?name WHERE {?p a owl:DatatypeProperty; rdfs:domain "
                                + algorithmIri
                                + "; rdfs:range xsd:decimal; rdfs:label ?name}",
                        "SELECT ?name WHERE {?p a owl:ObjectProperty; rdfs:domain "
                                + taskIri
                                + "; rdfs:range "
                                + algorithmIri
                                + "; rdfs:label ?name}",
                        "SELECT ?name WHERE {?p a owl:ObjectProperty; rdfs:domain "
                                + taskIri
                                + "; rdfs:label ?name}",
                        "SELECT ?name WHERE {?c rdfs:label ?name . ?p rdfs:domain ?c; rdfs:label"
                                + " '名称' . ?q rdfs:domain ?c; rdfs:label '优先级'}");
        List<List<String>> answers =
                List.of(
                        List.of("任务", "侦察任务", "算法"),
                        List.of("任务", "算法"),
                        List.of("侦察任务"),
                        List.of("侦察任务"),
                        List.of("优先级", "名称"),
                        List.of("优先级"),
                        List.of("评分"),
                        List.of("使用算法"),
                        List.of("使用算法"),
                        List.of("任务"));
        for (int i = 0; i < 10; i++)
            data(
                    post("/onto/operations"),
                    Map.of(
                            "spaceId",
                            s,
                            "expectedDraftRev",
                            rev(s),
                            "code",
                            "cq" + (i + 1),
                            "name",
                            questions.get(i),
                            "kind",
                            i == 7 || i == 8 ? "RELATION_QUERY" : "OBJECT_QUERY",
                            "queryTemplate",
                            prefix + queries.get(i),
                            "maxRows",
                            50,
                            "timeoutMs",
                            1000));
        var ops = data(get("/onto/operations").param("spaceId", s), null);
        String candidate = freeze(s).get("id").asText();
        data(put("/onto/classes/" + task), cl(s, "新草稿任务", null, rev(s)));
        for (int i = 0; i < 10; i++) {
            String code = "cq" + (i + 1), op = null;
            for (var row : ops)
                if (code.equals(row.path("code").asText())) op = row.path("id").asText();
            var execution =
                    data(
                            post("/onto/candidates/" + candidate + "/trial"),
                            Map.of("operationId", op));
            assertEquals("SUCCEEDED", execution.path("status").asText(), questions.get(i));
            List<String> actual = new ArrayList<>();
            for (var row : execution.path("rows")) actual.add(row.path("name").asText());
            List<String> expected = new ArrayList<>(answers.get(i));
            Collections.sort(actual);
            Collections.sort(expected);
            assertEquals(expected, actual, questions.get(i));
        }
    }

    @Test
    void manualInverseAndSymmetricRelationsAreMarkedAndOnlyOwnerCanWaive() throws Exception {
        String s = createSpace(), a = createClass(s, "A", null), b = createClass(s, "B", null);
        data(
                post("/onto/relations"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "合作",
                        "fromClassId",
                        a,
                        "toClassId",
                        b,
                        "cardinality",
                        "MANY_TO_MANY",
                        "inverseName",
                        "反向合作",
                        "transitive",
                        false,
                        "symmetric",
                        true,
                        "description",
                        ""));
        var relations = data(get("/onto/relations").param("spaceId", s), null);
        assertTrue(relations.get(0).path("subsetExceeded").asBoolean());
        String exported = data(get("/onto/spaces/" + s + "/export"), null).path("content").asText();
        assertTrue(
                exported.contains("inverseOf") && exported.contains("SymmetricProperty"), exported);
        String c = freeze(s).path("id").asText();
        var report = data(post("/onto/candidates/" + c + "/checks"), null);
        assertFalse(report.path("eligible").asBoolean());
        assertTrue(
                report.toString().contains("NOT_CHECKED")
                        && report.toString().contains("SUBSET_EXCEEDED"),
                report.toString());
        grant(s, viewer, "EDITOR");
        call(
                post("/onto/candidates/" + c + "/waivers"),
                viewerToken,
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "编辑者不能审批"),
                403);
        data(
                post("/onto/candidates/" + c + "/waivers"),
                Map.of("kind", "SUBSET_EXCEEDED", "reason", "OWNER已审查手工逆/对称语义"));
        assertTrue(
                data(post("/onto/candidates/" + c + "/checks"), null).path("eligible").asBoolean());
    }

    @Test
    void authoredConstraintsAreExportedAsRealShaclShapes() throws Exception {
        String s = createSpace(),
                a = createClass(s, "A", null),
                p = property(s, a, "数值", "INTEGER");
        data(
                post("/onto/constraints"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "name",
                        "范围",
                        "kind",
                        "RANGE",
                        "targetType",
                        "PROPERTY",
                        "targetRef",
                        p,
                        "expr",
                        Map.of("min", 1, "max", 10)));
        String content = data(get("/onto/spaces/" + s + "/export"), null).path("content").asText();
        var model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        try {
            org.apache.jena.riot.RDFParser.fromString(content)
                    .lang(org.apache.jena.riot.Lang.TURTLE)
                    .parse(model);
            assertTrue(
                    model.contains(
                            null,
                            model.createProperty("http://www.w3.org/ns/shacl#minInclusive"),
                            (org.apache.jena.rdf.model.RDFNode) null),
                    content);
            assertTrue(
                    model.contains(
                            null,
                            model.createProperty("http://www.w3.org/ns/shacl#maxInclusive"),
                            (org.apache.jena.rdf.model.RDFNode) null),
                    content);
        } finally {
            model.close();
        }
    }
}
