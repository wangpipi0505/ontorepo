package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** S5：就绪度计数来自真实表，守卫/候选计数可验证。 */
class ReadinessTest extends WorkflowFixture {
    @Test
    void readinessAggregatesModelGuardAndCandidateCounts() throws Exception {
        String s = createSpace(), c = createClass(s, "任务", null);
        data(
                post("/onto/properties"),
                Map.of(
                        "spaceId",
                        s,
                        "domainClassId",
                        c,
                        "name",
                        "priority",
                        "rangeType",
                        "INTEGER",
                        "required",
                        false,
                        "description",
                        "",
                        "facets",
                        Map.of(),
                        "expectedDraftRev",
                        rev(s)));
        data(
                put("/onto/spaces/" + s + "/guards"),
                Map.of(
                        "expectedDraftRev",
                        rev(s),
                        "guardCfg",
                        Map.of("region", Map.of("type", "STRING"))));
        freeze(s);
        var readiness = data(get("/onto/spaces/" + s + "/readiness"), null);
        assertEquals(1, readiness.path("classCount").asInt());
        assertEquals(1, readiness.path("propertyCount").asInt());
        assertEquals(1, readiness.path("guardCount").asInt());
        assertEquals(1, readiness.path("candidateCount").asInt());
        assertEquals(0, readiness.path("assistantCount").asInt());
        call(get("/onto/spaces/" + s + "/readiness"), strangerToken, null, 403);
    }
}
