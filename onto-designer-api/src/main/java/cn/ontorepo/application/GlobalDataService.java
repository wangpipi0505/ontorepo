package cn.ontorepo.application;

import cn.ontorepo.knowledge.application.KnowledgeService;
import cn.ontorepo.mapping.application.DatasourceService;
import cn.ontorepo.mapping.application.MappingService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 跨本体只读聚合：先取可见空间，再逐空间读取物料；权限仍由各空间服务复核。 */
@Service
public class GlobalDataService {
    private final SpaceService spaces;
    private final DatasourceService datasources;
    private final KnowledgeService knowledge;
    private final MappingService mappings;

    public GlobalDataService(
            SpaceService spaces,
            DatasourceService datasources,
            KnowledgeService knowledge,
            MappingService mappings) {
        this.spaces = spaces;
        this.datasources = datasources;
        this.knowledge = knowledge;
        this.mappings = mappings;
    }

    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> mappingCounts = new ArrayList<>();
        for (Map<String, Object> space : spaces.list(user)) {
            UUID id = UUID.fromString(space.get("id").toString());
            String name = String.valueOf(space.get("name"));
            for (Map<String, Object> source : datasources.list(user, id)) {
                source.put("spaceName", name);
                sources.add(source);
            }
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
        }
        return Map.of(
                "sources", sources,
                "knowledge", entries,
                "mappings", mappingCounts);
    }
}
