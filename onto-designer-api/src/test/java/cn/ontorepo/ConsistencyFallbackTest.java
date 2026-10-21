package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.trace.application.ConsistencyChecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 使用原完整RDF图触发真实ELK不完整；不能删数据属性、吞异常或把不可满足类冒充一致。 */
class ConsistencyFallbackTest {
    @Test
    void cancelledElkCheckCannotPoisonNextIndependentCheck() {
        var p = payload(":C a owl:Class .");
        var checker = new ConsistencyChecker(30_000);
        for (int round = 0; round < 20; round++) {
            assertEquals("TIMEOUT", checker.check(p, 1).get("reason"));
            assertEquals("ENGINE_ERROR", checker.check(Map.of(), 1000).get("reason"));
            var result = checker.check(p, 30_000);
            assertEquals(
                    "CONSISTENT",
                    result.get("conclusion"),
                    "取消不能污染下一独立检查，轮次" + round + ": " + result);
        }
    }

    static Map<String, Object> payload(String axioms) {
        return Map.of(
                "space",
                Map.of("namespaceUri", "https://example.org/fallback/"),
                "rdfDocuments",
                List.of(
                        Map.of(
                                "content",
                                "@prefix : <https://example.org/fallback/> . @prefix owl:"
                                        + " <http://www.w3.org/2002/07/owl#> . @prefix rdfs:"
                                        + " <http://www.w3.org/2000/01/rdf-schema#> . @prefix xsd:"
                                        + " <http://www.w3.org/2001/XMLSchema#> . "
                                        + axioms)));
    }

    static String datatype(String range) {
        return ":C a owl:Class . :p a owl:DatatypeProperty ; rdfs:domain :C ; rdfs:range xsd:"
                + range
                + " . ";
    }

    @ParameterizedTest
    @ValueSource(strings = {"string", "integer", "decimal"})
    void basicElDatatypesUseCompleteFallbackRatherThanFailingIncompleteElk(String range) {
        var result = new ConsistencyChecker(30_000).check(payload(datatype(range)));
        assertEquals("CONSISTENT", result.get("conclusion"), result.toString());
        var detail = (Map<?, ?>) result.get("details");
        assertEquals("ELK→HermiT", detail.get("engine"));
        assertEquals(true, detail.get("incomplete"));
        assertTrue(detail.get("fallbackReason").toString().startsWith("ELK_INCOMPLETE_"));
        assertTrue(((Number) detail.get("axiomCount")).intValue() >= 3);
    }

    @Test
    void actualFallbackRejectsInconsistentOriginalOntology() {
        var result =
                new ConsistencyChecker(30_000)
                        .check(
                                payload(
                                        datatype("string")
                                                + " :C rdfs:subClassOf owl:Nothing . :i a"
                                                + " owl:NamedIndividual, :C ."));
        assertEquals("INCONSISTENT", result.get("conclusion"), result.toString());
        assertEquals("ELK→HermiT", ((Map<?, ?>) result.get("details")).get("engine"));
    }

    @Test
    void originalExtremeOntologyEngineExceptionStaysFailed() {
        // 固定HermiT在此原公理上报空union异常；不得删公理、猜测一致或豁免该错误。
        var result =
                new ConsistencyChecker(30_000)
                        .check(
                                payload(
                                        datatype("string")
                                                + " owl:Thing rdfs:subClassOf owl:Nothing ."));
        assertEquals("FAILED", result.get("conclusion"));
        assertEquals("ENGINE_ERROR", result.get("reason"));
        assertEquals("ELK→HermiT", ((Map<?, ?>) result.get("details")).get("engine"));
    }

    @Test
    void fallbackEngineFailureCannotBeConvertedIntoConsistency() {
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var checker =
                new ConsistencyChecker(30_000) {
                    @Override
                    protected org.semanticweb.owlapi.reasoner.OWLReasoner createCompleteReasoner(
                            org.semanticweb.owlapi.model.OWLOntology ontology, long remaining) {
                        entered.set(true);
                        throw new IllegalStateException("注入完整引擎故障");
                    }
                };
        var result = checker.check(payload(datatype("string")));
        assertTrue(entered.get());
        assertEquals("FAILED", result.get("conclusion"));
        assertEquals("ENGINE_ERROR", result.get("reason"));
        assertEquals("ELK→HermiT", ((Map<?, ?>) result.get("details")).get("engine"));
    }

    @Test
    void timeoutDuringFallbackSharesOriginalBudgetAndDisposesRealEngine() throws Exception {
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var remainingAtFactory = new java.util.concurrent.atomic.AtomicLong();
        var disposed = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        var checker =
                new ConsistencyChecker(1000) {
                    @Override
                    protected org.semanticweb.owlapi.reasoner.OWLReasoner createCompleteReasoner(
                            org.semanticweb.owlapi.model.OWLOntology ontology, long remaining) {
                        remainingAtFactory.set(remaining);
                        var delegate = super.createCompleteReasoner(ontology, remaining);
                        return (org.semanticweb.owlapi.reasoner.OWLReasoner)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {
                                            org.semanticweb.owlapi.reasoner.OWLReasoner.class
                                        },
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("isConsistent")) {
                                                entered.set(true);
                                                unblock.await();
                                            }
                                            if (method.getName().equals("interrupt"))
                                                unblock.countDown();
                                            try {
                                                return method.invoke(delegate, args);
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException
                                                            error) {
                                                throw error.getCause();
                                            } finally {
                                                if (method.getName().equals("dispose"))
                                                    disposed.countDown();
                                            }
                                        });
                    }
                };
        long started = System.nanoTime();
        var result = checker.check(payload(datatype("string")));
        long elapsed =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(entered.get(), "真实ELK先报告不完整后必须已进入HermiT");
        assertEquals("FAILED", result.get("conclusion"));
        assertEquals("TIMEOUT", result.get("reason"));
        assertEquals("ELK→HermiT", ((Map<?, ?>) result.get("details")).get("engine"));
        assertTrue(remainingAtFactory.get() > 0 && remainingAtFactory.get() < 1000);
        assertTrue(elapsed < 2500, "不得在fallback重新获得总时限：" + elapsed);
        assertTrue(
                disposed.await(3, java.util.concurrent.TimeUnit.SECONDS),
                "取消后必须dispose实际已创建HermiT");
    }

    @Test
    void actualFallbackRejectsUnsatisfiableNamedClass() {
        var result =
                new ConsistencyChecker(30_000)
                        .check(payload(datatype("string") + " :C rdfs:subClassOf owl:Nothing ."));
        assertEquals("INCONSISTENT", result.get("conclusion"), result.toString());
        assertTrue(
                ((Map<?, ?>) result.get("details"))
                        .get("unsatisfiableClasses")
                        .toString()
                        .contains("https://example.org/fallback/C"));
    }

    @Test
    void completeElkAndOutsideElKeepTheirOriginalSeparateRules() {
        var checker = new ConsistencyChecker(30_000);
        var complete = checker.check(payload(":C a owl:Class ."));
        assertEquals("CONSISTENT", complete.get("conclusion"));
        assertEquals("ELK", ((Map<?, ?>) complete.get("details")).get("engine"));
        var outside =
                checker.check(
                        payload(
                                ":p a owl:ObjectProperty ; owl:inverseOf :q . :q a"
                                        + " owl:ObjectProperty ."));
        assertEquals("NOT_CHECKED", outside.get("conclusion"));
        assertEquals("SUBSET_EXCEEDED", outside.get("reason"));
        assertEquals("ELK", ((Map<?, ?>) outside.get("details")).get("engine"));
    }
}
