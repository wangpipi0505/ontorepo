package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 仅从执行、步骤和证据事实构建追溯图；空间授权始终取执行所属空间。 */
@Service
public class TraceAuditService {
    private final Db db;
    private final SpaceService spaces;

    public TraceAuditService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    /** 历史授权缺失返回空数组，步骤与证据时间缺失保持null，绝不推算时间。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> trace(UUID user, UUID execution) {
        var stored = db.one("SELECT * FROM op_execution WHERE id=?", execution);
        UUID space = Input.uuid(stored.get("spaceId"));
        spaces.role(user, space);
        var fact = new LinkedHashMap<>(stored);
        Object authorization = fact.remove("authorization");
        Object result = fact.remove("resultJson");
        if (result instanceof Map<?, ?> map) {
            fact.put("columns", map.get("columns"));
            fact.put("rows", map.get("rows"));
        }
        var steps =
                db.list(
                        "SELECT id,step_order,kind,status,detail,created_at FROM op_execution_step"
                                + " WHERE execution_id=? ORDER BY step_order,id",
                        execution);
        var rawEvidence =
                db.list(
                        "SELECT id,kind,ref,created_at FROM evidence_ref WHERE execution_id=? ORDER"
                                + " BY id",
                        execution);
        var evidence = new ArrayList<Map<String, Object>>();
        var timeline = new ArrayList<Map<String, Object>>();
        var nodes = new ArrayList<Map<String, Object>>();
        var edges = new ArrayList<Map<String, Object>>();
        String root = "execution:" + execution;
        nodes.add(node(root, "执行 " + stored.get("opCode"), "EXECUTION", execution.toString()));
        timeline.add(
                moment(
                        root,
                        stored.get("createdAt"),
                        "EXECUTION",
                        Objects.toString(stored.get("status"), "执行"),
                        Map.of("opCode", stored.get("opCode"), "caller", stored.get("caller"))));
        if (stored.get("subjectId") != null && stored.get("subjectType") != null) {
            String subject = "subject:" + stored.get("subjectType") + ":" + stored.get("subjectId");
            nodes.add(
                    node(
                            subject,
                            stored.get("subjectType").toString(),
                            stored.get("subjectType").toString(),
                            stored.get("subjectId")));
            edges.add(edge(root, subject, "执行主体"));
        }
        String previous = root;
        for (var step : steps) {
            String id = "step:" + step.get("id");
            nodes.add(node(id, Objects.toString(step.get("kind")), "STEP", step.get("id")));
            edges.add(edge(previous, id, "步骤"));
            previous = id;
            timeline.add(
                    moment(
                            id,
                            step.get("createdAt"),
                            Objects.toString(step.get("kind")),
                            Objects.toString(step.get("status")),
                            HistorySafety.redact(step.get("detail"))));
            step.put("detail", HistorySafety.redact(step.get("detail")));
        }
        for (var row : rawEvidence) {
            var ref = row.get("ref") instanceof Map<?, ?> m ? m : Map.of();
            String kind = Objects.toString(row.get("kind"));
            var item = new LinkedHashMap<String, Object>();
            item.put("id", row.get("id"));
            item.put("type", kind);
            item.put("ref", publicReference(kind, ref));
            item.put("sha256", ref.get("sha256"));
            item.put("detail", evidenceDetail(space, kind, ref));
            evidence.add(item);
            String id = "evidence:" + row.get("id");
            nodes.add(node(id, kind, "EVIDENCE", item.get("ref")));
            edges.add(edge(root, id, "证据"));
            timeline.add(moment(id, row.get("createdAt"), kind, "证据", item));
        }
        // 有真实时间的事实按时间排序；null时间只保持持久步骤顺序，不借用执行时间。
        // ISO字符串的小数位数可能不同；按真实时刻排序，历史无时间事实稳定保留在末尾。
        timeline.sort(
                Comparator.comparing(
                        m ->
                                m.get("at") == null
                                        ? null
                                        : java.time.Instant.parse(m.get("at").toString()),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        Object safeAuthorization =
                authorization instanceof List<?> ? HistorySafety.redact(authorization) : List.of();
        return Map.of(
                "execution",
                fact,
                "authorization",
                safeAuthorization,
                "steps",
                steps,
                "evidence",
                evidence,
                "timeline",
                timeline,
                "graph",
                Map.of("nodes", nodes, "edges", edges));
    }

    /** id倒序游标采用多读一条判断下一页，后续新增审计不会使已读页面重复或覆盖。 */
    public Map<String, Object> audit(UUID user, UUID space, int limit, Long beforeId) {
        return audit(user, space, limit, beforeId, null, null, null, null, null);
    }

