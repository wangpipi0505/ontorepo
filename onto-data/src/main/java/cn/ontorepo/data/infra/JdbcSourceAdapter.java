package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import java.sql.*;
import java.util.*;

/** PostgreSQL / ClickHouse 明确分派，所有实例查询只使用结构化计划。 */
public final class JdbcSourceAdapter extends AdapterSupport {
  @Override
  public Set<String> capabilities() {
    return Set.of(
        "METADATA",
        "PARAMETERIZED_SELECT",
        "INNER_EQ_JOIN",
        "SCOPE_BEFORE_LIMIT",
        "PRIMARY_KEY_EVIDENCE");
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    String url = text(s, "url");
    uriSocket(url.substring(5), s.kind().equals("WAREHOUSE") ? 8123 : 5432);
  }

  Connection open(SourceSpec s) throws Exception {
    String dialect = text(s, "dialect"), url = text(s, "url");
    if (!(s.kind().equals("RELATIONAL")
            && dialect.equals("postgresql")
            && url.startsWith("jdbc:postgresql://")
        || s.kind().equals("WAREHOUSE")
            && dialect.equals("clickhouse")
            && url.startsWith("jdbc:clickhouse://")))
      throw QueryPlanner.problem("UNSUPPORTED_DIALECT");
    Properties p = new Properties();
    p.setProperty("user", text(s, "user"));
    p.setProperty("password", s.connection().path("password").asText(""));
    p.setProperty("connectTimeout", "3");
    p.setProperty("socketTimeout", "10");
    return DriverManager.getConnection(url, p);
  }

