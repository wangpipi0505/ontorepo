package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 实例契约在冻结阶段就必须证明全部查询资源的过滤绑定，不能等待真实源访问才报错。 */
class P3AuditGuardTest extends P3Fixture {
    @Test
    void principalRuleAloneCannotCompileAnInstanceContract() throws Exception {
        var x = scenario();
        update(
                "/spaces/" + x.space() + "/guards",
                x.space(),
                Map.of(
                        "guardCfg",
                        Map.of(
                                "region",
                                Map.of(
                                        "type",
                                        "STRING",
                                        "authorization_rule",
                                        Map.of(
                                                "kind",
                                                "PRINCIPAL_ATTRIBUTE",
                                                "attribute",
                                                "region")))));
        var candidate = freeze(x.space());
        assertEquals("FAILED", candidate.path("status").asText());
        String id = candidate.path("id").asText();
        // 将真实源改为不可访问，若任何入口错误下探，便不会返回统一的守卫缺绑定分类。
        db.execute("ALTER TABLE p3_source.algorithms RENAME TO unavailable_algorithms");
        for (String caller : List.of("TRIAL", "SERVICE")) {
            boolean trial = caller.equals("TRIAL");
            var rejected =
                    call(
                            post(
                                    trial
                                            ? "/onto/candidates/" + id + "/trial"
                                            : "/onto/internal/candidates/"
                                                    + id
                                                    + "/ops/listClasses"),
                            ownerToken,
                            trial
                                    ? Map.of("operationId", x.operation(), "input", Map.of())
                                    : Map.of(),
                            400);
            assertEquals("GUARD_ATTR_UNRESOLVED", rejected.path("code").asText());
            assertEquals("NO_FILTER_BINDING", rejected.path("reason").asText());
        }
        call(post("/onto/candidates/" + id + "/tests"), ownerToken, null, 409);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM op_execution WHERE subject_id=? AND"
                            + " status='SUCCEEDED'",
                        Integer.class,
                        UUID.fromString(id)));
    }

    @Test
    void everyJoinedResourceAndGuardColumnMustResolveDuringFreeze() throws Exception {
        var x = scenario();
        for (List<Map<String, Object>> bindings :
                List.of(
                        List.of(
                                Map.<String, Object>of(
                                        "table",
                                        "p3_source.tasks",
                                        "column",
                                        "region",
                                        "operator",
                                        "IN")),
                        List.of(
                                Map.<String, Object>of(
                                        "table",
                                        "p3_source.tasks",
                                        "column",
                                        "missing_column",
                                        "operator",
                                        "IN"),
                                Map.<String, Object>of(
                                        "table",
                                        "p3_source.algorithms",
                                        "column",
                                        "region",
                                        "operator",
                                        "IN")))) {
            update(
                    "/spaces/" + x.space() + "/guards",
                    x.space(),
                    Map.of(
                            "guardCfg",
                            Map.of(
                                    "region",
                                    Map.of("type", "STRING", "filter_bindings", bindings))));
            assertEquals("FAILED", freeze(x.space()).path("status").asText());
        }
    }
}
