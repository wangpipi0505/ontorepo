package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.operation.domain.RuleExpression;
import cn.ontorepo.operation.domain.ScoringPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

class RuleExpressionTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String X = "${" + ID + "}";

    @Test
    void validatesWhitelistTypesAndReferences() {
        RuleExpression.validate(
                X + " >= 18 && contains(toString(" + X + "), '2')", Map.of(ID, "INTEGER"));
        assertEquals(
                Set.of(ID),
                RuleExpression.references("'${not-a-reference}' == 'x' || " + X + " > 0"));
        for (String bad :
                List.of(
                        "new Object()",
                        "T(java.lang.Runtime)",
                        "foo()",
                        X + ".toString()",
                        X + " = 1",
                        "true; false",
                        "true || foo()"))
            assertThrows(
                    RuntimeException.class,
                    () -> RuleExpression.validate(bad, Map.of(ID, "INTEGER")),
                    bad);
        assertThrows(RuntimeException.class, () -> RuleExpression.validate(X + " > 1", Map.of()));
        assertThrows(
                RuntimeException.class,
                () -> RuleExpression.validate(X + " > '1'", Map.of(ID, "INTEGER")));
        assertThrows(
                RuntimeException.class,
                () -> RuleExpression.validate(X + " + 1", Map.of(ID, "INTEGER")));
    }

    @Test
    void evaluatesOperatorsAndAllFiveFunctionsWithStrictInput() {
        assertTrue(RuleExpression.evaluate("", Map.of()));
        assertTrue(
                RuleExpression.evaluate(
                        X + " * 2 + 1 == 9 && !isEmpty(toString(" + X + "))", Map.of(ID, 4)));
        assertTrue(
                RuleExpression.evaluate(
                        "size('abc') == 3 && contains('abc', 'b') && toNumber('2.5') / 2 == 1.25",
                        Map.of()));
        assertTrue(RuleExpression.evaluate("isEmpty(" + X + ")", Map.of()));
        assertFalse(RuleExpression.evaluate(X + " > 0", Map.of()));
        assertThrows(
                RuntimeException.class,
                () -> RuleExpression.evaluate("toNumber('abc') > 0", Map.of()));
        assertThrows(
                RuntimeException.class, () -> RuleExpression.evaluate(X + " > 1", Map.of(ID, "2")));
        assertThrows(RuntimeException.class, () -> RuleExpression.evaluate("1 / 0 > 0", Map.of()));
        assertTrue(RuleExpression.evaluate("false && 1 / 0 > 0 || true", Map.of()));
    }

    @Test
    void intersectsRangesAndProvesImplicationWithBooleanStructure() {
        assertTrue(RuleExpression.overlap(X + " >= 10", X + " < 20"));
        assertFalse(RuleExpression.overlap(X + " >= 20", X + " < 20"));
        assertFalse(
                RuleExpression.overlap(
                        X + " >= 0 && " + X + " <= 10", X + " != 5 && " + X + " > 10"));
        assertTrue(RuleExpression.implies(X + " > 10 && " + X + " < 20", X + " > 0"));
        assertFalse(RuleExpression.implies(X + " > 0", X + " > 10"));
        assertTrue(
                RuleExpression.implies(
                        X + " != null && !(" + X + " < 10 || " + X + " > 20)", X + " >= 10"));
        assertTrue(RuleExpression.implies(X + " * 2 + 1 > 21", X + " > 10"));
        assertFalse(RuleExpression.overlap(X + " == 'red'", X + " == 'blue'"));
        assertFalse(RuleExpression.overlap(X + " == 1", X + " != 1"));
    }

    @Test
    void implicationAccountsForMissingValuesAndRejectsUnprovableMath() {
        assertFalse(RuleExpression.implies("", X + " > 0 || " + X + " <= 0"));
        assertTrue(RuleExpression.overlap("!(" + X + " > 0)", X + " == null"));
        assertFalse(RuleExpression.overlap(X + " > 0", X + " == null"));
        assertThrows(
                RuntimeException.class,
                () -> RuleExpression.overlap(X + " * " + X + " > 1", "true"));
        assertThrows(
                RuntimeException.class,
                () ->
                        RuleExpression.evaluate(
                                "toString(" + X + ") == ''",
                                Map.of(ID, new BigDecimal("1e100000000"))));
        assertThrows(
                cn.ontorepo.common.domain.Problem.class,
                () -> ScoringPolicy.validate(Map.of(), Map.of()));
    }

    @Test
    void emptyFunctionCanParticipateInConflictProofs() {
        assertTrue(RuleExpression.overlap("isEmpty(" + X + ")", X + " == null"));
        assertFalse(RuleExpression.overlap("!isEmpty(" + X + ")", X + " == ''"));
        assertTrue(RuleExpression.implies(X + " == ''", "isEmpty(" + X + ")"));
        assertThrows(RuntimeException.class, () -> RuleExpression.overlap(X + " < ''", "true"));
    }

    @Test
    void boundsRuntimeValuesAndHonorsInterrupts() {
        assertThrows(
                RuntimeException.class,
                () ->
                        RuleExpression.evaluate(
                                "contains(" + X + ", 'a')", Map.of(ID, "a".repeat(10001))));
        Thread.currentThread().interrupt();
        try {
            var problem =
                    assertThrows(
                            cn.ontorepo.common.domain.Problem.class,
                            () -> RuleExpression.evaluate("true", Map.of()));
            assertEquals("RULE_TIMEOUT", problem.code);
        } finally {
            Thread.interrupted();
        }
    }

    private Map<String, Object> policy(Object weight, List<Map<String, Object>> bands) {
        return Map.of(
                "metrics",
                List.of(Map.of("propertyId", ID, "weight", weight, "bands", bands)),
                "missingValue",
                "REJECT",
                "tieBreak",
                "STABLE_ID",
                "forbiddenProperties",
                List.of(),
                "explain",
                true);
    }

    @Test
    void validatesAndExecutesWeightedBands() {
        Map<String, Object> p =
                policy(
                        1,
                        List.of(
                                Map.of("min", 0, "max", 10, "score", 50),
                                Map.of("min", 11, "max", 20, "score", 100)));
        ScoringPolicy.validate(p, Map.of(ID, "DECIMAL"));
        assertEquals(0, new BigDecimal("100").compareTo(ScoringPolicy.score(p, Map.of(ID, 15))));
        assertThrows(RuntimeException.class, () -> ScoringPolicy.score(p, Map.of()));
        assertThrows(
                RuntimeException.class,
                () ->
                        ScoringPolicy.validate(
                                policy(0.5, List.of(Map.of("min", 0, "max", 10, "score", 5))),
                                Map.of(ID, "DECIMAL")));
        assertThrows(
                RuntimeException.class,
                () ->
                        ScoringPolicy.validate(
                                policy(
                                        1,
                                        List.of(
                                                Map.of("min", 0, "max", 10, "score", 5),
                                                Map.of("min", 10, "max", 20, "score", 6))),
                                Map.of(ID, "DECIMAL")));
        assertThrows(RuntimeException.class, () -> ScoringPolicy.validate(p, Map.of(ID, "STRING")));
        Map<String, Object> forbidden = new java.util.HashMap<>(p);
        forbidden.put("forbiddenProperties", List.of(ID));
        assertThrows(
                RuntimeException.class,
                () -> ScoringPolicy.validate(forbidden, Map.of(ID, "DECIMAL")));
        Map<String, Object> zero = new java.util.HashMap<>(p);
        zero.put("missingValue", "ZERO");
        assertEquals(0, ScoringPolicy.score(zero, Map.of()).signum());
    }
}
