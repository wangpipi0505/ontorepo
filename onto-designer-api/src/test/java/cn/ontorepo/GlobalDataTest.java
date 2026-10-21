package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 全局物料接口：跨可见空间聚合，权限仍由单空间服务复核。 */
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
    void globalDataAggregatesVisibleSpaces() throws Exception {
        String a = createSpace(), b = createSpace();
        source(a, "http-a");
        source(b, "http-b");
        var data = data(get("/onto/global/data"), null);
        assertEquals(2, data.path("sources").size());
        for (var item : data.path("sources"))
            assertFalse(item.path("spaceName").asText().isBlank());
    }
}
