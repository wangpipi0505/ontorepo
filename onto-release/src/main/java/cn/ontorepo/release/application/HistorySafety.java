package cn.ontorepo.release.application;

import java.util.*;

/** 历史报告和审计不透传凭据、内部地址或原始源行；缺失信息不通过当前草稿补全。 */
final class HistorySafety {
    private HistorySafety() {}

    /** 审计只公开约定的业务元信息；未知历史扩展不能被当成可信安全目录。 */
    static Object auditDetail(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        Set<String> fields =
                Set.of(
                        "reason",
                        "system",
                        "releaseId",
                        "candidateId",
                        "candidateSha256",
                        "bundleSha256",
                        "sha256",
                        "manifestSha256",
                        "draftRev",
                        "expectedDraftRev",
                        "oldDraftRev",
                        "newDraftRev",
                        "status",
                        "fromStatus",
                        "toStatus",
                        "kind",
                        "conclusion",
                        "actorId",
                        "principalId",
                        "userId",
                        "targetUserId",
                        "endpointId",
                        "opCode",
                        "eventId",
                        "mappingId",
                        "datasourceId",
                        "fileId",
                        "importId",
                        "batchId",
                        "entityId",
                        "count",
                        "retryCount",
                        "previousStatus",
                        "action",
                        "role",
                        "dataScope",
                        "expiresAt",
                        "operationId");
        map.forEach(
                (key, item) -> {
                    if (fields.contains(key.toString())) result.put(key.toString(), redact(item));
                });
        return result;
    }

    private static final Set<String> HIDDEN =
            Set.of(
                    "password",
                    "passwd",
                    "secret",
                    "secretkey",
                    "accesskey",
                    "apikey",
                    "key",
                    "token",
                    "tokenhash",
                    "authorization",
                    "conncfg",
                    "connectionstring",
                    "jdbcurl",
                    "url",
                    "endpoint",
                    "bundleref",
                    "manifestref",
                    "contextpackref",
                    "indexref",
                    "rows",
                    "row",
                    "result",
                    "resultjson",
                    "input",
                    "parameters",
                    "sourcerows",
                    "sourcerecord",
                    "credentials",
                    "payload");

    static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        String field = key.toString(),
                                normalized = field.replace("_", "").toLowerCase(Locale.ROOT);
                        if (!HIDDEN.contains(normalized)
                                && !normalized.endsWith("password")
                                && !normalized.endsWith("secret")
                                && !normalized.endsWith("token")) result.put(field, redact(item));
                    });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(HistorySafety::redact).toList();
        if (value instanceof String text
                && (text.matches(
                        "(?is).*(?:jdbc:|password\\s*[:=]|passwd\\s*[:=]|secret\\s*[:=]|api[_-]?key\\s*[:=]|bearer\\s+|https?://[^\\s/@]+:[^\\s/@]+@).*")))
            return "[敏感内容已隐藏]";
        return value;
    }
}
