package cn.ontorepo.application;

import cn.ontorepo.release.application.TraceAuditService;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.trace.application.CandidateService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 跨本体溯源聚合：执行与审计按可见空间汇总，单类上限防止全局页过载。 */
@Service
public class GlobalTraceService {
    private static final int MAX = 300;
    private final SpaceService spaces;
    private final CandidateService executions;
    private final TraceAuditService audit;

    public GlobalTraceService(
            SpaceService spaces, CandidateService executions, TraceAuditService audit) {
        this.spaces = spaces;
        this.executions = executions;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> data(UUID user) {
        List<Map<String, Object>> executionRows = new ArrayList<>();
        List<Map<String, Object>> auditRows = new ArrayList<>();
        for (Map<String, Object> space : spaces.list(user)) {
            UUID id = UUID.fromString(space.get("id").toString());
            String name = String.valueOf(space.get("name"));
            if (executionRows.size() < MAX)
                for (Map<String, Object> item : executions.executions(user, id, null, null, null, 50, 0)) {
                    item.put("spaceName", name);
                    executionRows.add(item);
                }
            if (auditRows.size() < MAX) {
                Object items = audit.audit(user, id, 50, null).get("items");
                if (items instanceof List<?> list)
                    for (Object row : list)
                        if (row instanceof Map<?, ?> map) {
                            Map<String, Object> item = (Map<String, Object>) map;
                            item.put("spaceName", name);
                            auditRows.add(item);
                        }
            }
        }
        return Map.of("executions", executionRows, "audit", auditRows);
    }
}
