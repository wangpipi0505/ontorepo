package cn.ontorepo.trace.application;

import cn.ontorepo.common.infra.Db;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 执行事实读取；调用方先完成空间授权，本服务不推断身份，也不补读草稿构造历史结果。 */
@Service
public class ExecutionFacts {
    private static final String FIELDS =
            "id,trace_id,subject_type,subject_id,op_code,status,error_code,elapsed_ms,created_at,candidate_sha256,result_json::text,caller";
    private final Db db;
    private final CanonicalPayload codec;

    public ExecutionFacts(Db db, CanonicalPayload codec) {
        this.db = db;
        this.codec = codec;
    }

    /** 最近50条固定三次查询；时间相同时按UUID稳定排序，子记录只查询本批执行标识。 */
    public List<Map<String, Object>> recent(UUID space) {
        return attach(
                db.list(
                        "SELECT "
                                + FIELDS
                                + " FROM op_execution WHERE space_id=? ORDER BY created_at DESC,id"
                                + " DESC LIMIT 50",
                        space));
    }

    /** 由实际写入者读取本次执行，保持与列表相同的结果/步骤/证据响应结构。 */
    public Map<String, Object> one(UUID id) {
        return attach(List.of(db.one("SELECT " + FIELDS + " FROM op_execution WHERE id=?", id)))
                .getFirst();
    }

    private List<Map<String, Object>> attach(List<Map<String, Object>> facts) {
        if (facts.isEmpty()) return facts;
        var byId = new HashMap<String, Map<String, Object>>();
        for (var fact : facts) {
            fact.putAll(
                    codec.decode(
                            fact.remove("resultJson").toString().getBytes(StandardCharsets.UTF_8)));
            fact.put("steps", new ArrayList<Map<String, Object>>());
            fact.put("evidence", new ArrayList<Map<String, Object>>());
            byId.put(fact.get("id").toString(), fact);
        }
        // 占位符数量来自服务端LIMIT，UUID以JDBC参数绑定，不拼接任何客户端标识。
        String placeholders = String.join(",", Collections.nCopies(facts.size(), "?"));
        Object[] ids =
                facts.stream().map(fact -> UUID.fromString(fact.get("id").toString())).toArray();
        attachChildren(
                byId,
                "steps",
                db.list(
                        "SELECT execution_id,kind,status,detail FROM op_execution_step WHERE"
                            + " execution_id IN ("
                                + placeholders
                                + ") ORDER BY execution_id,step_order",
                        ids));
        attachChildren(
                byId,
                "evidence",
                db.list(
                        "SELECT execution_id,kind,ref FROM evidence_ref WHERE execution_id IN ("
                                + placeholders
                                + ") ORDER BY execution_id,id",
                        ids));
        return facts;
    }

    @SuppressWarnings("unchecked")
    private static void attachChildren(
            Map<String, Map<String, Object>> byId, String key, List<Map<String, Object>> children) {
        for (var child : children) {
            var fact = byId.get(child.remove("executionId").toString());
            ((List<Map<String, Object>>) fact.get(key)).add(child);
        }
    }
}