  @Override
  void authenticate(SourceSpec s) throws Exception {
    try (Connection c = open(s)) {
      if (!c.isValid(3)) throw QueryPlanner.problem("AUTHENTICATION_FAILED");
    }
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) throws Exception {
    try (Connection c = open(s)) {
      if (s.kind().equals("WAREHOUSE")) return clickHouseMetadata(c, s);
      DatabaseMetaData m = c.getMetaData();
      List<SourceMetadata.Resource> out = new ArrayList<>();
      for (String r : s.accessScope()) {
        QueryPlanner.identifier(r);
        String[] names = r.split("\\.");
        if (names.length != 2) throw QueryPlanner.problem("INVALID_RESOURCE");
        String schema = names[0], table = names[1];
        List<SourceMetadata.Field> fs = new ArrayList<>();
        String escape = m.getSearchStringEscape();
        try (ResultSet rs =
            m.getColumns(
                c.getCatalog(),
                schema.replace("_", escape + "_"),
                table.replace("_", escape + "_"),
                null)) {
          while (rs.next())
            if (schema.equals(rs.getString("TABLE_SCHEM"))
                && table.equals(rs.getString("TABLE_NAME")))
              fs.add(
                  new SourceMetadata.Field(
                      rs.getString("COLUMN_NAME"),
                      rs.getString("TYPE_NAME"),
                      rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
        }
        if (fs.isEmpty()) continue;
        // 零行读取实际验证 SELECT 权限；元数据可见性不能冒充读取权限。
        try (Statement st = c.createStatement()) {
          st.setQueryTimeout(3);
          st.executeQuery("SELECT * FROM " + QueryPlanner.quote(r) + " WHERE 1=0").close();
        }
        Map<Short, String> pk = new TreeMap<>();
        try (ResultSet rs = m.getPrimaryKeys(c.getCatalog(), schema, table)) {
          while (rs.next()) pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        List<SourceMetadata.Relation> relations = new ArrayList<>();
        try (ResultSet rs = m.getImportedKeys(c.getCatalog(), schema, table)) {
          while (rs.next()) {
            String target = rs.getString("PKTABLE_SCHEM") + "." + rs.getString("PKTABLE_NAME");
            if (s.accessScope().contains(target))
              relations.add(
                  new SourceMetadata.Relation(
                      rs.getString("FKCOLUMN_NAME"), target, rs.getString("PKCOLUMN_NAME")));
          }
        }
        List<SourceMetadata.Index> indexes = new ArrayList<>();
        try (ResultSet rs = m.getIndexInfo(c.getCatalog(), schema, table, false, false)) {
          while (rs.next())
            if (rs.getString("INDEX_NAME") != null && rs.getString("COLUMN_NAME") != null)
              indexes.add(
                  new SourceMetadata.Index(
                      rs.getString("INDEX_NAME"),
                      List.of(rs.getString("COLUMN_NAME")),
                      !rs.getBoolean("NON_UNIQUE")));
        }
        out.add(
            new SourceMetadata.Resource(r, fs, new ArrayList<>(pk.values()), relations, indexes));
      }
      return new SourceMetadata(out);
    }
  }

  /** ClickHouse 的 database 是 catalog；独立读取系统目录，不能套用 PG 的 schema 行为。 */
  SourceMetadata clickHouseMetadata(Connection c, SourceSpec s) throws Exception {
    List<SourceMetadata.Resource> out = new ArrayList<>();
    for (String r : s.accessScope()) {
      QueryPlanner.identifier(r);
      String[] parts = r.split("\\.");
      if (parts.length != 2) throw QueryPlanner.problem("INVALID_RESOURCE");
      try (PreparedStatement engine =
          c.prepareStatement("SELECT engine FROM system.tables WHERE database=? AND name=?")) {
        engine.setString(1, parts[0]);
        engine.setString(2, parts[1]);
        engine.setQueryTimeout(3);
        try (ResultSet rs = engine.executeQuery()) {
          if (!rs.next()) continue;
          if (!rs.getString(1).endsWith("MergeTree"))
            throw QueryPlanner.problem("CLICKHOUSE_MERGETREE_REQUIRED");
        }
      }
      List<SourceMetadata.Field> fs = new ArrayList<>();
      List<String> sorting = new ArrayList<>();
      try (PreparedStatement columns =
          c.prepareStatement(
              "SELECT name,type,is_in_primary_key FROM system.columns WHERE database=? AND table=?"
                  + " ORDER BY position")) {
        columns.setString(1, parts[0]);
        columns.setString(2, parts[1]);
        columns.setQueryTimeout(3);
        try (ResultSet rs = columns.executeQuery()) {
          while (rs.next()) {
            String name = rs.getString(1), type = rs.getString(2);
            fs.add(new SourceMetadata.Field(name, type, type.startsWith("Nullable(")));
            if (rs.getBoolean(3)) sorting.add(name);
          }
        }
      }
      // MergeTree 排序键不保证唯一，证据改用物理分片和行偏移。
      fs.add(new SourceMetadata.Field("_part", "String", false));
      fs.add(new SourceMetadata.Field("_part_offset", "UInt64", false));
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(3);
        st.executeQuery("SELECT _part,_part_offset FROM " + QueryPlanner.quote(r) + " WHERE 1=0")
            .close();
      }
      out.add(
          new SourceMetadata.Resource(
              r,
              fs,
              List.of("_part", "_part_offset"),
              List.of(),
              sorting.isEmpty()
                  ? List.of()
                  : List.of(new SourceMetadata.Index("sorting_key", sorting, false))));
    }
    return new SourceMetadata(out);
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) throws Exception {
    Set<String> used = new LinkedHashSet<>();
    used.add(q.resource());
    q.joins().forEach(j -> used.add(j.resource()));
    for (String resource : used) {
      exact(s, resource);
      QueryPlanner.identifier(resource);
    }
    var metadata = readMetadata(new SourceSpec(s.id(), s.kind(), s.connection(), used));
    var plan = new QueryPlanner().plan(s, q, metadata);
    try (Connection c = open(s)) {
      boolean pg = s.kind().equals("RELATIONAL");
      if (pg) {
        c.setReadOnly(true);
        c.setAutoCommit(false);
      }
      String sql =
          plan.sql()
              + (pg
                  ? ""
                  : " SETTINGS max_execution_time=" + Math.max(1, (plan.timeoutMs() + 999) / 1000));
      try (PreparedStatement st = c.prepareStatement(sql)) {
        st.setQueryTimeout(Math.max(1, (plan.timeoutMs() + 999) / 1000));
        st.setMaxRows(Math.min(500, plan.limit()));
        for (int i = 0; i < plan.parameters().size(); i++)
          st.setObject(i + 1, plan.parameters().get(i));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<QueryResult.SourceRecord> evidence = new ArrayList<>();
        List<SourceMetadata.Field> columns = new ArrayList<>();
        try (ResultSet rs = st.executeQuery()) {
          for (int i = 0; i < q.projection().size(); i++)
            columns.add(
                new SourceMetadata.Field(
                    q.projection().get(i), rs.getMetaData().getColumnTypeName(i + 1), true));
          while (rows.size() < plan.limit() && rs.next()) {
            // 转换计划的LEFT LATERAL在无业务匹配时仍返回一条校验行；真实主键不可能为null。
            // 不把这条内部哨兵当业务空对象或源证据输出，计数列强制PG先完成全部授权转换。
            if (QueryPlanner.transformed(q) && rs.getObject(q.projection().size() + 1) == null)
              continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < q.projection().size(); i++)
              row.put(q.projection().get(i), rs.getObject(i + 1));
            rows.add(row);
            int index = q.projection().size() + 1;
            for (var entry : plan.keys().entrySet()) {
              Map<String, Object> key = new LinkedHashMap<>();
              for (String k : entry.getValue()) key.put(k, rs.getObject(index++));
              evidence.add(new QueryResult.SourceRecord(s.id(), entry.getKey(), key));
            }
          }
        } finally {
          if (pg) c.rollback();
        }
        return new QueryResult(columns, rows, evidence);
      }
    }
  }
}
