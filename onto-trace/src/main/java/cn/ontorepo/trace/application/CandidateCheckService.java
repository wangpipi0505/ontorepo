package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.operation.application.ConflictService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 初期三项检查不是正式发布资格；报告及豁免绑定候选字节摘要，过期草稿不能复用豁免。 */
@Service
public class CandidateCheckService {
    private final Db db;
    private final SpaceService spaces;
    private final CandidateService candidates;
    private final CandidateTestService tests;
    private final ConflictService conflicts;
    private final ConsistencyChecker consistency;
    private final ShaclChecker shacl;
    private final SupplyService supply;

    public CandidateCheckService(
            Db db,
            SpaceService spaces,
            CandidateService candidates,
            CandidateTestService tests,
            ConflictService conflicts,
            ConsistencyChecker consistency,
            ShaclChecker shacl,
            SupplyService supply) {
        this.db = db;
        this.spaces = spaces;
        this.candidates = candidates;
        this.tests = tests;
        this.conflicts = conflicts;
        this.consistency = consistency;
        this.shacl = shacl;
        this.supply = supply;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> checks(UUID u, UUID id) {
        var c = candidates.candidate(u, id);
        UUID s = Input.uuid(c.get("spaceId"));
        spaces.editor(u, s);
        var locked = db.one("SELECT status,draft_rev FROM ontology_space WHERE id=? FOR UPDATE", s);
        if (!"ACTIVE".equals(locked.get("status")))
            throw new Problem(409, "SPACE_ARCHIVED", "归档空间不能执行检查");
        var p = candidates.payload(c);
        List<Map<String, Object>> checks = new ArrayList<>();
        var conflict = conflicts.inspect(p);
        Map<String, Object> shape;
        try {
            shape = shacl.check(p);
        } catch (RuntimeException e) {
            shape =
                    Map.of(
                            "conforms",
                            false,
                            "error",
                            "SHACL_ENGINE_ERROR",
                            "message",
                            "SHACL映射或引擎检查失败，已阻断");
        }
        boolean noConflicts =
                ((List<?>) conflict.get("errors")).isEmpty()
                        && Boolean.TRUE.equals(shape.get("conforms"));
        checks.add(
                Map.of(
                        "kind",
                        "CONFLICT",
                        "conclusion",
                        noConflicts ? "PASSED" : "FAILED",
                        "reason",
                        noConflicts
                                ? ""
                                : "SHACL_ENGINE_ERROR".equals(shape.get("error"))
                                        ? "SHACL_ENGINE_ERROR"
                                        : "MODEL_CONFLICT",
                        "message",
                        noConflicts ? "引用、类型、约束与条件域检查通过" : "存在阻断冲突或SHACL约束错误",
                        "details",
                        Map.of("conflicts", conflict, "shacl", shape)));
        var consistent = consistency.check(p);
        checks.add(consistent);
        boolean waived =
                !db.list(
                                        "SELECT id FROM gate_waiver WHERE candidate_id=? AND"
                                                + " candidate_sha256=? AND draft_rev=?",
                                        id,
                                        c.get("sha256"),
                                        locked.get("draftRev"))
                                .isEmpty()
                        && Objects.equals(locked.get("draftRev"), c.get("draftRev"));
        boolean passConsistency =
                "CONSISTENT".equals(consistent.get("conclusion"))
                        || waived
                                && "NOT_CHECKED".equals(consistent.get("conclusion"))
                                && "SUBSET_EXCEEDED".equals(consistent.get("reason"));
        var tested = tests.tests(u, id);
        checks.add(
                Map.of(
                        "kind",
                        "TEST",
                        "conclusion",
                        Boolean.TRUE.equals(tested.get("passed")) ? "PASSED" : "FAILED",
                        "reason",
                        Boolean.TRUE.equals(tested.get("passed")) ? "" : "TEST_FAILED",
                        "message",
                        "按冻结启用用例执行确定性断言",
                        "details",
                        tested));
        boolean contractPassed = false;
        if ("CONTRACT".equals(p.get("capability"))) {
            try {
                var manifests = supply.manifests(p, id.toString());
                contractPassed = true;
                checks.add(
                        Map.of(
                                "kind",
                                "CONTRACT",
                                "conclusion",
                                "PASSED",
                                "reason",
                                "",
                                "message",
                                "冻结助手Schema、示例、守卫和策略资源完整",
                                "details",
                                Map.of("manifestCount", manifests.size())));
            } catch (cn.ontorepo.contract.ContractProblem e) {
                checks.add(
                        Map.of(
                                "kind",
                                "CONTRACT",
                                "conclusion",
                                "FAILED",
                                "reason",
                                e.code(),
                                "message",
                                e.getMessage(),
                                "details",
                                Map.of("field", e.field(), "reason", e.reason())));
            }
        }
        boolean localEligible =
                noConflicts && passConsistency && Boolean.TRUE.equals(tested.get("passed"));
        Map<String, Object> report =
                Map.of(
                        "candidateSha256",
                        c.get("sha256"),
                        "eligible",
                        localEligible
                                && (!"CONTRACT".equals(p.get("capability")) || contractPassed),
                        "supplyEligible",
                        localEligible && contractPassed,
                        "checks",
                        checks);
        db.update(
                "INSERT INTO candidate_check(id,candidate_id,candidate_sha256,report)"
                        + " VALUES(?,?,?,?::jsonb)",
                UUID.randomUUID(),
                id,
                c.get("sha256"),
                db.json(report));
        spaces.audit(u, s, "CANDIDATE_CHECK", ((Number) locked.get("draftRev")).longValue());
        return report;
    }

    @Transactional
    public Map<String, Object> waiver(UUID u, UUID id, Map<String, Object> b) {
        var c = candidates.candidate(u, id);
        UUID s = Input.uuid(c.get("spaceId"));
        String kind = Input.text(b, "kind", 30), reason = Input.text(b, "reason", 4000);
        var locked = db.one("SELECT status,draft_rev FROM ontology_space WHERE id=? FOR UPDATE", s);
        if (!"OWNER".equals(spaces.role(u, s)))
            throw new Problem(403, "FORBIDDEN", "仅空间OWNER可以审批豁免");
        if (!"ACTIVE".equals(locked.get("status")))
            throw new Problem(409, "SPACE_ARCHIVED", "归档空间不能审批");
        if (!Objects.equals(locked.get("draftRev"), c.get("draftRev")))
            throw new Problem(409, "DRAFT_REV_CONFLICT", "草稿已变更，须重新冻结并审批");
        if (!"SUBSET_EXCEEDED".equals(kind)) throw Problem.bad("仅允许超范围构造豁免");
        var actual = consistency.check(candidates.payload(c));
        if (!"NOT_CHECKED".equals(actual.get("conclusion")) || !kind.equals(actual.get("reason")))
            throw new Problem(409, "WAIVER_NOT_ALLOWED", "实际检查结果不允许豁免");
        UUID waiver = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " gate_waiver(id,candidate_id,candidate_sha256,kind,reason,approver,draft_rev)"
                    + " VALUES(?,?,?,?,?,?,?)",
                waiver,
                id,
                c.get("sha256"),
                kind,
                reason,
                u,
                c.get("draftRev"));
        spaces.audit(u, s, "CANDIDATE_WAIVER", ((Number) c.get("draftRev")).longValue());
        return Map.of(
                "id",
                waiver.toString(),
                "candidateSha256",
                c.get("sha256"),
                "kind",
                kind,
                "reason",
                reason,
                "approver",
                u.toString());
    }
}
