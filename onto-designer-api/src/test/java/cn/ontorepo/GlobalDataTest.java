package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 全局知识与数据接口：知识整理与数据源登记按所属空间分组，权限仍由单空间服务复核。 */
class GlobalDataTest extends WorkflowFixture {
    private void source(String space, String code) throws Exception {
        data(
                post("/onto/datasources"),
                Map.of(
                        "spaceId", space,
                        "expectedDraftRev", rev(space),
                        "code", code,
                        "name", code,
                        "kind", "HTTP",
                        "connection", Map.of("dialect", "json", "baseUrl", "http://127.0.0.1:1"),
                        "accessScope", List.of("/items"),
                        "tenant", "验收",
                        "ownerId", owner));
    }

    @Test
    void globalDataGroupsSourcesBySpaceWithStats() throws Exception {
        String a = createSpace(), b = createSpace();
        source(a, "http-a");
        source(b, "http-b");
        var data = data(get("/onto/global/data"), null);
        var groups = data.path("sourceGroups");
        assertEquals(2, groups.size());
        for (var group : groups) {
            assertFalse(group.path("spaceName").asText().isBlank());
            assertEquals(1, group.path("sources").size());
            var source = group.path("sources").get(0);
            assertFalse(source.path("id").asText().isBlank());
            assertTrue(source.has("snapshotCount"));
            assertTrue(source.has("lastSnapshotAt"));
            assertTrue(source.has("mappingCount"));
            assertEquals(0, source.path("snapshotCount").asInt());
            assertEquals(0, source.path("mappingCount").asInt());
        }
        assertTrue(data.path("knowledge").isArray());
        assertTrue(data.path("mappings").isArray());
        assertTrue(data.path("mappingRows").isArray());
    }
}
