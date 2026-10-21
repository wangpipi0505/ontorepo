package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.AuditDetail;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.contract.ContractProblem;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.apache.jena.query.QueryCancelledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 冻结读取在空间行锁内完成；执行路径只解码候选，绝不回查类、属性或操作草稿。 */
@Service
public class CandidateService {
    private final Db db;
    private final SpaceService spaces;
    private final CanonicalPayload codec;
    private final FrozenExecutor executor;
    private final SupplyService supply;
    private final TermMatcher matcher;
    private final FrozenBundleLoader bundles;
    private final cn.ontorepo.artifact.ArtifactStore artifacts;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final long freezeTimeoutMs;
    private final cn.ontorepo.space.infra.SpaceAuthorizationGate authorizationGate;
    private final ExecutionFacts facts;

    public CandidateService(
            Db db,
            SpaceService spaces,
            CanonicalPayload codec,
            FrozenExecutor executor,
            SupplyService supply,
            TermMatcher matcher,
            FrozenBundleLoader bundles,
            cn.ontorepo.artifact.ArtifactStore artifacts,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            cn.ontorepo.space.infra.SpaceAuthorizationGate authorizationGate,
            ExecutionFacts facts,
            @org.springframework.beans.factory.annotation.Value(
                            "${onto.release.freeze-timeout-ms:900000}")
                    long freezeTimeoutMs) {
        this.db = db;
        this.spaces = spaces;
        this.codec = codec;
        this.executor = executor;
        this.supply = supply;
        this.matcher = matcher;
        this.bundles = bundles;
        this.artifacts = artifacts;
        this.authorizationGate = authorizationGate;
        this.facts = facts;
        this.transactions =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        if (freezeTimeoutMs < 1000 || freezeTimeoutMs > 3600000)
            throw new IllegalArgumentException("冻结超时必须在1秒至1小时之间");
        this.freezeTimeoutMs = freezeTimeoutMs;
    }

    /** 返回可见候选摘要，不向客户端传输原始冻结 BYTEA。 */
    public List<Map<String, Object>> list(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list(
                "SELECT"
                    + " id,space_id,draft_rev,sha256,status,created_at,format_version,capability,pre_release_id,pre_version_no,sha256"
                    + " bundle_sha256 FROM publish_candidate WHERE space_id=? ORDER BY created_at"
                    + " DESC",
                space);
    }

    /** 持有空间锁时一次读取完整模型与操作，冻结不改变草稿修订，审计与 BYTEA 同事务。 */
    public Map<String, Object> freeze(UUID user, UUID space, long rev) {
        var candidate = transactions.execute(status -> reserve(user, space, rev));
        UUID id = Input.uuid(candidate.get("id"));
        Map<String, Object> ref = null;
        try {
            byte[] bytes = (byte[]) candidate.get("payload");
            var archived =
                    artifacts.storeImmutable(
                            "candidate/" + id + "/bundle.json", bytes, "application/json");
            artifacts.loadVerified(archived);
            ref =
                    Map.of(
                            "key",
                            archived.key(),
                            "sha256",
                            archived.sha256(),
                            "size",
                            archived.size());
            var frozen = codec.decode(bytes);
            var model = cn.ontorepo.model.infra.RdfCodec.model(frozen);
            try {
                model.size();
            } finally {
                model.close();
            }
            Object preview =
                    "CONTRACT".equals(frozen.get("capability"))
                            ? supply.manifests(frozen, id.toString())
                            : List.of();
            Map<String, Object> readyRef = ref;
            transactions.executeWithoutResult(
                    status -> {
                        db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", space);
                        int changed =
                                db.update(
                                        "UPDATE publish_candidate SET"
                                            + " bundle_ref=?::jsonb,manifest_preview=?::jsonb,status='VALIDATING'"
                                            + " WHERE id=? AND status='FREEZING' AND"
                                            + " freeze_deadline>clock_timestamp()",
                                        codec.json(readyRef),
                                        codec.json(preview),
                                        id);
                        // 即使调度器尚未扫描，已超过持久期限的迟到结果也不能恢复发布资格。
                        if (changed == 0) expireFreezing(id);
                    });
        } catch (RuntimeException failure) {
            String code =
                    failure instanceof cn.ontorepo.artifact.ArtifactProblem p
                            ? p.code()
                            : failure instanceof ContractProblem p
                                    ? p.code()
                                    : "BUNDLE_COMPILE_FAILED";
            Map<String, Object> failedRef = ref;
            transactions.executeWithoutResult(
                    status -> {
                        db.one("SELECT id FROM ontology_space WHERE id=? FOR UPDATE", space);
                        if (expireFreezing(id) > 0) return;
                        // 失败补偿只能结束本次仍在冻结的任务，不能覆盖锁外等待期间已提交的取消事实。
                        int changed =
                                db.update(
                                        "UPDATE publish_candidate SET"
                                            + " bundle_ref=?::jsonb,status='FAILED',failure_code=?,allocation_status='VOID'"
                                            + " WHERE id=? AND status='FREEZING'",
                                        failedRef == null ? null : codec.json(failedRef),
                                        code,
                                        id);
                        if (changed > 0) spaces.audit(user, space, "CANDIDATE_FREEZE_FAILED", rev);
                    });
        }
        return db.one(
                "SELECT"
                    + " id,space_id,draft_rev,sha256,status,created_at,format_version,capability,pre_release_id,pre_version_no,sha256"
                    + " bundle_sha256 FROM publish_candidate WHERE id=?",
                id);
    }

