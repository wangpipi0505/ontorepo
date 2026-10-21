package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.api.RequiresPermission;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 完整 RBAC 通过真实 HTTP 边界和 PostgreSQL 验证，不以菜单隐藏代替权限执行。 */
class IamManagementTest extends WorkflowFixture {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000001";
    @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping;

    @BeforeEach
    void administrator() {
        db.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?::uuid)", owner, ADMIN);
        db.update("UPDATE iam_state SET initialized=true WHERE id=1");
    }

    JsonNode role(String code, List<String> permissions) throws Exception {
        return data(
                post("/onto/system/roles"),
                Map.of(
                        "code",
                        code,
                        "name",
                        "角色" + code,
                        "description",
                        "验证自定义权限",
                        "permissions",
                        permissions));
    }

    JsonNode user(String name, List<String> roles) throws Exception {
        return data(
                post("/onto/system/users"),
                Map.of(
                        "username",
                        name,
                        "displayName",
                        name,
                        "password",
                        "initial-password-123",
                        "roleIds",
                        roles));
    }

    Map<String, Object> userEdit(JsonNode user, String status, List<String> roles) {
        return Map.of(
                "displayName",
                user.path("displayName").asText(),
                "status",
                status,
                "roleIds",
                roles,
                "expectedRevision",
                user.path("revision").asLong());
    }

    Map<String, Object> roleEdit(JsonNode role, String status, List<String> permissions) {
        return Map.of(
                "name",
                role.path("name").asText(),
                "description",
                "修改",
                "status",
                status,
                "permissions",
                permissions,
                "expectedRevision",
                role.path("revision").asLong());
    }

    @Test
    void multipleCustomRolesUnionAndChangesAffectExistingSession() throws Exception {
        var read = role("read", List.of("space.read"));
        var create = role("create", List.of("space.create"));
        var user = user("custom", List.of(read.get("id").asText(), create.get("id").asText()));
        String token = session(UUID.fromString(user.get("id").asText()));
        var effective = call(get("/onto/auth/permissions"), token, null, 200).get("data");
        assertEquals(
                List.of("space.create", "space.read"), json.convertValue(effective, List.class));
        call(get("/onto/spaces"), token, null, 200);
        call(get("/onto/system/users"), token, null, 403);
        data(
                put("/onto/system/roles/" + read.get("id").asText()),
                roleEdit(read, "DISABLED", List.of("space.read")));
        call(get("/onto/spaces"), token, null, 403);
        assertEquals(
                List.of("space.create"),
                json.convertValue(
                        call(get("/onto/auth/permissions"), token, null, 200).get("data"),
                        List.class));
    }

    @Test
    void noRoleDefaultsDenyAndSystemAdministratorStillNeedsSpaceMembership() throws Exception {
        var noRole = user("no-role", List.of());
        String token = session(UUID.fromString(noRole.get("id").asText()));
        call(get("/onto/auth/me"), token, null, 200);
        assertTrue(call(get("/onto/auth/permissions"), token, null, 200).get("data").isEmpty());
        call(get("/onto/spaces"), token, null, 403);
        var second = user("another-admin", List.of(ADMIN));
        String space = createSpace();
        call(
                get("/onto/spaces/" + space),
                session(UUID.fromString(second.get("id").asText())),
                null,
                403);
    }

    @Test
    void unknownDuplicateAndBuiltinPermissionsCannotBeChanged() throws Exception {
        call(
                post("/onto/system/roles"),
                ownerToken,
                Map.of("code", "bad", "name", "bad", "permissions", List.of("*")),
                400);
        call(
                post("/onto/system/roles"),
                ownerToken,
                Map.of(
                        "code",
                        "bad",
                        "name",
                        "bad",
                        "permissions",
                        List.of("space.read", "space.read")),
                400);
        call(
                put("/onto/system/roles/" + ADMIN),
                ownerToken,
                Map.of(
                        "name",
                        "bad",
                        "status",
                        "DISABLED",
                        "permissions",
                        List.of(),
                        "expectedRevision",
                        0),
                409);
        call(
                delete("/onto/system/roles/" + ADMIN).param("expectedRevision", "0"),
                ownerToken,
                null,
                409);
    }

    @Test
    void revisionConflictAndAssignedRoleDeletionCannotPartiallyChangePermissions()
            throws Exception {
        var role = role("reader", List.of("space.read"));
        String id = role.get("id").asText();
        var user = user("reader", List.of(id));
        call(
                delete("/onto/system/roles/" + id).param("expectedRevision", "0"),
                ownerToken,
                null,
                409);
        data(
                put("/onto/system/roles/" + id),
                roleEdit(role, "ACTIVE", List.of("space.read", "model.read")));
        var conflict =
                call(
                        put("/onto/system/roles/" + id),
                        ownerToken,
                        roleEdit(role, "DISABLED", List.of()),
                        409);
        assertEquals("IAM_REVISION_CONFLICT", conflict.get("code").asText());
        String token = session(UUID.fromString(user.get("id").asText()));
        assertEquals(2, call(get("/onto/auth/permissions"), token, null, 200).get("data").size());
        data(
                put("/onto/system/users/" + user.get("id").asText()),
                userEdit(user, "ACTIVE", List.of()));
        data(delete("/onto/system/roles/" + id).param("expectedRevision", "1"), null);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM local_role WHERE id=?::uuid", Integer.class, id));
    }

    @Test
    void passwordResetAndDisableRevokeSessionsWithoutLeakingCredentials() throws Exception {
        var user = user("reset", List.of());
        String id = user.get("id").asText();
        String token = session(UUID.fromString(id));
        var changed =
                data(
                        post("/onto/system/users/" + id + "/password"),
                        Map.of("password", "new-password-123", "expectedRevision", 0));
        call(get("/onto/auth/me"), token, null, 401);
        call(
                post("/onto/auth/login"),
                null,
                Map.of("username", "reset", "password", "initial-password-123"),
                401);
        var login =
                call(
                        post("/onto/auth/login"),
                        null,
                        Map.of("username", "reset", "password", "new-password-123"),
                        200);
        String nextToken = login.get("data").get("token").asText();
        data(put("/onto/system/users/" + id), userEdit(changed, "DISABLED", List.of()));
        call(get("/onto/auth/me"), nextToken, null, 401);
        call(
                post("/onto/auth/login"),
                null,
                Map.of("username", "reset", "password", "new-password-123"),
                401);
        String publicData =
                data(get("/onto/system/users"), null).toString()
                        + data(get("/onto/system/audit"), null).toString();
        assertFalse(publicData.contains("password"));
        assertFalse(publicData.contains("$2"));
        assertFalse(publicData.contains(nextToken));
    }

    @Test
    void lastAdministratorSurvivesConcurrentSelfDisableAndRejectedWriteRollsBack()
            throws Exception {
        var second = user("second", List.of(ADMIN));
        String secondId = second.get("id").asText();
        String secondToken = session(UUID.fromString(secondId));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    mvc.perform(
                                                    put("/onto/system/users/" + owner)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + ownerToken)
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsBytes(
                                                                            Map.of(
                                                                                    "displayName",
                                                                                    "owner",
                                                                                    "status",
                                                                                    "DISABLED",
                                                                                    "roleIds",
                                                                                    List.of(ADMIN),
                                                                                    "expectedRevision",
                                                                                    0))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var other =
                    pool.submit(
                            () ->
                                    mvc.perform(
                                                    put("/onto/system/users/" + secondId)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + secondToken)
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsBytes(
                                                                            userEdit(
                                                                                    second,
                                                                                    "DISABLED",
                                                                                    List.of(
                                                                                            ADMIN)))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertEquals(
                    List.of(200, 409),
                    List.of(first.get(10, TimeUnit.SECONDS), other.get(10, TimeUnit.SECONDS))
                            .stream()
                            .sorted()
                            .toList());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM local_user u JOIN user_role ur ON ur.user_id=u.id"
                            + " WHERE u.status='ACTIVE' AND ur.role_id=?::uuid",
                        Integer.class,
                        ADMIN));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM iam_audit WHERE action='USER_UPDATE'",
                        Integer.class));
    }

    @Test
    void removingLastAdministratorRoleAlsoRollsBack() throws Exception {
        var result =
                call(
                        put("/onto/system/users/" + owner),
                        ownerToken,
                        Map.of(
                                "displayName",
                                "owner",
                                "status",
                                "ACTIVE",
                                "roleIds",
                                List.of(),
                                "expectedRevision",
                                0),
                        409);
        assertEquals("LAST_SYSTEM_ADMIN", result.get("code").asText());
        call(get("/onto/system/users"), ownerToken, null, 200);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT revision FROM local_user WHERE id=?", Integer.class, owner));
    }

    @Test
    void everyBusinessHandlerHasKnownExplicitPermission() {
        requestMappingHandlerMapping
                .getHandlerMethods()
                .forEach(
                        (mapping, method) -> {
                            if (!method.getBeanType().getPackageName().equals("cn.ontorepo.api"))
                                return;
                            var annotation = method.getMethodAnnotation(RequiresPermission.class);
                            assertNotNull(annotation, method.toString());
                            assertTrue(
                                    annotation.value().isEmpty()
                                                    && method.getBeanType()
                                                            .getSimpleName()
                                                            .equals("AuthController")
                                            || cn.ontorepo.space.domain.PermissionCatalog.known(
                                                    annotation.value()),
                                    method.toString());
                        });
    }

    @Test
    void normalizedBusinessPathsCannotBypassFunctionPermission() throws Exception {
        String space = createSpace();
        // 保留空间 OWNER 身份但撤销全部功能角色；若只检查空间角色，本请求会错误写入。
        db.update("DELETE FROM user_role WHERE user_id=?", owner);
        for (String path : List.of("/onto;x/classes", "/%6fnto/classes")) {
            call(post(java.net.URI.create(path)), ownerToken, cl(space, "拒绝写入", null, 0), 403);
        }
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM ont_class WHERE space_id=?::uuid",
                        Integer.class,
                        space));
    }
}
