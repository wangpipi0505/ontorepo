package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.ontorepo.release.application.PublicationService;
import cn.ontorepo.release.infra.JdbcOutboxStore;
import cn.ontorepo.release.infra.OutboxProblem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;

/** 五写中最后事件落库故障必须回滚前序发布、投影、端点与候选状态，不能留下半发布。 */
class P4AtomicityTest extends P4Fixture {
    @Autowired PublicationService publication;
    @MockitoSpyBean JdbcOutboxStore outbox;

    @Test
    void finalOutboxFailureRollsBackEveryPublicationWriteAndAllowsSameCandidateRetry()
            throws Exception {
        var ready = ready();
        UUID id = UUID.fromString(ready.candidate().path("id").asText());
        var body = commitBody(ready);
        doThrow(new OutboxProblem("INJECTED_APPEND_FAILURE"))
                .when(outbox)
                .append(any(), any(), any(), any(), anyString(), anyString());
        assertThrows(OutboxProblem.class, () -> publication.commit(owner, id, body));
        for (String table :
                List.of(
                        "ontology_release",
                        "space_version",
                        "context_pack",
                        "release_entity",
                        "service_endpoint",
                        "publish_outbox"))
            assertEquals(
                    0, db.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
        assertEquals(
                "READY_TO_COMMIT",
                db.queryForObject(
                        "SELECT status FROM publish_candidate WHERE id=?", String.class, id));
        assertNull(
                db.queryForObject(
                        "SELECT release_id FROM publish_candidate WHERE id=?", UUID.class, id));
        reset(outbox);
        var release = publish(ready);
        assertEquals(ready.candidate().path("preReleaseId").asText(), release.path("id").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM publish_outbox", Integer.class));
    }
}
