package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.SchemaLoader;
import java.net.URI;

/** 使用 Draft 2020-12 标准校验 Schema 和实例；外部引用在交给校验器之前拒绝。 */
public final class JsonSchemaAssertions {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    // 无检索来源的内联 Schema 也需要稳定基址，以解析合法的相对 $id；此地址永不加载。
    private static final SchemaLocation INLINE_LOCATION =
            SchemaLocation.of("https://schemas.ontorepo.invalid/inline.json");
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .typeLoose(false).formatAssertionsEnabled(true).build())
                    .schemaLoader(SchemaLoader.builder().allow(iri ->
                            iri.toString().equals(DIALECT)
                                    || iri.toString().startsWith("https://json-schema.org/draft/2020-12/meta/"))
                            .build()));

    private JsonSchemaAssertions() {}

    /** 先验证 Schema 本身，防止错误类型或未知方言被误当作无约束的 schema。 */
    public static void validateSchema(JsonNode schema) {
        if (schema == null || (!schema.isObject() && !schema.isBoolean()))
            throw ContractFields.problem("INVALID_SCHEMA", "schema");
        if (schema.toString().length() > 262144) throw ContractFields.problem("SCHEMA_TOO_LARGE", "schema");
        SchemaTraversal.walk(schema, JsonSchemaAssertions::inspect);
        try {
            var errors = REGISTRY.getSchema(SchemaLocation.of(DIALECT))
                    .validate(schema.toString(), InputFormat.JSON);
            if (!errors.isEmpty()) throw new ContractProblem("INVALID_SCHEMA", "schema", errors.getFirst().getMessage());
            SchemaReferences.validate(schema);
            // 元 Schema 只证明语法有效；显式预加载才会让库在门禁阶段编译并解析实际引用。
            compile(schema).initializeValidators();
        } catch (ContractProblem e) {
            throw e;
        } catch (RuntimeException e) {
            throw ContractFields.problem("INVALID_SCHEMA", "schema");
        }
    }

    /** 不对输入作标量强制转换，包含 additionalProperties、组合关键字和本地 $defs 的完整断言。 */
    public static void validate(JsonNode schema, JsonNode instance) {
        validateSchema(schema);
        if (instance == null || instance.toString().length() > 1048576)
            throw ContractFields.problem("INVALID_INSTANCE", "instance");
        try {
            var errors = compile(schema)
                    .validate(instance.toString(), InputFormat.JSON);
            if (!errors.isEmpty())
                throw new ContractProblem("SCHEMA_VALIDATION_FAILED", "instance", errors.getFirst().getMessage());
        } catch (ContractProblem e) {
            throw e;
        } catch (RuntimeException e) {
            throw ContractFields.problem("INVALID_SCHEMA", "schema");
        }
    }

    private static Schema compile(JsonNode schema) {
        JsonNode normalized = schema.deepCopy();
        SchemaTraversal.walk(normalized, (node, pointer) -> {
            if (node instanceof ObjectNode object) {
                for (String keyword : new String[]{"$ref", "$dynamicRef"}) {
                    if (object.has(keyword)) {
                        String fragment = URI.create(object.path(keyword).textValue()).getFragment();
                        object.put(keyword, SchemaReferences.fragmentReference(fragment));
                    }
                }
            }
        });
        return REGISTRY.getSchema(INLINE_LOCATION, normalized.toString(), InputFormat.JSON);
    }

    /** 只允许文档内引用；资源的 $id 由标准库处理，方言固定为本工具声明的版本。 */
    private static void inspect(JsonNode node, String pointer) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ((key.equals("$ref") || key.equals("$dynamicRef"))
                        && (!value.isTextual() || !value.textValue().startsWith("#")))
                    throw ContractFields.problem("EXTERNAL_SCHEMA_REF", key);
                if (key.equals("$schema") && (!value.isTextual() || !DIALECT.equals(value.textValue())))
                    throw ContractFields.problem("UNSUPPORTED_DIALECT", key);
            });
        }
    }
}
