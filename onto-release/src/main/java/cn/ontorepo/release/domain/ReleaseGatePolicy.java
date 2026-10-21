package cn.ontorepo.release.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 发布准备阶段的五项门禁判定。
 *
 * <p>数据库服务负责取同一候选各门禁的最新持久报告，并在空间锁下验证身份和修订。 本类不查询数据库、不上传制品，也不会把此处的 allowed 当作 READY；READY
 * 还必须经过制品摘要复核和提交事务。
 */
public final class ReleaseGatePolicy {
    private ReleaseGatePolicy() {}

    public enum Kind {
        CONFLICT,
        CONSISTENCY,
        CONTRACT,
        TEST,
        REVIEW
    }

    public enum Conclusion {
        PASSED,
        FAILED,
        NOT_CHECKED
    }

    public record Check(Kind kind, String candidateSha256, Conclusion conclusion, String reason) {}

    /** 只能由服务端从已授权审批记录构建，不接收提交请求中自报的 role。 */
    public record Waiver(String role, String candidateSha256, long draftRev, String reason) {}

    public record Violation(String code, Kind kind) {}

    public record Result(boolean allowed, List<Violation> violations) {
        public Result {
            violations = List.copyOf(violations);
        }
    }

    /** 草稿修订或任何报告摘要不匹配时均失败；只允许一份有效报告代表每个门禁。 */
    public static Result evaluate(
            String sha,
            long candidateRev,
            long currentRev,
            List<Check> checks,
            int enabledCaseCount,
            Waiver waiver) {
        List<Violation> violations = new ArrayList<>();
        if (sha == null || !sha.matches("[0-9a-f]{64}") || candidateRev < 0 || currentRev < 0)
            violations.add(new Violation("INVALID_CANDIDATE", null));
        if (candidateRev != currentRev) violations.add(new Violation("DRAFT_CHANGED", null));
        if (enabledCaseCount < 1) violations.add(new Violation("NO_ENABLED_TEST_CASES", Kind.TEST));
        var byKind = new EnumMap<Kind, Check>(Kind.class);
        if (checks != null) {
            for (Check check : checks) {
                if (check == null || check.kind() == null || check.conclusion() == null) {
                    violations.add(new Violation("INVALID_GATE_REPORT", null));
                    continue;
                }
                if (byKind.putIfAbsent(check.kind(), check) != null)
                    violations.add(new Violation("DUPLICATE_GATE_REPORT", check.kind()));
            }
        }
        for (Kind kind : Kind.values()) {
            Check check = byKind.get(kind);
            if (check == null) {
                violations.add(new Violation("GATE_NOT_RUN", kind));
            } else if (sha == null || !sha.equals(check.candidateSha256())) {
                violations.add(new Violation("GATE_DIGEST_MISMATCH", kind));
            } else if (check.conclusion() != Conclusion.PASSED
                    && !waived(check, waiver, sha, candidateRev, currentRev)) {
                violations.add(new Violation("GATE_BLOCKED", kind));
            }
        }
        return new Result(violations.isEmpty(), violations);
    }

    private static boolean waived(
            Check check, Waiver waiver, String sha, long candidateRev, long currentRev) {
        return check.kind() == Kind.CONSISTENCY
                && check.conclusion() == Conclusion.NOT_CHECKED
                && "SUBSET_EXCEEDED".equals(check.reason())
                && waiver != null
                && "OWNER".equals(waiver.role())
                && sha.equals(waiver.candidateSha256())
                && waiver.draftRev() == candidateRev
                && candidateRev == currentRev
                && waiver.reason() != null
                && !waiver.reason().isBlank();
    }
}
