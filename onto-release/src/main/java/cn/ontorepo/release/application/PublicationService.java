package cn.ontorepo.release.application;

import cn.ontorepo.artifact.*;
import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.contract.ContractGenerator;
import cn.ontorepo.contract.ContractProblem;
import cn.ontorepo.knowledge.application.FrozenKnowledge;
import cn.ontorepo.operation.application.ConflictService;
import cn.ontorepo.release.domain.OutboxPolicy;
import cn.ontorepo.release.domain.ReleaseGatePolicy;
import cn.ontorepo.release.infra.JdbcOutboxStore;
import cn.ontorepo.space.application.*;
import cn.ontorepo.trace.application.*;
import cn.ontorepo.trace.infra.CanonicalPayload;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 五门禁、不可变制品计划与短事务发布；网络和Lucene编译均不进入提交事务。 */
@Service
public class PublicationService {
    private final Db db;
    private final SpaceService spaces;
    private final CandidateService candidates;
    private final CandidateTestService tests;
    private final ConflictService conflicts;
    private final ConsistencyChecker consistency;
    private final ShaclChecker shacl;
    private final SupplyService supply;
    private final FrozenBundleLoader bundles;
    private final ArtifactStore artifacts;
    private final FrozenKnowledge knowledge;
    private final CanonicalPayload codec;
    private final TransactionTemplate tx;
    private final ReleaseHistoryService history;
    private final JdbcOutboxStore outbox;
    private final String baseUrl;

    public PublicationService(
            Db db,
            SpaceService spaces,
            CandidateService candidates,
            CandidateTestService tests,
            ConflictService conflicts,
            ConsistencyChecker consistency,
            ShaclChecker shacl,
            SupplyService supply,
            FrozenBundleLoader bundles,
            ArtifactStore artifacts,
            FrozenKnowledge knowledge,
            CanonicalPayload codec,
            PlatformTransactionManager tm,
            ReleaseHistoryService history,
            JdbcOutboxStore outbox,
            @Value("${onto.service-base-url:http://127.0.0.1:18182}") String baseUrl) {
        this.db = db;
        this.spaces = spaces;
        this.candidates = candidates;
        this.tests = tests;
        this.conflicts = conflicts;
        this.consistency = consistency;
        this.shacl = shacl;
        this.supply = supply;
        this.bundles = bundles;
        this.artifacts = artifacts;
        this.knowledge = knowledge;
        this.codec = codec;
        this.tx = new TransactionTemplate(tm);
        this.history = history;
        this.outbox = outbox;
        this.baseUrl = baseUrl;
    }

    /** 安全发布概览，不返回内部对象键、源配置或原始bundle。 */
    public Map<String, Object> publication(UUID user, UUID id) {
        var c = candidates.candidate(user, id);
        var result = new LinkedHashMap<String, Object>();
        for (String field :
                List.of(
                        "id",
                        "sha256",
                        "draftRev",
                        "status",
                        "preReleaseId",
                        "preVersionNo",
                        "review",
                        "releaseId"))
            result.put(
                    switch (field) {
                        case "id" -> "candidateId";
                        case "sha256" -> "candidateSha256";
                        default -> field;
                    },
                    c.get(field));
        result.put("capability", c.get("capability"));
        result.put("checks", checks(id));
        result.put(
                "waivers",
                db.list(
                        "SELECT candidate_sha256,reason,approver actor_id,created_at FROM"
                                + " gate_waiver WHERE candidate_id=? ORDER BY created_at",
                        id));
        result.put("artifacts", safeArtifacts(c));
        return result;
    }

    public List<Map<String, Object>> checks(UUID id) {
        return db.list(
                "SELECT DISTINCT ON(kind) kind,conclusion,reason,candidate_sha256,checked_at,report"
                        + " FROM publication_check WHERE candidate_id=? ORDER BY kind,id DESC",
                id);
    }

    public static List<Map<String, Object>> safeArtifacts(Map<String, Object> row) {
        var out = new ArrayList<Map<String, Object>>();
        for (var e :
                Map.of(
                                "BUNDLE",
                                "bundleRef",
                                "MANIFEST",
                                "manifestRef",
                                "CONTEXT_PACK",
                                "contextPackRef",
                                "KNOWLEDGE_INDEX",
                                "indexRef")
                        .entrySet())
            if (row.get(e.getValue()) instanceof Map<?, ?> ref)
                out.add(
                        Map.of(
                                "kind",
                                e.getKey(),
                                "sha256",
                                ref.get("sha256"),
                                "size",
                                ref.get("size"),
                                "ready",
                                true));
        out.sort(Comparator.comparing(x -> x.get("kind").toString()));
        return out;
    }

