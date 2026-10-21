package cn.ontorepo.trace.application;

import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.TimeOutException;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 同一次检查只拥有一份总预算和一个活动引擎；按引擎隔离取消，迟到创建的实例立即释放。 */
final class ReasoningBudget {
    private final long started = System.nanoTime();
    private final long nanos;
    private volatile String fallbackReason;
    private boolean cancelled;
    private OWLReasoner active;
    private boolean activeInterruptAllowed;

    ReasoningBudget(long millis) {
        nanos = TimeUnit.MILLISECONDS.toNanos(millis);
    }

    long remainingNanos() {
        return Math.max(0, nanos - (System.nanoTime() - started));
    }

    long remainingMillis() {
        long left = remainingNanos();
        if (left <= 0 || Thread.currentThread().isInterrupted()) throw new TimeOutException();
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(left));
    }

    synchronized void attach(OWLReasoner reasoner, boolean interruptAllowed) {
        if (cancelled || remainingNanos() == 0) {
            // 工厂迟到的实例尚未开始查询，只需释放；不能中断其他请求共享的引擎设施。
            reasoner.dispose();
            throw new TimeOutException();
        }
        active = reasoner;
        activeInterruptAllowed = interruptAllowed;
    }

    synchronized void detach(OWLReasoner reasoner) {
        if (active == reasoner) {
            active = null;
            activeInterruptAllowed = false;
        }
    }

    synchronized void cancel() {
        cancelled = true;
        // ELK 0.6 的 interrupt() 使用静态共享 DEFAULT_INTERRUPTER，会污染并发和后续实例。
        // ELK 由调用方的 Future.cancel(true) 中断本任务线程；HermiT 的实例中断可安全使用。
        if (active != null && activeInterruptAllowed)
            try {
                active.interrupt();
            } catch (RuntimeException ignored) {
                /* 超时结果仍保持失败，工作线程finally负责dispose。 */
            }
    }

    void fallback(String reason) {
        remainingMillis();
        fallbackReason = reason;
    }

    Map<String, Object> details(Map<String, ?> extra) {
        var result = new LinkedHashMap<String, Object>();
        result.put("engine", fallbackReason == null ? "ELK" : "ELK→HermiT");
        if (fallbackReason != null) {
            result.put("engines", List.of("ELK", "HermiT"));
            result.put("incomplete", true);
            result.put("fallbackReason", fallbackReason);
        }
        result.putAll(extra);
        return result;
    }
}
