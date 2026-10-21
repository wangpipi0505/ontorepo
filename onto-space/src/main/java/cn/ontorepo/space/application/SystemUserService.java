package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 账号生命周期与显式多角色分配；用户停用保留业务外键和历史身份。 */
@Service
public class SystemUserService {
    private final Db db;
    private final PermissionService permissions;
    private final IamSupport iam;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private static final String SELECT =
            "SELECT"
                + " u.id,u.username,u.display_name,u.status,u.revision,u.created_at,COALESCE(jsonb_agg(jsonb_build_object('id',r.id,'code',r.code,'name',r.name)"
                + " ORDER BY r.code) FILTER(WHERE r.id IS NOT NULL),'[]'::jsonb) roles FROM"
                + " local_user u LEFT JOIN user_role ur ON ur.user_id=u.id LEFT JOIN local_role r"
                + " ON r.id=ur.role_id ";

    public SystemUserService(Db db, PermissionService permissions, IamSupport iam) {
        this.db = db;
        this.permissions = permissions;
        this.iam = iam;
    }

    public List<Map<String, Object>> list(UUID actor) {
        permissions.require(actor, "system.user.read");
        return db.list(SELECT + "GROUP BY u.id ORDER BY u.username,u.id");
    }

    /** 新用户不继承默认角色；空集合具有明确的默认拒绝语义。 */
    @Transactional
    public Map<String, Object> create(UUID actor, Map<String, Object> body) {
        iam.lock(actor, "system.user.write");
        String username = Input.text(body, "username", 100);
        if (username.chars().anyMatch(Character::isISOControl)) throw Problem.bad("用户名不能包含控制字符");
        String displayName = Input.text(body, "displayName", 200);
        List<String> roles = roles(body);
        String hash = passwords.encode(password(body));
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO local_user(id,username,display_name,password_hash) VALUES(?,?,?,?)",
                id,
                username,
                displayName,
                hash);
        assign(id, roles);
        var result = publicUser(id);
        iam.audit(actor, "USER_CREATE", id, Map.of(), result);
        return result;
    }

    /** 配置、角色、撤销会话和审计必须同事务提交，失败不会留下部分授权。 */
    @Transactional
    public Map<String, Object> update(UUID actor, UUID id, Map<String, Object> body) {
        iam.lock(actor, "system.user.write");
        var before = publicUser(id);
        iam.revision(before, body);
        String status = iam.status(body);
        var roles = roles(body);
        db.update(
                "UPDATE local_user SET display_name=?,status=?,revision=revision+1 WHERE id=?",
                Input.text(body, "displayName", 200),
                status,
                id);
        db.update("DELETE FROM user_role WHERE user_id=?", id);
        assign(id, roles);
        iam.retainAdministrator();
        if (status.equals("DISABLED")) revokeSessions(id);
        var after = publicUser(id);
        iam.audit(actor, "USER_UPDATE", id, before, after);
        return after;
    }

    /** 密码从不进入审计或返回对象；重置后旧会话全部撤销，新登录才可继续。 */
    @Transactional
    public Map<String, Object> resetPassword(UUID actor, UUID id, Map<String, Object> body) {
        iam.lock(actor, "system.user.write");
        var before = publicUser(id);
        iam.revision(before, body);
        db.update(
                "UPDATE local_user SET password_hash=?,revision=revision+1 WHERE id=?",
                passwords.encode(password(body)),
                id);
        revokeSessions(id);
        var after = publicUser(id);
        iam.audit(
                actor,
                "USER_PASSWORD_RESET",
                id,
                Map.of("revision", before.get("revision")),
                Map.of("revision", after.get("revision")));
        return after;
    }

    static String password(Map<String, Object> body) {
        Object raw = body.get("password");
        if (!(raw instanceof String value)
                || value.length() < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw Problem.bad("密码至少12字符，UTF-8编码至多72字节");
        return value;
    }

    private List<String> roles(Map<String, Object> body) {
        var ids = Input.uuids(body.get("roleIds"));
        if (ids.size() > 64) throw Problem.bad("每个用户最多分配64个角色");
        for (String id : ids) db.one("SELECT id FROM local_role WHERE id=?", Input.uuid(id));
        return ids;
    }

    private void assign(UUID user, List<String> roles) {
        for (String role : roles)
            db.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", user, Input.uuid(role));
    }

    private void revokeSessions(UUID user) {
        db.update("UPDATE auth_session SET revoked=true WHERE user_id=?", user);
    }

    private Map<String, Object> publicUser(UUID id) {
        return db.one(SELECT + "WHERE u.id=? GROUP BY u.id", id);
    }
}