    /** 支持动作/对象/时间范围过滤；对象检索按 subject_type + subject_id 命中。 */
    public Map<String, Object> audit(
            UUID user,
            UUID space,
            int limit,
            Long beforeId,
            String action,
            String subjectType,
            UUID subjectId,
            String from,
            String to) {
        spaces.role(user, space);
        if (limit < 1) throw Problem.bad("limit必须为正整数");
        if (beforeId != null && beforeId < 1) throw Problem.bad("beforeId必须为正整数");
        int size = Math.min(limit, 200);
        var sql =
                new StringBuilder(
                        "SELECT a.id,a.space_id,a.actor_id,a.action,a.subject_id,a.detail,"
                                + "a.subject_type,a.version_refs,a.input_digest,a.output_digest,a.result,"
                                + "a.created_at,u.display_name actor_name FROM audit_log a LEFT JOIN"
                                + " local_user u ON u.id=a.actor_id WHERE a.space_id=?");
        var params = new ArrayList<Object>();
        params.add(space);
        if (action != null && !action.isBlank()) {
            sql.append(" AND a.action=?");
            params.add(action);
        }
        if (subjectType != null && !subjectType.isBlank()) {
            sql.append(" AND a.subject_type=?");
            params.add(subjectType);
        }
        if (subjectId != null) {
            sql.append(" AND a.subject_id=?");
            params.add(subjectId.toString());
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND a.created_at>=?");
            params.add(java.sql.Timestamp.from(instant(from)));
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND a.created_at<=?");
            params.add(java.sql.Timestamp.from(instant(to)));
        }
        if (beforeId != null) {
            sql.append(" AND a.id<?");
            params.add(beforeId);
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        params.add(size + 1);
        var rows = db.list(sql.toString(), params.toArray());
        boolean more = rows.size() > size;
        var items = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        items.forEach(row -> row.put("detail", HistorySafety.auditDetail(row.get("detail"))));
        var page = new LinkedHashMap<String, Object>();
        page.put("items", items);
        page.put("nextBeforeId", more ? items.getLast().get("id") : null);
        return page;
    }

    private java.time.Instant instant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException e) {
            throw Problem.bad("时间必须为 ISO-8601，例如 2026-10-21T00:00:00Z");
        }
    }

    private Object publicReference(String kind, Map<?, ?> ref) {
        // 定义节点定位具体规则/服务，所属release作为细节保留，不能替换定义本身的身份。
        if (kind.equals("RULE_VER")) return ref.get("ruleId");
        if (kind.equals("SERVICE_VER")) return ref.get("serviceId");
        if (kind.equals("SOURCE_RECORD")) {
            var result = new LinkedHashMap<String, Object>();
            for (String key : List.of("sourceId", "resource", "key")) result.put(key, ref.get(key));
            return result;
        }
        for (String key : List.of("id", "subjectId", "releaseId", "ruleId", "serviceId"))
            if (ref.get(key) != null) return ref.get(key);
        return null;
    }

    /** 快照只能按证据中的原id和执行空间查当前/归档，不根据当前映射猜测。 */
    private Object evidenceDetail(UUID space, String kind, Map<?, ?> ref) {
        var detail = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "subjectType",
                        "subjectId",
                        "releaseId",
                        "ruleId",
                        "serviceId",
                        "opCode",
                        "bundleSha256",
                        "id",
                        "sha256",
                        "objectMappingIds",
                        "sourceId",
                        "resource")) if (ref.containsKey(key)) detail.put(key, ref.get(key));
        if (kind.equals("SOURCE_RECORD")) detail.put("key", ref.get("key"));
        if (kind.equals("META_SNAPSHOT") && ref.get("id") != null) {
            UUID id;
            try {
                id = Input.uuid(ref.get("id"));
            } catch (Problem invalid) {
                return detail;
            }
            var snapshots =
                    db.list(
                            "SELECT id,datasource_id,sha256,captured_at FROM meta_snapshot WHERE"
                                + " id=? AND space_id=? UNION ALL SELECT"
                                + " id,datasource_id,sha256,captured_at FROM meta_snapshot_archive"
                                + " WHERE id=? AND space_id=? LIMIT 1",
                            id,
                            space,
                            id,
                            space);
            if (!snapshots.isEmpty()) {
                var snapshot = snapshots.getFirst();
                // 摘要冲突时仍展示原证据，不把另一份内容伪装成它的来源。
                if (ref.get("sha256") == null
                        || Objects.equals(ref.get("sha256"), snapshot.get("sha256")))
                    detail.put("snapshot", snapshot);
            }
        }
        return detail;
    }

    private Map<String, Object> moment(
            String id, Object at, String kind, String title, Object detail) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("at", at);
        result.put("kind", kind);
        result.put("title", title);
        result.put("detail", detail);
        return result;
    }

    private Map<String, Object> node(String id, String label, String kind, Object ref) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("label", label);
        result.put("kind", kind);
        result.put("ref", ref);
        return result;
    }

    private Map<String, Object> edge(String from, String to, String label) {
        return Map.of("id", from + "->" + to, "from", from, "to", to, "label", label);
    }
}
