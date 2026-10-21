package cn.ontorepo.release.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.trace.infra.CanonicalPayload;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 投影直接来自冻结包；密文与源配置不能进入管理响应。 */
class ReleaseHistoryServiceTest {
    private final ReleaseHistoryService history =
            new ReleaseHistoryService(null, null, new CanonicalPayload(), null, null);

    @Test
    void projectionContainsThreeMappingLayersWithDistinctStableKeys() {
        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("classes", List.of(Map.of("id", "class-1", "name", "任务")));
        bundle.put("objectMappings", List.of(Map.of("id", "shared", "targetClassId", "class-1")));
        bundle.put("propertyMappings", List.of(Map.of("id", "shared", "srcField", "name")));
        bundle.put("relationMappings", List.of(Map.of("id", "shared", "relationId", "relation-1")));
        var projection = history.project(bundle);
        assertEquals(4, projection.size());
        assertEquals(3, projection.stream().filter(x -> x.get("kind").equals("MAPPING")).count());
        assertEquals(
                4,
                projection.stream()
                        .map(x -> x.get("kind") + ":" + x.get("key"))
                        .distinct()
                        .count());
    }

    @Test
    void projectionPreservesModelSemanticsButExcludesVolatileStorageFields() {
        var relation = new LinkedHashMap<String, Object>();
        relation.put("id", "r");
        relation.put("spaceId", "s");
        relation.put("createdAt", "today");
        relation.put("inverseOf", "inverse");
        relation.put("symmetric", true);
        var value =
                (Map<?, ?>)
                        history.project(Map.of("relations", List.of(relation)))
                                .getFirst()
                                .get("value");
        assertEquals("inverse", value.get("inverseOf"));
        assertEquals(true, value.get("symmetric"));
        assertFalse(value.containsKey("spaceId"));
        assertFalse(value.containsKey("createdAt"));
    }

    @Test
    void projectionOrderIsDeterministicAndInputIsNotMutated() {
        var bundle =
                Map.<String, Object>of("classes", List.of(Map.of("id", "z"), Map.of("id", "a")));
        assertEquals("a", history.project(bundle).getFirst().get("key"));
        assertEquals("z", ((List<Map<String, Object>>) bundle.get("classes")).getFirst().get("id"));
    }
}
