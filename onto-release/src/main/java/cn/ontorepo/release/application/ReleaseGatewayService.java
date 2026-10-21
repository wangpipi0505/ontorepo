package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.data.domain.LabelScope;
import cn.ontorepo.space.application.*;
import cn.ontorepo.trace.application.CandidateService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 显式端点授予与凭据目录；调用身份只来自会话或经过哈希核验的API key。 */
@Service
public class ReleaseGatewayService {
    private static final String CREDENTIAL =
            "id,space_id,principal_id,name,status,created_at,expires_at";
    private final Db db;
    private final SpaceService spaces;
    private final CandidateService execution;

    public ReleaseGatewayService(Db db, SpaceService spaces, CandidateService execution) {
        this.db = db;
        this.spaces = spaces;
        this.execution = execution;
    }

    /** 管理/消费均固定真实release主体；路由空间与API凭据所属空间不得替换版本身份。 */
    public Map<String, Object> invoke(
            UUID user,
            UUID release,
            String code,
            Object input,
            String caller,
            Object credentialSpace,
            String spaceCode) {
        var r =
                db.one(
                        "SELECT r.space_id,s.code FROM ontology_release r JOIN ontology_space s ON"
                                + " s.id=r.space_id WHERE r.id=?",
                        release);
        if (credentialSpace != null && !Input.sameUuid(credentialSpace, r.get("spaceId")))
            throw new Problem(403, "NO_PERMISSION", "凭据不属于该版本空间");
        if (spaceCode != null && !spaceCode.equals(r.get("code")))
            throw new Problem(404, "NOT_FOUND", "固定版本路由与空间不匹配");
        return execution.releaseService(user, release, code, input, caller);
    }

    /** 列举可见版本端点；非READY版本统一显示禁用，ACTIVE本身不代表调用者获准消费。 */
    public List<Map<String, Object>> endpoints(UUID user, UUID release) {
        var r = db.one("SELECT space_id,status FROM ontology_release WHERE id=?", release);
        spaces.role(user, Input.uuid(r.get("spaceId")));
        return db.list(
                "SELECT id,release_id,op_code,route,CASE WHEN ?='READY' THEN status ELSE 'DISABLED'"
                        + " END status,exec_attrs FROM service_endpoint WHERE release_id=? ORDER BY"
                        + " op_code",
                r.get("status"),
                release);
    }

    /** OWNER查看显式端点授权目录，空间角色与端点授权始终分别判断。 */
    public List<Map<String, Object>> grants(UUID user, UUID endpoint) {
        var e = endpoint(endpoint);
        owner(user, Input.uuid(e.get("spaceId")));
        return db.list(
                "SELECT g.user_id,u.username,u.display_name,g.data_scope,g.expires_at,g.status FROM"
                    + " endpoint_grant g JOIN local_user u ON u.id=g.user_id WHERE g.endpoint_id=?"
                    + " ORDER BY u.username",
                endpoint);
    }

    @Transactional
    /** 在空间锁内更新授权并审计；NULL范围表示不额外收窄，撤销/到期由执行链实时拒绝。 */
    public Map<String, Object> saveGrant(
            UUID user, UUID endpoint, UUID target, Map<String, Object> body) {
        var e = endpoint(endpoint);
        UUID space = Input.uuid(e.get("spaceId"));
        lockOwner(user, space);
        db.one("SELECT id FROM local_user WHERE id=?", target);
        String status = Input.text(body, "status", 10);
        if (!Set.of("ACTIVE", "REVOKED").contains(status)) throw Problem.bad("端点授予状态无效");
        LabelScope.parse(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(body.get("dataScope")));
        db.update(
                "INSERT INTO endpoint_grant(endpoint_id,user_id,data_scope,expires_at,status)"
                    + " VALUES(?,?,?::jsonb,?,?) ON CONFLICT(endpoint_id,user_id) DO UPDATE SET"
                    + " data_scope=excluded.data_scope,expires_at=excluded.expires_at,status=excluded.status",
                endpoint,
                target,
                body.get("dataScope") == null ? null : db.json(body.get("dataScope")),
                expiry(body.get("expiresAt")),
                status);
        audit(
                user,
                space,
                "ENDPOINT_GRANT",
                endpoint.toString(),
                Map.of("principalId", target.toString(), "status", status));
        return grants(user, endpoint).stream()
                .filter(g -> Input.sameUuid(g.get("userId"), target))
                .findFirst()
                .orElseThrow(Problem::missing);
    }