    /** 机器校验只运行三门禁；CONTRACT失败不会先执行任何验收用例。 */
    public Map<String, Object> validate(UUID user, UUID id) {
        var c = managed(user, id);
        requireStage(c, "VALIDATING", "TESTING", "FAILED");
        var p = bundles.payload(c);
        tx.executeWithoutResult(status -> lockEditor(user, c, "VALIDATING", "TESTING", "FAILED"));
        var reports = new ArrayList<Map<String, Object>>();
        var conflict = conflicts.inspect(p);
        Map<String, Object> shape;
        try {
            shape = shacl.check(p);
        } catch (RuntimeException e) {
            shape = Map.of("conforms", false, "reason", "SHACL_ENGINE_ERROR");
        }
        boolean passed =
                ((List<?>) conflict.get("errors")).isEmpty()
                        && Boolean.TRUE.equals(shape.get("conforms"));
        reports.add(
                report(
                        "CONFLICT",
                        passed ? "PASSED" : "FAILED",
                        passed
                                ? ""
                                : "SHACL_ENGINE_ERROR".equals(shape.get("reason"))
                                        ? "SHACL_ENGINE_ERROR"
                                        : "MODEL_CONFLICT",
                        Map.of("conflicts", conflict, "shacl", shape)));
        var actual = consistency.check(p);
        String raw = actual.get("conclusion").toString();
        reports.add(
                report(
                        "CONSISTENCY",
                        "CONSISTENT".equals(raw)
                                ? "PASSED"
                                : "NOT_CHECKED".equals(raw) ? "NOT_CHECKED" : "FAILED",
                        Objects.toString(actual.get("reason"), ""),
                        actual));
        reports.add(contract(c, p));
        tx.executeWithoutResult(
                status -> {
                    lockEditor(user, c, "VALIDATING", "TESTING", "FAILED");
                    for (var r : reports) saveCheck(c, r);
                    boolean ok =
                            reports.stream().allMatch(r -> "PASSED".equals(r.get("conclusion")));
                    db.update(
                            "UPDATE publish_candidate SET status=?,failure_code=? WHERE id=?",
                            ok ? "TESTING" : "FAILED",
                            ok ? null : "MACHINE_GATE_BLOCKED",
                            id);
                    spaces.audit(
                            user,
                            Input.uuid(c.get("spaceId")),
                            "CANDIDATE_VALIDATE",
                            ((Number) c.get("draftRev")).longValue(),
                            new AuditDetail(
                                    "CANDIDATE",
                                    id.toString(),
                                    Map.of("sha256", c.get("sha256")),
                                    null,
                                    null,
                                    ok ? "SUCCEEDED" : "FAILED"));
                });
        return publication(user, id);
    }

