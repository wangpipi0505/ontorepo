package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.domain.PermissionCatalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** 自定义角色可组合有限权限目录；内置角色不可编辑，防止改变升级兼容与管理员保护语义。 */
@Service
public class SystemRoleService {
    private final Db db;
    private final PermissionService permissions;
    private final IamSupport iam;
    private static final String SELECT =
            "SELECT r.*,(SELECT count(*) FROM user_role ur WHERE ur.role_id=r.id)"
                + " member_count,COALESCE((SELECT jsonb_agg(p.permission_code ORDER BY"
                + " p.permission_code) FROM role_permission p WHERE p.role_id=r.id),'[]'::jsonb)"
                + " permissions FROM local_role r ";

    public SystemRoleService(Db db, PermissionService permissions, IamSupport iam) {
        this.db = db;
        this.permissions = permissions;
        this.iam = iam;
    }

    public List<Map<String, Object>> list(UUID actor) {
        permissions.require(actor, "system.role.read");
        return db.list(SELECT + "ORDER BY r.built_in DESC,r.code").stream()
                .map(this::builtinPermissions)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(UUID actor, Map<String, Object> body) {
        iam.lock(actor, "system.role.write");
        String code = Input.text(body, "code", 80);
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"))
            throw Problem.bad("角色编码须以字母开头，仅含字母、数字、下划线或短横线");
        var codes = permissionCodes(body);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO local_role(id,code,name,description) VALUES(?,?,?,?)",
                id,
                code,
                Input.text(body, "name", 200),
                Input.optionalText(body, "description", 2000));
        assign(id, codes);
        var after = role(id);
        iam.audit(actor, "ROLE_CREATE", id, Map.of(), after);
        return after;
    }

    @Transactional
    public Map<String, Object> update(UUID actor, UUID id, Map<String, Object> body) {
        iam.lock(actor, "system.role.write");
        var before = role(id);
        editable(before);
        iam.revision(before, body);
        var codes = permissionCodes(body);
        db.update(
                "UPDATE local_role SET name=?,description=?,status=?,revision=revision+1 WHERE"
                    + " id=?",
                Input.text(body, "name", 200),
                Input.optionalText(body, "description", 2000),
                iam.status(body),
                id);
        db.update("DELETE FROM role_permission WHERE role_id=?", id);
        assign(id, codes);
        var after = role(id);
        iam.audit(actor, "ROLE_UPDATE", id, before, after);
        return after;
    }

    @Transactional
    public void delete(UUID actor, UUID id, long expected) {
        iam.lock(actor, "system.role.write");
        var before = role(id);
        editable(before);
        iam.revision(before, Map.of("expectedRevision", expected));
        if (((Number) before.get("memberCount")).longValue() > 0)
            throw new Problem(409, "ROLE_IN_USE", "角色仍分配给用户，请先调整用户角色");
        db.update("DELETE FROM local_role WHERE id=?", id);
        iam.audit(actor, "ROLE_DELETE", id, before, Map.of());
    }

    private void editable(Map<String, Object> role) {
        if (Boolean.TRUE.equals(role.get("builtIn")))
            throw new Problem(409, "BUILTIN_ROLE_IMMUTABLE", "内置角色不可编辑或删除");
    }

    private Map<String, Object> role(UUID id) {
        return builtinPermissions(db.one(SELECT + "WHERE r.id=?", id));
    }

    private Map<String, Object> builtinPermissions(Map<String, Object> row) {
        if (Boolean.TRUE.equals(row.get("builtIn")))
            row.put("permissions", PermissionCatalog.codes("SYSTEM_ADMIN".equals(row.get("code"))));
        return row;
    }

    private List<String> permissionCodes(Map<String, Object> body) {
        if (!(body.get("permissions") instanceof List<?> values)
                || values.size() > PermissionCatalog.all().size())
            throw Problem.bad("权限必须为受支持代码数组");
        var result = new TreeSet<String>();
        for (Object value : values) {
            if (!(value instanceof String code)
                    || !PermissionCatalog.known(code)
                    || !result.add(code)) throw Problem.bad("权限代码未知或重复");
        }
        return List.copyOf(result);
    }

    private void assign(UUID role, List<String> codes) {
        for (String code : codes)
            db.update(
                    "INSERT INTO role_permission(role_id,permission_code) VALUES(?,?)", role, code);
    }
}
