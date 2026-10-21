package cn.ontorepo.application;

import cn.ontorepo.knowledge.application.KnowledgeService;
import cn.ontorepo.mapping.application.DatasourceService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 跨本体只读聚合：先取可见空间，再逐空间读取物料；权限仍由各空间服务复核。 */
@Service
public class GlobalDataService {
    private final SpaceService spaces;
    private final DatasourceService datasources;
    private final KnowledgeService knowledge;

    public GlobalDataService(
            SpaceService spaces, DatasourceService datasources, KnowledgeService knowledge) {
        this.spaces = spaces;
        this.datasources = datasources;
        this.knowledge = knowledge;
    }

    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
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
        }
        return Map.of("sources", sources, "knowledge", entries);
    }
}
