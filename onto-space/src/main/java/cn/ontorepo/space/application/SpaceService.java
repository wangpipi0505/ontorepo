package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.AuditDetail;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 空间行锁是所有草稿写事务的串行化边界，授权/修订/审计不拆分提交。 */
@Service
public class SpaceService {
    private final Db db;
    private final cn.ontorepo.space.infra.SpaceAuthorizationGate authorization;

    public SpaceService(Db db, cn.ontorepo.space.infra.SpaceAuthorizationGate authorization) {
        this.db = db;
        this.authorization = authorization;
    }

    private static final String SELECT =
            "SELECT s.*,m.role,(SELECT count(*) FROM ont_class c WHERE c.space_id=s.id)"
                + " class_count,(SELECT count(*) FROM ont_property p WHERE p.space_id=s.id)"
                + " property_count,(SELECT count(*) FROM ont_operation o WHERE o.space_id=s.id)"
                + " operation_count FROM ontology_space s JOIN space_grant m ON m.space_id=s.id ";

    /** 空间授权默认拒绝：仅返回数据库存在的显式成员角色，不信任客户端身份或角色。 */
    public String role(UUID user, UUID space) {
        var r = db.list("SELECT role FROM space_grant WHERE user_id=? AND space_id=?", user, space);
        if (r.isEmpty()) throw new Problem(403, "FORBIDDEN", "没有该空间访问权限");
        return (String) r.getFirst().get("role");
    }

    /** OWNER 和 EDITOR 允许写草稿及试执行，VIEWER 只能读取。 */
    public void editor(UUID user, UUID space) {
        if (role(user, space).equals("VIEWER")) throw new Problem(403, "FORBIDDEN", "此操作需要编辑权限");
    }

    /** 通过成员关联限定可见集合，禁止先查询全部空间再在客户端过滤。 */
    public List<Map<String, Object>> list(UUID user) {
        return db.list(SELECT + "WHERE m.user_id=? ORDER BY s.created_at DESC", user);
    }

    /** 详情与计数在通过成员授权后读取。 */
    public Map<String, Object> get(UUID user, UUID space) {
        role(user, space);
        return db.one(SELECT + "WHERE m.user_id=? AND s.id=?", user, space);
    }

