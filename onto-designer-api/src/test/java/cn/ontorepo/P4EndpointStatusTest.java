package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 端点级启停：停用在访问源前拒绝、可恢复、仅OWNER可操作并留审计。 */
class P4EndpointStatusTest extends P4Fixture {
    private void grant(String endpoint, UUID user) throws Exception {
        var b = new LinkedHashMap<String, Object>();
        b.put("status", "ACTIVE");
        b.put("dataScope", null);
        b.put("expiresAt", null);
        data(put("/onto/endpoints/" + endpoint + "/grants/" + user), b);
    }

    @Test
    void disabledEndpointIsRejectedBeforeAccessAndCanBeReenabled() throws Exception {
        var ready = ready();
        var release = publish(ready);
        String releaseId = release.path("id").asText();
        var endpoints = data(get("/onto/releases/" + releaseId + "/endpoints"), null);
        assertEquals(1, endpoints.size());
        String endpointId = endpoints.get(0).path("id").asText();
        grant(endpointId, owner);
        data(post("/onto/internal/releases/" + releaseId + "/ops/listClasses"), Map.of());

        var disabled =
                data(
                        put("/onto/endpoints/" + endpointId + "/status"),
                        Map.of("status", "DISABLED"));
        assertEquals("DISABLED", disabled.path("status").asText());
        var rejected =
                call(
                        post("/onto/internal/releases/" + releaseId + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        409);
        assertEquals("ENDPOINT_DISABLED", rejected.path("code").asText());

        var enabled =
                data(put("/onto/endpoints/" + endpointId + "/status"), Map.of("status", "ACTIVE"));
        assertEquals("ACTIVE", enabled.path("status").asText());
        data(post("/onto/internal/releases/" + releaseId + "/ops/listClasses"), Map.of());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action='ENDPOINT_STATUS'",
                        Integer.class));
    }

    @Test
    void viewerCannotToggleEndpointOrReadGrants() throws Exception {
        var ready = ready();
        var release = publish(ready);
        String releaseId = release.path("id").asText();
        String endpointId =
                data(get("/onto/releases/" + releaseId + "/endpoints"), null)
                        .get(0)
                        .path("id")
                        .asText();
        grant(endpointId, owner);
        call(
                put("/onto/endpoints/" + endpointId + "/status"),
                viewerToken,
                Map.of("status", "DISABLED"),
                403);
        call(get("/onto/endpoints/" + endpointId + "/grants"), viewerToken, null, 403);
        assertEquals(
                "ACTIVE",
                db.queryForObject(
                        "SELECT status FROM service_endpoint WHERE id=?::uuid",
                        String.class,
                        endpointId));
    }
}
