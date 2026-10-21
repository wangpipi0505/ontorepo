package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.ingest.application.SuggestionService;
import cn.ontorepo.ingest.domain.ExtractionEngine;
import cn.ontorepo.mapping.application.ManagedFileService;
import cn.ontorepo.mapping.application.MappingService;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.FrozenExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 数据侧独立审核反例；不启动服务器，也不以测试数据库替代业务持久化。 */
class DataAuditRegressionTest {
    @TempDir Path root;
    static final String PROPERTY = "00000000-0000-0000-0000-000000000001";
    static final String RULE = "00000000-0000-0000-0000-000000000002";

    FrozenExecutor.Result execute(String kind, boolean explain, int value) {
        var scoring =
                Map.of(
                        "metrics",
                        List.of(
                                Map.of(
                                        "propertyId",
                                        PROPERTY,
                                        "weight",
                                        1,
                                        "bands",
                                        List.of(Map.of("min", 0, "max", 10, "score", 7)))),
                        "missingValue",
                        "REJECT",
                        "tieBreak",
                        "STABLE_ID",
                        "explain",
                        explain,
                        "forbiddenProperties",
                        List.of());
        var payload =
                Map.<String, Object>of(
                        "properties",
                        List.of(Map.of("id", PROPERTY, "rangeType", "INTEGER")),
                        "rules",
                        List.of(
                                Map.of(
                                        "id",
                                        RULE,
                                        "condition",
                                        "${" + PROPERTY + "} > 0",
                                        "scoring",
                                        scoring,
                                        "action",
                                        Map.of("type", "ACCEPT", "message", "通过"))));
        var operation =
                Map.<String, Object>of(
                        "kind", kind, "maxRows", 10, "timeoutMs", 1000, "ruleIds", List.of(RULE));
        return new FrozenExecutor(null).execute(payload, operation, Map.of(PROPERTY, value));
    }

    @Test
    void scoringTraceContainsOnlyActuallyPerformedRuleScoreAndExplanationSteps() {
        var result = execute("SCORING", true, 3);
        assertEquals("7", result.rows().getFirst().get("score"));
        assertEquals(
                List.of("RULE_FIRE", "SCORING", "EXPLAIN", "RULE_EXECUTION"),
                result.steps().stream().map(s -> s.get("kind")).toList());
        for (var step : result.steps().subList(0, 3)) assertTrue(step.get("detail").contains(RULE));
        assertTrue(result.steps().get(1).get("detail").contains("7"));
    }

    @Test
    void unmatchedRulesAndDisabledExplanationsNeverClaimExecution() {
        assertEquals(
                List.of("RULE_EXECUTION"),
                execute("SCORING", true, 0).steps().stream().map(s -> s.get("kind")).toList());
        assertEquals(
                List.of("RULE_FIRE", "SCORING", "RULE_EXECUTION"),
                execute("SCORING", false, 3).steps().stream().map(s -> s.get("kind")).toList());
        assertEquals(
                List.of("RULE_FIRE", "RULE_EXECUTION"),
                execute("RULE_CHECK", false, 3).steps().stream().map(s -> s.get("kind")).toList());
    }

