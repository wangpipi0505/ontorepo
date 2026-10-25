package cn.ontorepo.operation.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.common.domain.Problem;

import org.apache.jena.query.Query;
import org.junit.jupiter.api.Test;

/** QueryPolicy 白名单边界与放行基线（UIF-O7 前置：错误码契约稳定可被前端消费）。 */
class QueryPolicyTest {

    private Query ok(String text) {
        return QueryPolicy.validate(text, 100, 1000);
    }

    @Test
    void safeSelectPassesAndGetsLimitClamped() {
        Query q =
                ok(
                        "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
                                + " SELECT ?n WHERE { ?c a <urn:t:X> ; rdfs:label ?n }");
        assertEquals(100, q.getLimit());
    }

    @Test
    void rejectUpdate() {
        var e =
                assertThrows(
                        Problem.class,
                        () -> QueryPolicy.validate("DELETE WHERE { ?s ?p ?o }", 100, 1000));
        // DELETE 不是合法 SELECT 语法，在 Jena 解析层即被拒（INVALID_QUERY）；
        // UNSAFE_QUERY 面向"能解析但含非白名单语法"的查询。
        assertEquals("INVALID_QUERY", e.code);
    }

    @Test
    void rejectServiceAndPropertyFunctions() {
        assertThrows(
                Problem.class,
                () ->
                        QueryPolicy.validate(
                                "SELECT * WHERE { SERVICE <http://x> { ?s ?p ?o } }", 100, 1000));
        assertThrows(
                Problem.class,
                () ->
                        QueryPolicy.validate(
                                "SELECT (EXISTS {?s ?p ?o} AS ?b) WHERE {}", 100, 1000));
    }

    @Test
    void rejectDatasetAndAggregation() {
        assertThrows(
                Problem.class,
                () ->
                        QueryPolicy.validate(
                                "SELECT * FROM <urn:g> WHERE { ?s ?p ?o }", 100, 1000));
        assertThrows(
                Problem.class,
                () ->
                        QueryPolicy.validate(
                                "SELECT (count(*) AS ?n) WHERE { ?s ?p ?o } GROUP BY ?s",
                                100,
                                1000));
    }

    @Test
    void syntaxErrorHasStableCode() {
        var e =
                assertThrows(
                        Problem.class,
                        () -> QueryPolicy.validate("SELECT ?n WHERE { ?s ?p", 100, 1000));
        assertEquals("INVALID_QUERY", e.code);
    }
}
