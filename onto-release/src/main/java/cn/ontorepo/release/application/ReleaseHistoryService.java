package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.FrozenBundleLoader;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 版本浏览和比较以实际验证的冻结包为准，投影表不能替代制品完整性检查。 */
@Service
public class ReleaseHistoryService {
    private static final String SUMMARY =
            "id,space_id,version_no,candidate_id,status,bundle_sha256,manifest_sha256,published_at,published_by";
    private static final Set<String> VISIBLE =
            Set.of(
                    "CLASS",
                    "PROPERTY",
                    "RELATION",
                    "OPERATION",
                    "RULE",
                    "STRATEGY",
                    "KNOWLEDGE",
                    "MAPPING");
    private static final String[][] KINDS = {
        {"classes", "CLASS"},
        {"properties", "PROPERTY"},
        {"relations", "RELATION"},
        {"operations", "OPERATION"},
        {"rules", "RULE"},
        {"strategies", "STRATEGY"},
        {"knowledge", "KNOWLEDGE"},
        {"codebooks", "CODEBOOK"},
        {"constraints", "CONSTRAINT"},
        {"testCases", "TEST_CASE"},
        {"rdfDocuments", "RDF_DOCUMENT"},
        {"datasources", "DATASOURCE"},
        {"metaSnapshots", "META_SNAPSHOT"},
        {"dataFiles", "DATA_FILE"},
        {"objectMappings", "MAPPING"},
        {"propertyMappings", "MAPPING"},
        {"relationMappings", "MAPPING"}
    };
    private final Db db;
    private final SpaceService spaces;
    private final CanonicalPayload codec;
    private final FrozenBundleLoader bundles;
    private final PlatformTransactionManager transactions;

    public ReleaseHistoryService(
            Db db,
            SpaceService spaces,
            CanonicalPayload codec,
            FrozenBundleLoader bundles,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.spaces = spaces;
        this.codec = codec;
        this.bundles = bundles;
        this.transactions = transactions;
    }

    /** 管理列表保留弃用和损坏状态，但不包含内部对象存储引用。 */
    public List<Map<String, Object>> list(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list(
                "SELECT "
                        + SUMMARY
                        + " FROM ontology_release WHERE space_id=? ORDER BY version_no DESC",
                space);
    }

    /** 管理详情只返回安全制品证明，非READY端点显式禁用。 */
    public Map<String, Object> detail(UUID user, UUID release) {
        var row = authorized(user, release);
        var endpoints =
                db.list(
                        "SELECT id,release_id,op_code,route,status,exec_attrs FROM service_endpoint"
                                + " WHERE release_id=? ORDER BY op_code,id",
                        release);
        if (!"READY".equals(row.get("status"))) endpoints.forEach(e -> e.put("status", "DISABLED"));
        var checks =
                db.list(
                        "SELECT DISTINCT ON(kind)"
                                + " kind,conclusion,reason,report,candidate_sha256,checked_at FROM"
                                + " publication_check WHERE candidate_id=? ORDER BY kind,id DESC",
                        Input.uuid(row.get("candidateId")));
        return Map.of(
                "release",
                summary(row),
                "artifacts",
                artifacts(row),
                "endpoints",
                endpoints,
                "checks",
                HistorySafety.redact(checks));
    }

    /** 展示枚举严格遵守管理契约；先复核包，再从包重建展示投影。 */
    public List<Map<String, Object>> entities(UUID user, UUID release, String kind) {
        if (kind != null && !VISIBLE.contains(kind)) throw Problem.bad("实体类型无效");
        return project(bundles.payload(authorized(user, release))).stream()
                .filter(p -> VISIBLE.contains(p.get("kind")))
                .filter(p -> kind == null || kind.equals(p.get("kind")))
                .toList();
    }

    /** 同空间两个历史包按稳定实体标识比较，顺序和存储时间不产生虚假差异。 */
    public Map<String, Object> diff(UUID user, UUID space, UUID from, UUID to) {
        spaces.role(user, space);
        var left = authorized(user, from);
        var right = authorized(user, to);
        sameSpace(space, left);
        sameSpace(space, right);
        var result = compare(project(bundles.payload(left)), project(bundles.payload(right)));
        result.put("fromReleaseId", from.toString());
        result.put("toReleaseId", to.toString());
        return result;
    }

    /** 首次候选与空集比较，后续候选只与冻结前最近的READY版本比较。 */
    public Map<String, Object> candidateDiff(UUID user, UUID candidate) {
        var row = db.one("SELECT * FROM publish_candidate WHERE id=?", candidate);
        UUID space = Input.uuid(row.get("spaceId"));
        spaces.role(user, space);
        var previous =
                db.list(
                        "SELECT * FROM ontology_release WHERE space_id=? AND status='READY' AND"
                                + " published_at<=?::timestamptz ORDER BY version_no DESC LIMIT 1",
                        space,
                        row.get("createdAt"));
        var result =
                compare(
                        previous.isEmpty()
                                ? List.of()
                                : project(bundles.payload(previous.getFirst())),
                        project(bundles.payload(row)));
        result.put("candidateId", candidate.toString());
        result.put("fromReleaseId", previous.isEmpty() ? null : previous.getFirst().get("id"));
        result.put("toReleaseId", null);
        return result;
    }