    @Test
    void tableSuggestionsDeclareRulePriorsAndNoInstanceSampleEvidence() {
        var suggestions =
                new ExtractionEngine()
                        .table(
                                "source",
                                List.of(
                                        Map.of(
                                                "name",
                                                "asset",
                                                "fields",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "count",
                                                                "type",
                                                                "INTEGER",
                                                                "nullable",
                                                                false)),
                                                "relations",
                                                List.of())),
                                null);
        assertEquals(2, suggestions.size());
        for (var suggestion : suggestions) {
            assertEquals("RULE_PRIOR", suggestion.location().get("confidenceKind"));
            assertEquals("METADATA_ONLY", suggestion.location().get("evidenceScope"));
            assertEquals(false, suggestion.location().get("samplesIncluded"));
            assertNotNull(suggestion.location().get("sampleOmissionReason"));
            assertFalse(suggestion.location().containsKey("sample"));
        }
    }

    @Test
    void configuredVocabularyReplacesDefaultsWithoutRemovingGeneralPatternExtraction() {
        var terms = ExtractionEngine.vocabulary("海洋观测浮标,深海装备,海洋观测浮标");
        assertEquals(List.of("海洋观测浮标", "深海装备"), terms);
        var suggestions =
                new ExtractionEngine(terms)
                        .document(
                                "domain.txt",
                                "txt",
                                "任务及海洋观测浮标有关。".getBytes(StandardCharsets.UTF_8));
        assertTrue(suggestions.stream().anyMatch(s -> s.payload().get("name").equals("海洋观测浮标")));
        assertTrue(suggestions.stream().noneMatch(s -> s.payload().get("name").equals("任务")));
        assertTrue(
                new ExtractionEngine(terms)
                                .document(
                                        "domain.txt",
                                        "txt",
                                        "装置使用传感器。".getBytes(StandardCharsets.UTF_8))
                                .stream()
                                .anyMatch(s -> s.kind().equals("RELATION")));
        assertThrows(IllegalArgumentException.class, () -> ExtractionEngine.vocabulary("合法,,空项"));
        assertThrows(
                IllegalArgumentException.class, () -> ExtractionEngine.vocabulary("a".repeat(65)));
    }

    @Test
    void extractionConsumesTheBytesItVerifiedInsteadOfReopeningReturnedPath() throws Exception {
        byte[] original = "approved\nvalue\n".getBytes(StandardCharsets.UTF_8);
        Path path = root.resolve("source.csv");
        Files.write(path, original);
        var file =
                Map.<String, Object>of(
                        "resource",
                        "source.csv",
                        "sha256",
                        AuthService.digest(original),
                        "name",
                        "source.csv");
        var files =
                new ManagedFileService(null, null, root.toString()) {
                    @Override
                    public Map<String, Object> owned(UUID space, Object id) {
                        return file;
                    }

                    @Override
                    public Path path(Map<String, Object> row) {
                        Path verified = super.path(row);
                        try {
                            Files.writeString(verified, "replaced\nvalue\n");
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return verified;
                    }
                };
        List<String> insertedPayloads = new ArrayList<>();
        var db =
                new Db(null) {
                    @Override
                    public int update(String sql, Object... args) {
                        insertedPayloads.add(args[5].toString());
                        return 1;
                    }
                };
        var spaces =
                new SpaceService(null, null) {
                    @Override
                    public Map<String, Object> write(
                            UUID user, UUID space, long rev, String action, Runnable work) {
                        work.run();
                        return Map.of();
                    }
                };
        new SuggestionService(db, spaces, files, null, null, "")
                .extract(
                        UUID.randomUUID(),
                        Map.of(
                                "spaceId",
                                UUID.randomUUID().toString(),
                                "expectedDraftRev",
                                0,
                                "sourceKind",
                                "FILE",
                                "fileId",
                                UUID.randomUUID().toString()));
        assertTrue(insertedPayloads.stream().anyMatch(p -> p.contains("approved")));
        assertTrue(insertedPayloads.stream().noneMatch(p -> p.contains("replaced")));
    }

    @Test
    void failedRdfImportClosesEveryTemporaryAndAccumulatedModel() {
        var accumulated = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        var first = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        var broken = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        try (var factory =
                org.mockito.Mockito.mockStatic(
                        org.apache.jena.rdf.model.ModelFactory.class,
                        org.mockito.Mockito.CALLS_REAL_METHODS)) {
            factory.when(org.apache.jena.rdf.model.ModelFactory::createDefaultModel)
                    .thenReturn(accumulated, first, broken);
            assertThrows(
                    RuntimeException.class,
                    () ->
                            cn.ontorepo.model.infra.RdfCodec.model(
                                    Map.of(
                                            "space", Map.of("namespaceUri", "https://example.org/"),
                                            "rdfDocuments",
                                                    List.of(
                                                            Map.of(
                                                                    "content",
                                                                    "<urn:s> <urn:p> <urn:o> ."),
                                                            Map.of(
                                                                    "content",
                                                                    "<urn:s> <urn:p>")))));
            assertTrue(first.isClosed());
            assertTrue(broken.isClosed());
            assertTrue(accumulated.isClosed());
        } finally {
            accumulated.close();
            first.close();
            broken.close();
        }
    }

    @Test
    void modelLookupRejectsEveryNonModelTableBeforeTouchingDatabase() {
        var db =
                new Db(null) {
                    @Override
                    public List<Map<String, Object>> list(String sql, Object... args) {
                        fail("非法实体表不能进入数据库查询");
                        return List.of();
                    }
                };
        var service = new MappingService(db, null, null);
        for (String table : List.of("local_user", "ont_class WHERE true--", "ontology_space"))
            assertThrows(
                    Problem.class,
                    () -> service.model(UUID.randomUUID(), table, UUID.randomUUID()));
    }
}
