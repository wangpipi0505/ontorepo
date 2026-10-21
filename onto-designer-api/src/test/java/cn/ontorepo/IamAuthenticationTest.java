package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.release.application.ReleaseGatewayService;
import cn.ontorepo.space.application.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 身份生命周期补验：一次引导、密码更新与登录并发、正式 API Key 主体停用及权限撤销。 */
class IamAuthenticationTest extends WorkflowFixture {
    @Autowired AuthService auth;
    @Autowired ReleaseGatewayService gateway;
    @Autowired javax.sql.DataSource source;

    @BeforeEach
    void administrator() {
        db.update(
                "INSERT INTO user_role(user_id,role_id)"
                    + " VALUES(?,'00000000-0000-0000-0000-000000000001')",
                owner);
        db.update("UPDATE iam_state SET initialized=true WHERE id=1");
    }

    @Test
    void bootstrapRunsOnceAndRestartDoesNotRestoreRevokedAdministratorOrPassword() {
        Object oldName = ReflectionTestUtils.getField(auth, "username"),
                oldPass = ReflectionTestUtils.getField(auth, "password"),
                oldDisplay = ReflectionTestUtils.getField(auth, "displayName");
        try {
            ReflectionTestUtils.setField(auth, "username", "owner");
            ReflectionTestUtils.setField(auth, "password", "different-bootstrap-123");
            ReflectionTestUtils.setField(auth, "displayName", "引导管理员");
            db.update(
                    "DELETE FROM user_role WHERE user_id=? AND"
                        + " role_id='00000000-0000-0000-0000-000000000001'",
                    owner);
            db.update("UPDATE iam_state SET initialized=false WHERE id=1");
            String before =
                    db.queryForObject(
                            "SELECT password_hash FROM local_user WHERE id=?", String.class, owner);
            auth.bootstrap();
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM user_role WHERE user_id=? AND"
                                + " role_id='00000000-0000-0000-0000-000000000001'",
                            Integer.class,
                            owner));
            db.update(
                    "DELETE FROM user_role WHERE user_id=? AND"
                        + " role_id='00000000-0000-0000-0000-000000000001'",
                    owner);
            auth.bootstrap();
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM user_role WHERE user_id=? AND"
                                + " role_id='00000000-0000-0000-0000-000000000001'",
                            Integer.class,
                            owner));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM iam_audit WHERE action='IAM_BOOTSTRAP'",
                            Integer.class));
            assertTrue(
                    before.equals(
                            db.queryForObject(
                                    "SELECT password_hash FROM local_user WHERE id=?",
                                    String.class,
                                    owner)),
                    "重启不能覆盖已有密码");
        } finally {
            ReflectionTestUtils.setField(auth, "username", oldName);
            ReflectionTestUtils.setField(auth, "password", oldPass);
            ReflectionTestUtils.setField(auth, "displayName", oldDisplay);
        }
    }

    @Test
    void emptyBootstrapConfigurationDoesNotPromoteAnArbitraryUser() {
        Object oldName = ReflectionTestUtils.getField(auth, "username"),
                oldPass = ReflectionTestUtils.getField(auth, "password");
        try {
            ReflectionTestUtils.setField(auth, "username", "");
            ReflectionTestUtils.setField(auth, "password", "");
            db.update("DELETE FROM user_role WHERE role_id='00000000-0000-0000-0000-000000000001'");
            db.update("UPDATE iam_state SET initialized=false WHERE id=1");
            auth.bootstrap();
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM user_role WHERE"
                                + " role_id='00000000-0000-0000-0000-000000000001'",
                            Integer.class));
            assertEquals(
                    false,
                    db.queryForObject(
                            "SELECT initialized FROM iam_state WHERE id=1", Boolean.class));
        } finally {
            ReflectionTestUtils.setField(auth, "username", oldName);
            ReflectionTestUtils.setField(auth, "password", oldPass);
        }
    }

    @Test
    void loginWaitsForPasswordChangeAndCannotCreateSessionWithOldPassword() throws Exception {
        try (var connection = source.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var update =
                    connection.prepareStatement(
                            "UPDATE local_user SET password_hash=? WHERE id=?")) {
                update.setString(1, new BCryptPasswordEncoder(4).encode("changed-password-123"));
                update.setObject(2, viewer);
                update.executeUpdate();
            }
            var entered = new CountDownLatch(1);
            var login =
                    pool.submit(
                            () -> {
                                entered.countDown();
                                return mvc.perform(
                                                post("/onto/auth/login")
                                                        .contentType("application/json")
                                                        .content(
                                                                json.writeValueAsBytes(
                                                                        Map.of(
                                                                                "username",
                                                                                "viewer",
                                                                                "password",
                                                                                "test-password-123"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && !login.isDone()) {
                waiting =
                        Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                            + " datname=current_database() AND"
                                            + " pid<>pg_backend_pid() AND wait_event_type='Lock'"
                                            + " AND query LIKE '%local_user%FOR SHARE%')",
                                        Boolean.class));
                if (waiting) break;
                Thread.sleep(20);
            }
            // 即使断言失败也先放开行锁，否则线程池关闭会等待尚未完成的登录线程。
            connection.commit();
            assertTrue(waiting, "登录必须等待同一用户的密码修改事务提交");
            assertEquals(401, login.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void apiKeyAlsoRequiresActivePrincipalAndCurrentServicePermission() throws Exception {
        UUID space = UUID.fromString(createSpace());
        db.update(
                "INSERT INTO space_grant(space_id,user_id,role) VALUES(?,?,'VIEWER')",
                space,
                viewer);
        String key =
                (String)
                        gateway.createCredential(
                                        owner,
                                        Map.of(
                                                "spaceId",
                                                space.toString(),
                                                "principalId",
                                                viewer.toString(),
                                                "name",
                                                "测试凭据"))
                                .get("key");
        assertEquals(viewer.toString(), gateway.authenticateKey(key).get("principalId"));
        db.update("DELETE FROM user_role WHERE user_id=?", viewer);
        var denied =
                mvc.perform(
                                post("/onto/r/test/releases/" + UUID.randomUUID() + "/ops/test")
                                        .header("X-API-Key", key)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn();
        assertEquals(403, denied.getResponse().getStatus());
        data(
                put("/onto/system/users/" + viewer),
                Map.of(
                        "displayName",
                        "viewer",
                        "status",
                        "DISABLED",
                        "roleIds",
                        List.of(),
                        "expectedRevision",
                        0));
        assertEquals(401, assertThrows(Problem.class, () -> gateway.authenticateKey(key)).status);
    }
}
