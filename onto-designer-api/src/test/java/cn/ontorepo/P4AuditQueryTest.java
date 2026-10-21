package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 处理审计按对象/动作/时间检索；执行记录按入口筛选并分页。 */
class P4AuditQueryTest extends P4Fixture {
    @Test
    void auditCanBeFilteredByObjectActionAndTime() throws Exception {
        var ready = ready();
        var release = publish(ready);
        String releaseId = release.path("id").asText();
        var byObject =
                data(
                        get("/onto/audit")
                                .param("spaceId", ready.space())
                                .param("subjectType", "RELEASE")
                                .param("subjectId", releaseId),
                        null);
        assertEquals(1, byObject.path("items").size());
        var item = byObject.path("items").get(0);
        assertEquals("RELEASE_COMMIT", item.path("action").asText());
        assertEquals("RELEASE", item.path("subjectType").asText());
        assertFalse(item.path("actorName").asText().isBlank(), "审计必须返回操作者姓名");
        assertTrue(item.path("versionRefs").has("bundleSha256"));
        assertEquals("SUCCEEDED", item.path("result").asText());

        var byAction =
                data(
                        get("/onto/audit")
                                .param("spaceId", ready.space())
                                .param("action", "CANDIDATE_VALIDATE"),
                        null);
        assertTrue(byAction.path("items").size() >= 1);
        assertEquals("CANDIDATE_VALIDATE", byAction.path("items").get(0).path("action").asText());

        var future =
                data(
                        get("/onto/audit")
                                .param("spaceId", ready.space())
                                .param("from", "2999-01-01T00:00:00Z"),
                        null);
        assertEquals(0, future.path("items").size());
    }

    @Test
    void executionsFilterByCallerAndPageStably() throws Exception {
        var ready = ready();
        var release = publish(ready);
        String releaseId = release.path("id").asText();
        String endpointId =
                data(get("/onto/releases/" + releaseId + "/endpoints"), null)
                        .get(0)
                        .path("id")
                        .asText();
        var grant = new LinkedHashMap<String, Object>();
        grant.put("status", "ACTIVE");
        grant.put("dataScope", null);
        grant.put("expiresAt", null);
        data(put("/onto/endpoints/" + endpointId + "/grants/" + owner), grant);
        data(post("/onto/internal/releases/" + releaseId + "/ops/listClasses"), Map.of());
        data(post("/onto/internal/releases/" + releaseId + "/ops/listClasses"), Map.of());
        var service =
                data(
                        get("/onto/spaces/" + ready.space() + "/executions")
                                .param("caller", "SERVICE"),
                        null);
        assertTrue(service.size() >= 2);
        for (var item : service) assertEquals("SERVICE", item.path("caller").asText());

        var page0 =
                data(
                        get("/onto/spaces/" + ready.space() + "/executions")
                                .param("limit", "1")
                                .param("offset", "0"),
                        null);
        var page1 =
                data(
                        get("/onto/spaces/" + ready.space() + "/executions")
                                .param("limit", "1")
                                .param("offset", "1"),
                        null);
        assertEquals(1, page0.size());
        assertEquals(1, page1.size());
        assertNotEquals(
                page0.get(0).path("id").asText(), page1.get(0).path("id").asText(), "分页不得重复");
    }
}
