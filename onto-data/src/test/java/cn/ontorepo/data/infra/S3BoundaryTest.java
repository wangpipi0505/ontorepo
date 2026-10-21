package cn.ontorepo.data.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class S3BoundaryTest {
  final ObjectMapper json = new ObjectMapper();

  QuerySpec query(int timeout) {
    return new QuerySpec(
        "rows.parquet",
        List.of("id"),
        List.of(),
        List.of(),
        1,
        timeout,
        Map.of(),
        LabelScope.unrestricted(),
        Map.of("region", "region"));
  }

  @Test
  void invalidPrefixesCannotTurnIntoWholeBucketAccess() {
    for (String prefix : List.of("", "../", "/", "valid/../")) {
      var source = new SourceSpec("s", "LAKE", json.createObjectNode(), Set.of(prefix));
      assertEquals(
          "INVALID_PREFIX",
          assertThrows(
                  DataProblem.class, () -> new S3ParquetSourceAdapter().query(source, query(100)))
              .code());
    }
  }

  @Test
  void remainingBudgetCannotRestartPerNetworkStage() {
    var adapter = new S3ParquetSourceAdapter();
    long expired = System.nanoTime() - 20_000_000;
    assertEquals(
        "QUERY_TIMEOUT",
        assertThrows(DataProblem.class, () -> adapter.remaining(expired, query(10))).code());
    int remaining = adapter.remaining(System.nanoTime() - 40_000_000, query(100));
    assertTrue(remaining > 0 && remaining <= 60);
  }
}