    /** 只在短事务中读取整份草稿并预留标识；编译/存储失败也不回收已经提交的空间流水。 */
    private Map<String, Object> reserve(UUID user, UUID space, long rev) {
        spaces.lock(user, space, rev, true);
        Map<String, Object> payload = new TreeMap<>();
        payload.put(
                "space",
                db.one(
                        "SELECT"
                            + " id,code,name,namespace_uri,domain,status,draft_rev,created_at,guard_cfg"
                            + " FROM ontology_space WHERE id=?",
                        space));
        for (String[] table :
                new String[][] {
                    {"classes", "ont_class"},
                    {"properties", "ont_property"},
                    {"operations", "ont_operation"},
                    {"codebooks", "ont_codebook"},
                    {"relations", "ont_relation"},
                    {"constraints", "ont_constraint"},
                    {"rules", "ont_rule"},
                    {"strategies", "ont_strategy"},
                    {"testCases", "ont_test_case"},
                    {"rdfDocuments", "ont_rdf_document"},
                    {"knowledge", "ont_knowledge"}
                })
            payload.put(
                    table[0],
                    db.list("SELECT * FROM " + table[1] + " WHERE space_id=? ORDER BY id", space));
        payload.put(
                "datasources",
                db.list("SELECT * FROM datasource WHERE space_id=? ORDER BY id", space));
        payload.put(
                "metaSnapshots",
                db.list("SELECT * FROM meta_snapshot WHERE space_id=? ORDER BY id", space));
        payload.put(
                "dataFiles",
                db.list("SELECT * FROM data_file WHERE space_id=? ORDER BY id", space));
        Map<String, Object> mappingSnapshot = new TreeMap<>();
        for (String[] layer :
                new String[][] {
                    {"objectMappings", "OBJECT"},
                    {"propertyMappings", "PROPERTY"},
                    {"relationMappings", "RELATION"}
                }) {
            var definitions =
                    db
                            .list(
                                    "SELECT id,definition FROM data_mapping WHERE space_id=? AND"
                                            + " kind=? ORDER BY id",
                                    space,
                                    layer[1])
                            .stream()
                            .map(
                                    row -> {
                                        var definition =
                                                cn.ontorepo.mapping.domain.MappingValues.map(
                                                        row.get("definition"));
                                        definition.put("id", row.get("id"));
                                        return definition;
                                    })
                            .toList();
            payload.put(layer[0], definitions);
            mappingSnapshot.put(layer[0], definitions);
        }
        payload.put("mappingSha256", AuthService.digest(codec.encode(mappingSnapshot)));
        // 新格式显式记录供给能力；不修改任何已经存在的候选原始字节。
        payload.put("formatVersion", 3);
        boolean contract =
                codec.rows(payload, "operations").stream()
                        .anyMatch(op -> op.get("assistantTool") != null);
        payload.put("capability", contract ? "CONTRACT" : "MODEL_ONLY");
        byte[] bytes = codec.encode(payload);
        UUID id = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        long version =
                ((Number)
                                db.one(
                                                "SELECT next_version_no FROM ontology_space WHERE"
                                                        + " id=?",
                                                space)
                                        .get("nextVersionNo"))
                        .longValue();
        db.update("UPDATE ontology_space SET next_version_no=next_version_no+1 WHERE id=?", space);
        db.update(
                "INSERT INTO"
                    + " publish_candidate(id,space_id,draft_rev,sha256,payload,format_version,capability,pre_release_id,pre_version_no,status,allocation_status,freeze_deadline)"
                    + " VALUES(?,?,?,?,?,3,?,?,?,'FREEZING','RESERVED',clock_timestamp()+(? *"
                    + " interval '1 millisecond'))",
                id,
                space,
                rev,
                AuthService.digest(bytes),
                bytes,
                payload.get("capability"),
                release,
                version,
                freezeTimeoutMs);
        spaces.audit(user, space, "CANDIDATE_FREEZE", rev);
        return db.one("SELECT * FROM publish_candidate WHERE id=?", id);
    }

