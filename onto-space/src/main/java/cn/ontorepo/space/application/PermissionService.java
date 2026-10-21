package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.domain.PermissionCatalog;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** 实时功能权限取 ACTIVE 角色的并集；不缓存到会话，也不替代空间与数据权限。 */
@Service
public class PermissionService {
    private final Db db;

    public PermissionService(Db db) {
        this.db = db;
    }

    public List<String> effective(UUID user) {
        if (db.list("SELECT 1 FROM local_user WHERE id=? AND status='ACTIVE'", user).isEmpty())
            throw new Problem(401, "UNAUTHORIZED", "账号已停用或不存在，请重新登录");
        var result = new TreeSet<String>();
        for (var row :
                db.list(
                        "SELECT r.code,r.built_in,p.permission_code FROM user_role ur JOIN"
                            + " local_role r ON r.id=ur.role_id LEFT JOIN role_permission p ON"
                            + " p.role_id=r.id WHERE ur.user_id=? AND r.status='ACTIVE'",
                        user)) {
            String code = (String) row.get("code");
            if (Boolean.TRUE.equals(row.get("builtIn"))
                    && ("SYSTEM_ADMIN".equals(code) || "MEMBER".equals(code)))
                result.addAll(PermissionCatalog.codes("SYSTEM_ADMIN".equals(code)));
            Object permission = row.get("permissionCode");
            if (permission instanceof String value && PermissionCatalog.known(value))
                result.add(value);
        }
        return List.copyOf(result);
    }

    /** 未登记权限与缺授权均拒绝，避免新接口注解拼写错误形成放行。 */
    public void require(UUID user, String permission) {
        if (!PermissionCatalog.known(permission) || !effective(user).contains(permission))
            throw new Problem(403, "PERMISSION_DENIED", "没有此功能的操作权限：" + permission);
    }
}
