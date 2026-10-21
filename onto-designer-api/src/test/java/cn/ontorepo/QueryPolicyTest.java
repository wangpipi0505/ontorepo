package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.ontorepo.operation.domain.QueryPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryPolicyTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * WHERE {SERVICE <https://example.com/sparql> {?s ?p ?o}}",
                "SELECT * FROM <file:///etc/passwd> WHERE {?s ?p ?o}",
                "SELECT (<https://evil.invalid/f>(?s) AS ?v) WHERE {?s ?p ?o}",
                "SELECT (IRI('file:///tmp/x') AS ?x) WHERE {}",
                "SELECT * WHERE {BIND(EXISTS {SERVICE <https://evil.invalid/> {?s ?p ?o}} AS ?x)}",
                "SELECT * WHERE {?s <http://jena.apache.org/ARQ/property#listFiles> ?o}"
            })
    void rejectsExternalCapabilities(String text) {
        assertThrows(RuntimeException.class, () -> QueryPolicy.validate(text, 50, 1000));
    }

    @Test
    void clampsResultLimit() {
        assertEquals(
                5, QueryPolicy.validate("SELECT * WHERE {?s ?p ?o} LIMIT 100", 5, 1000).getLimit());
    }
}
