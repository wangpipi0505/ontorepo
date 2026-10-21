package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 非推进循环必须在生成期失败，正常递归仍由标准库逐层验证真实实例。 */
class SchemaRecursionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode fixture(int index) throws Exception {
        try (var input = getClass().getResourceAsStream("/sample-" + index + ".json")) {
            assertNotNull(input);
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private ObjectNode operation(JsonNode input, JsonNode output) throws Exception {
        ObjectNode operation = JSON.createObjectNode().put("code", "task_used_algorithms");
        operation.set("assistant_tool", fixture(1));
        operation.set("input_schema", input);
        operation.set("output_schema", output);
        operation.set("policy", JSON.readTree("{\"timeout_ms\":5000,\"max_rows\":200}"));
        return operation;
    }

    private ObjectNode preview(JsonNode input, JsonNode output) throws Exception {
        return previewOperation(operation(input, output));
    }

    private ObjectNode previewOperation(ObjectNode operation) throws Exception {
        JsonNode guards = JSON.readTree("""
            {"region":{"type":"STRING","authorization_rule":{"kind":"PRINCIPAL_ATTRIBUTE","attribute":"region"}},
             "tenant_label":{"type":"STRING","authorization_rule":{"kind":"PRINCIPAL_ATTRIBUTE","attribute":"tenant"}}}
            """);
        return new ContractGenerator().preview(operation, guards,
                "https://onto.example.com", "sp_001", "candidate_1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"$ref\":\"#\"}",
            "{\"$anchor\":\"loop\",\"$ref\":\"#loop\"}",
            "{\"$dynamicAnchor\":\"loop\",\"$dynamicRef\":\"#loop\"}",
            "{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},\"b\":{\"$ref\":\"#/$defs/a\"}},\"$ref\":\"#/$defs/a\"}",
            "{\"$defs\":{\"unused\":{\"$ref\":\"#/$defs/unused\"}}}",
            "{\"allOf\":[{\"$ref\":\"#\"}]}",
            "{\"anyOf\":[{\"$ref\":\"#\"},{\"type\":\"string\"}]}",
            "{\"oneOf\":[{\"$ref\":\"#\"},{\"type\":\"string\"}]}",
            "{\"not\":{\"$ref\":\"#\"}}",
            "{\"if\":{\"$ref\":\"#\"},\"then\":true}",
            "{\"if\":true,\"then\":{\"$ref\":\"#\"}}",
            "{\"if\":false,\"else\":{\"$ref\":\"#\"}}",
            "{\"dependentSchemas\":{\"taskId\":{\"$ref\":\"#\"}}}",
            "{\"dependencies\":{\"taskId\":{\"$ref\":\"#\"}}}",
            "{\"properties\":{\"child\":{\"$id\":\"urn:example:child\",\"$ref\":\"#\"}}}",
            "{\"$defs\":{\"a/b~c\":{\"$ref\":\"#%2F$defs%2Fa~1b~0c\"}}}",
            """
            {"$dynamicAnchor":"loop","allOf":[
                {"$id":"urn:example:inner","$defs":{"target":{"$dynamicAnchor":"loop","type":"object"}},
                 "$dynamicRef":"#loop"}]}
            """
    })
    void nonAdvancingCyclesFailAtEveryContractBoundary(String text) throws Exception {
        ObjectNode cycle = (ObjectNode) JSON.readTree(text);
        JsonNode emptySchema = JSON.createObjectNode();
        ObjectNode importInput = fixture(2);
        importInput.set("input_schema", cycle);
        ObjectNode importOutput = fixture(2);
        ObjectNode outputResource = cycle.deepCopy().put("$id", "urn:example:cyclic-output");
        ((ObjectNode) importOutput.at("/output_schema/properties")).set("data", outputResource);
        ObjectNode formalization = preview(emptySchema, emptySchema);
        formalization.set("input_schema", cycle);
        assertAll(
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> JsonSchemaAssertions.validateSchema(cycle)).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> JsonSchemaAssertions.validate(cycle, JSON.readTree("{\"taskId\":\"T-1\"}"))).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> preview(cycle, emptySchema)).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> preview(emptySchema, cycle)).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> ContractValidator.validateImport(importInput)).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> ContractValidator.validateImport(importOutput)).code()),
                () -> assertEquals("INVALID_SCHEMA", assertThrows(ContractProblem.class,
                        () -> new ContractGenerator().formalize(formalization, "https://onto.example.com",
                                "sp", "rel", "1", "a".repeat(64))).code()));
    }

    static Stream<Arguments> advancingRecursion() {
        return Stream.of(
                Arguments.of("""
                    {"type":"object","required":["value"],"properties":{
                        "value":{"type":"string"},"child":{"$ref":"#"}}}
                    """, "{\"value\":\"one\",\"child\":{\"value\":\"two\"}}",
                        "{\"value\":\"one\",\"child\":{\"value\":false}}"),
                Arguments.of("""
                    {"$anchor":"tree","anyOf":[{"type":"string"},
                        {"type":"array","items":{"$ref":"#tree"}}]}
                    """, "[\"one\",[\"two\"]]", "[\"one\",[false]]"),
                Arguments.of("""
                    {"$dynamicAnchor":"tree","anyOf":[{"type":"string"},
                        {"type":"array","prefixItems":[{"$dynamicRef":"#tree"}],"items":false}]}
                    """, "[[\"leaf\"]]", "[[false]]"),
                Arguments.of("""
                    {"anyOf":[{"type":"string"},
                        {"type":"array","contains":{"$ref":"#"},"minContains":1}]}
                    """, "[[\"leaf\"]]", "[[false]]"),
                Arguments.of("""
                    {"anyOf":[{"type":"string"},{"type":"object",
                        "additionalProperties":{"$ref":"#"}}]}
                    """, "{\"one\":{\"two\":\"leaf\"}}", "{\"one\":{\"two\":false}}"),
                Arguments.of("""
                    {"anyOf":[{"type":"string"},{"type":"object",
                        "patternProperties":{"^child":{"$ref":"#"}},"additionalProperties":false}]}
                    """, "{\"child1\":{\"child2\":\"leaf\"}}", "{\"child1\":{\"child2\":false}}"),
                Arguments.of("""
                    {"anyOf":[{"type":"string"},{"type":"object",
                        "unevaluatedProperties":{"$ref":"#"}}]}
                    """, "{\"one\":{\"two\":\"leaf\"}}", "{\"one\":{\"two\":false}}"),
                Arguments.of("""
                    {"anyOf":[{"type":"string"},{"type":"array",
                        "unevaluatedItems":{"$ref":"#"}}]}
                    """, "[[\"leaf\"]]", "[[false]]"),
                Arguments.of("""
                    {"$defs":{
                        "a":{"type":"object","required":["value"],"properties":{
                            "value":{"type":"string"},"child":{"$ref":"#/$defs/b"}}},
                        "b":{"type":"object","required":["value"],"properties":{
                            "value":{"type":"string"},"child":{"$ref":"#/$defs/a"}}}
                     },"$ref":"#/$defs/a"}
                    """, "{\"value\":\"one\",\"child\":{\"value\":\"two\",\"child\":{\"value\":\"three\"}}}",
                        "{\"value\":\"one\",\"child\":{\"value\":\"two\",\"child\":{\"value\":false}}}"));
    }

    @ParameterizedTest
    @MethodSource("advancingRecursion")
    void recursionThroughChildInstancesRemainsValid(String text, String valid, String invalid) throws Exception {
        JsonNode schema = JSON.readTree(text);
        JsonNode original = schema.deepCopy();
        JsonSchemaAssertions.validateSchema(schema);
        JsonSchemaAssertions.validate(schema, JSON.readTree(valid));
        assertEquals("SCHEMA_VALIDATION_FAILED", assertThrows(ContractProblem.class,
                () -> JsonSchemaAssertions.validate(schema, JSON.readTree(invalid))).code());
        ObjectNode inputOperation = operation(schema, JSON.createObjectNode());
        ((ObjectNode) inputOperation.path("assistant_tool")).putArray("param_examples").add(JSON.readTree(valid));
        assertDoesNotThrow(() -> previewOperation(inputOperation));
        ((ObjectNode) inputOperation.path("assistant_tool")).putArray("param_examples").add(JSON.readTree(invalid));
        assertEquals("SCHEMA_VALIDATION_FAILED", assertThrows(ContractProblem.class,
                () -> previewOperation(inputOperation)).code());
        ObjectNode preview = preview(JSON.createObjectNode(), schema);
        ObjectNode response = fixture(3);
        response.set("data", JSON.readTree(valid));
        JsonSchemaAssertions.validate(preview.path("output_schema"), response);
        response.set("data", JSON.readTree(invalid));
        assertEquals("SCHEMA_VALIDATION_FAILED", assertThrows(ContractProblem.class,
                () -> JsonSchemaAssertions.validate(preview.path("output_schema"), response)).code());
        assertEquals(original, schema);
    }
}
