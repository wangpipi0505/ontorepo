package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理到真实源的集成入口，数据库使用专属P3测试库。 */
class P3DataTest extends WorkflowFixture {
    @Test
    void sourceCatalogIsAuthorizedAndConnectionNeverLeavesTheBoundary() throws Exception {
        String s = createSpace();
        assertTrue(data(get("/onto/datasources").param("spaceId", s), null).isArray());
        call(get("/onto/datasources").param("spaceId", s), strangerToken, null, 403);
        data(
                post("/onto/datasources"),
                Map.of(
                        "spaceId",
                        s,
                        "expectedDraftRev",
                        rev(s),
                        "code",
                        "http",
                        "name",
                        "接口",
                        "kind",
                        "HTTP",
                        "connection",
                        Map.of(
                                "dialect",
                                "json",
                                "baseUrl",
                                "http://127.0.0.1:1",
                                "headers",
                                Map.of("Authorization", "secret-p3"),
                                "endpoints",
                                List.of("/items")),
                        "accessScope",
                        List.of("/items")));
        var sources = data(get("/onto/datasources").param("spaceId", s), null);
        assertEquals(1, sources.size());
        assertFalse(sources.toString().contains("secret-p3"));
        assertFalse(sources.get(0).has("connection"));
        String encrypted =
                db.queryForObject(
                        "SELECT conn_cfg FROM datasource WHERE space_id=?",
                        String.class,
                        UUID.fromString(s));
        assertTrue(encrypted.startsWith("v1."));
        assertFalse(encrypted.contains("secret-p3"));
        var probe =
                data(
                        post("/onto/datasources/" + sources.get(0).path("id").asText() + "/probe"),
                        Map.of("spaceId", s));
        assertEquals("FAILED", probe.path("stages").get(0).path("status").asText());
        assertEquals("NOT_RUN", probe.path("stages").get(1).path("status").asText());
    }

    @Test
    void spaceAndDatasourceGovernanceFieldsArePersisted() throws Exception {
        String code = "gov" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var space =
                data(
                        post("/onto/spaces"),
                        Map.of(
                                "code",
                                code,
                                "name",
                                "治理空间",
                                "namespaceUri",
                                "https://example.test/" + code + "/",
                                "domain",
                                "测试",
                                "description",
                                "空间说明"));
        String s = space.path("id").asText();
        assertEquals("空间说明", space.path("description").asText());
        assertFalse(space.path("updatedAt").asText().isBlank());

        var updated =
                data(
                        put("/onto/spaces/" + s),
                        Map.of(
                                "name",
                                "治理空间2",
                                "domain",
                                "测试",
                                "description",
                                "更新说明",
                                "expectedDraftRev",
                                space.path("draftRev").asLong()));
        assertEquals("更新说明", updated.path("description").asText());

        var body = new LinkedHashMap<String, Object>();
        body.put("spaceId", s);
        body.put("expectedDraftRev", updated.path("draftRev").asLong());
        body.put("code", "governance");
        body.put("name", "治理数据源");
        body.put("kind", "HTTP");
        body.put(
                "connection",
                Map.of(
                        "dialect",
                        "json",
                        "baseUrl",
                        "http://127.0.0.1:1",
                        "headers",
                        Map.of(),
                        "endpoints",
                        List.of("/items")));
        body.put("accessScope", List.of("/items"));
        body.put("description", "源说明");
        body.put("tenant", "租户A");
        String ownerId = db.queryForObject("SELECT id::text FROM local_user LIMIT 1", String.class);
        body.put("ownerId", ownerId);
        data(post("/onto/datasources"), body);
        var source = data(get("/onto/datasources").param("spaceId", s), null).get(0);
        assertEquals("源说明", source.path("description").asText());
        assertEquals("租户A", source.path("tenant").asText());
        assertEquals(ownerId, source.path("ownerId").asText());
    }
}
