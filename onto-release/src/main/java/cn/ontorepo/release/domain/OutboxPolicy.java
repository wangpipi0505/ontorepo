package cn.ontorepo.release.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 只根据持久化事件锚点、顺序与重试时间决定投递资格。
 *
 * <p>故意不读取 release 当前状态：RELEASED 创建后发生弃用/损坏，历史通知仍有资格投递。 实际 relay 必须按本规则在数据库中原子领取，网络发送在事务外，目的地按原事件
 * id 去重。
 */
public final class OutboxPolicy {
    private OutboxPolicy() {}

    public enum Type {
        RELEASED,
        DEPRECATED,
        CORRUPTED_ALERT
    }

    public enum Status {
        PENDING,
        SENT,
        DEAD
    }

    public enum Action {
        DELIVER,
        REJECT
    }

    public record Event(
            long id,
            String releaseId,
            Type type,
            String boundStatus,
            Status status,
            int retryCount,
            Instant nextRetryAt) {
        public Event {
            if (id < 1
                    || releaseId == null
                    || releaseId.isBlank()
                    || type == null
                    || status == null
                    || retryCount < 0
                    || nextRetryAt == null) throw new IllegalArgumentException("事件身份、状态或重试信息无效");
        }
    }

    public record Decision(long eventId, Action action) {}

    public record Retry(Status status, int retryCount, Instant nextRetryAt) {}

    /**
     * 每个 release 只选择最早的 PENDING。即使它尚未到期，也必须阻止该 release 的后续事件越序。 非法资格返回 REJECT，由持久层记录
     * DEAD/告警后才允许后继；绝不向目的地发出非法通知。
     */
    public static List<Decision> next(List<Event> events, Instant now) {
        if (events == null || now == null) throw new IllegalArgumentException("事件列表和时间不可为空");
        var seen = new HashSet<String>();
        var decisions = new ArrayList<Decision>();
        for (Event event : events.stream().sorted(Comparator.comparingLong(Event::id)).toList()) {
            if (event.status() != Status.PENDING || !seen.add(event.releaseId())) continue;
            if (!eligible(event)) decisions.add(new Decision(event.id(), Action.REJECT));
            else if (!event.nextRetryAt().isAfter(now))
                decisions.add(new Decision(event.id(), Action.DELIVER));
        }
        return List.copyOf(decisions);
    }

    /** 有限指数退避；到达上限后 DEAD，不再占据后继的排序屏障。 */
    public static Retry failure(Event event, Instant now, int maxAttempts) {
        if (event == null
                || event.status() != Status.PENDING
                || now == null
                || maxAttempts < 1
                || maxAttempts > 10) throw new IllegalArgumentException("失败重试上下文无效");
        int retry = Math.addExact(event.retryCount(), 1);
        if (retry >= maxAttempts) return new Retry(Status.DEAD, retry, now);
        long delaySeconds = Math.min(300L, 5L << Math.min(retry - 1, 6));
        return new Retry(Status.PENDING, retry, now.plusSeconds(delaySeconds));
    }

    /** 人工重放保留事件 id/锚点/载荷身份，只重置本轮重试预算。 OWNER 权限、原因和累计尝试历史由应用事务校验并另记审计。 */
    public static Event replay(Event event, Instant now) {
        if (event == null || event.status() != Status.DEAD || now == null || !eligible(event))
            throw new IllegalArgumentException("只有资格有效的 DEAD 事件可以重放");
        return new Event(
                event.id(),
                event.releaseId(),
                event.type(),
                event.boundStatus(),
                Status.PENDING,
                0,
                now);
    }

    private static boolean eligible(Event event) {
        String expected =
                switch (event.type()) {
                    case RELEASED -> "READY";
                    case DEPRECATED -> "DEPRECATED";
                    case CORRUPTED_ALERT -> "CORRUPTED";
                };
        return expected.equals(event.boundStatus());
    }
}
