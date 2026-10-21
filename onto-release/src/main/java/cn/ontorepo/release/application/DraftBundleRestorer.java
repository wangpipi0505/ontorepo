package cn.ontorepo.release.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.trace.infra.CanonicalPayload;

import java.util.*;

/** 只在调用方空间锁事务内工作；按外键顺序重建草稿，不触碰候选、执行或导入事实。 */
final class DraftBundleRestorer {
    private static final String[][] TABLES = {
        {"codebooks", "ont_codebook"},
        {"classes", "ont_class"},
        {"properties", "ont_property"},
        {"relations", "ont_relation"},
        {"constraints", "ont_constraint"},
        {"rules", "ont_rule"},
        {"strategies", "ont_strategy"},
        {"operations", "ont_operation"},
        {"testCases", "ont_test_case"},
        {"rdfDocuments", "ont_rdf_document"},
        {"knowledge", "ont_knowledge"},
        {"dataFiles", "data_file"},
        {"datasources", "datasource"},
        {"metaSnapshots", "meta_snapshot"}
    };
    private final Db db;
    private final CanonicalPayload codec;

    DraftBundleRestorer(Db db, CanonicalPayload codec) {
        this.db = db;
        this.codec = codec;
    }

    /** 快照先逐字段核对归档，避免同id异内容被ON CONFLICT静默吞掉。 */
    void restore(UUID space, Map<String, Object> payload) {
        if (!(payload.get("space") instanceof Map<?, ?> frozenSpace)
                || !Input.sameUuid(space, frozenSpace.get("id")))
            throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结空间与版本归属不一致");
        for (var pair : TABLES)
            for (var row : codec.rows(payload, pair[0])) {
                if (!Input.sameUuid(space, row.get("spaceId")))
                    throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结实体空间归属不一致");
                Input.uuid(row.get("id"));
            }
        archiveSnapshots(space);
        for (String table :
                List.of(
                        "data_mapping",
                        "ont_test_case",
                        "ont_operation",
                        "ont_knowledge",
                        "ont_rule",
                        "ont_constraint",
                        "ont_relation",
                        "ont_property",
                        "ont_codebook",
                        "ont_class",
                        "ont_strategy",
                        "ont_rdf_document",
                        "meta_snapshot",
                        "datasource",
                        "data_file"))
            db.update("DELETE FROM " + table + " WHERE space_id=?", space);
        db.update(
                "UPDATE ontology_space SET name=?,domain=?,guard_cfg=?::jsonb WHERE id=?",
                frozenSpace.get("name"),
                frozenSpace.get("domain"),
                codec.json(
                        frozenSpace.containsKey("guardCfg")
                                ? frozenSpace.get("guardCfg")
                                : Map.of()),
                space);
        for (var pair : TABLES) {
            var rows = codec.rows(payload, pair[0]);
            if (pair[1].equals("meta_snapshot")) for (var row : rows) verifyArchived(space, row);
            insertRows(pair[1], rows, space);
        }
        for (var pair :
                new String[][] {
                    {"objectMappings", "OBJECT"},
                    {"propertyMappings", "PROPERTY"},
                    {"relationMappings", "RELATION"}
                })
            for (var row : codec.rows(payload, pair[0])) {
                var definition = new LinkedHashMap<>(row);
                Object id = definition.remove("id");
                if (definition.containsKey("spaceId")
                        && !Input.sameUuid(space, definition.remove("spaceId")))
                    throw new Problem(409, "FROZEN_REFERENCE_INVALID", "冻结映射空间归属不一致");
                db.update(
                        "INSERT INTO data_mapping(id,space_id,kind,definition)"
                                + " VALUES(?,?,?,?::jsonb)",
                        Input.uuid(id),
                        space,
                        pair[1],
                        codec.json(definition));
            }
    }

    private void archiveSnapshots(UUID space) {
        var snapshots = db.list("SELECT * FROM meta_snapshot WHERE space_id=? ORDER BY id", space);
        for (var row : snapshots) {
            verifyArchived(space, row);
            db.update(
                    "INSERT INTO"
                        + " meta_snapshot_archive(id,space_id,datasource_id,sha256,resources,captured_at)"
                        + " SELECT id,space_id,datasource_id,sha256,resources,captured_at FROM"
                        + " meta_snapshot WHERE id=? ON CONFLICT(id) DO NOTHING",
                    Input.uuid(row.get("id")));
            verifyArchived(space, row);
        }
    }

    private void verifyArchived(UUID space, Map<String, Object> row) {
        var old =
                db.list(
                        "SELECT * FROM meta_snapshot_archive WHERE id=?",
                        Input.uuid(row.get("id")));
        if (old.isEmpty()) return;
        var archived = old.getFirst();
        if (!Input.sameUuid(space, archived.get("spaceId"))) throw conflict();
        for (String key :
                List.of("id", "spaceId", "datasourceId", "sha256", "resources", "capturedAt"))
            if (!Arrays.equals(codec.encode(archived.get(key)), codec.encode(row.get(key))))
                throw conflict();
    }

    /** 列表单条INSERT恢复自引用父类；列名来自固定表的PG元数据，不接受bundle提供SQL标识。 */
    private void insertRows(String table, List<Map<String, Object>> rows, UUID space) {
        if (rows.isEmpty()) return;
        var columns =
                db
                        .list(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                        + " table_schema=current_schema() AND table_name=? AND"
                                        + " is_generated='NEVER' ORDER BY ordinal_position",
                                table)
                        .stream()
                        .map(c -> c.get("columnName").toString())
                        .toList();
        var normalized = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var mapped = new LinkedHashMap<String, Object>();
            row.forEach(
                    (key, value) -> {
                        String column = snake(key);
                        if (columns.contains(column)) mapped.put(column, value);
                    });
            mapped.put("space_id", space.toString());
            normalized.add(mapped);
        }
        // 同表整批插入在一个SQL语句中完成，父子UUID排序不会制造临时外键错误。
        String names =
                columns.stream()
                        .map(c -> "\"" + c + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
        db.update(
                "INSERT INTO "
                        + table
                        + "("
                        + names
                        + ") SELECT "
                        + names
                        + " FROM jsonb_populate_recordset(NULL::"
                        + table
                        + ",?::jsonb)",
                codec.json(normalized));
    }

    private static String snake(String key) {
        var result = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) result.append('_').append(Character.toLowerCase(c));
            else result.append(c);
        }
        return result.toString();
    }

    private static Problem conflict() {
        return new Problem(409, "SNAPSHOT_ARCHIVE_CONFLICT", "同一元数据快照标识存在不同历史事实，恢复已回滚");
    }
}
