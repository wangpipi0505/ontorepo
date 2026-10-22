package cn.ontorepo.application;

import cn.ontorepo.common.infra.Db;
import cn.ontorepo.operation.application.TestCaseService;
import cn.ontorepo.release.application.ReleaseGatewayService;
import cn.ontorepo.release.application.ReleaseHistoryService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.CandidateService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 跨本体测试与发布聚合：用例、候选、正式版本、服务端点按可见空间汇总。 */
@Service
public class GlobalDeliveryService {
    private final SpaceService spaces;
    private final CandidateService candidates;
    private final ReleaseHistoryService releases;
    private final TestCaseService testCases;
    private final ReleaseGatewayService gateway;
    private final Db db;

    public GlobalDeliveryService(
            SpaceService spaces,
            CandidateService candidates,
            ReleaseHistoryService releases,
            TestCaseService testCases,
            ReleaseGatewayService gateway,
            Db db) {
        this.spaces = spaces;
        this.candidates = candidates;
        this.releases = releases;
        this.testCases = testCases;
        this.gateway = gateway;
        this.db = db;
    }

    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> candidateRows = new ArrayList<>();
        List<Map<String, Object>> releaseRows = new ArrayList<>();
        List<Map<String, Object>> testRows = new ArrayList<>();
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
            for (Map<String, Object> item : testCases.list(user, id)) {
                item.put("spaceName", name);
                testRows.add(item);
            }
        }
        return Map.of(
                "candidates", candidateRows,
                "releases", releaseRows,
                "testCases", testRows);
    }

    /** 端点目录只读；调用方已由控制器要求 release.read，服务端仍逐空间复核。 */
    public List<Map<String, Object>> services(UUID user) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> space : spaces.list(user)) {
            UUID spaceId = UUID.fromString(space.get("id").toString());
            String spaceName = String.valueOf(space.get("name"));
            boolean owner = "OWNER".equals(space.get("role"));
            for (Map<String, Object> release : releases.list(user, spaceId)) {
                UUID releaseId = UUID.fromString(release.get("id").toString());
                for (Map<String, Object> endpoint : gateway.endpoints(user, releaseId)) {
                    endpoint.put("spaceId", spaceId.toString());
                    endpoint.put("spaceName", spaceName);
                    endpoint.put("releaseId", releaseId.toString());
                    endpoint.put("versionNo", release.get("versionNo"));
                    endpoint.put(
                            "executionsCount",
                            db.one(
                                            "SELECT count(*) c FROM op_execution WHERE space_id=?"
                                                    + " AND subject_type='RELEASE' AND subject_id=?"
                                                    + " AND op_code=?",
                                            spaceId,
                                            releaseId,
                                            endpoint.get("opCode"))
                                    .get("c"));
                    if (owner)
                        endpoint.put(
                                "grantsCount",
                                gateway.grants(
                                                user,
                                                UUID.fromString(
                                                        endpoint.get("id").toString()))
                                        .size());
                    rows.add(endpoint);
                }
            }
        }
        return rows;
    }
}
