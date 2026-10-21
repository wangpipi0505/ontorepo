package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.infra.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeGuardsTest {
  @Test
  void allNativeAdaptersRejectUnguardedQueriesBeforeNetwork() {
    var json = new ObjectMapper();
    List<SourceAdapter> adapters =
        List.of(
            new JdbcSourceAdapter(),
            new MongoSourceAdapter(),
            new RedisSourceAdapter(),
            new CassandraSourceAdapter(),
            new Neo4jSourceAdapter(),
            new S3ParquetSourceAdapter());
    for (var adapter : adapters) {
      var source = new SourceSpec("s", "unused", json.createObjectNode(), Set.of("safe"));
      var q =
          new QuerySpec(
              "safe",
              List.of("id"),
              List.of(),
              List.of(),
              1,
              1,
              Map.of(),
              LabelScope.unrestricted(),
              Map.of());
      assertEquals(
          "GUARD_ATTR_UNRESOLVED",
          assertThrows(DataProblem.class, () -> adapter.query(source, q)).code());
    }
  }
}
