package cn.ontorepo.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.Test;

/** 协议资源与SQL标识符是两个边界；多资源声明必须无歧义。 */
class P3GuardTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode op() throws Exception {
        return json.readTree("{\"code\":\"q\",\"assistant_tool\":{\"guard_attrs\":[\"region\"]}}");
    }

    @Test
    void acceptsProtocolResourcesAndExplicitJoinBindings() throws Exception {
        for (String resource :
                new String[] {"public.tasks", "123.csv", "/items", "prefix/file.parquet"}) {
            var cfg = json.createObjectNode();
            var d = cfg.putObject("region").put("type", "STRING");
            d.putArray("filter_bindings")
                    .addObject()
                    .put("table", resource)
                    .put("column", "region")
                    .put("operator", "IN");
            assertDoesNotThrow(() -> GuardValidator.validate(op(), cfg));
        }
    }

    @Test
    void rejectsDuplicateAndConflictingBindings() throws Exception {
        for (String binding :
                new String[] {
                    "\"filter_bindings\":[{\"table\":\"a\",\"column\":\"r\",\"operator\":\"IN\"},{\"table\":\"a\",\"column\":\"r\",\"operator\":\"IN\"}]",
                    "\"filter_bindings\":[],\"filter_binding\":{\"table\":\"a\",\"column\":\"r\",\"operator\":\"IN\"}"
                }) {
            var cfg = json.readTree("{\"region\":{\"type\":\"STRING\"," + binding + "}}");
            assertThrows(ContractProblem.class, () -> GuardValidator.validate(op(), cfg));
        }
        assertFalse(GuardValidator.identifier("schema.table"));
    }
}
