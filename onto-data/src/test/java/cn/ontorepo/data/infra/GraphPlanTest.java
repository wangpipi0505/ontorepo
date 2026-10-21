package cn.ontorepo.data.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class GraphPlanTest {
  @Test
  void relationshipTemplateGuardsBothLabelsAndBindsValues() throws Exception {
    var json = new ObjectMapper();
    var source = new SourceSpec("g", "GRAPH", json.createObjectNode(), Set.of("Task", "Algorithm"));
    var options =
        Map.of(
            "relationship",
            json.getNodeFactory().textNode("USES"),
            "targetLabel",
            json.getNodeFactory().textNode("Algorithm"));
    var q =
        new QuerySpec(
            "Task",
            List.of("id", "_targetId", "_relationshipId"),
            List.of(),
            List.of(),
            1,
            1000,
            new HashMap<>(options),
            LabelScope.parse(json.readTree("{\"attr\":\"region\",\"in\":[\"A' injected\"]}")),
            Map.of("region", "region"));
    var plan = new Neo4jSourceAdapter().plan(source, q);
    assertFalse(plan.cypher().contains("injected"));
    assertTrue(plan.cypher().contains("m.`region`"));
    assertTrue(plan.cypher().contains("r:`USES`"));
    assertTrue(
        plan.parameters().values().stream()
            .anyMatch(v -> v instanceof List<?> list && list.contains("A' injected")));
    assertThrows(
        DataProblem.class,
        () ->
            new Neo4jSourceAdapter()
                .plan(new SourceSpec("g", "GRAPH", source.connection(), Set.of("Task")), q));
  }
}
