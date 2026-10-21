package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** 消费侧导入断言和生成侧共用的字段验证；正式版本必须有完整、非预览的四元组。 */
public final class ContractValidator {
    private ContractValidator() {}

    public static void validateImport(JsonNode manifest) {
        JsonNode meta = ContractFields.object(manifest, "ontology_meta");
        if (meta.path("preview").asBoolean(false))
            throw ContractFields.problem("PREVIEW_NOT_RELEASE", "ontology_meta");
        validateBody(manifest);
        for (String field : new String[]{"spaceCode", "releaseId", "version", "contentDigest"})
            ContractFields.text(meta, field);
        if (!meta.path("contentDigest").textValue().matches("[0-9a-f]{64}"))
            throw ContractFields.problem("TYPE_MISMATCH", "contentDigest");
    }

    static void validateBody(JsonNode manifest) {
        for (String field : new String[]{"tool_code", "name", "description", "category"})
            ContractFields.text(manifest, field);
        ContractFields.enumeration(manifest, "invocation_kind", Set.of("SYNC", "SUBMIT_QUERY", "CALLBACK"));
        ContractFields.enumeration(manifest, "adapter_type", Set.of("HTTP", "MCP", "ALGORITHM"));
        ContractFields.httpUrl(ContractFields.text(manifest, "endpoint"));
        ContractFields.enumeration(manifest, "http_method", Set.of("POST"));
        JsonSchemaAssertions.validateSchema(ContractFields.object(manifest, "input_schema"));
        JsonNode outputSchema = ContractFields.object(manifest, "output_schema");
        JsonSchemaAssertions.validateSchema(outputSchema);
        validateOutputEnvelope(outputSchema);
        ContractFields.strings(ContractFields.required(manifest, "guard_attrs"), "guard_attrs", true);
        JsonNode exec = ContractFields.object(manifest, "exec_attrs");
        boolean readonly = ContractFields.bool(exec, "readonly_flag");
        boolean sideEffect = ContractFields.bool(exec, "side_effect");
        ContractFields.bool(exec, "idempotent");
        ContractFields.bool(exec, "cancellable");
        ContractFields.positiveInt(exec, "timeout_ms");
        ContractFields.positiveInt(exec, "max_rows");
        String risk = ContractFields.enumeration(manifest, "risk_level", Set.of("READ_ONLY", "CONFIRM_REQUIRED"));
        if ((!readonly || sideEffect) && risk.equals("READ_ONLY"))
            throw ContractFields.problem("RISK_DOWNGRADE", "risk_level");
        JsonNode semantics = ContractFields.required(manifest, "error_semantics");
        if (!semantics.isArray()) throw ContractFields.problem("TYPE_MISMATCH", "error_semantics");
        for (JsonNode entry : semantics) {
            ContractFields.text(entry, "match");
            ContractFields.enumeration(entry, "semantic",
                    Set.of("PARAM_ERROR", "NO_PERMISSION", "QUOTA", "RETRYABLE_FAILURE", "RESULT_UNKNOWN"));
        }
        JsonNode returns = ContractFields.object(manifest, "return_spec");
        if (!"$.data".equals(ContractFields.text(returns, "structuredPath"))
                || !"$.meta".equals(ContractFields.text(returns, "ontologyMetaPath")))
            throw ContractFields.problem("INVALID_RETURN_PATH", "return_spec");
        ContractFields.object(returns, "modelSummary");
        ContractFields.strings(ContractFields.required(returns, "fileRefPaths"), "fileRefPaths", false);
        ContractFields.text(returns, "cardTemplate");
    }

    /** 消费协议要求显式信封约束；合法 JSON Schema 本身并不保证响应携带版本证据。 */
    private static void validateOutputEnvelope(JsonNode schema) {
        requireEnvelope(hasOnlyType(schema, "object"), "output_schema.type");
        requireFields(schema, Set.of("code", "data", "meta"), "output_schema.required");
        JsonNode properties = schema.path("properties");
        requireEnvelope(properties.isObject() && properties.has("data"), "output_schema.properties.data");
        JsonNode code = properties.path("code");
        requireEnvelope("OK".equals(code.path("const").textValue())
                        || (code.path("enum").isArray() && code.path("enum").size() == 1
                            && "OK".equals(code.path("enum").get(0).textValue())),
                "output_schema.properties.code");
        JsonNode meta = properties.path("meta");
        requireEnvelope(hasOnlyType(meta, "object"), "output_schema.properties.meta.type");
        requireFields(meta, Set.of("spaceCode", "releaseId", "version", "contentDigest"),
                "output_schema.properties.meta.required");
        for (String field : new String[]{"spaceCode", "releaseId", "version", "contentDigest"}) {
            requireEnvelope(hasOnlyType(meta.path("properties").path(field), "string"),
                    "output_schema.properties.meta.properties." + field);
        }
        // §8.8 规定的摘要格式必须作为实际断言保留，额外约束可以收紧但不能移除此保证。
        requireEnvelope("^[0-9a-f]{64}$".equals(meta.at("/properties/contentDigest/pattern").textValue()),
                "output_schema.properties.meta.properties.contentDigest.pattern");
    }

    private static boolean hasOnlyType(JsonNode schema, String type) {
        JsonNode declaration = schema.path("type");
        return type.equals(declaration.textValue())
                || (declaration.isArray() && declaration.size() == 1
                    && type.equals(declaration.get(0).textValue()));
    }

    private static void requireFields(JsonNode schema, Set<String> fields, String path) {
        JsonNode required = schema.path("required");
        requireEnvelope(required.isArray(), path);
        requireEnvelope(ContractFields.strings(required, path, true).containsAll(fields), path);
    }

    private static void requireEnvelope(boolean condition, String field) {
        if (!condition) {
            throw ContractFields.problem("INVALID_OUTPUT_ENVELOPE", field);
        }
    }
}
