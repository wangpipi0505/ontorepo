package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** IAM 写事务共用的锁、修订、最后管理员与安全审计；调用者必须已开启事务。 */
@Component
public class IamSupport {
    private final Db db;
    private final PermissionService permissions;

    public IamSupport(Db db, PermissionService permissions) {
        this.db = db;
        this.permissions = permissions;
    }

    public void lock(UUID actor, String permission) {
        db.one("SELECT id FROM iam_state WHERE id=1 FOR UPDATE");
        // 锁后复验权限，排队等待期间被撤权的管理者不得继续修改配置。
        permissions.require(actor, permission);
    }

    public void revision(Map<String, Object> row, Map<String, Object> body) {
        long expected = Input.number(body, "expectedRevision");
        if (expected < 0 || expected != ((Number) row.get("revision")).longValue())
            throw new Problem(409, "IAM_REVISION_CONFLICT", "配置已被修改，请刷新后重试");
    }

    public String status(Map<String, Object> body) {
        String value = Input.text(body, "status", 20);
        if (!value.equals("ACTIVE") && !value.equals("DISABLED"))
            throw Problem.bad("状态必须为 ACTIVE 或 DISABLED");
        return value;
    }

    public void retainAdministrator() {
        if (db.list(
                        "SELECT 1 FROM local_user u JOIN user_role ur ON ur.user_id=u.id JOIN"
                            + " local_role r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND"
                            + " r.code='SYSTEM_ADMIN' AND r.built_in AND r.status='ACTIVE' LIMIT 1")
                .isEmpty()) throw new Problem(409, "LAST_SYSTEM_ADMIN", "不能停用或移除最后一个有效系统管理员");
    }

    /** 只能传入公开配置快照，密码重置只记录动作，不接受凭据字段。 */
    public void audit(UUID actor, String action, UUID target, Object before, Object after) {
        db.update(
                "INSERT INTO iam_audit(actor_id,action,target_id,before_state,after_state)"
                    + " VALUES(?,?,?,?::jsonb,?::jsonb)",
                actor,
                action,
                target,
                db.json(before),
                db.json(after));
    }
}
