package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.release.domain.OutboxPolicy;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.FrozenBundleLoader;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 生命周期与故障分类：真实确认的缺失/损坏才隔离，存储不可达保留状态且拒绝本次消费。 */
@Service
public class ReleaseLifecycleService {
    private final Db db;
    private final SpaceService spaces;
    private final FrozenBundleLoader bundles;
    private final CanonicalPayload codec;
    private final PublicationService publication;
    private final ReleaseHistoryService history;
    private final TransactionTemplate tx;

    public ReleaseLifecycleService(
            Db db,
            SpaceService spaces,
            FrozenBundleLoader bundles,
            CanonicalPayload codec,
            PublicationService publication,
            ReleaseHistoryService history,
            PlatformTransactionManager tm) {
        this.db = db;
        this.spaces = spaces;
        this.bundles = bundles;
        this.codec = codec;
        this.publication = publication;
        this.history = history;
        tx = new TransactionTemplate(tm);
    }

    /** 在空间锁内原子记录弃用状态、历史绑定事件和审计，重复弃用不增加事件。 */
    public Map<String, Object> deprecate(UUID user, UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 4000)
            throw Problem.bad("弃用原因不能为空");
        tx.executeWithoutResult(
                status -> {
                    var r = lock(user, id);
                    if ("DEPRECATED".equals(r.get("status"))) return;
                    if (!"READY".equals(r.get("status")))
                        throw new Problem(409, "RELEASE_NOT_READY", "当前版本不能弃用");
                    db.update("UPDATE ontology_release SET status='DEPRECATED' WHERE id=?", id);
                    publication.enqueue(
                            Input.uuid(r.get("spaceId")),
                            id,
                            OutboxPolicy.Type.DEPRECATED,
                            "DEPRECATED",
                            Map.of("releaseId", id.toString(), "reason", reason));
                    audit(user, r, "RELEASE_DEPRECATE", Map.of("reason", reason));
                });
        return history.detail(user, id);
    }

    /** OWNER发起四制品实检；网络读取在事务外，只有确认损坏才隔离版本。 */
    public Map<String, Object> integrity(UUID user, UUID id) {
        var r = bundles.release(id, false);
        owner(user, Input.uuid(r.get("spaceId")));
        String result = scan(r, user);
        var out = new LinkedHashMap<>(history.detail(user, id));
        out.put(
                "integrity",
                Map.of("conclusion", result, "checkedAt", java.time.Instant.now().toString()));
        return out;
    }

    /** 定时任务使用同一校验路径、有限批次；系统身份为NULL并明确标记，不能冒用发布人。 */
    public void scanBatch(int limit) {
        for (var r :
                db.list(
                        "SELECT * FROM ontology_release WHERE status IN('READY','DEPRECATED') ORDER"
                                + " BY integrity_checked_at NULLS FIRST,published_at LIMIT ?",
                        Math.min(20, Math.max(1, limit)))) scan(r, null);
    }

    private String scan(Map<String, Object> initial, UUID actor) {
        String failure = null;
        try {
            for (String k : List.of("bundleRef", "manifestRef", "contextPackRef", "indexRef"))
                bundles.verified((Map<?, ?>) initial.get(k));
        } catch (Problem e) {
            failure = e.code;
        }
        String code = failure;
        tx.executeWithoutResult(
                status -> {
                    UUID space = Input.uuid(initial.get("spaceId"));
                    db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", space);
                    var latest =
                            db.one(
                                    "SELECT * FROM ontology_release WHERE id=? FOR UPDATE",
                                    Input.uuid(initial.get("id")));
                    db.update(
                            "UPDATE ontology_release SET"
                                    + " integrity_checked_at=now(),integrity_error=? WHERE id=?",
                            code,
                            Input.uuid(latest.get("id")));
                    if (code == null || "ARTIFACT_STORAGE_UNAVAILABLE".equals(code)) return;
                    if (!Set.of(
                                    "ARTIFACT_CORRUPT",
                                    "ARTIFACT_MISSING",
                                    "INVALID_ARTIFACT_REF",
                                    "INVALID_ARTIFACT_KEY")
                            .contains(code)) return;
                    if ("CORRUPTED".equals(latest.get("status"))) return;
                    db.update(
                            "UPDATE ontology_release SET status='CORRUPTED' WHERE id=?",
                            Input.uuid(latest.get("id")));
                    publication.enqueue(
                            space,
                            Input.uuid(latest.get("id")),
                            OutboxPolicy.Type.CORRUPTED_ALERT,
                            "CORRUPTED",
                            Map.of("releaseId", latest.get("id"), "reason", code));
                    audit(
                            actor,
                            latest,
                            "RELEASE_CORRUPTED",
                            Map.of("reason", code, "system", actor == null));
                });
        return code == null
                ? "PASSED"
                : "ARTIFACT_STORAGE_UNAVAILABLE".equals(code) ? "UNAVAILABLE" : "CORRUPTED";
    }

    /** READY拉取实对象，不对消费者返回内部bundle、源密文或对象存储地址。 */
    public Object consume(UUID user, UUID id, String field) {
        var r = bundles.release(id, true);
        spaces.role(user, Input.uuid(r.get("spaceId")));
        byte[] bytes = bundles.verified((Map<?, ?>) r.get(field));
        bundles.release(id, true);
        return codec.decode(bytes);
    }

    private Map<String, Object> lock(UUID user, UUID id) {
        var r = bundles.release(id, false);
        db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", Input.uuid(r.get("spaceId")));
        owner(user, Input.uuid(r.get("spaceId")));
        return db.one("SELECT * FROM ontology_release WHERE id=? FOR UPDATE", id);
    }

    private void owner(UUID user, UUID space) {
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "仅OWNER可管理版本生命周期");
    }

    private void audit(UUID user, Map<String, Object> r, String action, Object detail) {
        UUID space = Input.uuid(r.get("spaceId"));
        String result = action.equals("RELEASE_DEPRECATE") ? "DEPRECATED" : "CORRUPTED";
        db.update(
                "INSERT INTO"
                    + " audit_log(space_id,actor_id,action,draft_rev,subject_type,subject_id,version_refs,result,detail)"
                    + " VALUES(?,?,?,?,'RELEASE',?,?::jsonb,?,?::jsonb)",
                space,
                user,
                action,
                db.one("SELECT draft_rev FROM ontology_space WHERE id=?", space).get("draftRev"),
                r.get("id"),
                codec.json(
                        Map.of(
                                "releaseId", r.get("id"),
                                "versionNo", r.get("versionNo"),
                                "bundleSha256", Objects.toString(r.get("bundleSha256"), ""))),
                result,
                codec.json(detail));
    }
}