    /** 只有真实完成的正数冻结用例才能产生发布TEST证据；旧本地零用例结果不提升发布资格。 */
    public Map<String, Object> tests(UUID user, UUID id) {
        var c = candidates.candidate(user, id);
        if (c.get("preReleaseId") == null) return tests.tests(user, id);
        tx.executeWithoutResult(
                status -> lockEditor(user, c, "VALIDATING", "TESTING", "REVIEWING", "FAILED"));
        var p = bundles.payload(c);
        var contractual = contract(c, p);
        tx.executeWithoutResult(
                status -> {
                    lockEditor(user, c, "VALIDATING", "TESTING", "REVIEWING", "FAILED");
                    saveCheck(c, contractual);
                });
        if ("CONTRACT".equals(c.get("capability"))
                && !"PASSED".equals(contractual.get("conclusion"))) {
            tx.executeWithoutResult(
                    status -> {
                        lockEditor(user, c, "VALIDATING", "TESTING", "REVIEWING", "FAILED");
                        db.update(
                                "UPDATE publish_candidate SET"
                                        + " status='FAILED',failure_code='CONTRACT_FAILED' WHERE"
                                        + " id=?",
                                id);
                    });
            return Map.of(
                    "candidateSha256",
                    c.get("sha256"),
                    "passed",
                    false,
                    "results",
                    List.of(
                            Map.of(
                                    "caseId",
                                    "",
                                    "name",
                                    "契约门禁",
                                    "passed",
                                    false,
                                    "executionId",
                                    "",
                                    "message",
                                    "冻结契约未通过，未执行用例")));
        }
        var result = new LinkedHashMap<String, Object>(tests.tests(user, id));
        int enabled =
                (int)
                        codec.rows(p, "testCases").stream()
                                .filter(r -> Boolean.TRUE.equals(r.get("enabled")))
                                .count();
        var results = (List<?>) result.get("results");
        int completed =
                (int)
                        results.stream()
                                .filter(
                                        r ->
                                                r instanceof Map<?, ?> m
                                                        && !Objects.toString(
                                                                        m.get("executionId"), "")
                                                                .isBlank())
                                .count();
        boolean noOperations = operationCount(c) == 0;
        boolean passed =
                noOperations
                        ? completed == 0 && Boolean.TRUE.equals(result.get("passed"))
                        : enabled > 0
                                && completed == enabled
                                && Boolean.TRUE.equals(result.get("passed"));
        // 旧候选仍走原LOCAL_TEST返回值；零操作纯模型候选按空真通过（DEC-1=A），其余零用例仍明确未通过。
        result.put("passed", passed);
        String gateReason =
                noOperations
                        ? (passed ? "NO_OPERATION_NO_CASE" : "NO_OPERATION_NO_CASE_FAILED")
                        : enabled == 0 ? "NO_ENABLED_TEST_CASES" : passed ? "" : "TEST_FAILED";
        if (!gateReason.isEmpty()) result.put("reason", gateReason);
        tx.executeWithoutResult(
                status -> {
                    var current =
                            lockEditor(user, c, "VALIDATING", "TESTING", "REVIEWING", "FAILED");
                    db.update(
                            "INSERT INTO"
                                + " test_run(id,candidate_id,candidate_sha256,enabled_count,completed_count,passed,report)"
                                + " VALUES(?,?,?,?,?,?,?::jsonb)",
                            UUID.randomUUID(),
                            id,
                            c.get("sha256"),
                            enabled,
                            completed,
                            passed,
                            codec.json(result));
                    saveCheck(
                            c,
                            report(
                                    "TEST",
                                    passed ? "PASSED" : "FAILED",
                                    gateReason,
                                    result));
                    // 本地用例可先于正式机器校验执行；TEST通过不能补造缺失门禁，也不能清除真实失败。
                    // 在写回锁内读取最新同摘要结果，避免并行validate已写入的失败被较早开始的tests覆盖。
                    String nextStage = passed ? stageAfterPassedTests(current) : "FAILED";
                    db.update(
                            "UPDATE publish_candidate SET status=?,failure_code=? WHERE id=?",
                            nextStage,
                            passed
                                    ? "FAILED".equals(nextStage) ? "MACHINE_GATE_BLOCKED" : null
                                    : gateReason,
                            id);
                });
        return result;
    }

    /**
     * 测试通过后的阶段只由已有机器证据决定：真实失败优先，未检查保留VALIDATING。
     * SUBSET_EXCEEDED仅允许进入等待人工资格的阶段，绝不把NOT_CHECKED改为PASSED；
     * review/prepare仍必须通过绑定摘要、当前修订和OWNER身份的原豁免门禁。
     */
    private String stageAfterPassedTests(Map<String, Object> candidate) {
        var machineChecks = new HashMap<String, Map<String, Object>>();
        for (var check : checks(Input.uuid(candidate.get("id"))))
            if (Objects.equals(candidate.get("sha256"), check.get("candidateSha256")))
                machineChecks.put(check.get("kind").toString(), check);
        boolean missing = false;
        for (String kind : List.of("CONFLICT", "CONSISTENCY", "CONTRACT")) {
            var check = machineChecks.get(kind);
            if (check == null) {
                missing = true;
                continue;
            }
            boolean eligible =
                    "PASSED".equals(check.get("conclusion"))
                            || "CONSISTENCY".equals(kind)
                                    && "NOT_CHECKED".equals(check.get("conclusion"))
                                    && "SUBSET_EXCEEDED".equals(check.get("reason"));
            if (!eligible) return "FAILED";
        }
        return missing ? "VALIDATING" : "REVIEWING";
    }

