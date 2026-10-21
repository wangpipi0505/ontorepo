package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 候选阶段编译完整契约，提交前只填预分配四元组与正式路由，保证门禁所见内容不被重生成。 */
public final class ContractGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 参数、Schema、守卫执行绑定全部有效后才返回可用于 CONTRACT 门禁的预览。 */
    public ObjectNode preview(JsonNode operation, JsonNode guardCfg, String baseUrl,
                              String spaceCode, String candidateId) {
        GuardValidator.validate(operation, guardCfg);
        JsonNode assistant = ContractFields.object(operation, "assistant_tool");
        JsonNode input = ContractFields.object(operation, "input_schema");
        JsonNode output = ContractFields.object(operation, "output_schema");
        JsonSchemaAssertions.validateSchema(input);
        JsonSchemaAssertions.validateSchema(output);
        JsonNode examples = assistant.path("param_examples");
        if (!examples.isArray() || examples.isEmpty())
            throw new ContractProblem("MISSING_PARAM_EXAMPLES", operation.path("code").asText(), "MISSING_PARAM_EXAMPLES");
        for (JsonNode example : examples) JsonSchemaAssertions.validate(input, example);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("tool_code", ContractFields.text(operation, "code"));
        manifest.put("name", ContractFields.text(assistant, "name"));
        manifest.put("description", ContractFields.text(assistant, "description"));
        manifest.put("invocation_kind", "SYNC").put("adapter_type", "HTTP").put("category", "ontology_service");
        manifest.put("endpoint", base(baseUrl) + "/onto/internal/candidates/" + segment(candidateId)
                + "/ops/" + segment(manifest.path("tool_code").textValue()));
        manifest.put("http_method", "POST");
        manifest.set("input_schema", input.deepCopy());
        manifest.set("output_schema", envelope(output));
        manifest.set("guard_attrs", assistant.path("guard_attrs").deepCopy());
        JsonNode sourceExec = ContractFields.object(assistant, "exec");
        JsonNode policy = ContractFields.object(operation, "policy");
        var exec = manifest.putObject("exec_attrs");
        exec.put("readonly_flag", ContractFields.bool(sourceExec, "readonly"));
        for (String field : new String[]{"side_effect", "idempotent", "cancellable"})
            exec.put(field, ContractFields.bool(sourceExec, field));
        exec.put("timeout_ms", ContractFields.positiveInt(policy, "timeout_ms"));
        exec.put("max_rows", ContractFields.positiveInt(policy, "max_rows"));
        manifest.put("risk_level", ContractFields.text(assistant, "risk_level"));
        manifest.set("error_semantics", ContractFields.required(assistant, "error_semantics").deepCopy());
        ObjectNode returns = (ObjectNode) ContractFields.object(assistant, "return_spec").deepCopy();
        returns.put("ontologyMetaPath", "$.meta");
        manifest.set("return_spec", returns);
        var meta = manifest.putObject("ontology_meta");
        for (String field : new String[]{"spaceCode", "releaseId", "version", "contentDigest"}) meta.putNull(field);
        meta.put("preview", true);
        ContractValidator.validateBody(manifest);
        return manifest;
    }

    /** 正式化不修改原预览，预分配标识非法时不得生成可归档的正式 manifest。 */
    public ObjectNode formalize(JsonNode preview, String baseUrl, String spaceCode,
                                String releaseId, String version, String sha256) {
        if (!preview.path("ontology_meta").path("preview").asBoolean(false))
            throw ContractFields.problem("PREVIEW_REQUIRED", "ontology_meta");
        ContractValidator.validateBody(preview);
        ObjectNode result = (ObjectNode) preview.deepCopy();
        var meta = result.putObject("ontology_meta");
        meta.put("spaceCode", spaceCode).put("releaseId", releaseId).put("version", version).put("contentDigest", sha256);
        result.put("endpoint", base(baseUrl) + "/onto/r/" + segment(spaceCode) + "/releases/"
                + segment(releaseId) + "/ops/" + segment(ContractFields.text(result, "tool_code")));
        ContractValidator.validateImport(result);
        return result;
    }

    /** 业务 Schema 放入 data；消费侧必须针对原始响应校验，meta 不能是可选字段。 */
    private ObjectNode envelope(JsonNode output) {
        ObjectNode envelope = JSON.createObjectNode().put("type", "object");
        envelope.putArray("required").add("code").add("data").add("meta");
        var props = envelope.putObject("properties");
        props.putObject("code").put("const", "OK");
        JsonNode business = output.deepCopy();
        relocatePointers(business);
        props.set("data", business);
        var meta = props.putObject("meta").put("type", "object");
        meta.putArray("required").add("spaceCode").add("releaseId").add("version").add("contentDigest");
        var fields = meta.putObject("properties");
        for (String key : new String[]{"spaceCode", "releaseId", "version"})
            fields.putObject(key).put("type", "string");
        fields.putObject("contentDigest").put("type", "string").put("pattern", "^[0-9a-f]{64}$");
        return envelope;
    }

    /**
     * 业务 Schema 包入 data 后，原本指向业务根的 JSON Pointer 也必须迁移。
     * 只调整与业务根共享资源的本地指针；遇到 $id 后已有独立资源基址，整棵资源保持原样。
     * 命名锚点由标准库按各自资源作用域解析。
     */
    private void relocatePointers(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("$id")) {
                return;
            }
            for (String key : new String[]{"$ref", "$dynamicRef"}) {
                JsonNode ref = object.path(key);
                if (ref.isTextual()) {
                    String fragment = URI.create(ref.textValue()).getFragment();
                    if (fragment.isEmpty() || fragment.startsWith("/")) {
                        object.put(key, SchemaReferences.fragmentReference("/properties/data" + fragment));
                    }
                }
            }
            SchemaTraversal.children(object, (child, pointer) -> relocatePointers(child));
        }
    }

    private String base(String url) {
        ContractFields.httpUrl(url);
        if (url.contains("?")) throw ContractFields.problem("INVALID_URL", "baseUrl");
        return url.replaceAll("/+$", "");
    }

    private String segment(String value) {
        if (value == null || value.isBlank()) throw ContractFields.problem("MISSING_FIELD", "route");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