    @Transactional
    public Map<String, Object> create(UUID user, Map<String, Object> b) {
        String code = Input.text(b, "code", 80);
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]*")) throw Problem.bad("空间编码须以字母开头，仅含字母数字下划线或短横线");
        String uri = Input.text(b, "namespaceUri", 1000);
        try {
            URI u = URI.create(uri);
            if (!Set.of("http", "https").contains(u.getScheme())
                    || u.getHost() == null
                    || u.getUserInfo() != null
                    || u.getQuery() != null
                    || !(uri.endsWith("/") || uri.endsWith("#")))
                throw Problem.bad("命名空间必须为绝对HTTP(S) URI并以 / 或 # 结尾");
        } catch (IllegalArgumentException e) {
            throw Problem.bad("命名空间 URI 格式错误");
        }
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO ontology_space(id,code,name,namespace_uri,domain,description)"
                        + " VALUES(?,?,?,?,?,?)",
                id,
                code,
                Input.text(b, "name", 200),
                uri,
                Input.optionalText(b, "domain", 200),
                Input.description(b));
        db.update("INSERT INTO space_grant(space_id,user_id,role) VALUES(?,?, 'OWNER')", id, user);
        audit(user, id, "SPACE_CREATE", 0);
        return get(user, id);
    }

    /** 发布就绪度只做计数聚合，不替代候选五项门禁；未分类/未检查项不折算为通过。 */
    public Map<String, Object> readiness(UUID user, UUID space) {
        role(user, space);
        return db.one(
                "SELECT"
                    + " (SELECT count(*) FROM ont_class c WHERE c.space_id=s.id AND NOT c.deprecated) class_count,"
                    + " (SELECT count(*) FROM ont_property p WHERE p.space_id=s.id) property_count,"
                    + " (SELECT count(*) FROM ont_relation r WHERE r.space_id=s.id) relation_count,"
                    + " (SELECT count(*) FROM ont_rule r WHERE r.space_id=s.id) rule_count,"
                    + " (SELECT count(*) FROM ont_operation o WHERE o.space_id=s.id AND o.assistant_tool IS NOT NULL) assistant_count,"
                    + " (SELECT count(*) FROM jsonb_object_keys(s.guard_cfg)) guard_count,"
                    + " (SELECT count(*) FROM ont_test_case t WHERE t.space_id=s.id AND t.enabled) case_count,"
                    + " (SELECT count(*) FROM publish_candidate c WHERE c.space_id=s.id) candidate_count"
                    + " FROM ontology_space s WHERE s.id=?",
                space);
    }

    /** action 在行锁持有期间执行；抛错使整个写入和审计一起回滚。 */
    @Transactional
    public Map<String, Object> write(
            UUID user, UUID space, long expected, String action, Runnable work) {
        return write(user, space, expected, action, (AuditDetail) null, work);
    }

    /** 带对象/版本/摘要的处理审计版本；work 与审计同事务。 */
    @Transactional
    public Map<String, Object> write(
            UUID user,
            UUID space,
            long expected,
            String action,
            AuditDetail detail,
            Runnable work) {
        return write(user, space, expected, action, () -> detail, work);
    }

    /** 审计要素在 work 完成后才能确定的场景（如新快照 id/摘要）。 */
    @Transactional
    public Map<String, Object> write(
            UUID user,
            UUID space,
            long expected,
            String action,
            java.util.function.Supplier<AuditDetail> detail,
            Runnable work) {
        lock(user, space, expected, true);
        work.run();
        db.update(
                "UPDATE ontology_space SET draft_rev=draft_rev+1,updated_at=now() WHERE id=?",
                space);
        audit(user, space, action, expected + 1, detail.get());
        return get(user, space);
    }

    /** 调用方必须已建立事务；先锁空间再读权限、状态和修订，覆盖所有草稿变更路径。 */
    public void lock(UUID user, UUID space, long expected, boolean editing) {
        var s = db.one("SELECT * FROM ontology_space WHERE id=? FOR UPDATE", space);
        if (editing) editor(user, space);
        else role(user, space);
        if (!s.get("status").equals("ACTIVE"))
            throw new Problem(409, "SPACE_ARCHIVED", "空间已归档，禁止修改");
        if (((Number) s.get("draftRev")).longValue() != expected)
            throw new Problem(409, "DRAFT_REV_CONFLICT", "草稿已被修改，请刷新后重新编辑");
    }

    /** 审计沿用调用方事务；写入失败时业务修改必须同步回滚。 */
    public void audit(UUID user, UUID space, String action, long rev) {
        db.update(
                "INSERT INTO audit_log(space_id,actor_id,action,draft_rev) VALUES(?,?,?,?)",
                space,
                user,
                action,
                rev);
    }

    /** 带对象/版本/摘要/结果的处理审计；缺省字段保持 null，不伪造历史事实。 */
    public void audit(UUID user, UUID space, String action, long rev, AuditDetail detail) {
        db.update(
                "INSERT INTO"
                    + " audit_log(space_id,actor_id,action,draft_rev,subject_type,subject_id,version_refs,input_digest,output_digest,result)"
                    + " VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)",
                space,
                user,
                action,
                rev,
                detail == null ? null : detail.subjectType(),
                detail == null ? null : detail.subjectId(),
                detail == null || detail.versionRefs() == null
                        ? null
                        : db.json(detail.versionRefs()),
                detail == null ? null : detail.inputDigest(),
                detail == null ? null : detail.outputDigest(),
                detail == null ? null : detail.result());
    }

    /** 外部入口必须显式开启事务：同类调用 write 不经过 Spring 代理，不能单靠 write 注解。 */
    @Transactional
    public Map<String, Object> update(UUID user, UUID id, Map<String, Object> b) {
        String name = Input.text(b, "name", 200), domain = Input.optionalText(b, "domain", 200);
        String description = b.containsKey("description") ? Input.description(b) : null;
        return write(
                user,
                id,
                Input.number(b, "expectedDraftRev"),
                "SPACE_UPDATE",
                () ->
                        db.update(
                                "UPDATE ontology_space SET"
                                    + " name=?,domain=?,description=COALESCE(?,description),updated_at=now()"
                                    + " WHERE id=?",
                                name,
                                domain,
                                description,
                                id));
    }

    /** 归档仅所有者可用；状态、修订与审计在外部入口事务中一起提交。 */
    @Transactional
    public Map<String, Object> archive(UUID user, UUID id, long expected) {
        authorization.exclusive(id);
        return write(
                user,
                id,
                expected,
                "SPACE_ARCHIVE",
                () -> {
                    if (!role(user, id).equals("OWNER"))
                        throw new Problem(403, "FORBIDDEN", "仅空间所有者可以归档");
                    db.update("UPDATE ontology_space SET status='ARCHIVED' WHERE id=?", id);
                });
    }
}
