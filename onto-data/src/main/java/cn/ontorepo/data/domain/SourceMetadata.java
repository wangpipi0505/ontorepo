package cn.ontorepo.data.domain;

import java.util.List;

/** 源侧资源与类型快照；未知关系/索引返回空集合，不能推断成已存在。 */
public record SourceMetadata(List<Resource> resources) {
  public SourceMetadata {
    resources = List.copyOf(resources);
  }

  public record Field(String name, String type, boolean nullable) {}

  public record Relation(String fromColumn, String targetResource, String targetColumn) {}

  public record Index(String name, List<String> columns, boolean unique) {
    public Index {
      columns = List.copyOf(columns);
    }
  }

  public record Resource(
      String name,
      List<Field> fields,
      List<String> primaryKey,
      List<Relation> relations,
      List<Index> indexes) {
    public Resource {
      fields = List.copyOf(fields);
      primaryKey = List.copyOf(primaryKey);
      relations = List.copyOf(relations);
      indexes = List.copyOf(indexes);
    }
  }
}
