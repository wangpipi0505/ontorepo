package cn.ontorepo.data.domain;

import java.util.*;

/** 值保留源类型；证据定位单条源记录，不能只记录查询文本。 */
public record QueryResult(
    List<SourceMetadata.Field> columns,
    List<Map<String, Object>> rows,
    List<SourceRecord> evidence) {
  public QueryResult {
    columns = List.copyOf(columns);
    rows = rows.stream().map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
    evidence = List.copyOf(evidence);
  }

  public record SourceRecord(String sourceId, String resource, Map<String, Object> key) {
    public SourceRecord {
      key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
    }
  }
}
