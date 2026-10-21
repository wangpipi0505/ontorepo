package cn.ontorepo.release.application;

import cn.ontorepo.release.infra.JdbcOutboxStore;
import cn.ontorepo.release.infra.OutboxDestination;
import cn.ontorepo.release.infra.OutboxProblem;

import java.util.Objects;

/** 有界relay：领取事务已提交后才发送，目的地确认后再CAS确认；无日志冒充SENT路径。 进程崩溃/响应丢失由持久租约恢复并重复投递原eventId，目的地负责幂等接收。 */
public final class OutboxRelay {
    private final JdbcOutboxStore store;
    private final OutboxDestination destination;

    public OutboxRelay(JdbcOutboxStore store, OutboxDestination destination) {
        this.store = Objects.requireNonNull(store);
        this.destination = Objects.requireNonNull(destination);
    }

    /** 返回本批真正获确认并完成CAS的数量；失败及失效确认不计入成功。 */
    public int runOnce(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("投递批次须为1..100");
        int sent = 0;
        for (int attempted = 0; attempted < limit; attempted++) {
            // 串行worker只即时领取一项，避免等待前项网络时消耗整批后继租约。
            var claimed = store.claim(1);
            if (claimed.isEmpty()) break;
            var delivery = claimed.getFirst();
            try {
                destination.deliver(delivery);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                store.fail(delivery, "DELIVERY_INTERRUPTED");
                break;
            } catch (Exception e) {
                store.fail(
                        delivery,
                        e instanceof OutboxProblem p ? p.code() : "DESTINATION_UNAVAILABLE");
                continue;
            }
            // 确认写库失败不改写成“发送失败”：收件方可能已经持久化，租约到期后按原id重试。
            if (store.acknowledge(delivery)) sent++;
        }
        return sent;
    }
}
