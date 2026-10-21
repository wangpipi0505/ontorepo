package cn.ontorepo.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** 连接仅允许在适配器内部使用；日志和异常不能展开明文配置。 */
public record SourceSpec(String id, String kind, JsonNode connection, Set<String> accessScope) {
  public SourceSpec {
    if (id == null
        || kind == null
        || connection == null
        || !connection.isObject()
        || accessScope == null
        || accessScope.size() > 1000) throw new DataProblem("INVALID_SOURCE", "数据源配置无效");
    connection = connection.deepCopy();
    accessScope = Set.copyOf(accessScope);
  }

  @Override
  public JsonNode connection() {
    return connection.deepCopy();
  }

  @Override
  public String toString() {
    return "SourceSpec[id=" + id + ", kind=" + kind + ", connection=REDACTED]";
  }
}
