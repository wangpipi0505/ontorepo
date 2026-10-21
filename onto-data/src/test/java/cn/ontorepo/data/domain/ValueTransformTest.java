package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueTransformTest {
  @Test
  void strictConversionsPreserveNullAndRejectInvalidValues() {
    assertEquals(
        ValueTransform.Kind.IDENTITY,
        new QuerySpec.Filter("id", QuerySpec.Operator.EQ, List.of(IntNode.valueOf(1)))
            .transform()
            .kind());
    var number = new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "DECIMAL");
    assertEquals(new BigDecimal("10"), number.apply("10"));
    for (String bad : List.of("", " ", " 2", "true", "NaN"))
      assertEquals(
          "TRANSFORM_FAILED", assertThrows(DataProblem.class, () -> number.apply(bad)).code());
    assertNull(number.apply(null));
    var bool = new ValueTransform(ValueTransform.Kind.TO_BOOLEAN, Map.of(), "BOOLEAN");
    for (Object value : List.of(true, "TrUe", "1", 1)) assertEquals(true, bool.apply(value));
    for (Object value : List.of(false, "FALSE", "0", 0)) assertEquals(false, bool.apply(value));
    assertThrows(DataProblem.class, () -> bool.apply("yes"));
    assertThrows(
        DataProblem.class,
        () -> new ValueTransform(ValueTransform.Kind.TO_NUMBER, Map.of(), "INTEGER").apply("1.5"));
    var date = new ValueTransform(ValueTransform.Kind.TO_STRING, Map.of(), "DATE");
    assertEquals("2024-02-29", date.apply("2024-02-29"));
    assertThrows(DataProblem.class, () -> date.apply("2023-02-29"));
  }

  @Test
  void frozenCodebookRejectsMutableValuesAndBudgets() {
    Map<String, JsonNode> values = new LinkedHashMap<>();
    values.put("1", TextNode.valueOf("approved"));
    values.put("nil", NullNode.instance);
    var transform = new ValueTransform(ValueTransform.Kind.CODEBOOK, values, "STRING");
    values.put("1", TextNode.valueOf("draft"));
    assertEquals("approved", transform.apply(1));
    assertNull(transform.apply("nil"));
    assertThrows(UnsupportedOperationException.class, () -> transform.codebook().clear());
    assertThrows(DataProblem.class, () -> transform.apply("missing"));
    assertThrows(
        DataProblem.class,
        () ->
            new ValueTransform(
                ValueTransform.Kind.CODEBOOK,
                Map.of("x", JsonNodeFactory.instance.objectNode()),
                null));
    assertThrows(
        DataProblem.class,
        () ->
            new ValueTransform(
                ValueTransform.Kind.CODEBOOK,
                Map.of("x", TextNode.valueOf("a".repeat(1048577))),
                null));
    assertThrows(
        DataProblem.class,
        () -> new ValueTransform(ValueTransform.Kind.IDENTITY, Map.of(), "raw SQL"));
  }
}
