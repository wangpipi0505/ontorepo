package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryPlannerTest {
  private final ObjectMapper json = new ObjectMapper();
  private final SourceSpec source =
      new SourceSpec(
          "s",
          "RELATIONAL",
          json.createObjectNode().put("dialect", "postgresql"),
          Set.of("public.tasks", "public.algorithms"));
  private final SourceMetadata metadata =
      new SourceMetadata(
          List.of(
              new SourceMetadata.Resource(
                  "public.tasks",
                  List.of(
                      new SourceMetadata.Field("id", "INTEGER", false),
                      new SourceMetadata.Field("region", "VARCHAR", true),
                      new SourceMetadata.Field("algo", "INTEGER", false)),
                  List.of("id"),
                  List.of(),
                  List.of()),
              new SourceMetadata.Resource(
                  "public.algorithms",
                  List.of(
                      new SourceMetadata.Field("id", "INTEGER", false),
                      new SourceMetadata.Field("region", "VARCHAR", true)),
                  List.of("id"),
                  List.of(),
                  List.of())));

  QuerySpec query(LabelScope scope, Map<String, String> guards) {
    return new QuerySpec(
        "public.tasks",
        List.of("public.tasks.id"),
        List.of(),
        List.of(),
        10000,
        90000,
        Map.of(),
        scope,
        guards);
  }

  @Test
  void historicalAttributeNamesKeepQualifiedBindingsAndSourceIdentifierBoundaries() {
    for(String attribute:List.of("_region","r".repeat(128))){
      var scope=LabelScope.parse(json.valueToTree(Map.of("attr",attribute,"in",List.of("A"))));
      var plan=assertDoesNotThrow(()->new QueryPlanner().plan(source,query(scope,Map.of(attribute+"@public.tasks","public.tasks.region")),metadata));
      assertEquals(List.of("A",500),plan.parameters());
      assertTrue(plan.sql().contains("IS NOT NULL"));
      assertThrows(DataProblem.class,()->new QueryPlanner().plan(source,query(scope,Map.of(attribute+"@public.tasks;DROP","public.tasks.region")),metadata));
    }
  }

  @Test
  void bindsLabelsAndCapsExecution() throws Exception {
    var scope = LabelScope.parse(json.readTree("{\"attr\":\"region\",\"in\":[\"A' OR 1=1 --\"]}"));
    var plan =
        new QueryPlanner()
            .plan(source, query(scope, Map.of("region", "public.tasks.region")), metadata);
    assertFalse(plan.sql().contains("OR 1=1"));
    assertTrue(plan.sql().contains("IS NOT NULL"));
    assertEquals("A' OR 1=1 --", plan.parameters().getFirst());
    assertEquals(500, plan.limit());
    assertEquals(10000, plan.timeoutMs());
  }

  @Test
  void emptyIntersectionIsFalseAndMissingGuardFails() {
    var scope = new LabelScope(Map.of("region", Set.of()));
    assertTrue(
        new QueryPlanner()
            .plan(source, query(scope, Map.of("region", "public.tasks.region")), metadata)
            .sql()
            .contains("1=0"));
    assertThrows(
        DataProblem.class, () -> new QueryPlanner().plan(source, query(scope, Map.of()), metadata));
    assertThrows(
        DataProblem.class,
        () ->
            new QueryPlanner().plan(source, query(LabelScope.unrestricted(), Map.of()), metadata));
  }

  @Test
  void rejectsUnknownTablesColumnsAndOptions() {
    assertThrows(
        DataProblem.class,
        () ->
            new QueryPlanner()
                .plan(
                    source,
                    query(LabelScope.unrestricted(), Map.of("region", "public.tasks.bad")),
                    metadata));
    assertThrows(
        DataProblem.class,
        () ->
            new QueryPlanner()
                .plan(
                    new SourceSpec("s", "RELATIONAL", source.connection(), Set.of()),
                    query(LabelScope.unrestricted(), Map.of("region", "public.tasks.region")),
                    metadata));
    assertThrows(
        DataProblem.class,
        () ->
            new QueryPlanner()
                .plan(
                    source,
                    new QuerySpec(
                        "public.tasks;DROP",
                        List.of("id"),
                        List.of(),
                        List.of(),
                        1,
                        1,
                        Map.of(),
                        LabelScope.unrestricted(),
                        Map.of("region", "region")),
                    metadata));
  }

  @Test
  void joinsRequireGuardForEveryParticipatingTable() {
    var joined =
        new QuerySpec(
            "public.tasks",
            List.of("public.tasks.id", "public.algorithms.id"),
            List.of(),
            List.of(
                new QuerySpec.Join(
                    "public.algorithms", "public.tasks.algo", "public.algorithms.id")),
            1,
            1000,
            Map.of(),
            LabelScope.unrestricted(),
            Map.of("region", "public.tasks.region"));
    assertEquals(
        "GUARD_ATTR_UNRESOLVED",
        assertThrows(DataProblem.class, () -> new QueryPlanner().plan(source, joined, metadata))
            .code());
    var complete =
        new QuerySpec(
            joined.resource(),
            joined.projection(),
            joined.filters(),
            joined.joins(),
            1,
            1000,
            Map.of(),
            LabelScope.unrestricted(),
            Map.of(
                "region",
                "public.tasks.region",
                "region@public.algorithms",
                "public.algorithms.region"));
    assertTrue(new QueryPlanner().plan(source, complete, metadata).sql().contains("JOIN"));
  }
}
