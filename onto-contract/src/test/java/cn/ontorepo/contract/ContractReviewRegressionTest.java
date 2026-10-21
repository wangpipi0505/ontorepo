package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 审查反例直接调用公开契约 API，检查生成、导入及实际响应断言的一致性。 */
class ContractReviewRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode fixture(int index) throws Exception {
        try (var input = getClass().getResourceAsStream("/sample-" + index + ".json")) {
            assertNotNull(input);
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private ObjectNode operation(JsonNode output) throws Exception {
        ObjectNode operation = JSON.createObjectNode().put("code", "task_used_algorithms");
        operation.set("assistant_tool", fixture(1));
        operation.set("input_schema", fixture(2).path("input_schema"));
        operation.set("output_schema", output);
        operation.set("policy", JSON.readTree("{\"timeout_ms\":5000,\"max_rows\":200}"));
        return operation;
    }

    private ObjectNode guards() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"region":{"type":"STRING","filter_binding":{"table":"task_table","column":"region_col","operator":"IN"}},
             "tenant_label":{"type":"STRING","filter_binding":{"table":"task_table","column":"tenant_col","operator":"EQ"}}}
            """);
    }

    private ObjectNode preview(JsonNode output) throws Exception {
        return new ContractGenerator().preview(operation(output), guards(),
                "https://onto.example.com", "sp_001", "candidate_1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"const", "enum", "default", "examples", "x-annotation"})
    void wrappingDoesNotRewriteInstanceValuesOrUnknownAnnotations(String keyword) throws Exception {
        JsonNode literal = JSON.readTree("{\"$ref\":\"#/literal\"}");
        ObjectNode schema = JSON.createObjectNode();
        if (keyword.equals("enum") || keyword.equals("examples")) {
            schema.putArray(keyword).add(literal);
        } else {
            schema.set(keyword, literal);
        }
        JsonSchemaAssertions.validate(schema, literal);
        JsonNode original = schema.deepCopy();
        ObjectNode preview = preview(schema);
        assertEquals(original, schema, "生成不能修改传入 Schema");
        assertEquals(original, preview.at("/output_schema/properties/data"),
                "实例字面量与未知注释不能被当作 Schema 引用迁移");
        ObjectNode response = fixture(3);
        response.set("data", literal);
        JsonSchemaAssertions.validate(preview.path("output_schema"), response);
    }

    @Test
    void keywordLikePropertyNamesAreOrdinaryBusinessFields() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"type":"object","required":["$ref","$id","$schema"],
             "properties":{"$ref":{"type":"string"},"$id":{"type":"string"},"$schema":{"type":"string"}}}
            """);
        JsonNode data = JSON.readTree("{\"$ref\":\"literal\",\"$id\":\"id\",\"$schema\":\"text\"}");
        JsonSchemaAssertions.validate(schema, data);
        ObjectNode response = fixture(3);
        response.set("data", data);
        JsonSchemaAssertions.validate(preview(schema).path("output_schema"), response);
    }

    @Test
    void externalLookingReferencesInsideAnnotationsStayOpaque() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"type":"string","default":{"$ref":"https://example.invalid/data"},
             "x-annotation":{"$schema":"not-a-dialect","$id":"data","$dynamicRef":"file:///data"}}
            """);
        JsonSchemaAssertions.validate(schema, JSON.readTree("\"value\""));
        assertEquals(schema, preview(schema).at("/output_schema/properties/data"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"properties\":{\"stdCode\":{\"$ref\":\"#/$defs/missing\"}}}",
            "{\"$defs\":{\"unused\":{\"$ref\":\"#/$defs/missing\"}}}",
            "{\"if\":{\"const\":false},\"then\":{\"$ref\":\"#/$defs/missing\"}}",
            "{\"$ref\":\"#/title\",\"title\":\"not-a-schema\"}",
            "{\"$ref\":\"#/required\",\"required\":[]}",
            "{\"$ref\":\"#/properties\",\"properties\":{\"value\":true}}",
            "{\"$ref\":\"#missing\"}",
            "{\"$dynamicRef\":\"#missing\"}"
    })
    void unresolvedOrNonSchemaLocalTargetsFailBeforeRuntime(String text) throws Exception {
        JsonNode schema = JSON.readTree(text);
        assertEquals("INVALID_SCHEMA",
                assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validateSchema(schema)).code());
        assertEquals("INVALID_SCHEMA",
                assertThrows(ContractProblem.class, () -> preview(schema)).code());
        ObjectNode manifest = fixture(2);
        // 在导入文档中也保留同一个缺陷，不依赖响应数据是否触发该分支。
        ((ObjectNode) manifest.at("/output_schema/properties")).set("data", schema);
        assertEquals("INVALID_SCHEMA",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(manifest)).code());
    }

    @Test
    void validNamedAnchorsAndBooleanTargetsRemainUsable() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"$defs":{"named":{"$anchor":"code","type":"string","pattern":"^ALG-"},
                      "allowed":true,"forbidden":false},
             "type":"object","properties":{"stdCode":{"$ref":"#code"},"extra":{"$ref":"#/$defs/allowed"},
                                           "forbidden":{"$ref":"#/$defs/forbidden"}}}
            """);
        JsonSchemaAssertions.validateSchema(schema);
        JsonSchemaAssertions.validate(schema, JSON.readTree("{\"stdCode\":\"ALG-001\",\"extra\":17}"));
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(
                schema, JSON.readTree("{\"stdCode\":\"bad\"}")));
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(
                schema, JSON.readTree("{\"forbidden\":true}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "root.type", "root.required.code", "root.required.data", "root.required.meta",
            "code.const", "data.schema", "meta.type",
            "meta.required.spaceCode", "meta.required.releaseId", "meta.required.version", "meta.required.contentDigest",
            "meta.type.spaceCode", "meta.type.releaseId", "meta.type.version", "meta.type.contentDigest",
            "digest.pattern", "digest.weakPattern"
    })
    void importRejectsWeakenedResponseEnvelope(String defect) throws Exception {
        ObjectNode manifest = fixture(2);
        ObjectNode schema = (ObjectNode) manifest.path("output_schema");
        ObjectNode meta = (ObjectNode) schema.at("/properties/meta");
        if (defect.startsWith("root.required.")) {
            removeRequired(schema, defect.substring("root.required.".length()));
        } else if (defect.startsWith("meta.required.")) {
            removeRequired(meta, defect.substring("meta.required.".length()));
        } else if (defect.startsWith("meta.type.")) {
            ((ObjectNode) meta.path("properties").path(defect.substring("meta.type.".length())))
                    .put("type", "boolean");
        } else {
            switch (defect) {
                case "root.type" -> schema.remove("type");
                case "code.const" -> ((ObjectNode) schema.at("/properties/code")).remove("const");
                case "data.schema" -> ((ObjectNode) schema.path("properties")).remove("data");
                case "meta.type" -> meta.remove("type");
                case "digest.pattern" -> ((ObjectNode) meta.at("/properties/contentDigest")).remove("pattern");
                case "digest.weakPattern" -> ((ObjectNode) meta.at("/properties/contentDigest"))
                        .put("pattern", "^[0-9a-f]*$");
                default -> fail("未知测试缺陷：" + defect);
            }
        }
        assertEquals("INVALID_OUTPUT_ENVELOPE",
                assertThrows(ContractProblem.class, () -> ContractValidator.validateImport(manifest)).code());
    }

    private void removeRequired(ObjectNode schema, String field) {
        var required = schema.putArray("required");
        String[] fields = field.equals("code") || field.equals("data") || field.equals("meta")
                ? new String[]{"code", "data", "meta"}
                : new String[]{"spaceCode", "releaseId", "version", "contentDigest"};
        for (String name : fields) {
            if (!name.equals(field)) {
                required.add(name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"urn:example:business", "https://example.invalid/business", "business.schema.json"})
    void businessRootIdPreservesItsLocalReferenceScope(String id) throws Exception {
        ObjectNode schema = (ObjectNode) JSON.readTree("""
            {"type":"object","required":["stdCode"],
             "$defs":{"code":{"type":"string","pattern":"^ALG-[0-9]+$"}},
             "properties":{"stdCode":{"$ref":"#/$defs/code"}}}
            """);
        schema.put("$id", id);
        JsonSchemaAssertions.validate(schema, fixture(3).path("data"));
        ObjectNode preview = assertDoesNotThrow(() -> preview(schema));
        assertEquals(schema, preview.at("/output_schema/properties/data"),
                "有 $id 的业务根是独立资源，引用不得迁移到外层信封");
        JsonSchemaAssertions.validate(preview.path("output_schema"), fixture(3));
        ObjectNode invalid = fixture(3);
        ((ObjectNode) invalid.path("data")).put("stdCode", 42);
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(preview.path("output_schema"), invalid));
        ObjectNode formal = new ContractGenerator().formalize(preview, "https://onto.example.com",
                "sp_001", "rel_001", "3", fixture(2).at("/ontology_meta/contentDigest").textValue());
        ContractValidator.validateImport(formal);
        assertEquals(preview.path("output_schema"), formal.path("output_schema"));
    }

    @Test
    void embeddedResourcesKeepTheirOwnAnchorsAndPointers() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"type":"object","required":["stdCode","nested"],
             "$defs":{"code":{"$anchor":"code","type":"string","pattern":"^ALG-" }},
             "properties":{"stdCode":{"$ref":"#code"},
                 "nested":{"$id":"urn:example:nested","type":"object","required":["value"],
                           "$defs":{"code":{"$anchor":"code","type":"integer"}},
                           "properties":{"value":{"$ref":"#/$defs/code"}}}}}
            """);
        JsonNode data = JSON.readTree("{\"stdCode\":\"ALG-1\",\"nested\":{\"value\":4}}");
        assertDoesNotThrow(() -> JsonSchemaAssertions.validate(schema, data));
        ObjectNode preview = preview(schema);
        assertEquals("#/$defs/code", preview.at(
                "/output_schema/properties/data/properties/nested/properties/value/$ref").textValue());
        ObjectNode response = fixture(3);
        response.set("data", data);
        JsonSchemaAssertions.validate(preview.path("output_schema"), response);
        ((ObjectNode) data.path("nested")).put("value", "ALG-1");
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(preview.path("output_schema"), response));
    }

    @Test
    void recursiveDynamicAnchorSurvivesRootIdWrapping() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"$id":"urn:example:tree","$dynamicAnchor":"node","type":"object",
             "required":["value"],"properties":{"value":{"type":"string"},"child":{"$dynamicRef":"#node"}}}
            """);
        ObjectNode response = fixture(3);
        response.set("data", JSON.readTree("{\"value\":\"one\",\"child\":{\"value\":\"two\"}}"));
        ObjectNode preview = assertDoesNotThrow(() -> preview(schema));
        JsonSchemaAssertions.validate(preview.path("output_schema"), response);
        ((ObjectNode) response.at("/data/child")).put("value", false);
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(preview.path("output_schema"), response));
    }

    @Test
    void httpIdAndLocalReferencesNeverOpenConnections() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(100);
            ObjectNode schema = (ObjectNode) JSON.readTree("""
                {"$defs":{"value":{"type":"string"}},"$ref":"#/$defs/value"}
                """);
            schema.put("$id", "http://127.0.0.1:" + server.getLocalPort() + "/schema");
            ObjectNode response = fixture(3);
            response.put("data", "value");
            ObjectNode preview = assertDoesNotThrow(() -> preview(schema));
            JsonSchemaAssertions.validate(preview.path("output_schema"), response);
            assertThrows(SocketTimeoutException.class, server::accept);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"table:true", "table:false", "column:true", "column:false",
            "attribute:true", "attribute:false"})
    void booleanGuardIdentifiersNeverBecomeExecutableBindings(String example) throws Exception {
        String[] parts = example.split(":");
        ObjectNode guards = guards();
        ObjectNode region = (ObjectNode) guards.path("region");
        if (parts[0].equals("attribute")) {
            region.remove("filter_binding");
            region.putObject("authorization_rule").put("kind", "PRINCIPAL_ATTRIBUTE")
                    .put("attribute", Boolean.parseBoolean(parts[1]));
        } else {
            ((ObjectNode) region.path("filter_binding")).put(parts[0], Boolean.parseBoolean(parts[1]));
        }
        ObjectNode operation = operation(fixture(2).at("/output_schema/properties/data"));
        ContractProblem problem = assertThrows(ContractProblem.class,
                () -> GuardValidator.validate(operation, guards));
        assertEquals("GUARD_ATTR_UNRESOLVED", problem.code());
        assertEquals("task_used_algorithms.region", problem.field());
        assertEquals("NO_FILTER_BINDING", problem.reason());
    }

    @Test
    void textualPrincipalAttributeIsAnExecutableGuardBinding() throws Exception {
        ObjectNode guards = guards();
        ObjectNode region = (ObjectNode) guards.path("region");
        region.remove("filter_binding");
        region.putObject("authorization_rule").put("kind", "PRINCIPAL_ATTRIBUTE").put("attribute", "region");
        GuardValidator.validate(operation(fixture(2).at("/output_schema/properties/data")), guards);
    }

    @ParameterizedTest
    @ValueSource(strings = {"#%2F$defs%2Fcode", "#/$defs/a~1b~0c", "#/$defs/space%20key"})
    void encodedJsonPointersKeepTheirBusinessTargetAfterWrapping(String ref) throws Exception {
        ObjectNode schema = (ObjectNode) JSON.readTree("""
            {"type":"object","required":["stdCode"],
             "$defs":{"code":{"type":"string"},"a/b~c":{"type":"string"},"space key":{"type":"string"}},
             "properties":{"stdCode":{}}}
            """);
        ((ObjectNode) schema.at("/properties/stdCode")).put("$ref", ref);
        JsonSchemaAssertions.validate(schema, fixture(3).path("data"));
        ObjectNode preview = assertDoesNotThrow(() -> preview(schema));
        JsonSchemaAssertions.validate(preview.path("output_schema"), fixture(3));
        ObjectNode response = fixture(3);
        ((ObjectNode) response.path("data")).put("stdCode", false);
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(preview.path("output_schema"), response));
    }

    @Test
    void legacySchemaDependenciesRetainTheirLocalReferences() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"type":"object","$defs":{"conditional":{"properties":{"stdCode":{"type":"string"}}}},
             "dependencies":{"stdCode":{"$ref":"#/$defs/conditional"},"other":["stdCode"]}}
            """);
        JsonSchemaAssertions.validate(schema, fixture(3).path("data"));
        ObjectNode preview = assertDoesNotThrow(() -> preview(schema));
        JsonSchemaAssertions.validate(preview.path("output_schema"), fixture(3));
        ObjectNode response = fixture(3);
        ((ObjectNode) response.path("data")).put("stdCode", false);
        assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validate(preview.path("output_schema"), response));
    }

    @Test
    void legacyDependenciesCannotHideExternalSchemaReferences() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"dependencies":{"unused":{"$ref":"https://example.invalid/remote"}}}
            """);
        assertEquals("EXTERNAL_SCHEMA_REF",
                assertThrows(ContractProblem.class, () -> JsonSchemaAssertions.validateSchema(schema)).code());
    }
}
