package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 本地身份采用随机不透明令牌；数据库只保存哈希，撤销和到期即时生效。 */
@Service
public class AuthService {
    private final Db db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    @Value("${ONTO_BOOTSTRAP_USERNAME:}")
    String username;

    @Value("${ONTO_BOOTSTRAP_PASSWORD:}")
    String password;

    @Value("${ONTO_BOOTSTRAP_DISPLAY_NAME:管理员}")
    String displayName;

    public AuthService(Db db) {
        this.db = db;
    }

    /** 首次管理员由部署环境引导；重复启动不覆盖已有密码，禁止内置默认密码。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (username.isBlank() && password.isBlank()) return;
        if (username.isBlank()
                || username.length() > 100
                || displayName.length() > 200
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("引导用户名须1~100字符、显示名至多200字符，密码至少12字符且UTF-8至多72字节");
        db.update(
                "INSERT INTO local_user(id,username,display_name,password_hash) VALUES(?,?,?,?) ON"
                        + " CONFLICT(username) DO NOTHING",
                UUID.randomUUID(),
                username,
                displayName,
                passwords.encode(password));
        // IAM 引导只执行一次；后续重启绝不把已撤销的管理员重新授予回来。
        var state = db.one("SELECT initialized FROM iam_state WHERE id=1 FOR UPDATE");
        if (!Boolean.TRUE.equals(state.get("initialized"))) {
            UUID user =
                    Input.uuid(
                            db.one(
                                            "SELECT id FROM local_user WHERE username=? AND"
                                                + " status='ACTIVE'",
                                            username)
                                    .get("id"));
            db.update(
                    "INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM local_role WHERE"
                        + " code='SYSTEM_ADMIN' ON CONFLICT DO NOTHING",
                    user);
            db.update("UPDATE iam_state SET initialized=true WHERE id=1");
            db.update(
                    "INSERT INTO iam_audit(actor_id,action,target_id,before_state,after_state)"
                        + " VALUES(?,'IAM_BOOTSTRAP',?,'{}'::jsonb,'{\"role\":\"SYSTEM_ADMIN\"}'::jsonb)",
                    user,
                    user);
        }
    }

    /** 令牌与制品使用 SHA-256；密码另用带盐 BCrypt，不能以快速哈希替代密码算法。 */
    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Problem unauthorized() {
        return new Problem(401, "UNAUTHORIZED", "请重新登录");
    }

    /** 成功登录生成256位随机令牌，只将摘要落库，原始令牌仅此响应一次。 */
    @Transactional
    public Map<String, Object> login(Map<String, Object> body) {
        String name = Input.text(body, "username", 100);
        Object rawPassword = body.get("password");
        if (!(rawPassword instanceof String pass)
                || pass.isEmpty()
                || pass.getBytes(StandardCharsets.UTF_8).length > 72)
            throw Problem.bad("密码不能为空且UTF-8编码不能超过72字节");
        // 与密码重置/停用的行更新互斥，避免旧密码校验后又在重置提交之后创建有效会话。
        var users =
                db.list(
                        "SELECT * FROM local_user WHERE username=? AND status='ACTIVE' FOR SHARE",
                        name);
        if (users.isEmpty()
                || !passwords.matches(pass, (String) users.getFirst().get("passwordHash")))
            throw new Problem(401, "LOGIN_FAILED", "用户名或密码错误");
        var user = users.getFirst();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(Duration.ofHours(8));
        db.update(
                "INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
                digest(token.getBytes(StandardCharsets.UTF_8)),
                Input.uuid(user.get("id")),
                java.sql.Timestamp.from(expires));
        return Map.of("token", token, "expiresAt", expires.toString(), "user", publicUser(user));
    }

    /** 每次请求读取有效期和撤销标志，退出和过期无需等待缓存刷新。 */
    public UUID authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 200)
            throw unauthorized();
        var rows =
                db.list(
                        "SELECT s.user_id FROM auth_session s JOIN local_user u ON u.id=s.user_id"
                            + " WHERE s.token_hash=? AND NOT s.revoked AND s.expires_at>now() AND"
                            + " u.status='ACTIVE'",
                        digest(header.substring(7).getBytes(StandardCharsets.UTF_8)));
        if (rows.isEmpty()) throw unauthorized();
        return Input.uuid(rows.getFirst().get("userId"));
    }

    /** 用户公开信息仅包含稳定标识、用户名和显示名。 */
    public Map<String, Object> me(UUID user) {
        return db.one("SELECT id,username,display_name FROM local_user WHERE id=?", user);
    }

    /** 仅撤销当前令牌，不影响该用户的其他独立会话。 */
    public void logout(String header) {
        db.update(
                "UPDATE auth_session SET revoked=true WHERE token_hash=?",
                digest(header.substring(7).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> publicUser(Map<String, Object> user) {
        return Map.of(
                "id",
                user.get("id"),
                "username",
                user.get("username"),
                "displayName",
                user.get("displayName"));
    }
}
