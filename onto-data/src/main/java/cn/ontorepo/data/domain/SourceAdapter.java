package cn.ontorepo.data.domain;

import java.util.Set;

/** 适配器不得接受原始 SQL/脚本；能力不支持必须拒绝。 */
public interface SourceAdapter {
  ProbeResult probe(SourceSpec source);

  SourceMetadata metadata(SourceSpec source);

  QueryResult query(SourceSpec source, QuerySpec query);

  Set<String> capabilities();
}
