package cn.ontorepo.application;

import cn.ontorepo.release.application.ReleaseHistoryService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.CandidateService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 跨本体测试与发布聚合：候选、正式版本按可见空间汇总。 */
@Service
public class GlobalDeliveryService {
    private final SpaceService spaces;
    private final CandidateService candidates;
    private final ReleaseHistoryService releases;

    public GlobalDeliveryService(
            SpaceService spaces, CandidateService candidates, ReleaseHistoryService releases) {
        this.spaces = spaces;
        this.candidates = candidates;
        this.releases = releases;
    }

    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> candidateRows = new ArrayList<>();
        List<Map<String, Object>> releaseRows = new ArrayList<>();
        for (Map<String, Object> space : spaces.list(user)) {
            UUID id = UUID.fromString(space.get("id").toString());
            String name = String.valueOf(space.get("name"));
            for (Map<String, Object> item : candidates.list(user, id)) {
                item.put("spaceName", name);
                candidateRows.add(item);
            }
            for (Map<String, Object> item : releases.list(user, id)) {
                item.put("spaceName", name);
                releaseRows.add(item);
            }
        }
        return Map.of("candidates", candidateRows, "releases", releaseRows);
    }
}
