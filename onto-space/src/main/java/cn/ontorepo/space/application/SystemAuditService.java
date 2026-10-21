package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** 系统管理审计独立于空间审计，不伪造空间标识，分页读取安全变更快照。 */
@Service
public class SystemAuditService {
    private final Db db;
    private final PermissionService permissions;

    public SystemAuditService(Db db, PermissionService permissions) {
        this.db = db;
        this.permissions = permissions;
    }

    public Map<String, Object> list(UUID actor, int page, int pageSize) {
        permissions.require(actor, "system.audit.read");
        if (page < 1 || page > 1000000 || pageSize < 1 || pageSize > 100)
            throw Problem.bad("页码必须为正整数，每页1至100条");
        var items =
                db.list(
                        "SELECT a.*,u.display_name actor_name FROM iam_audit a JOIN local_user u ON"
                            + " u.id=a.actor_id ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET"
                            + " ?",
                        pageSize,
                        (long) (page - 1) * pageSize);
        return Map.of(
                "items",
                items,
                "page",
                page,
                "pageSize",
                pageSize,
                "total",
                db.one("SELECT count(*) total FROM iam_audit").get("total"));
    }
}
