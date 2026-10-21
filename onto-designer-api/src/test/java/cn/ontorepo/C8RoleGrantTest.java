package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** C8：角色级端点授予生效，撤销后与用户授予一样拒绝。 */
class C8RoleGrantTest extends P4Fixture {
    @Test
    void roleGrantAuthorizesReleaseInvocationAndRevocationRejectsIt() throws Exception {
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
        data(put("/onto/endpoints/" + endpointId + "/role-grants/MEMBER"), grant);
        var listed = data(get("/onto/endpoints/" + endpointId + "/role-grants"), null);
        assertEquals(1, listed.size());
        assertEquals("MEMBER", listed.get(0).path("roleCode").asText());
        data(post("/onto/internal/releases/" + releaseId + "/ops/listClasses"), Map.of());

        grant.put("status", "REVOKED");
        data(put("/onto/endpoints/" + endpointId + "/role-grants/MEMBER"), grant);
        var denied =
                call(
                        post("/onto/internal/releases/" + releaseId + "/ops/listClasses"),
                        ownerToken,
                        Map.of(),
                        403);
        assertEquals("ENDPOINT_FORBIDDEN", denied.path("code").asText());
    }
}