    /** OWNER复核独立于机器检查，理由、摘要及修订缺一不可。 */
    public Map<String, Object> review(UUID user, UUID id, Map<String, Object> body) {
        String reason = Input.text(body, "reason", 4000);
        tx.executeWithoutResult(
                status -> {
                    var c = locked(user, id, body, "candidateSha256");
                    requireStage(c, "REVIEWING");
                    requireGates(c, true);
                    var review =
                            Map.of(
                                    "actorId",
                                    user.toString(),
                                    "reason",
                                    reason,
                                    "reviewedAt",
                                    java.time.Instant.now().toString());
                    saveCheck(c, report("REVIEW", "PASSED", "", review));
                    db.update(
                            "UPDATE publish_candidate SET review=?::jsonb,status='REVIEWING' WHERE"
                                    + " id=?",
                            codec.json(review),
                            id);
                    spaces.audit(
                            user,
                            Input.uuid(c.get("spaceId")),
                            "CANDIDATE_REVIEW",
                            ((Number) c.get("draftRev")).longValue(),
                            new AuditDetail(
                                    "CANDIDATE",
                                    id.toString(),
                                    Map.of("sha256", c.get("sha256")),
                                    null,
                                    null,
                                    "SUCCEEDED"));
                });
        return publication(user, id);
    }

    /** 将确切编译字节先保存为不可变计划，允许MinIO上传故障/重启后幂等续传。 */
    public Map<String, Object> prepare(UUID user, UUID id, Map<String, Object> body) {
        var c =
                tx.execute(
                        status -> {
                            var row = locked(user, id, body, "candidateSha256");
                            requireStage(row, "REVIEWING", "READY_TO_COMMIT");
                            requireGates(row, false);
                            if ("COMMITTED".equals(row.get("status")))
                                throw new Problem(409, "ALREADY_COMMITTED", "候选已提交");
                            // READY重试仅重新核验既有计划，不能倒退到人工复核阶段。
                            return row;
                        });
        var p = bundles.payload(c);
        if (db.list("SELECT kind FROM candidate_artifact_plan WHERE candidate_id=?", id)
                .isEmpty()) {
            var bytes = compilePlan(c, p);
            tx.executeWithoutResult(
                    status -> {
                        var latest = locked(user, id, body, "candidateSha256");
                        requireStage(latest, "REVIEWING", "READY_TO_COMMIT");
                        requireGates(latest, false);
                        for (var item : bytes.entrySet()) {
                            String key =
                                    "release/"
                                            + c.get("preReleaseId")
                                            + "/"
                                            + item.getKey().toLowerCase(java.util.Locale.ROOT)
                                            + (item.getKey().equals("KNOWLEDGE_INDEX")
                                                    ? ".zip"
                                                    : ".json");
                            db.update(
                                    "INSERT INTO"
                                        + " candidate_artifact_plan(candidate_id,kind,object_key,sha256,size,bytes)"
                                        + " VALUES(?,?,?,?,?,?) ON CONFLICT(candidate_id,kind) DO"
                                        + " NOTHING",
                                    id,
                                    item.getKey(),
                                    key,
                                    AuthService.digest(item.getValue()),
                                    item.getValue().length,
                                    item.getValue());
                        }
                    });
        }
        try {
            var refs = new HashMap<String, Object>();
            bundles.bytes(c);
            for (var plan :
                    db.list(
                            "SELECT * FROM candidate_artifact_plan WHERE candidate_id=? ORDER BY"
                                    + " kind",
                            id)) {
                var stored =
                        artifacts.storeImmutable(
                                plan.get("objectKey").toString(),
                                (byte[]) plan.get("bytes"),
                                plan.get("kind").equals("KNOWLEDGE_INDEX")
                                        ? "application/zip"
                                        : "application/json");
                if (!stored.sha256().equals(plan.get("sha256")))
                    throw new Problem(409, "ARTIFACT_CORRUPT", "制品计划摘要不匹配");
                artifacts.loadVerified(stored);
                refs.put(
                        plan.get("kind").toString(),
                        Map.of(
                                "key",
                                stored.key(),
                                "sha256",
                                stored.sha256(),
                                "size",
                                stored.size()));
            }
            if (refs.size() != 3) throw new Problem(409, "ARTIFACT_NOT_READY", "正式制品尚未完整归档");
            tx.executeWithoutResult(
                    status -> {
                        var latest = locked(user, id, body, "candidateSha256");
                        requireStage(latest, "REVIEWING", "READY_TO_COMMIT");
                        requireGates(latest, false);
                        db.update(
                                "UPDATE publish_candidate SET"
                                    + " manifest_ref=?::jsonb,context_pack_ref=?::jsonb,index_ref=?::jsonb,status='READY_TO_COMMIT',failure_code=NULL"
                                    + " WHERE id=?",
                                codec.json(refs.get("MANIFEST")),
                                codec.json(refs.get("CONTEXT_PACK")),
                                codec.json(refs.get("KNOWLEDGE_INDEX")),
                                id);
                    });
        } catch (ArtifactProblem failure) {
            throw new Problem(
                    "ARTIFACT_STORAGE_UNAVAILABLE".equals(failure.code()) ? 503 : 409,
                    failure.code(),
                    "正式制品归档或校验失败，请检查制品状态后重试");
        }
        return publication(user, id);
    }

