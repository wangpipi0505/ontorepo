package cn.ontorepo.common.infra;

import cn.ontorepo.common.domain.Problem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC 映射仅负责存储转换，领域授权留在应用服务；标识和时间统一为 JSON 字符串。 */
@Component
public class Db {
    public final JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** JSONB 在存储边界解码，业务层和冻结载荷中保持对象/数组类型。 */
    public String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw Problem.bad("JSON内容无效");
        }
    }

    public Object read(String value) {
        try {
            return JSON.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("数据库JSON损坏", e);
        }
    }

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.query(
                sql,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    var md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String name = md.getColumnLabel(i);
                        StringBuilder key = new StringBuilder();
                        boolean upper = false;
                        for (char c : name.toCharArray()) {
                            if (c == '_') {
                                upper = true;
                                continue;
                            }
                            key.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                        Object value = rs.getObject(i);
                        if (value != null
                                && (md.getColumnTypeName(i).equals("jsonb")
                                        || md.getColumnTypeName(i).equals("json")))
                            value = read(rs.getString(i));
                        if (value instanceof UUID) value = value.toString();
                        if (value instanceof Timestamp t) value = t.toInstant().toString();
                        m.put(key.toString(), value);
                    }
                    return m;
                },
                args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) throw Problem.missing();
        return rows.getFirst();
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }
}
