package cn.ontorepo.release.domain;

import cn.ontorepo.common.infra.Db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * R11：执行事实 → W3C PROV-O Turtle 投影（纯函数，无存储访问）。
 *
 * <p>映射（TraceAuditService.trace 的执行图为数据源）：
 * <ul>
 *   <li>EXECUTION 节点 → prov:Activity；caller/principal → prov:Agent（prov:SoftwareAgent）</li>
 *   <li>EVIDENCE 节点 → prov:Entity；边"证据" → prov:used（活动使用输入证据）</li>
 *   <li>STEP 节点 → 子 prov:Activity；边"步骤" → prov:wasInformedBy（步骤链）；
 *       最后步骤 → prov:wasGeneratedBy 由执行到结果</li>
 *   <li>subject 节点 → prov:Entity；边"执行主体" → prov:generated（执行产出主体状态），
 *       并记 prov:hadMember 集合关系</li>
 * </ul>
 * 时间事实：startedAtTime/endedAtTime 仅使用持久值，缺失不推算（与 trace 同口径）。
 * 输出基准命名空间 https://semantica.runisys.cn/ns/onto/，实体 URI 用
 * execution:{id} 等稳定前缀（与执行图节点 id 一致，前后端可互链）。
 */
public final class ProvExporter {
    /** 审计交换的基准命名空间；改动会破坏已导出报告的可比性。 */
    public static final String BASE = "https://semantica.runisys.cn/ns/onto/";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private ProvExporter() {}

    /**
     * @param execution 执行事实行（op_execution）
     * @param steps     op_execution_step 行列表（order by step_order,id）
     * @param evidence  evidence_ref 行列表（order by id）
     * @param graph     TraceAuditService.trace 产出的 graph（nodes/edges）
     * @param actorId   操作者本地用户 id（可空——LEGACY 调用无 principal）
     */
    public static Map<String, Object> turtle(
            Map<String, Object> execution,
            List<Map<String, Object>> steps,
            List<Map<String, Object>> evidence,
            Map<String, Object> graph,
            String actorId) {
        String execId = String.valueOf(execution.get("id"));
        String subject = execId(execId);
        StringBuilder ttl = new StringBuilder();
        header(ttl);
        // 活动：执行本体。结束时间仅在有持久步骤时间时取最后步骤值；
        // 无步骤不输出 endedAtTime，绝不拿开始时间充数（与 trace 的时间口径一致）。
        ttl.append("\n").append(subject).append(" a prov:Activity");
        appendTimes(ttl, execution.get("createdAt"), endedAt(execution, steps));
        ttl.append(" ;\n    prov:label ").append(literal(execution.get("opCode")));
        Object digest = execution.get("outputDigest");
        if (digest != null)
            ttl.append(" ;\n    onto:outputDigest ").append(literal(digest));
        Object caller = execution.get("caller");
        if (caller != null) ttl.append(" ;\n    onto:caller ").append(literal(caller));
        if (actorId != null && !actorId.isBlank())
            ttl.append(" ;\n    prov:wasAssociatedWith ").append(agentRef(execId));
        if (execution.get("errorCode") != null)
            ttl.append(" ;\n    onto:errorCode ").append(literal(execution.get("errorCode")));
        ttl.append(" .\n");
        // 代理
        if (actorId != null && !actorId.isBlank()) {
            ttl.append("\n").append(agentRef(execId))
                    .append(" a prov:SoftwareAgent ;\n    prov:label ")
                    .append(literal(actorId)).append(" .\n");
        }
        // 步骤：子活动 + wasInformedBy 链
        String previous = null;
        for (var step : steps) {
            String id = stepRef(execId, String.valueOf(step.get("id")));
            ttl.append("\n").append(id).append(" a prov:Activity");
            appendTimes(ttl, step.get("createdAt"), null);
            ttl.append(" ;\n    prov:label ").append(literal(step.get("kind")))
                    .append(" ;\n    onto:status ").append(literal(step.get("status")))
                    .append(" ;\n    prov:wasInformedBy ").append(subject).append(" .\n");
            if (previous != null)
                ttl.append("\n").append(previous).append(" prov:wasInformedBy ").append(id).append(" .\n");
            previous = id;
        }
        if (previous != null)
            ttl.append("\n").append(subject).append(" prov:wasGeneratedBy ").append(previous).append(" .\n");
        // 证据：实体 + used
        for (var row : evidence) {
            String id = evidenceRef(execId, String.valueOf(row.get("id")));
            String kind = String.valueOf(row.get("kind"));
            var ref = row.get("ref") instanceof Map<?, ?> m ? m : Map.of();
            ttl.append("\n").append(id).append(" a prov:Entity");
            appendTimes(ttl, row.get("createdAt"), null);
            ttl.append(" ;\n    prov:label ").append(literal(kind));
            Object sha = ref.get("sha256");
            if (sha != null) ttl.append(" ;\n    onto:sha256 ").append(literal(sha));
            ttl.append(" .\n");
            ttl.append("\n").append(subject).append(" prov:used ").append(id).append(" .\n");
        }
        // 执行主体（产物实体）
        Object subjectType = execution.get("subjectType"), subjectId = execution.get("subjectId");
        if (subjectType != null && subjectId != null) {
            String entity = entityRef(execId);
            ttl.append("\n").append(entity).append(" a prov:Entity ;\n    prov:label ")
                    .append(literal(subjectType)).append(" ;\n    onto:subjectId ")
                    .append(literal(subjectId)).append(" .\n");
            ttl.append("\n").append(subject).append(" prov:generated ").append(entity).append(" .\n");
        }
        return Map.of(
                "format", "turtle",
                "base", BASE,
                "prov", PROV,
                "content", ttl.toString());
    }