    /** 网络核验在事务外，短事务内重新授权、空间CAS、全域恢复和审计原子提交。 */
    public Map<String, Object> restore(
            UUID user, UUID release, long expectedDraftRev, String reason) {
        String why = Input.text(Collections.singletonMap("reason", reason), "reason", 2000);
        var row = authorized(user, release);
        UUID space = Input.uuid(row.get("spaceId"));
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "恢复草稿需要空间所有者权限");
        var payload = bundles.payload(row);
        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            spaces.lock(user, space, expectedDraftRev, true);
                            if (!"OWNER".equals(spaces.role(user, space)))
                                throw new Problem(403, "FORBIDDEN", "恢复草稿需要空间所有者权限");
                            new DraftBundleRestorer(db, codec).restore(space, payload);
                            if (db.update(
                                            "UPDATE ontology_space SET draft_rev=draft_rev+1 WHERE"
                                                    + " id=? AND draft_rev=?",
                                            space,
                                            expectedDraftRev)
                                    != 1) throw new Problem(409, "DRAFT_REV_CONFLICT", "草稿已被修改");
                            db.update(
                                    "INSERT INTO"
                                        + " audit_log(space_id,actor_id,action,draft_rev,subject_id,detail)"
                                        + " VALUES(?,?,'RELEASE_RESTORE_DRAFT',?,?,?::jsonb)",
                                    space,
                                    user,
                                    expectedDraftRev + 1,
                                    release.toString(),
                                    codec.json(
                                            Map.of(
                                                    "reason",
                                                    why,
                                                    "releaseId",
                                                    release.toString(),
                                                    "bundleSha256",
                                                    row.get("bundleSha256"))));
                            return spaces.get(user, space);
                        });
    }

    /** 全域投影供提交和diff复用；敏感连接密文只保留摘要，恢复仍读取原bundle。 */
    public List<Map<String, Object>> project(Map<String, Object> bundle) {
        var result = new ArrayList<Map<String, Object>>();
        if (bundle.get("space") instanceof Map<?, ?> space)
            add(result, "SPACE", Objects.toString(space.get("id"), "space"), space);
        for (var pair : KINDS)
            for (var row : codec.rows(bundle, pair[0])) {
                String key = Objects.toString(row.get("id"), "");
                if (key.isBlank()) throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结实体缺少稳定标识");
                if (pair[1].equals("MAPPING")) key = pair[0] + ":" + key;
                add(result, pair[1], key, row);
            }
        result.sort(
                Comparator.comparing((Map<String, Object> p) -> p.get("kind").toString())
                        .thenComparing(p -> p.get("key").toString()));
        return result;
    }

    private void add(List<Map<String, Object>> result, String kind, String key, Map<?, ?> row) {
        var value = new TreeMap<String, Object>();
        row.forEach((k, v) -> value.put(k.toString(), v));
        for (String field : List.of("spaceId", "createdAt", "capturedAt", "lastTestAt"))
            value.remove(field);
        if (kind.equals("SPACE")) {
            value.remove("draftRev");
            value.remove("status");
            value.remove("nextVersionNo");
        }
        Object config = value.remove("connCfg");
        if (config != null) value.put("connCfgSha256", AuthService.digest(codec.encode(config)));
        result.add(Map.of("kind", kind, "key", key, "value", value));
    }

    private Map<String, Object> compare(
            List<Map<String, Object>> left, List<Map<String, Object>> right) {
        Map<String, Map<String, Object>> before = new TreeMap<>(), after = new TreeMap<>();
        for (var p : left) before.put(p.get("kind") + "/" + p.get("key"), p);
        for (var p : right) after.put(p.get("kind") + "/" + p.get("key"), p);
        var added = new ArrayList<Map<String, Object>>();
        var removed = new ArrayList<Map<String, Object>>();
        var changed = new ArrayList<Map<String, Object>>();
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (var key : keys) {
            var a = before.get(key);
            var b = after.get(key);
            var p = a == null ? b : a;
            var change = new LinkedHashMap<String, Object>();
            change.put("kind", p.get("kind"));
            change.put("key", p.get("key"));
            if (a != null) change.put("before", a.get("value"));
            if (b != null) change.put("after", b.get("value"));
            if (a == null) added.add(change);
            else if (b == null) removed.add(change);
            else if (!Arrays.equals(codec.encode(a.get("value")), codec.encode(b.get("value"))))
                changed.add(change);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        return result;
    }

    private Map<String, Object> authorized(UUID user, UUID release) {
        var row = bundles.release(release, false);
        spaces.role(user, Input.uuid(row.get("spaceId")));
        return row;
    }

    private void sameSpace(UUID space, Map<String, Object> row) {
        if (!Input.sameUuid(space, row.get("spaceId"))) throw Problem.missing();
    }

    private Map<String, Object> summary(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "id",
                        "spaceId",
                        "versionNo",
                        "candidateId",
                        "status",
                        "bundleSha256",
                        "manifestSha256",
                        "publishedAt",
                        "publishedBy")) result.put(key, row.get(key));
        return result;
    }

    private List<Map<String, Object>> artifacts(Map<String, Object> row) {
        var result = new ArrayList<Map<String, Object>>();
        for (var pair :
                new String[][] {
                    {"bundleRef", "BUNDLE"},
                    {"manifestRef", "MANIFEST"},
                    {"contextPackRef", "CONTEXT_PACK"},
                    {"indexRef", "KNOWLEDGE_INDEX"}
                }) {
            var item = new LinkedHashMap<String, Object>();
            item.put("kind", pair[1]);
            var ref = row.get(pair[0]) instanceof Map<?, ?> m ? m : Map.of();
            item.put("sha256", ref.get("sha256"));
            item.put("size", ref.get("size"));
            item.put("ready", !ref.isEmpty());
            result.add(item);
        }
        return result;
    }
}