    /** 四对象实检在锁外；锁内二次核对引用与五门禁，再一次事务完成发布五写。 */
    public Map<String, Object> commit(UUID user, UUID id, Map<String, Object> body) {
        var initial = managed(user, id);
        owner(user, Input.uuid(initial.get("spaceId")));
        if (!Objects.equals(initial.get("sha256"), body.get("expectedSha256")))
            throw new Problem(409, "CANDIDATE_SHA_CONFLICT", "候选摘要不匹配");
        if ("COMMITTED".equals(initial.get("status")))
            return releaseSummary(bundles.release(Input.uuid(initial.get("releaseId")), false));
        if (!"READY_TO_COMMIT".equals(initial.get("status")))
            throw new Problem(409, "CANDIDATE_NOT_READY", "候选尚未完成正式制品准备");
        var p = bundles.payload(initial);
        for (String key : List.of("bundleRef", "manifestRef", "contextPackRef", "indexRef")) {
            if (!(initial.get(key) instanceof Map<?, ?> ref))
                throw new Problem(409, "ARTIFACT_NOT_READY", "正式制品引用缺失");
            bundles.verified(ref);
        }
        UUID release = Input.uuid(initial.get("preReleaseId"));
        return tx.execute(
                status -> {
                    var c = db.one("SELECT * FROM publish_candidate WHERE id=?", id);
                    if ("COMMITTED".equals(c.get("status")))
                        return releaseSummary(
                                bundles.release(Input.uuid(c.get("releaseId")), false));
                    c = locked(user, id, body, "expectedSha256", true);
                    if ("COMMITTED".equals(c.get("status")))
                        return releaseSummary(
                                bundles.release(Input.uuid(c.get("releaseId")), false));
                    if (!"READY_TO_COMMIT".equals(c.get("status")))
                        throw new Problem(409, "CANDIDATE_NOT_READY", "候选状态已改变");
                    for (String key :
                            List.of("bundleRef", "manifestRef", "contextPackRef", "indexRef"))
                        if (!Objects.equals(initial.get(key), c.get(key)))
                            throw new Problem(409, "ARTIFACT_CHANGED", "制品引用已改变");
                    requireGates(c, false);
                    UUID space = Input.uuid(c.get("spaceId"));
                    db.update(
                            "INSERT INTO"
                                + " ontology_release(id,space_id,version_no,candidate_id,status,bundle_ref,manifest_ref,context_pack_ref,index_ref,bundle_sha256,manifest_sha256,published_by)"
                                + " VALUES(?,?,?,?,'READY',?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?)",
                            release,
                            space,
                            c.get("preVersionNo"),
                            id,
                            codec.json(c.get("bundleRef")),
                            codec.json(c.get("manifestRef")),
                            codec.json(c.get("contextPackRef")),
                            codec.json(c.get("indexRef")),
                            c.get("sha256"),
                            ((Map<?, ?>) c.get("manifestRef")).get("sha256"),
                            user);
                    db.update(
                            "INSERT INTO space_version(id,space_id,release_id,version_no,status)"
                                    + " VALUES(?,?,?,?,'PUBLISHED')",
                            UUID.randomUUID(),
                            space,
                            release,
                            c.get("preVersionNo"));
                    db.update(
                            "INSERT INTO context_pack(id,release_id,artifact_ref)"
                                    + " VALUES(?,?,?::jsonb)",
                            UUID.randomUUID(),
                            release,
                            codec.json(c.get("contextPackRef")));
                    for (var entity : history.project(p))
                        db.update(
                                "INSERT INTO release_entity(release_id,kind,entity_key,value)"
                                        + " VALUES(?,?,?,?::jsonb)",
                                release,
                                entity.get("kind"),
                                entity.get("key"),
                                codec.json(entity.get("value")));
                    for (var op : codec.rows(p, "operations"))
                        if (op.get("assistantTool") != null) {
                            var tool = (Map<?, ?>) op.get("assistantTool");
                            String route =
                                    "/onto/r/"
                                            + ((Map<?, ?>) p.get("space")).get("code")
                                            + "/releases/"
                                            + release
                                            + "/ops/"
                                            + op.get("code");
                            db.update(
                                    "INSERT INTO"
                                        + " service_endpoint(id,release_id,op_code,route,description,exec_attrs)"
                                        + " VALUES(?,?,?,?,?,?::jsonb)",
                                    UUID.randomUUID(),
                                    release,
                                    op.get("code"),
                                    route,
                                    Objects.toString(op.get("description"), ""),
                                    codec.json(tool.get("exec")));
                        }
                    enqueue(
                            space,
                            release,
                            OutboxPolicy.Type.RELEASED,
                            "READY",
                            Map.of(
                                    "releaseId",
                                    release.toString(),
                                    "manifestSha256",
                                    ((Map<?, ?>) c.get("manifestRef")).get("sha256"),
                                    "bundleSha256",
                                    c.get("sha256")));
                    db.update(
                            "UPDATE publish_candidate SET"
                                + " status='COMMITTED',release_id=?,allocation_status='COMMITTED'"
                                + " WHERE id=?",
                            release,
                            id);
                    spaces.audit(
                            user,
                            space,
                            "RELEASE_COMMIT",
                            ((Number) c.get("draftRev")).longValue(),
                            new AuditDetail(
                                    "RELEASE",
                                    release.toString(),
                                    Map.of(
                                            "candidateId", id.toString(),
                                            "versionNo", c.get("preVersionNo"),
                                            "bundleSha256", c.get("sha256")),
                                    null,
                                    c.get("sha256").toString(),
                                    "SUCCEEDED"));
                    return releaseSummary(bundles.release(release, false));
                });
    }

