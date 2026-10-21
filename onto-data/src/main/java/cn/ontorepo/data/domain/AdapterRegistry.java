package cn.ontorepo.data.domain;

import cn.ontorepo.data.infra.*;
import java.util.*;

/** kind 和 dialect 双重匹配；不允许用关系库伪装数仓验收。 */
public final class AdapterRegistry {
  private final Map<String, SourceAdapter> adapters;

  public AdapterRegistry() {
    SourceAdapter jdbc = new JdbcSourceAdapter();
    SourceAdapter file = new FileSourceAdapter();
    adapters =
        Map.ofEntries(
            Map.entry("RELATIONAL/postgresql", jdbc),
            Map.entry("WAREHOUSE/clickhouse", jdbc),
            Map.entry("FILE/csv", file),
            Map.entry("FILE/json", file),
            Map.entry("FILE/excel", file),
            Map.entry("HTTP/json", new HttpSourceAdapter()),
            Map.entry("LAKE/s3-parquet", new S3ParquetSourceAdapter()),
            Map.entry("DOCUMENT/mongodb", new MongoSourceAdapter()),
            Map.entry("KEY_VALUE/redis", new RedisSourceAdapter()),
            Map.entry("WIDE_COLUMN/cassandra", new CassandraSourceAdapter()),
            Map.entry("GRAPH/neo4j", new Neo4jSourceAdapter()));
  }

  public SourceAdapter adapter(SourceSpec source) {
    SourceAdapter adapter =
        adapters.get(source.kind() + "/" + source.connection().path("dialect").asText());
    if (adapter == null) throw QueryPlanner.problem("UNSUPPORTED_DIALECT");
    return adapter;
  }
}
