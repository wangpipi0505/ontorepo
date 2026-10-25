package cn.ontorepo.application;

import cn.ontorepo.common.infra.Db;
import cn.ontorepo.knowledge.application.KnowledgeService;
import cn.ontorepo.mapping.application.DatasourceService;
import cn.ontorepo.mapping.application.MappingService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 跨本体只读聚合：知识整理与数据源登记按所属空间分组展示（技术要求 2.3①③⑤⑥）；
 * 数据源行附元数据快照统计、映射引用统计与维护主体名，权限仍由各空间服务复核。
 */
@Service
public class GlobalDataService {
    private final SpaceService spaces;
    private final DatasourceService datasources;
    private final KnowledgeService knowledge;
    private final MappingService mappings;
    private final Db db;

    public GlobalDataService(
            SpaceService spaces,
            DatasourceService datasources,
            KnowledgeService knowledge,
            MappingService mappings,
            Db db) {
        this.spaces = spaces;
        this.datasources = datasources;
        this.knowledge = knowledge;
        this.mappings = mappings;
        this.db = db;
    }

    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> mappingCounts = new ArrayList<>();
        List<Map<String, Object>> mappingRows = new ArrayList<>();
        List<Map<String, Object>> sourceGroups = new ArrayList<>();
        for (Map<String, Object> space : spaces.list(user)) {
            UUID id = UUID.fromString(space.get("id").toString());
            String name = String.valueOf(space.get("name"));
            for (Map<String, Object> entry : knowledge.list(user, id)) {
                entry.put("spaceName", name);
                entries.add(entry);
            }
            Map<String, Object> mapping = mappings.list(user, id);
            mappingCounts.add(
                    Map.of(
                            "spaceId", id,
                            "spaceName", name,
                            "objects", ((List<?>) mapping.get("objects")).size(),
                            "properties", ((List<?>) mapping.get("properties")).size(),
                            "relations", ((List<?>) mapping.get("relations")).size()));
            for (String category : List.of("objects", "properties", "relations")) {
                Map<String, String> names = new HashMap<>();
                String table =
                        switch (category) {
                            case "objects" -> "ont_class";
                            case "properties" -> "ont_property";
                            default -> "ont_relation";
                        };
                for (Map<String, Object> named :
                        db.list("SELECT id,name FROM " + table + " WHERE space_id=?", id))
                    names.put(named.get("id").toString(), String.valueOf(named.get("name")));
                for (Object entry : (List<?>) mapping.get(category)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) entry);
                    row.put("spaceId", id.toString());
                    row.put("spaceName", name);
                    row.put("category", category);
                    String targetId =
                            switch (category) {
                                case "objects" -> Objects.toString(row.get("targetClassId"), null);
                                case "properties" ->
                                        Objects.toString(row.get("targetPropertyId"), null);
                                default -> Objects.toString(row.get("relationId"), null);
                            };
                    if (targetId != null) row.put("targetName", names.get(targetId));
                    mappingRows.add(row);
                }
            }
            sourceGroups.add(
                    Map.of(
                            "spaceId",
                            id.toString(),
                            "spaceName",
                            name,
                            "sources",
                            sourceRows(user, id, name)));
        }
        return Map.of(
                "knowledge", entries,
                "sourceGroups", sourceGroups,
                "mappings", mappingCounts,
                "mappingRows", mappingRows);
    }

    /** 数据源登记清单（按所属空间分组）：附元数据快照统计、映射引用统计与维护主体名。 */
    private List<Map<String, Object>> sourceRows(UUID user, UUID spaceId, String spaceName) {
        Map<String, Map<String, Object>> snapshotStats = new HashMap<>();
        for (Map<String, Object> row :
                db.list(
                        "SELECT datasource_id, count(*) AS cnt, max(captured_at) AS last_at"
                                + " FROM meta_snapshot WHERE space_id=? GROUP BY datasource_id",
                        spaceId)) {
            snapshotStats.put(row.get("datasourceId").toString(), row);
        }
        Map<String, Map<String, Object>> mappingStats = new HashMap<>();
        for (Map<String, Object> row :
                db.list(
                        "SELECT definition->>'datasourceId' AS ds, count(*) AS cnt"
                                + " FROM data_mapping WHERE space_id=? GROUP BY 1",
                        spaceId)) {
            mappingStats.put(Objects.toString(row.get("ds"), ""), row);
        }
        List<Map<String, Object>> rows = datasources.list(user, spaceId);
        List<UUID> ownerIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object owner = row.get("ownerId");
            if (owner != null) ownerIds.add(UUID.fromString(owner.toString()));
        }
        Map<String, String> ownerNames = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ownerIds.size(), "?"));
            Object[] ownerArgs = ownerIds.toArray();
            for (Map<String, Object> account :
                    db.list(
                            "SELECT id, display_name FROM local_user WHERE id IN ("
                                    + placeholders
                                    + ")",
                            ownerArgs)) {
                ownerNames.put(
                        account.get("id").toString(), String.valueOf(account.get("displayName")));
            }
        }
        for (Map<String, Object> row : rows) {
            String datasourceId = row.get("id").toString();
            row.put("spaceName", spaceName);
            var snapshot = snapshotStats.get(datasourceId);
            row.put(
                    "snapshotCount",
                    snapshot == null ? 0 : ((Number) snapshot.get("cnt")).intValue());
            row.put("lastSnapshotAt", snapshot == null ? null : snapshot.get("lastAt"));
            var referenced = mappingStats.get(datasourceId);
            row.put(
                    "mappingCount",
                    referenced == null ? 0 : ((Number) referenced.get("cnt")).intValue());
            Object owner = row.get("ownerId");
            row.put(
                    "ownerName",
                    owner == null ? "" : ownerNames.getOrDefault(owner.toString(), ""));
        }
        return rows;
    }
}
