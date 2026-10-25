package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import org.springframework.transaction.annotation.Transactional;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * R12/UIF-O3：提案元素级评论。
 *
 * <p>评论是 REVIEW 阶段的多人协作数据——按候选隔离、actor 取当前会话身份、
 * 空间锁保护写入；候选提交发布后评论不可再写（协作数据不进正式记录）。
 * 与 PublicationCheck 分离：Check 是门禁结论权威（每门禁一行），评论是多人
 * 多轮的讨论线程。
 */
@Service
public class ProposalCommentService {
    private final Db db;
    private final SpaceService spaces;

    public ProposalCommentService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    /** 按候选查全部评论（id 升序 = 时间序）；调用方须对该候选有 trace.read。 */
    public List<Map<String, Object>> list(UUID user, UUID candidateId) {
        spaces.role(user, spaceOf(candidateId));
        return db.list(
                "SELECT c.id,c.actor_id,c.subject_kind,c.subject_key,c.body,c.created_at,"
                        + "u.display_name AS actor_name FROM proposal_comment c"
                        + " JOIN local_user u ON u.id=c.actor_id"
                        + " WHERE c.candidate_id=? ORDER BY c.id",
                candidateId);
    }

    /** 新增评论；subject_kind/subject_key 可 null（对整个提案的总体评论）。 */
    @Transactional
    public Map<String, Object> create(
            UUID user, UUID candidateId, String subjectKind, String subjectKey, String body) {
        UUID space = spaceOf(candidateId);
        String text = Input.text(Collections.singletonMap("body", body), "body", 2000);
        spaces.lock(user, space, -1, false);
        db.update(
                "INSERT INTO proposal_comment(space_id,candidate_id,actor_id"
                        + ",subject_kind,subject_key,body) VALUES(?,?,?,?,?,?)",
                space,
                candidateId,
                user,
                subjectKind,
                subjectKey,
                text);
        return Map.of("candidateId", candidateId.toString(), "created", true);
    }

    private UUID spaceOf(UUID candidateId) {
        var row =
                db.one(
                        "SELECT c.space_id FROM publish_candidate c WHERE c.id=?",
                        candidateId);
        return java.util.UUID.fromString(row.get("spaceId").toString());
    }
}