    public Map<String, Object> cancel(UUID user, UUID id, Map<String, Object> body) {
        Input.text(body, "reason", 4000);
        tx.executeWithoutResult(
                status -> {
                    var c = locked(user, id, body, "candidateSha256");
                    if ("COMMITTED".equals(c.get("status")))
                        throw new Problem(409, "ALREADY_COMMITTED", "已提交候选不能取消");
                    db.update(
                            "UPDATE publish_candidate SET"
                                    + " status='CANCELLED',allocation_status='VOID' WHERE id=?",
                            id);
                    spaces.audit(
                            user,
                            Input.uuid(c.get("spaceId")),
                            "CANDIDATE_CANCEL",
                            ((Number) c.get("draftRev")).longValue(),
                            new AuditDetail(
                                    "CANDIDATE",
                                    id.toString(),
                                    Map.of("sha256", c.get("sha256")),
                                    null,
                                    null,
                                    "CANCELLED"));
                });
        return publication(user, id);
    }

    private Map<String, Object> contract(Map<String, Object> c, Map<String, Object> p) {
        boolean modelOnly = "MODEL_ONLY".equals(c.get("capability"));
        if (modelOnly)
            // 纯模型发布不生成供给契约；供给端点仍独立拒绝 MODEL_ONLY。
            return report(
                    "CONTRACT",
                    "PASSED",
                    "MODEL_ONLY_NO_CONTRACT",
                    Map.of("manifestCount", 0, "contractMode", "MODEL_ONLY"));
        try {
            var manifests = supply.manifests(p, c.get("id").toString());
            return report(
                    "CONTRACT",
                    "PASSED",
                    "",
                    Map.of(
                            "manifestCount",
                            manifests.size(),
                            "contractMode",
                            "CONTRACT"));
        } catch (ContractProblem e) {
            return report(
                    "CONTRACT",
                    "FAILED",
                    e.code(),
                    Map.of("field", e.field(), "reason", e.reason()));
        } catch (Problem e) {
            return report("CONTRACT", "FAILED", e.code, Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> report(
            String kind, String conclusion, String reason, Object report) {
        return Map.of("kind", kind, "conclusion", conclusion, "reason", reason, "report", report);
    }

    private void saveCheck(Map<String, Object> c, Map<String, Object> r) {
        db.update(
                "INSERT INTO"
                    + " publication_check(candidate_id,candidate_sha256,kind,conclusion,reason,report)"
                    + " VALUES(?,?,?,?,?,?::jsonb)",
                Input.uuid(c.get("id")),
                c.get("sha256"),
                r.get("kind"),
                r.get("conclusion"),
                r.get("reason"),
                codec.json(r.get("report")));
    }

    private Map<String, Object> managed(UUID user, UUID id) {
        var c = candidates.candidate(user, id);
        if (c.get("preReleaseId") == null)
            throw new Problem(409, "REFREEZE_REQUIRED", "历史候选需重新冻结后方可发布");
        return c;
    }

    private Map<String, Object> lockEditor(UUID user, Map<String, Object> c, String... allowed) {
        UUID space = Input.uuid(c.get("spaceId"));
        db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", space);
        // 空间锁排队期间可能被降权，权限只能在取锁后作为本次写入依据。
        spaces.editor(user, space);
        var latest =
                db.one(
                        "SELECT * FROM publish_candidate WHERE id=? FOR" + " UPDATE",
                        Input.uuid(c.get("id")));
        if (Set.of("COMMITTED", "CANCELLED").contains(latest.get("status"))
                || "VOID".equals(latest.get("allocationStatus")))
            throw new Problem(409, "CANDIDATE_TERMINAL", "候选已结束");
        requireStage(latest, allowed);
        return latest;
    }

    /** 阶段资格在开始及锁外工作写回时均复核，防止旧检查覆盖已完成的较后阶段。 */
    private static void requireStage(Map<String, Object> candidate, String... allowed) {
        if (!Arrays.asList(allowed).contains(candidate.get("status")))
            throw new Problem(409, "CANDIDATE_STAGE_CONFLICT", "候选阶段已改变，请刷新发布状态后继续");
    }

    /** 只包围纯编译边界；数据库/授权异常保持其原分类，不能误报成可重试的存储故障。 */
    private Map<String, byte[]> compilePlan(Map<String, Object> c, Map<String, Object> p) {
        try {
            var frozenSpace = (Map<?, ?>) p.get("space");
            var formal = new ArrayList<JsonNode>();
            if (!(c.get("manifestPreview") instanceof List<?> previews))
                throw new Problem(409, "CONTRACT_NOT_AVAILABLE", "冻结契约预览缺失");
            else
                for (Object preview : previews)
                    formal.add(
                            new ContractGenerator()
                                    .formalize(
                                            new com.fasterxml.jackson.databind.ObjectMapper()
                                                    .valueToTree(preview),
                                            baseUrl,
                                            frozenSpace.get("code").toString(),
                                            c.get("preReleaseId").toString(),
                                            "v" + c.get("preVersionNo"),
                                            c.get("sha256").toString()));
            return Map.of(
                    "MANIFEST",
                    codec.encode(Map.of("manifests", formal)),
                    "CONTEXT_PACK",
                    codec.encode(knowledge.context(p)),
                    "KNOWLEDGE_INDEX",
                    knowledge.archive(p));
        } catch (Problem | ContractProblem failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Problem(500, "ARTIFACT_COMPILE_FAILED", "正式制品编译失败，候选仍保留当前阶段，可修复原因后重试");
        }
    }

    private Map<String, Object> locked(UUID user, UUID id, Map<String, Object> b, String shaField) {
        return locked(user, id, b, shaField, false);
    }

    /** 锁外编译/上传后仍须重新检查终态；只有commit能读取已提交结果以维持幂等。 */
    private Map<String, Object> locked(
            UUID user, UUID id, Map<String, Object> b, String shaField, boolean allowCommitted) {
        var c = managed(user, id);
        UUID space = Input.uuid(c.get("spaceId"));
        spaces.lock(user, space, Input.number(b, "expectedDraftRev"), false);
        owner(user, space);
        c = db.one("SELECT * FROM publish_candidate WHERE id=? FOR UPDATE", id);
        if (!Objects.equals(c.get("sha256"), b.get(shaField))
                || ((Number) c.get("draftRev")).longValue() != Input.number(b, "expectedDraftRev"))
            throw new Problem(409, "CANDIDATE_SHA_CONFLICT", "草稿修订或候选摘要不符，须重新冻结");
        if ("CANCELLED".equals(c.get("status")))
            throw new Problem(409, "CANDIDATE_CANCELLED", "候选已取消");
        if ((!allowCommitted && "COMMITTED".equals(c.get("status")))
                || "VOID".equals(c.get("allocationStatus")))
            throw new Problem(409, "CANDIDATE_TERMINAL", "候选已结束，不能回退状态或复用作废序号");
        return c;
    }

    private void owner(UUID user, UUID space) {
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "仅空间OWNER可管理发布");
    }

    private void requireGates(Map<String, Object> c, boolean reviewing) {
        var checks = new ArrayList<ReleaseGatePolicy.Check>();
        for (var r : checks(Input.uuid(c.get("id"))))
            if (!reviewing || !"REVIEW".equals(r.get("kind")))
                checks.add(
                        new ReleaseGatePolicy.Check(
                                ReleaseGatePolicy.Kind.valueOf(r.get("kind").toString()),
                                r.get("candidateSha256").toString(),
                                ReleaseGatePolicy.Conclusion.valueOf(
                                        r.get("conclusion").toString()),
                                Objects.toString(r.get("reason"), "")));
        if (reviewing)
            checks.add(
                    new ReleaseGatePolicy.Check(
                            ReleaseGatePolicy.Kind.REVIEW,
                            c.get("sha256").toString(),
                            ReleaseGatePolicy.Conclusion.PASSED,
                            ""));
        var runs =
                db.list(
                        "SELECT enabled_count,completed_count,passed FROM test_run WHERE"
                                + " candidate_id=? AND candidate_sha256=? ORDER BY created_at DESC"
                                + " LIMIT 1",
                        Input.uuid(c.get("id")),
                        c.get("sha256"));
        int count =
                runs.isEmpty()
                                || !Boolean.TRUE.equals(runs.getFirst().get("passed"))
                                || !Objects.equals(
                                        runs.getFirst().get("enabledCount"),
                                        runs.getFirst().get("completedCount"))
                        ? 0
                        : ((Number) runs.getFirst().get("enabledCount")).intValue();
        long current =
                ((Number)
                                db.one(
                                                "SELECT draft_rev FROM ontology_space WHERE id=?",
                                                Input.uuid(c.get("spaceId")))
                                        .get("draftRev"))
                        .longValue();
        var waivers =
                db.list(
                        "SELECT * FROM gate_waiver WHERE candidate_id=? AND candidate_sha256=? AND"
                                + " draft_rev=? ORDER BY created_at DESC LIMIT 1",
                        Input.uuid(c.get("id")),
                        c.get("sha256"),
                        current);
        var waiver =
                waivers.isEmpty()
                        ? null
                        : new ReleaseGatePolicy.Waiver(
                                "OWNER",
                                waivers.getFirst().get("candidateSha256").toString(),
                                ((Number) waivers.getFirst().get("draftRev")).longValue(),
                                waivers.getFirst().get("reason").toString());
        var result =
                ReleaseGatePolicy.evaluate(
                        c.get("sha256").toString(),
                        ((Number) c.get("draftRev")).longValue(),
                        current,
                        checks,
                        count,
                        operationCount(c),
                        waiver);
        if (!result.allowed())
            throw new Problem(409, "RELEASE_GATES_BLOCKED", "缺少验收用例、有效复核或必要门禁未通过");
    }

    /** 冻结载荷中的操作数量；零操作候选的 TEST 门禁按空真通过（DEC-1=A）。 */
    private int operationCount(Map<String, Object> c) {
        Object raw = c.get("payload");
        if (raw instanceof byte[] bytes)
            return codec.rows(codec.decode(bytes), "operations").size();
        return codec.rows(bundles.payload(c), "operations").size();
    }

    public void enqueue(
            UUID space, UUID release, OutboxPolicy.Type type, String bound, Object payload) {
        db.jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            outbox.append(
                                    connection, space, release, type, bound, codec.json(payload));
                            return null;
                        });
    }

    public static Map<String, Object> releaseSummary(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        for (String k :
                List.of(
                        "id",
                        "spaceId",
                        "versionNo",
                        "candidateId",
                        "status",
                        "bundleSha256",
                        "manifestSha256",
                        "publishedAt",
                        "publishedBy")) out.put(k, row.get(k));
        return out;
    }
}
