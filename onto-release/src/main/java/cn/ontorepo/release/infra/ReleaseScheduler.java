package cn.ontorepo.release.infra;

import cn.ontorepo.release.application.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 生产调度有界且可禁用；异常仅输出安全分类，不泄露目标响应/数据库连接或凭据。 */
@Component
public class ReleaseScheduler {
    private final OutboxRelay relay;
    private final ReleaseLifecycleService lifecycle;
    private final boolean relayEnabled, integrityEnabled;
    private final cn.ontorepo.trace.application.CandidateService candidates;
    private final boolean freezeRecoveryEnabled;

    public ReleaseScheduler(
            OutboxRelay relay,
            ReleaseLifecycleService lifecycle,
            @Value("${onto.release.relay-enabled:true}") boolean relayEnabled,
            @Value("${onto.release.integrity-enabled:true}") boolean integrityEnabled,
            cn.ontorepo.trace.application.CandidateService candidates,
            @Value("${onto.release.freeze-recovery-enabled:true}") boolean freezeRecoveryEnabled) {
        this.relay = relay;
        this.lifecycle = lifecycle;
        this.relayEnabled = relayEnabled;
        this.integrityEnabled = integrityEnabled;
        this.candidates = candidates;
        this.freezeRecoveryEnabled = freezeRecoveryEnabled;
    }

    /** 每次最多恢复20条崩溃冻结；失败留待后续扫描，不扩大为人工复核超时。 */
    @Scheduled(
            fixedDelayString = "${onto.release.freeze-recovery-delay-ms:30000}",
            initialDelayString = "${onto.release.freeze-recovery-initial-delay-ms:30000}")
    public void recoverFreezing() {
        if (freezeRecoveryEnabled)
            try {
                candidates.recoverFreezing(20);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("CANDIDATE_FREEZE_RECOVERY_RETRYABLE_FAILURE");
            }
    }

    @Scheduled(
            fixedDelayString = "${onto.release.relay-delay-ms:5000}",
            initialDelayString = "${onto.release.relay-initial-delay-ms:10000}")
    public void relay() {
        if (relayEnabled)
            try {
                relay.runOnce(20);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("OUTBOX_RELAY_RETRYABLE_FAILURE");
            }
    }

    @Scheduled(
            fixedDelayString = "${onto.release.integrity-delay-ms:60000}",
            initialDelayString = "${onto.release.integrity-initial-delay-ms:60000}")
    public void integrity() {
        if (integrityEnabled)
            try {
                lifecycle.scanBatch(10);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("ARTIFACT_SCAN_RETRYABLE_FAILURE");
            }
    }
}
