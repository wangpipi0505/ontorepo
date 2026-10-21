package cn.ontorepo.release.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.common.domain.Problem;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 真PG验证授权、游标和历史缺值，不从当前草稿补造历史事实。 */
class TraceAuditServiceTest {
    @Test
    void timelineUsesActualInstantsAcrossDifferentFractionalPrecision() throws Exception {
        try (var fixture = new HistoryDatabase()) {
            var id = fixture.execution();
            fixture.db.update(
                    "UPDATE op_execution SET created_at='2026-10-21T00:00:00.123Z' WHERE id=?", id);
            fixture.db.update(
                    "INSERT INTO"
                        + " op_execution_step(execution_id,step_order,kind,status,detail,created_at)"
                        + " VALUES(?,0,'QUERY_EXEC','SUCCEEDED','后发生','2026-10-21T00:00:00.123456Z')",
                    id);
            fixture.db.update(
                    "INSERT INTO op_execution_step(execution_id,step_order,kind,status,detail)"
                            + " VALUES(?,1,'LEGACY_STEP','SUCCEEDED','时间缺失')",
                    id);
            var timeline =
                    (List<Map<String, Object>>)
                            new TraceAuditService(fixture.db, fixture.spaces)
                                    .trace(fixture.user, id)
                                    .get("timeline");
            assertEquals("EXECUTION", timeline.get(0).get("kind"));
            assertEquals("QUERY_EXEC", timeline.get(1).get("kind"));
            assertEquals("LEGACY_STEP", timeline.get(2).get("kind"));
            assertNull(timeline.get(2).get("at"));
        }
    }

    @Test
    void legacyTraceDoesNotInventAuthorizationOrTimestamps() throws Exception {
        try (var fixture = new HistoryDatabase()) {
            var id = fixture.execution();
            fixture.db.update(
                    "INSERT INTO op_execution_step(execution_id,step_order,kind,status,detail)"
                            + " VALUES(?,0,'QUERY_EXEC','SUCCEEDED','完成')",
                    id);
            fixture.db.update(
                    "INSERT INTO evidence_ref(execution_id,kind,ref)"
                            + " VALUES(?,'META_SNAPSHOT',?::jsonb)",
                    id,
                    "{\"id\":\"snapshot\",\"sha256\":\"" + "a".repeat(64) + "\"}");
            var trace = new TraceAuditService(fixture.db, fixture.spaces).trace(fixture.user, id);
            assertEquals(List.of(), trace.get("authorization"));
            var timeline = (List<Map<String, Object>>) trace.get("timeline");
            assertEquals(2, timeline.stream().filter(x -> x.get("at") == null).count());
            var evidence = (List<Map<String, Object>>) trace.get("evidence");
            assertEquals("a".repeat(64), evidence.getFirst().get("sha256"));
            assertEquals("META_SNAPSHOT", evidence.getFirst().get("type"));
            assertThrows(
                    Problem.class,
                    () ->
                            new TraceAuditService(fixture.db, fixture.spaces)
                                    .trace(fixture.other, id));
        }
    }

    @Test
    void auditCursorIsDescendingAndExcludesSensitiveDetails() throws Exception {
        try (var fixture = new HistoryDatabase()) {
            for (int n = 0; n < 3; n++)
                fixture.db.update(
                        "INSERT INTO"
                                + " audit_log(space_id,actor_id,action,subject_id,detail,draft_rev)"
                                + " VALUES(?,?,'RESTORE','release',?::jsonb,?)",
                        fixture.space,
                        fixture.user,
                        "{\"reason\":\"恢复核对\",\"password\":\"secret\",\"connCfg\":\"encrypted\",\"rows\":[{\"sensitive\":\"source-data\"}],\"unrecognizedSourceOutput\":{\"diagnosis\":\"private-source-output\"}}",
                        n);
            var service = new TraceAuditService(fixture.db, fixture.spaces);
            var page = service.audit(fixture.user, fixture.space, 2, null);
            var items = (List<Map<String, Object>>) page.get("items");
            assertEquals(2, items.size());
            assertTrue(
                    ((Number) items.getFirst().get("id")).longValue()
                            > ((Number) items.getLast().get("id")).longValue());
            String safe = fixture.db.json(page);
            assertFalse(safe.contains("secret"));
            assertFalse(safe.contains("encrypted"));
            assertFalse(safe.contains("source-data"));
            assertFalse(safe.contains("private-source-output"));
            assertTrue(safe.contains("恢复核对"));
            var next =
                    service.audit(
                            fixture.user,
                            fixture.space,
                            2,
                            ((Number) page.get("nextBeforeId")).longValue());
            assertEquals(1, ((List<?>) next.get("items")).size());
            assertNull(next.get("nextBeforeId"));
            assertThrows(Problem.class, () -> service.audit(fixture.other, fixture.space, 2, null));
        }
    }
}
