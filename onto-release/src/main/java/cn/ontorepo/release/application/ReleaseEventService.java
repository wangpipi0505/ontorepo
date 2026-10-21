package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.release.infra.JdbcOutboxStore;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 空间隔离的事件目录、持久接收证据与OWNER原id重放，不对外暴露事件内部载荷。 */
@Service
public class ReleaseEventService {
    private final Db db;
    private final SpaceService spaces;
    private final JdbcOutboxStore outbox;

    public ReleaseEventService(Db db, SpaceService spaces, JdbcOutboxStore outbox) {
        this.db = db;
        this.spaces = spaces;
        this.outbox = outbox;
    }

    /** 按空间授权读取最近事件的安全状态目录，通知载荷及内部目标地址不向客户端透传。 */
    public List<Map<String, Object>> list(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list(
                "SELECT o.id,o.space_id,o.release_id,o.type,o.bound_status,r.status"
                    + " release_status,o.status,o.retry_count,o.next_retry_at,o.last_error,o.created_at"
                    + " FROM publish_outbox o JOIN ontology_release r ON r.id=o.release_id WHERE"
                    + " o.space_id=? ORDER BY o.id DESC LIMIT 200",
                space);
    }

    /** 通过现存版本所属空间读取真实接收凭证，不能借孤立收件记录穿透空间隔离。 */
    public List<Map<String, Object>> inbox(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list(
                "SELECT"
                    + " i.event_id,i.release_id,i.type,i.received_at,jsonb_build_object('accepted',i.receipt->'accepted','payloadSha256',i.receipt->'payloadSha256')"
                    + " receipt FROM publish_inbox i JOIN ontology_release r ON r.id=i.release_id"
                    + " WHERE r.space_id=? ORDER BY i.event_id DESC LIMIT 200",
                space);
    }

    @Transactional
    /** OWNER在空间锁内重置符合有限重放规则的DEAD事件，原id和不可变载荷保持不变。 */
    public Map<String, Object> replay(UUID user, long id, Map<String, Object> body) {
        String reason = Input.text(body, "reason", 4000);
        var e = db.one("SELECT space_id FROM publish_outbox WHERE id=?", id);
        UUID space = Input.uuid(e.get("spaceId"));
        var s = db.one("SELECT draft_rev FROM ontology_space WHERE id=? FOR UPDATE", space);
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "仅OWNER可以重放事件");
        db.jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            outbox.replay(connection, id);
                            return null;
                        });
        db.update(
                "INSERT INTO audit_log(space_id,actor_id,action,draft_rev,subject_id,detail)"
                        + " VALUES(?,?,'EVENT_REPLAY',?,?,?::jsonb)",
                space,
                user,
                s.get("draftRev"),
                Long.toString(id),
                db.json(Map.of("reason", reason)));
        return db.one(
                "SELECT o.id,o.space_id,o.release_id,o.type,o.bound_status,r.status"
                    + " release_status,o.status,o.retry_count,o.next_retry_at,o.last_error,o.created_at"
                    + " FROM publish_outbox o JOIN ontology_release r ON r.id=o.release_id WHERE"
                    + " o.id=? AND o.space_id=?",
                id,
                space);
    }
}