    /**
     * 恢复已崩溃或超时的冻结工作。数据库时钟是唯一期限依据；SKIP LOCKED使多实例扫描互不等待。 只回收FREEZING，不触碰人工复核及其可续传制品计划，已预分配序号永久作废。
     */
    public int recoverFreezing(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("冻结恢复批量必须为1至100");
        return transactions.execute(
                status -> {
                    var expired =
                            db.list(
                                    "SELECT id,space_id FROM publish_candidate WHERE"
                                            + " status='FREEZING' AND"
                                            + " freeze_deadline<=clock_timestamp() ORDER BY"
                                            + " freeze_deadline,id LIMIT ?",
                                    limit);
                    int recovered = 0;
                    for (var candidate : expired) {
                        // 与人工取消保持空间→候选的锁顺序；其他实例/人工正在处理的空间留给下一批。
                        if (!db.list(
                                        "SELECT id FROM ontology_space WHERE id=? FOR UPDATE SKIP"
                                                + " LOCKED",
                                        Input.uuid(candidate.get("spaceId")))
                                .isEmpty())
                            recovered += expireFreezing(Input.uuid(candidate.get("id")));
                    }
                    return recovered;
                });
    }

    /** 状态结束、版本作废和系统审计共享调用方事务；晚到成功与取消均不能被覆盖。 */
    private int expireFreezing(UUID id) {
        var expired =
                db.list(
                        "UPDATE publish_candidate SET"
                            + " status='FAILED',failure_code='FREEZE_INTERRUPTED',allocation_status='VOID'"
                            + " WHERE id=? AND status='FREEZING' AND"
                            + " freeze_deadline<=clock_timestamp() RETURNING space_id,draft_rev",
                        id);
        for (var candidate : expired)
            spaces.audit(
                    null,
                    Input.uuid(candidate.get("spaceId")),
                    "CANDIDATE_FREEZE_INTERRUPTED",
                    ((Number) candidate.get("draftRev")).longValue());
        return expired.size();
    }

    public Map<String, Object> candidate(UUID user, UUID id) {
        var c = db.one("SELECT * FROM publish_candidate WHERE id=?", id);
        spaces.role(user, Input.uuid(c.get("spaceId")));
        return c;
    }

    /** 常量时间比较摘要；不以重新序列化结果校验，避免掩盖原始字节损坏。 */
    public Map<String, Object> payload(Map<String, Object> c) {
        return bundles.payload(c);
    }

    /** 旧候选操作独立于当前草稿，读取前必须复核实际载荷字节摘要。 */
    public List<Map<String, Object>> operations(UUID user, UUID id) {
        return codec.rows(payload(candidate(user, id)), "operations");
    }

    /** 直接试执行的源查询不持草稿行锁；授权gate仍保证撤权、归档与实际访问的先后顺序。 */
    public Map<String, Object> trial(UUID user, UUID id, UUID operation) {
        return trial(user, id, operation, Map.of());
    }