    /** 端点级启停：停用后执行链在访问源前拒绝；启用可逆，状态变更入审计。 */
    @Transactional
    public Map<String, Object> updateStatus(
            UUID user, UUID endpoint, Map<String, Object> body) {
        String status = Input.text(body, "status", 10);
        if (!Set.of("ACTIVE", "DISABLED").contains(status))
            throw Problem.bad("端点状态必须为ACTIVE或DISABLED");
        var e = endpoint(endpoint);
        UUID space = Input.uuid(e.get("spaceId"));
        lockOwner(user, space);
        db.update(
                "UPDATE service_endpoint SET status=?,updated_at=now(),status_changed_by=? WHERE"
                        + " id=?",
                status,
                user,
                endpoint);
        audit(
                user,
                space,
                "ENDPOINT_STATUS",
                endpoint.toString(),
                Map.of(
                        "status", status,
                        "releaseId", e.get("releaseId").toString(),
                        "opCode", e.get("opCode").toString()));
        return db.one(
                "SELECT id,release_id,op_code,route,status,exec_attrs,updated_at FROM"
                        + " service_endpoint WHERE id=?",
                endpoint);
    }

    /** 凭据目录仅展示安全元数据，任何读取接口都不再返回明文或哈希。 */
    public List<Map<String, Object>> credentials(UUID user, UUID space) {
        owner(user, space);
        return db.list(
                "SELECT "
                        + CREDENTIAL
                        + " FROM api_credential WHERE space_id=? ORDER BY created_at DESC",
                space);
    }

    @Transactional
    /** OWNER为既有空间成员签发随机凭据；事务内只保存摘要，创建响应仅一次携带明文。 */
    public Map<String, Object> createCredential(UUID user, Map<String, Object> body) {
        UUID space = Input.uuid(body.get("spaceId")),
                principal = Input.uuid(body.get("principalId"));
        lockOwner(user, space);
        db.one("SELECT id FROM local_user WHERE id=?", principal);
        spaces.role(principal, space);
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String key = "onto_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " api_credential(id,space_id,principal_id,name,key_hash,status,expires_at)"
                    + " VALUES(?,?,?,?,?,'ACTIVE',?)",
                id,
                space,
                principal,
                Input.text(body, "name", 200),
                AuthService.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                expiry(body.get("expiresAt")));
        audit(
                user,
                space,
                "API_CREDENTIAL_CREATE",
                id.toString(),
                Map.of("principalId", principal.toString()));
        return Map.of(
                "credential",
                db.one("SELECT " + CREDENTIAL + " FROM api_credential WHERE id=?", id),
                "key",
                key);
    }

    @Transactional
    /** 空间锁内撤销凭据并审计，使后续API认证立即拒绝。 */
    public Map<String, Object> revoke(UUID user, UUID id) {
        var c = db.one("SELECT space_id FROM api_credential WHERE id=?", id);
        UUID space = Input.uuid(c.get("spaceId"));
        lockOwner(user, space);
        db.update("UPDATE api_credential SET status='REVOKED' WHERE id=?", id);
        audit(user, space, "API_CREDENTIAL_REVOKE", id.toString(), Map.of());
        return db.one("SELECT " + CREDENTIAL + " FROM api_credential WHERE id=?", id);
    }

    /** 明文只在创建时返回，认证与目录均不读取/保存可逆key；过期或撤销立即拒绝。 */
    public Map<String, Object> authenticateKey(String key) {
        if (key == null || key.length() > 200 || !key.matches("onto_[A-Za-z0-9_-]{43}"))
            throw new Problem(401, "UNAUTHORIZED", "API凭据无效");
        var rows =
                db.list(
                        "SELECT c.principal_id,c.space_id FROM api_credential c JOIN local_user u ON u.id=c.principal_id WHERE c.key_hash=? AND"
                                + " c.status='ACTIVE' AND u.status='ACTIVE' AND(c.expires_at IS NULL OR c.expires_at>now())",
                        AuthService.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (rows.isEmpty()) throw new Problem(401, "UNAUTHORIZED", "API凭据无效或已过期/撤销");
        return rows.getFirst();
    }

    private Map<String, Object> endpoint(UUID id) {
        return db.one(
                "SELECT e.*,r.space_id FROM service_endpoint e JOIN ontology_release r ON"
                        + " r.id=e.release_id WHERE e.id=?",
                id);
    }

    private void owner(UUID user, UUID space) {
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "仅OWNER可管理端点及凭据");
    }

    private void lockOwner(UUID user, UUID space) {
        db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", space);
        owner(user, space);
    }

    private Timestamp expiry(Object value) {
        if (value == null) return null;
        try {
            return Timestamp.from(Instant.parse(value.toString()));
        } catch (RuntimeException e) {
            throw Problem.bad("过期时间必须为ISO时间");
        }
    }

    private void audit(UUID user, UUID space, String action, String subject, Object detail) {
        db.update(
                "INSERT INTO audit_log(space_id,actor_id,action,draft_rev,subject_id,detail)"
                        + " VALUES(?,?,?,?,?,?::jsonb)",
                space,
                user,
                action,
                db.one("SELECT draft_rev FROM ontology_space WHERE id=?", space).get("draftRev"),
                subject,
                db.json(detail));
    }
}
