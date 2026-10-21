package cn.ontorepo.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原详设四段样例是互相制约的契约夹具；不能把能解析 JSON 等同于逐项通过。 */
class ContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode fixture(int index) throws Exception {
        try (var input = getClass().getResourceAsStream("/sample-" + index + ".json")) {
            assertNotNull(input);
            return JSON.readTree(input);
        }
    }

    private ObjectNode operation() throws Exception {
        var manifest = fixture(2);
        var op = JSON.createObjectNode().put("code", "task_used_algorithms");
        op.set("assistant_tool", fixture(1));
        op.set("input_schema", manifest.path("input_schema"));
        op.set("output_schema", manifest.at("/output_schema/properties/data"));
        op.set("policy", JSON.readTree("{\"timeout_ms\":5000,\"max_rows\":200}"));
        return op;
    }

    private ObjectNode guards() throws Exception {
        return (ObjectNode)
                JSON.readTree(
                        """
                        {"region":{"type":"STRING","filter_binding":{"table":"task_table","column":"region_col","operator":"IN"}},
                         "tenant_label":{"type":"STRING","filter_binding":{"table":"task_table","column":"tenant_col","operator":"EQ"}}}
                        """);
    }

    @Test
    void fourSamplesParseAndEveryContractStageIsValidated() throws Exception {
        var op = operation();
        var manifest = fixture(2);
        var response = fixture(3);
        var imported = fixture(4).path("skillVersion");
        GuardValidator.validate(op, guards());
        for (var example : fixture(1).path("param_examples"))
            JsonSchemaAssertions.validate(manifest.path("input_schema"), example);
        JsonSchemaAssertions.validate(manifest.path("output_schema"), response);
        JsonSchemaAssertions.validate(op.path("output_schema"), response.path("data"));
        assertEquals(manifest.path("ontology_meta"), response.path("meta"));
        assertEquals(manifest.path("output_schema"), imported.path("outputSchema"));
        assertEquals(manifest.path("guard_attrs"), imported.path("guardAttrs"));
        assertEquals(manifest.path("return_spec"), imported.path("returnSpec"));
        assertEquals(5000, imported.path("timeoutMs").intValue());
        assertEquals("SYNC", imported.path("invocationKind").textValue());
        assertEquals("HTTP", imported.path("adapterType").textValue());
        ContractValidator.validateImport(manifest);
        var copy = op.deepCopy();
        var preview =
                new ContractGenerator()
                        .preview(op, guards(), "https://onto.example.com", "sp_001", "candidate_1");
        for (String field : new String[] {"spaceCode", "releaseId", "version", "contentDigest"})
            assertTrue(preview.path("ontology_meta").path(field).isNull(), field);
        var formal =
                new ContractGenerator()
                        .formalize(
                                preview,
                                "https://onto.example.com",
                                "sp_001",
                                "rel_001",
                                "3",
                                manifest.at("/ontology_meta/contentDigest").textValue());
        assertEquals(manifest, formal);
        assertEquals(copy, op, "生成过程不得修改传入声明");
        assertTrue(preview.path("ontology_meta").path("preview").booleanValue());
    }

    /** 第④段是消费侧导入结果示例，不是本工程已接入外部技能中心的证据。 逐字段从第②段生成期望映射，并显式检查消费侧身份和缺省值；整对象比较同时防止漏字段。 */
    @Test
    void importedSampleMapsEveryManifestFieldAndConsumerDefault() throws Exception {
        var manifest = fixture(2);
        var expected = JSON.createObjectNode();
        expected.put("skillId", "SK-210").put("versionNo", 1).put("status", "DRAFT");
        for (var mapping :
                new String[][] {
                    {"invocationKind", "/invocation_kind"},
                    {"adapterType", "/adapter_type"},
                    {"category", "/category"},
                    {"endpoint", "/endpoint"},
                    {"httpMethod", "/http_method"},
                    {"timeoutMs", "/exec_attrs/timeout_ms"},
                    {"inputSchema", "/input_schema"},
                    {"outputSchema", "/output_schema"},
                    {"guardAttrs", "/guard_attrs"},
                    {"readonlyFlag", "/exec_attrs/readonly_flag"},
                    {"sideEffect", "/exec_attrs/side_effect"},
                    {"idempotent", "/exec_attrs/idempotent"},
                    {"cancellable", "/exec_attrs/cancellable"},
                    {"riskLevel", "/risk_level"},
                    {"errorSemantics", "/error_semantics"},
                    {"returnSpec", "/return_spec"},
                    {"ontologyMetaExpectation", "/ontology_meta"}
                }) {
            var source = manifest.at(mapping[1]);
            assertFalse(source.isMissingNode(), mapping[1]);
            expected.set(mapping[0], source);
        }
        // 本例没有传入认证引用、并发数和响应上限；超时则由 manifest 显式覆盖消费侧缺省。
        assertFalse(manifest.has("auth_ref"));
        assertFalse(manifest.path("exec_attrs").has("concurrency_limit"));
        assertFalse(manifest.path("exec_attrs").has("output_max_bytes"));
        expected.putNull("authRef").put("concurrencyLimit", 10).put("outputMaxBytes", 1048576);
        expected.set(
                "defaultsApplied",
                JSON.createObjectNode()
                        .put("timeoutMs", "manifest 覆盖缺省 30000→5000")
                        .put("concurrencyLimit", "缺省 10")
                        .put("outputMaxBytes", "缺省 1048576"));
        var expectedEnvelope = JSON.createObjectNode().put("result", "ACCEPTED");
        expectedEnvelope.set("skillVersion", expected);
        assertEquals(expectedEnvelope, fixture(4), "第④段全部字段必须符合导入映射和缺省规则");
        for (var example : fixture(1).path("param_examples"))
            JsonSchemaAssertions.validate(fixture(4).at("/skillVersion/inputSchema"), example);
        JsonSchemaAssertions.validate(fixture(4).at("/skillVersion/outputSchema"), fixture(3));
    }

    @Test
    void successWithoutMetaFailsWholeEnvelopeSchema() throws Exception {
        ObjectNode response = (ObjectNode) fixture(3);
        response.remove("meta");
        assertThrows(
                ContractProblem.class,
                () -> JsonSchemaAssertions.validate(fixture(2).path("output_schema"), response));
    }

    @Test
    void missingTenantBindingAndUnknownGuardNeverPass() throws Exception {
        var config = guards();
        ((ObjectNode) config.path("tenant_label")).remove("filter_binding");
        var error =
                assertThrows(
                        ContractProblem.class, () -> GuardValidator.validate(operation(), config));
        assertEquals("GUARD_ATTR_UNRESOLVED", error.code());
        assertEquals("NO_FILTER_BINDING", error.reason());
        config.remove("tenant_label");
        assertEquals(
                "UNDEFINED",
                assertThrows(
                                ContractProblem.class,
                                () -> GuardValidator.validate(operation(), config))
                        .reason());
    }

    @Test
    void emptyGuardsAndMissingExamplesFailWithExactErrors() throws Exception {
        var op = operation();
        ((ObjectNode) op.path("assistant_tool")).remove("guard_attrs");
        assertEquals(
                "MISSING_GUARD_ATTRS",
                assertThrows(ContractProblem.class, () -> GuardValidator.validate(op, guards()))
                        .code());
        var noExample = operation();
        ((ObjectNode) noExample.path("assistant_tool")).remove("param_examples");
        assertEquals(
                "MISSING_PARAM_EXAMPLES",
                assertThrows(
                                ContractProblem.class,
                                () ->
                                        new ContractGenerator()
                                                .preview(
                                                        noExample,
                                                        guards(),
                                                        "https://onto.example.com",
                                                        "sp",
                                                        "c"))
                        .code());
    }

    @Test
    void parameterExamplesUseActualSchema() throws Exception {
        var op = operation();
        ((ObjectNode) op.path("assistant_tool"))
                .set("param_examples", JSON.readTree("[{\"taskId\":123}]"));
        assertThrows(
                ContractProblem.class,
                () ->
                        new ContractGenerator()
                                .preview(op, guards(), "https://onto.example.com", "sp", "c"));
    }

    @Test
    void unsupportedEnumMissingFieldsAndWrongTypesAreRejected() throws Exception {
        ObjectNode m = (ObjectNode) fixture(2);
        m.put("invocation_kind", "HTTP");
        assertEquals(
                "INVALID_ENUM",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(m))
                        .code());
        m.put("invocation_kind", "SYNC");
        m.remove("adapter_type");
        assertEquals(
                "MISSING_FIELD",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(m))
                        .code());
        m.put("adapter_type", "HTTP");
        ((ObjectNode) m.path("exec_attrs")).put("timeout_ms", 1.5);
        assertEquals(
                "TYPE_MISMATCH",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(m))
                        .code());
        ((ObjectNode) m.path("exec_attrs")).put("timeout_ms", 5000);
        m.put("guard_attrs", "region");
        assertEquals(
                "TYPE_MISMATCH",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(m))
                        .code());
    }

    @Test
    void previewCannotBeConsumedAsReleaseAndRisksCannotBeDowngraded() throws Exception {
        var op = operation();
        var preview =
                new ContractGenerator()
                        .preview(op, guards(), "https://onto.example.com", "sp", "c");
        assertEquals(
                "PREVIEW_NOT_RELEASE",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(preview))
                        .code());
        ((ObjectNode) op.at("/assistant_tool/exec")).put("readonly", false);
        assertEquals(
                "RISK_DOWNGRADE",
                assertThrows(
                                ContractProblem.class,
                                () ->
                                        new ContractGenerator()
                                                .preview(
                                                        op,
                                                        guards(),
                                                        "https://onto.example.com",
                                                        "sp",
                                                        "c"))
                        .code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://external.invalid/schema",
                "file:///secret/schema",
                "classpath:private.json"
            })
    void schemasNeverRetrieveExternalReferences(String ref) throws Exception {
        var schema = JSON.createObjectNode().put("$ref", ref);
        assertEquals(
                "EXTERNAL_SCHEMA_REF",
                assertThrows(
                                ContractProblem.class,
                                () ->
                                        JsonSchemaAssertions.validate(
                                                schema, JSON.createObjectNode()))
                        .code());
    }

    @Test
    void localDefsAndAdditionalPropertiesReallyValidate() throws Exception {
        var schema =
                JSON.readTree(
                        """
                        {"type":"object","required":["id"],"additionalProperties":false,
                         "$defs":{"identifier":{"type":"string","pattern":"^T-[0-9]+$"}},
                         "properties":{"id":{"$ref":"#/$defs/identifier"}}}
                        """);
        JsonSchemaAssertions.validate(schema, JSON.readTree("{\"id\":\"T-1\"}"));
        assertThrows(
                ContractProblem.class,
                () -> JsonSchemaAssertions.validate(schema, JSON.readTree("{\"id\":\"bad\"}")));
        assertThrows(
                ContractProblem.class,
                () ->
                        JsonSchemaAssertions.validate(
                                schema, JSON.readTree("{\"id\":\"T-1\",\"extra\":true}")));
    }

    @Test
    void wrappingBusinessSchemaPreservesLocalReferenceMeaning() throws Exception {
        var op = operation();
        op.set(
                "output_schema",
                JSON.readTree(
                        """
                        {"type":"object","required":["stdCode"],"$defs":{"code":{"type":"string","pattern":"^ALG-[0-9]+$"}},
                         "properties":{"stdCode":{"$ref":"#/$defs/code"}}}
                        """));
        var preview =
                new ContractGenerator()
                        .preview(op, guards(), "https://onto.example.com", "sp", "c");
        JsonSchemaAssertions.validate(preview.path("output_schema"), fixture(3));
        ObjectNode wrong = (ObjectNode) fixture(3);
        ((ObjectNode) wrong.path("data")).put("stdCode", "bad");
        assertThrows(
                ContractProblem.class,
                () -> JsonSchemaAssertions.validate(preview.path("output_schema"), wrong));
    }
}
