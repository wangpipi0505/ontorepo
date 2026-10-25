package cn.ontorepo.release;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.ProvExporter;

import java.util.*;

import org.junit.jupiter.api.Test;

/**
 * R11：PROV-O Turtle 投影——活动/代理/步骤链/证据 used/主体 generated 全要素，
 * 时间只用持久值，Turtle 字面量转义与 IRI 编码不破坏语法。
 */
class ProvExporterTest {

    private Map<String, Object> row(Object... kv) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) result.put(String.valueOf(kv[i]), kv[i + 1]);
        return result;
    }

    @Test
    void fullProjectionCoversActivityStepsEvidenceAgentAndSubject() {
        var execution =
                row(
                        "id", "11111111-1111-1111-1111-111111111111",
                        "opCode", "queryEntities",
                        "caller", "SERVICE",
                        "outputDigest", "a".repeat(64),
                        "subjectType", "CANDIDATE",
                        "subjectId", "22222222-2222-2222-2222-222222222222",
                        "createdAt", "2026-10-25T07:38:36.993425Z");
        var steps =
                List.of(
                        row("id", 1L, "kind", "LOAD_MODEL", "status", "SUCCEEDED",
                                "createdAt", "2026-10-25T07:38:37Z"),
                        row("id", 2L, "kind", "EXECUTE_QUERY", "status", "SUCCEEDED",
                                "createdAt", "2026-10-25T07:38:38Z"));
        var evidence =
                List.of(
                        row("id", 7L, "kind", "SOURCE_RECORD",
                                "ref", Map.of("sha256", "b".repeat(64)),
                                "createdAt", "2026-10-25T07:38:36Z"));
        var result =
                ProvExporter.turtle(execution, steps, evidence, Map.of(), "33333333-3333-3333-3333-333333333333");
        String ttl = String.valueOf(result.get("content"));
        // 活动与时间
        assertTrue(ttl.contains("<execution/11111111-1111-1111-1111-111111111111> a prov:Activity"));
        assertTrue(ttl.contains("prov:startedAtTime \"2026-10-25T07:38:36.993425Z\"^^xsd:dateTime"));
        // 步骤链：前序步骤 informedBy 后序（信息从后向前流动），执行 wasGeneratedBy 最后步骤
        assertTrue(ttl.contains("prov:wasInformedBy"));
        assertTrue(ttl.contains(
                "<execution/11111111-1111-1111-1111-111111111111#step/1> prov:wasInformedBy"
                        + " <execution/11111111-1111-1111-1111-111111111111#step/2>"));
        assertTrue(ttl.contains("prov:wasGeneratedBy <execution/11111111-1111-1111-1111-111111111111#step/2>"));
        // 证据 used 与摘要
        assertTrue(ttl.contains("prov:used <execution/11111111-1111-1111-1111-111111111111#evidence/7>"));
        assertTrue(ttl.contains("onto:sha256 \"" + "b".repeat(64) + "\""));
        // 代理与主体产物
        assertTrue(ttl.contains("a prov:SoftwareAgent"));
        assertTrue(ttl.contains("prov:generated <execution/11111111-1111-1111-1111-111111111111#subject>"));
    }

    @Test
    void legacyCallerOmitsAgentAndNullTimesAreNotInvented() {
        var execution =
                row(
                        "id", "e2",
                        "opCode", "legacy run \"quoted\"\nnewline",
                        "caller", "LEGACY",
                        "createdAt", "2026-10-25T07:00:00Z");
        var result = ProvExporter.turtle(execution, List.of(), List.of(), Map.of(), null);
        String ttl = String.valueOf(result.get("content"));
        assertFalse(ttl.contains("prov:SoftwareAgent"));
        assertFalse(ttl.contains("wasAssociatedWith"));
        // 字面量转义保证 Turtle 语法不破
        assertTrue(ttl.contains("legacy run \\\"quoted\\\"\\nnewline"));
        // 只有 createdAt 一个时间事实
        assertEquals(1, ttl.split("prov:startedAtTime").length - 1);
        assertFalse(ttl.contains("prov:endedAtTime"));
    }
}