    /** 试执行输入进入摘要，执行事实仍绑定不可变候选。 */
    public Map<String, Object> trial(UUID user, UUID id, UUID operation, Object input) {
        // 被既有事务调用时继续原空间锁语义；直接HTTP入口才建立独立session授权gate。
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) return run(user, id, operation, input, "TRIAL");
        UUID space = Input.uuid(candidate(user, id).get("spaceId"));
        return authorizationGate.shared(
                space,
                () -> runSubject(user, id, operation, input, "TRIAL", null, null, null, true));
    }

    /** 测试入口调用方必须建立事务，caller 仅由服务端选择。 */
    public Map<String, Object> run(
            UUID user, UUID id, UUID operation, Object input, String caller) {
        return run(user, id, operation, input, caller, null);
    }

    /** NL选择只接收问题，期望操作不会传入匹配器；所有入口共用下方冻结授权与执行。 */
    public Map<String, Object> run(
            UUID user, UUID id, UUID operation, Object input, String caller, String question) {
        return run(user, id, operation, input, caller, question, null);
    }

    /** SERVICE按操作码选择也必须处于同一事实边界，完整性失败时仍保留请求码与实际主体。 */
    private Map<String, Object> run(
            UUID user,
            UUID id,
            UUID operation,
            Object input,
            String caller,
            String question,
            String requestedCode) {
        return runSubject(user, id, operation, input, caller, question, requestedCode, null);
    }

    /** 候选/正式版本共用参数校验、冻结守卫、P3执行器与持久事实；subjectType不由客户端指定。 */
    private Map<String, Object> runSubject(
            UUID user,
            UUID id,
            UUID operation,
            Object input,
            String caller,
            String question,
            String requestedCode,
            Map<String, Object> release) {
        return runSubject(
                user, id, operation, input, caller, question, requestedCode, release, false);
    }

    /** 独立trial用两个短事务包围真实查询；其他入口继续由原调用方事务保护整条执行。 */
    private Map<String, Object> runSubject(
            UUID user,
            UUID id,
            UUID operation,
            Object input,
            String caller,
            String question,
            String requestedCode,
            Map<String, Object> release,
            boolean independentTrial) {
        boolean formal = release != null;
        var c = formal ? new java.util.LinkedHashMap<>(release) : candidate(user, id);
        if (formal) c.put("sha256", c.get("bundleSha256"));
        UUID space = Input.uuid(c.get("spaceId"));
        var locked =
                independentTrial
                        ? transactions.execute(
                                tx -> {
                                    var row =
                                            db.one(
                                                    "SELECT status,draft_rev FROM ontology_space"
                                                        + " WHERE id=? FOR UPDATE",
                                                    space);
                                    spaces.editor(user, space);
                                    if (!"ACTIVE".equals(row.get("status")))
                                        throw new Problem(409, "SPACE_ARCHIVED", "归档空间不能试执行");
                                    return row;
                                })
                        : db.one(
                                "SELECT status,draft_rev FROM ontology_space WHERE id=? FOR UPDATE",
                                space);
        // 等待空间锁期间可能发生撤权/弃用；必须在锁内重新读取实际执行版本与入口角色。
        if (formal) {
            c = new java.util.LinkedHashMap<>(bundles.release(id, false));
            c.put("sha256", c.get("bundleSha256"));
        } else spaces.editor(user, space);
        if (!locked.get("status").equals("ACTIVE"))
            throw new Problem(409, "SPACE_ARCHIVED", "归档空间不能试执行");
        UUID execution = UUID.randomUUID(), trace = UUID.randomUUID();
        long start = System.nanoTime();
        String status = "SUCCEEDED",
                error = null,
                opCode = requestedCode == null ? "UNKNOWN" : requestedCode;
        String frozenSpaceCode = null;
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, String>> steps = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> authorization = new ArrayList<>();
        authorization.add(auth(1, "AUTHENTICATION", true, "服务端已认证本地主体"));
        RuntimeException rejection = null;
        try {
            cn.ontorepo.data.domain.LabelScope endpointScope = null;
            Object endpointDataScope = null;
            if (formal) {
                if (!"READY".equals(c.get("status")))
                    throw new Problem(409, "RELEASE_NOT_READY", "该版本不可消费");
                var endpoints =
                        db.list(
                                "SELECT id,status FROM service_endpoint WHERE release_id=? AND"
                                        + " op_code=?",
                                id,
                                requestedCode);
                if (endpoints.isEmpty())
                    throw new Problem(403, "ENDPOINT_FORBIDDEN", "没有该端点的有效授予记录");
                if (!"ACTIVE".equals(endpoints.getFirst().get("status")))
                    throw new Problem(409, "ENDPOINT_DISABLED", "服务端点已停用");
                var grants =
                        new ArrayList<>(
                                db.list(
                                        "SELECT g.data_scope FROM service_endpoint e JOIN endpoint_grant g"
                                            + " ON g.endpoint_id=e.id WHERE e.release_id=? AND e.op_code=?"
                                            + " AND g.user_id=? AND g.status='ACTIVE'"
                                            + " AND (g.expires_at IS NULL OR g.expires_at>now())",
                                        id,
                                        requestedCode,
                                        user));
                grants.addAll(
                        db.list(
                                "SELECT g.data_scope FROM service_endpoint e JOIN endpoint_role_grant g"
                                    + " ON g.endpoint_id=e.id JOIN local_role r ON r.code=g.role_code"
                                    + " JOIN user_role ur ON ur.role_id=r.id WHERE e.release_id=?"
                                    + " AND e.op_code=? AND ur.user_id=? AND g.status='ACTIVE'"
                                    + " AND (g.expires_at IS NULL OR g.expires_at>now())",
                                id,
                                requestedCode,
                                user));
                if (grants.isEmpty()) throw new Problem(403, "ENDPOINT_FORBIDDEN", "没有该端点的有效授予记录");
                // 多条授予（用户+多个角色）逐项求交，任一范围都不能被另一条扩大。
                endpointScope = cn.ontorepo.data.domain.LabelScope.unrestricted();
                for (var grant : grants)
                    endpointScope =
                            cn.ontorepo.data.domain.LabelScope.intersect(
                                    endpointScope,
                                    cn.ontorepo.data.domain.LabelScope.parse(
                                            new com.fasterxml.jackson.databind.ObjectMapper()
                                                    .valueToTree(grant.get("dataScope"))));
                endpointDataScope = endpointScope.parameters();
            }
            authorization.add(
                    auth(
                            2,
                            "ENTRY_AUTHORIZATION",
                            true,
                            formal ? "显式端点授予存在且未撤销/过期" : "内部入口角色满足EDITOR及以上"));
            var p = payload(c);
            frozenSpaceCode = Objects.toString(((Map<?, ?>) p.get("space")).get("code"), "");
            steps.add(step("VERIFY_PAYLOAD", "SUCCEEDED", "冻结载荷摘要复核通过"));
            if (question != null) {
                try {
                    operation = matcher.select(p, question);
                    steps.add(step("TERM_MATCH", "SUCCEEDED", "冻结术语匹配操作 " + operation));
                } catch (Problem e) {
                    steps.add(step("TERM_MATCH", "FAILED", e.getMessage()));
                    throw e;
                }
            }
            final UUID selectedOperation = operation;
            var op =
                    codec.rows(p, "operations").stream()
                            .filter(
                                    o ->
                                            requestedCode != null
                                                    ? requestedCode.equals(o.get("code"))
                                                    : selectedOperation != null
                                                            && o.get("id")
                                                                    .equals(
                                                                            selectedOperation
                                                                                    .toString()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Problem(
                                                    formal || "SERVICE".equals(caller) ? 404 : 400,
                                                    "OPERATION_NOT_IN_CANDIDATE",
                                                    "操作不在当前候选中"));
            opCode = op.get("code").toString();
            operation = Input.uuid(op.get("id"));
            // 仅旧的无Schema/无助手操作沿用P1显式null视为空属性对象；声明nullable输入保留真实null。
            if (input == null && op.get("inputSchema") == null && op.get("assistantTool") == null)
                input = Map.of();
            var effectiveScope =
                    supply.authorize(
                            user,
                            space,
                            p,
                            op,
                            caller,
                            id.toString(),
                            endpointScope,
                            authorization);
            // MODEL_ONLY保留P1三步骤事实格式；实际授权仍必经上方统一检查。
            if (op.get("assistantTool") != null)
                steps.add(step("AUTHORIZE_GUARDS", "SUCCEEDED", "冻结守卫与当前空间授权复核通过"));
            supply.input(op, input);
            if (op.get("dataQuery") != null || formal) {
                var context = new java.util.LinkedHashMap<String, Object>();
                context.put("effectiveScope", effectiveScope.parameters());
                context.put(
                        "spaceScope",
                        db.one(
                                        "SELECT data_scope FROM space_grant WHERE space_id=? AND"
                                                + " user_id=?",
                                        space,
                                        user)
                                .get("dataScope"));
                context.put(
                        "endpointScope", formal ? endpointDataScope : context.get("spaceScope"));
                context.put(
                        "policyScopes",
                        cn.ontorepo.operation.domain.FrozenPolicy.of(p, op).scopes());
                context.put(
                        "sourceWhitelist",
                        codec.rows(p, "datasources").stream()
                                .map(
                                        source ->
                                                Map.of(
                                                        "sourceId",
                                                        source.get("id"),
                                                        "accessScope",
                                                        source.get("accessScope")))
                                .toList());
                context.put("origin", "SERVER_FROZEN_POLICY_AND_EXPLICIT_GRANTS");
                evidence.add(
                        Map.of(
                                "kind",
                                "AUTHORIZATION_CONTEXT",
                                "ref",
                                Map.of(
                                        "context",
                                        context,
                                        "sha256",
                                        AuthService.digest(codec.encode(context)))));
            }
            var evaluated =
                    op.get("dataQuery") == null
                            ? executor.execute(p, op, input)
                            : executor.execute(p, op, input, effectiveScope);
            columns.addAll(evaluated.columns());
            for (var row : evaluated.rows()) rows.add(new java.util.LinkedHashMap<>(row));
            steps.addAll(evaluated.steps());
            supply.output(op, Map.of("columns", columns, "rows", rows));
            steps.add(step("EXPLAIN", "SUCCEEDED", explain(op, rows, input)));
            evidence.addAll(evaluated.evidence());
            recordDefinitionEvidence(evidence, p, op, id, c.get("sha256").toString(), formal);
            authorization.add(auth(5, "SCOPE_INTERSECTION", true, "策略、端点、空间范围求交与冻结源白名单验证通过"));
            authorization.add(auth(6, "LABEL_FILTER", true, "已由统一冻结执行器强制非空标签与范围过滤"));
            if (op.get("dataQuery") != null || formal)
                evidence.add(
                        Map.of(
                                "kind",
                                "ONTOLOGY_VERSION",
                                "ref",
                                Map.of(
                                        "subjectType",
                                        formal ? "RELEASE" : "CANDIDATE",
                                        "subjectId",
                                        id.toString(),
                                        "sha256",
                                        c.get("sha256"))));
        } catch (cn.ontorepo.data.domain.DataProblem e) {
            status = "FAILED";
            error = e.code();
            columns.clear();
            rows.clear();
            steps.add(step("QUERY_EXEC", "FAILED", e.getMessage()));
            if (formal || "SERVICE".equals(caller))
                rejection =
                        new Problem(
                                formal && "SCOPE_EXCEEDED".equals(e.code()) ? 403 : 400,
                                e.code(),
                                e.getMessage());
        } catch (ContractProblem e) {
            status = "FAILED";
            error = e.code();
            rejection = e;
            columns.clear();
            rows.clear();
            steps.add(step("CONTRACT_VALIDATE", "FAILED", e.getMessage()));
        } catch (Problem e) {
            if (e.code.equals("OPERATION_NOT_IN_CANDIDATE") && !formal && !"SERVICE".equals(caller))
                throw e;
            status = "FAILED";
            error = e.code;
            columns.clear();
            rows.clear();
            steps.add(step("EXECUTE", "FAILED", e.getMessage()));
            if (formal
                    || "SERVICE".equals(caller)
                    || e.code.startsWith("GUARD_")
                    || e.code.equals("DATA_SCOPE_DENIED")
                    || e.code.equals("CONTRACT_NOT_AVAILABLE")
                    || e.code.equals("ASSISTANT_REQUIRED")) rejection = e;
        } catch (QueryCancelledException e) {
            status = "FAILED";
            error = "QUERY_TIMEOUT";
            columns.clear();
            rows.clear();
            steps.add(step("MODEL_SELECT", "FAILED", "查询超过执行时限"));
        } catch (Exception e) {
            status = "FAILED";
            error = "EXECUTION_FAILED";
            columns.clear();
            rows.clear();
            steps.add(step("MODEL_SELECT", "FAILED", "模型查询失败"));
        }
        if (!"SUCCEEDED".equals(status)) {
            int next = authorization.size() + 1;
            String stage =
                    next <= 2
                            ? "ENTRY_AUTHORIZATION"
                            : next == 3
                                    ? "FROZEN_GUARD_INTEGRITY"
                                    : next == 4 ? "SPACE_READ" : "SCOPE_INTERSECTION";
            authorization.add(
                    auth(
                            Math.min(next, 6),
                            stage,
                            false,
                            Objects.toString(error, "EXECUTION_FAILED")));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Map<String, Object> result = Map.of("columns", columns, "rows", rows);
        // 输入摘要包含冻结主体、操作标识及空参数，输出摘要包含状态、错误与实际结果。
        // 不把当前草稿修订加入输入，确保相同候选试执行事实可独立复核。
        Map<String, Object> inputFact = new TreeMap<>();
        if (formal) {
            inputFact.put("subjectType", "RELEASE");
            inputFact.put("subjectId", id.toString());
            inputFact.put("bundleSha256", c.get("sha256"));
        } else {
            inputFact.put("candidateId", id.toString());
            inputFact.put("candidateSha256", c.get("sha256"));
        }
        inputFact.put("operationId", operation == null ? "UNRESOLVED" : operation.toString());
        inputFact.put("parameters", input);
        if (requestedCode != null) inputFact.put("operationCode", requestedCode);
        if (question != null) inputFact.put("question", question);
        String inputDigest = AuthService.digest(codec.encode(inputFact));
        Map<String, Object> outputFact = new TreeMap<>();
        outputFact.put("status", status);
        outputFact.put("errorCode", error);
        outputFact.put("result", result);
        String outputDigest = AuthService.digest(codec.encode(outputFact));
        String finalStatus = status;
        String auditSha = Objects.toString(c.get("sha256"), "");
        // 固定本次已完成的执行结果，再在同一连接的短事务中写入整组事实；不把源查询包进事务。
        Object[] factValues = {
            execution,
            space,
            trace,
            formal ? "RELEASE" : "CANDIDATE",
            id,
            opCode,
            status,
            error,
            elapsed,
            c.get("sha256"),
            codec.json(result),
            user,
            caller,
            inputDigest,
            outputDigest,
            codec.json(authorization)
        };
        transactions.executeWithoutResult(
                tx -> {
                    db.update(
                            "INSERT INTO"
                                + " op_execution(id,space_id,trace_id,subject_type,subject_id,op_code,status,error_code,elapsed_ms,candidate_sha256,result_json,principal_id,caller,input_digest,output_digest,\"authorization\")"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb)",
                            factValues);
                    int order = 0;
                    for (var item : evidence)
                        db.update(
                                "INSERT INTO evidence_ref(execution_id,kind,ref)"
                                    + " VALUES(?,?,?::jsonb)",
                                execution,
                                item.get("kind"),
                                codec.json(item.get("ref")));
                    for (var step : steps)
                        db.update(
                                "INSERT INTO"
                                    + " op_execution_step(execution_id,step_order,kind,status,detail)"
                                    + " VALUES(?,?,?,?,?)",
                                execution,
                                order++,
                                step.get("kind"),
                                step.get("status"),
                                step.get("detail"));
                    spaces.audit(
                            user,
                            space,
                            formal ? "RELEASE_EXECUTE" : "CANDIDATE_TRIAL",
                            ((Number) locked.get("draftRev")).longValue(),
                            new AuditDetail(
                                    formal ? "RELEASE" : "CANDIDATE",
                                    id.toString(),
                                    Map.of("sha256", auditSha),
                                    inputDigest,
                                    outputDigest,
                                    finalStatus));
                });
        // 外部事务声明noRollbackFor，拒绝事实提交后HTTP边界才能抛出原始结构化错误。
        if (rejection != null && !"LOCAL_TEST".equals(caller)) throw rejection;
        var fact = facts.one(execution);
        fact.put("spaceCode", frozenSpaceCode);
        return fact;
    }

    /** 只为实际成功执行的冻结定义建证据；摘要来自原定义字节，不从当前规则/操作草稿补造版本。 */
    private void recordDefinitionEvidence(
            List<Map<String, Object>> evidence,
            Map<String, Object> payload,
            Map<String, Object> operation,
            UUID subject,
            String bundleSha,
            boolean formal) {
        if (formal && operation.get("assistantTool") != null) {
            evidence.add(
                    Map.of(
                            "kind",
                            "SERVICE_VER",
                            "ref",
                            Map.of(
                                    "serviceId",
                                    operation.get("id"),
                                    "releaseId",
                                    subject.toString(),
                                    "opCode",
                                    operation.get("code"),
                                    "sha256",
                                    AuthService.digest(codec.encode(operation)),
                                    "bundleSha256",
                                    bundleSha)));
        }
        if (operation.get("dataQuery") != null
                || !java.util.Set.of("RULE_CHECK", "SCORING")
                        .contains(Objects.toString(operation.get("kind")))) return;
        for (Object id : (List<?>) operation.getOrDefault("ruleIds", List.of())) {
            var rule =
                    codec.rows(payload, "rules").stream()
                            .filter(row -> Input.sameUuid(row.get("id"), id))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Problem(
                                                    409, "FROZEN_REFERENCE_INVALID", "实际冻结规则不存在"));
            var ref = new java.util.LinkedHashMap<String, Object>();
            ref.put("ruleId", rule.get("id"));
            ref.put("subjectType", formal ? "RELEASE" : "CANDIDATE");
            ref.put("subjectId", subject.toString());
            if (formal) ref.put("releaseId", subject.toString());
            ref.put("sha256", AuthService.digest(codec.encode(rule)));
            ref.put("bundleSha256", bundleSha);
            evidence.add(Map.of("kind", "RULE_VER", "ref", ref));
        }
    }

    private Map<String, Object> auth(int order, String kind, boolean passed, String detail) {
        return Map.of("order", order, "kind", kind, "passed", passed, "detail", detail);
    }

    /** 真正RELEASE主体的服务调用；返回元数据固定取实际载入版本，失败不建立成功信封。 */
    @Transactional(noRollbackFor = {Problem.class, ContractProblem.class})
    public Map<String, Object> releaseService(
            UUID user, UUID id, String opCode, Object input, String caller) {
        if (!java.util.Set.of("SERVICE", "EXTERNAL").contains(caller)) throw Problem.bad("非法服务入口");
        var release = bundles.release(id, false);
        var fact = runSubject(user, id, null, input, caller, null, opCode, release);
        if (!"SUCCEEDED".equals(fact.get("status")))
            throw new Problem(
                    400, Objects.toString(fact.get("errorCode"), "EXECUTION_FAILED"), "冻结操作执行失败");
        var meta =
                Map.of(
                        "spaceCode",
                        fact.get("spaceCode"),
                        "releaseId",
                        id.toString(),
                        "version",
                        "v" + release.get("versionNo"),
                        "contentDigest",
                        release.get("bundleSha256"),
                        "preview",
                        false);
        return Map.of(
                "code",
                "OK",
                "data",
                Map.of("columns", fact.get("columns"), "rows", fact.get("rows")),
                "meta",
                meta);
    }

    /** 内部入口按冻结opCode选择；业务data不包含执行id，meta来自真实候选与空间上下文。 */
    @Transactional(noRollbackFor = {Problem.class, ContractProblem.class})
    public Map<String, Object> service(UUID user, UUID id, String opCode, Object input) {
        var fact = run(user, id, null, input, "SERVICE", null, opCode);
        if (!"SUCCEEDED".equals(fact.get("status")))
            throw new Problem(
                    400, Objects.toString(fact.get("errorCode"), "EXECUTION_FAILED"), "冻结操作执行失败");
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("spaceCode", fact.get("spaceCode"));
        meta.put("releaseId", null);
        meta.put("version", null);
        meta.put("contentDigest", fact.get("candidateSha256"));
        meta.put("preview", true);
        return Map.of(
                "code",
                "OK",
                "data",
                Map.of("columns", fact.get("columns"), "rows", fact.get("rows")),
                "meta",
                meta);
    }

    private Map<String, String> step(String kind, String status, String detail) {
        return Map.of("kind", kind, "status", status, "detail", detail);
    }

    /** NL 结果解释：优先渲染冻结 return_spec.modelSummary 模板，缺省时给出可读摘要。 */
    private String explain(
            Map<String, Object> op, List<Map<String, Object>> rows, Object input) {
        String template = null;
        if (op.get("assistantTool") instanceof Map<?, ?> tool
                && tool.get("return_spec") instanceof Map<?, ?> returns
                && returns.get("modelSummary") instanceof Map<?, ?> summary
                && "TEMPLATE".equals(summary.get("mode")))
            template = Objects.toString(summary.get("template"), null);
        Map<String, Object> variables = new LinkedHashMap<>();
        if (input instanceof Map<?, ?> values)
            values.forEach((key, value) -> variables.put(String.valueOf(key), value));
        if (!rows.isEmpty())
            rows.getFirst().forEach((key, value) -> variables.putIfAbsent(key, value));
        variables.put("opCode", op.get("code"));
        variables.put("rowCount", rows.size());
        String text =
                template == null || template.isBlank()
                        ? "操作 " + op.get("code") + " 返回 " + rows.size() + " 行"
                        : template;
        for (var entry : variables.entrySet())
            text =
                    text.replace(
                            "${" + entry.getKey() + "}",
                            Objects.toString(entry.getValue(), ""));
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** 执行历史按空间授权读取，首批仅保留最近50条返回，不暴露内部身份事实字段。 */
    public List<Map<String, Object>> executions(UUID user, UUID space) {
        return executions(user, space, null, null, null, 50, 0);
    }

    /** 按入口/结果/操作筛选并分页；仍先校验空间可见性。 */
    public List<Map<String, Object>> executions(
            UUID user,
            UUID space,
            String caller,
            String status,
            String opCode,
            Integer limit,
            Integer offset) {
        spaces.role(user, space);
        return facts.recent(space, caller, status, opCode, limit, offset);
    }
}
