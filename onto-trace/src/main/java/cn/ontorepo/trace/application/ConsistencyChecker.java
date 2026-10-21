package cn.ontorepo.trace.application;

import cn.ontorepo.model.infra.RdfCodec;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.vocabulary.*;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.profiles.OWL2ELProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** OWL API profile后ELK优先，仅明确不完整才用HermiT完整兜底；原图、总预算和失败闭合边界不变。 */
@Component
public class ConsistencyChecker {
    private final long timeoutMs;

    public ConsistencyChecker(@Value("${onto.consistency.timeout-ms:120000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Map<String, Object> check(Map<String, Object> payload) {
        return check(payload, timeoutMs);
    }

    /** 独立时限入口用于确定性超时验证；HTTP 不接收扩大或绕过时限的参数。 */
    public Map<String, Object> check(Map<String, Object> payload, long budgetMs) {
        if (budgetMs <= 0) return result("FAILED", "TIMEOUT", "一致性检查超过时限", Map.of("engine", "ELK"));
        var budget = new ReasoningBudget(budgetMs);
        ExecutorService pool =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "onto-elk-check");
                            t.setDaemon(true);
                            return t;
                        });
        Future<Map<String, Object>> future = pool.submit(() -> run(payload, budget));
        try {
            var checked = future.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            // 调用线程调度迟到或dispose耗尽预算时也不能把已超时的成功结果放行。
            budget.remainingMillis();
            return checked;
        } catch (TimeoutException e) {
            budget.cancel();
            future.cancel(true);
            return result("FAILED", "TIMEOUT", "一致性检查超过总时限", budget.details(Map.of()));
        } catch (Exception e) {
            budget.cancel();
            future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            boolean timed =
                    cause instanceof org.semanticweb.owlapi.reasoner.TimeOutException
                            || cause
                                    instanceof
                                    org.semanticweb.owlapi.reasoner.ReasonerInterruptedException
                            || budget.remainingNanos() == 0;
            return result(
                    "FAILED",
                    timed ? "TIMEOUT" : "ENGINE_ERROR",
                    timed ? "一致性检查超过总时限" : "一致性引擎运行失败，请修复后重新检查",
                    budget.details(Map.of()));
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Object> run(Map<String, Object> payload, ReasoningBudget budget)
            throws Exception {
        Model graph = RdfCodec.model(payload);
        try {
            // 门禁以投影叠加后的实际候选图为准；原预览标记保留作来源证据，不能造成误报或漏报。
            List<Map<String, Object>> unsupported =
                    RdfCodec.unsupported(RdfCodec.ttl(graph), graph);
            // owl:imports 不触发任何外部读取；无法本地闭包时明确阻断为未检查。
            if (graph.contains(null, OWL.imports, (RDFNode) null))
                unsupported.add(Map.of("construct", "imports", "message", "外部导入未解析；仅允许本地闭包"));
            if (!unsupported.isEmpty())
                return result(
                        "NOT_CHECKED",
                        "SUBSET_EXCEEDED",
                        "存在超范围或未闭合导入构造",
                        Map.of("engine", "ELK", "unsupported", unsupported));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RDFDataMgr.write(out, graph, Lang.RDFXML);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology =
                    manager.loadOntologyFromOntologyDocument(
                            new ByteArrayInputStream(out.toByteArray()));
            try {
                var profile = new OWL2ELProfile().checkOntology(ontology);
                if (!profile.isInProfile())
                    return result(
                            "NOT_CHECKED",
                            "SUBSET_EXCEEDED",
                            "OWL API确认存在OWL 2 EL范围外公理",
                            Map.of(
                                    "engine",
                                    "ELK",
                                    "violations",
                                    profile.getViolations().stream()
                                            .map(Object::toString)
                                            .toList()));
                var config =
                        org.semanticweb.elk.reasoner.config.ReasonerConfiguration
                                .getConfiguration();
                config.setParameter(
                        org.semanticweb.elk.reasoner.config.ReasonerConfiguration
                                .NUM_OF_WORKING_THREADS,
                        "2");
                var reasoner =
                        new ElkReasonerFactory()
                                .createReasoner(
                                        ontology,
                                        new org.semanticweb.elk.owlapi.ElkReasonerConfiguration(
                                                new org.semanticweb.owlapi.reasoner
                                                        .SimpleConfiguration(),
                                                config));
                budget.attach(reasoner, false);
                String incomplete = null;
                try {
                    var consistencyResult = reasoner.checkIsConsistent();
                    if (consistencyResult.getIncompletenessMonitor().isIncompletenessDetected())
                        incomplete = "ELK_INCOMPLETE_CONSISTENCY";
                    else {
                        boolean consistent =
                                org.semanticweb.elk.reasoner.completeness.Incompleteness.getValue(
                                        consistencyResult);
                        if (!consistent)
                            return result(
                                    "INCONSISTENT",
                                    "INCONSISTENT",
                                    "ELK发现本体不一致",
                                    Map.of("engine", "ELK"));
                        var bottomResult = reasoner.computeBottomClassNode();
                        if (bottomResult.getIncompletenessMonitor().isIncompletenessDetected())
                            incomplete = "ELK_INCOMPLETE_CLASSIFICATION";
                        else {
                            var unsatisfiable =
                                    org
                                            .semanticweb
                                            .elk
                                            .reasoner
                                            .completeness
                                            .Incompleteness
                                            .getValue(bottomResult)
                                            .getEntitiesMinusBottom()
                                            .stream()
                                            .map(c -> c.getIRI().toString())
                                            .sorted()
                                            .toList();
                            if (!consistent || !unsatisfiable.isEmpty())
                                return result(
                                        "INCONSISTENT",
                                        "INCONSISTENT",
                                        "ELK发现不一致或不可满足命名类",
                                        Map.of(
                                                "engine",
                                                "ELK",
                                                "unsatisfiableClasses",
                                                unsatisfiable));
                            return result(
                                    "CONSISTENT",
                                    "",
                                    "ELK一致性推理完成",
                                    Map.of(
                                            "engine",
                                            "ELK",
                                            "axiomCount",
                                            ontology.getAxiomCount()));
                        }
                    }
                } finally {
                    budget.detach(reasoner);
                    reasoner.dispose();
                }
                return complete(ontology, budget, incomplete);
            } finally {
                manager.removeOntology(ontology);
            }
        } finally {
            graph.close();
        }
    }

    /** 只有ELK明确声明覆盖不完整才进入此处，原ontology不删公理、不重新计时。 */
    private Map<String, Object> complete(
            OWLOntology ontology, ReasoningBudget budget, String reason) {
        budget.fallback(reason);
        org.semanticweb.owlapi.reasoner.OWLReasoner reasoner;
        try {
            reasoner = createCompleteReasoner(ontology, budget.remainingMillis());
        } catch (org.semanticweb.owlapi.reasoner.InconsistentOntologyException inconsistent) {
            return result(
                    "INCONSISTENT",
                    "INCONSISTENT",
                    "HermiT确认原完整本体不一致",
                    budget.details(Map.of("axiomCount", ontology.getAxiomCount())));
        }
        budget.attach(reasoner, true);
        try {
            if (!reasoner.isConsistent())
                return result(
                        "INCONSISTENT",
                        "INCONSISTENT",
                        "HermiT确认原完整本体不一致",
                        budget.details(Map.of("axiomCount", ontology.getAxiomCount())));
            budget.remainingMillis();
            var unsatisfiable =
                    reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().stream()
                            .map(c -> c.getIRI().toString())
                            .sorted()
                            .toList();
            budget.remainingMillis();
            if (!unsatisfiable.isEmpty())
                return result(
                        "INCONSISTENT",
                        "INCONSISTENT",
                        "HermiT发现不可满足命名类",
                        budget.details(
                                Map.of(
                                        "unsatisfiableClasses",
                                        unsatisfiable,
                                        "axiomCount",
                                        ontology.getAxiomCount())));
            return result(
                    "CONSISTENT",
                    "",
                    "ELK不完整，HermiT已完整验证一致性与命名类可满足性",
                    budget.details(
                            Map.of(
                                    "unsatisfiableClasses",
                                    List.of(),
                                    "axiomCount",
                                    ontology.getAxiomCount())));
        } catch (org.semanticweb.owlapi.reasoner.InconsistentOntologyException inconsistent) {
            return result(
                    "INCONSISTENT",
                    "INCONSISTENT",
                    "HermiT确认原完整本体不一致",
                    budget.details(Map.of("axiomCount", ontology.getAxiomCount())));
        } finally {
            budget.detach(reasoner);
            reasoner.dispose();
        }
    }

    /** 完整引擎的窄边界便于故障/超时注入；生产始终使用固定HermiT与剩余总预算。 */
    protected org.semanticweb.owlapi.reasoner.OWLReasoner createCompleteReasoner(
            OWLOntology ontology, long remainingMillis) {
        var config = new org.semanticweb.HermiT.Configuration();
        config.individualTaskTimeout = remainingMillis;
        config.ignoreUnsupportedDatatypes = false;
        config.throwInconsistentOntologyException = false;
        return new org.semanticweb.HermiT.ReasonerFactory().createReasoner(ontology, config);
    }

    private Map<String, Object> result(
            String conclusion, String reason, String message, Map<String, Object> details) {
        return Map.of(
                "kind",
                "CONSISTENCY",
                "conclusion",
                conclusion,
                "reason",
                reason,
                "message",
                message,
                "details",
                details);
    }
}