    private static void header(StringBuilder ttl) {
        ttl.append("@prefix prov: <").append(PROV).append("> .\n")
                .append("@prefix onto: <").append(BASE).append("> .\n")
                .append("@prefix xsd: <").append(XSD).append("> .\n")
                .append("@base <").append(BASE).append("> .\n");
    }

    private static void appendTimes(StringBuilder ttl, Object start, Object end) {
        if (start != null)
            ttl.append(" ;\n    prov:startedAtTime ").append(timeLiteral(start));
        if (end != null)
            ttl.append(" ;\n    prov:endedAtTime ").append(timeLiteral(end));
    }

    /** 持久时间原样输出（ISO 字符串或时间戳转 ISO）；无值不输出，绝不推算。 */
    private static String timeLiteral(Object value) {
        String text = value instanceof java.sql.Timestamp t
                ? t.toInstant().toString()
                : value instanceof OffsetDateTime o ? o.toString() : String.valueOf(value);
        return "\"" + text + "\"^^xsd:dateTime";
    }

    private static Object endedAt(Map<String, Object> execution, List<Map<String, Object>> steps) {
        return steps.isEmpty() ? null : steps.getLast().get("createdAt");
    }

    private static String execId(String id) {
        return "<execution/" + url(id) + ">";
    }

    private static String stepRef(String exec, String stepId) {
        return "<execution/" + url(exec) + "#step/" + url(stepId) + ">";
    }

    private static String evidenceRef(String exec, String evidenceId) {
        return "<execution/" + url(exec) + "#evidence/" + url(evidenceId) + ">";
    }

    private static String entityRef(String exec) {
        return "<execution/" + url(exec) + "#subject>";
    }

    private static String agentRef(String exec) {
        return "<execution/" + url(exec) + "#agent>";
    }

    private static String url(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** Turtle 字面量转义：反斜杠、双引号、换行/回车/制表。 */
    private static String literal(Object value) {
        String text = String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        return "\"" + text + "\"";
    }
}
