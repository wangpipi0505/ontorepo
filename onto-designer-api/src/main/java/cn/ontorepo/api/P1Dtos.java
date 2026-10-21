package cn.ontorepo.api;

import java.util.*;

/** P1 扩展传输模型；动态 JSON 仅用于表达式、分面和确定性用例断言。 */
public final class P1Dtos {
    private P1Dtos() {}

    /** 码表契约，空间写入必须携带预期修订。 */
    public record Codebook(
            String id,
            String spaceId,
            String code,
            String name,
            String kind,
            List<CodebookEntry> entries) {}

    /** P1 CodebookEntry契约，空间写入必须携带预期修订。 */
    public record CodebookEntry(String code, String label) {}

    /** P1 CodebookWrite契约，空间写入必须携带预期修订。 */
    public record CodebookWrite(
            String spaceId,
            Long expectedDraftRev,
            String code,
            String name,
            String kind,
            List<CodebookEntry> entries) {}

    /** 关系契约，空间写入必须携带预期修订。 */
    public record Relation(
            String id,
            String spaceId,
            String name,
            String fromClassId,
            String toClassId,
            String cardinality,
            String inverseOf,
            boolean transitive,
            boolean symmetric,
            String description,
            boolean subsetExceeded) {}

    /** P1 RelationWrite契约，空间写入必须携带预期修订。 */
    public record RelationWrite(
            String spaceId,
            Long expectedDraftRev,
            String name,
            String fromClassId,
            String toClassId,
            String cardinality,
            String inverseName,
            Boolean transitive,
            Boolean symmetric,
            String description) {}

    /** 约束契约，空间写入必须携带预期修订。 */
    public record Constraint(
            String id,
            String spaceId,
            String name,
            String kind,
            String targetType,
            String targetRef,
            Map<String, Object> expr) {}

    /** P1 ConstraintWrite契约，空间写入必须携带预期修订。 */
    public record ConstraintWrite(
            String spaceId,
            Long expectedDraftRev,
            String name,
            String kind,
            String targetType,
            String targetRef,
            Map<String, Object> expr) {}

    /** 规则契约，空间写入必须携带预期修订。 */
    public record Rule(
            String id,
            String spaceId,
            String name,
            String applicableClassId,
            String kind,
            String condition,
            Map<String, Object> action,
            Map<String, Object> scoring,
            String severity) {}

    /** P1 RuleWrite契约，空间写入必须携带预期修订。 */
    public record RuleWrite(
            String spaceId,
            Long expectedDraftRev,
            String name,
            String applicableClassId,
            String kind,
            String condition,
            Map<String, Object> action,
            Map<String, Object> scoring,
            String severity) {}

    /** 执行策略契约，空间写入必须携带预期修订。 */
    public record Strategy(
            String id,
            String spaceId,
            String name,
            Map<String, Object> policy,
            List<String> boundOps) {}

    /** P1 StrategyWrite契约，空间写入必须携带预期修订。 */
    public record StrategyWrite(
            String spaceId,
            Long expectedDraftRev,
            String name,
            Map<String, Object> policy,
            List<String> boundOps) {}

    /** 确定性用例契约，空间写入必须携带预期修订。 */
    public record TestCase(
            String id,
            String spaceId,
            String name,
            String question,
            String operationId,
            @io.swagger.v3.oas.annotations.media.Schema(
                            types = {"object", "array", "string", "number", "boolean", "null"})
                    Object input,
            Map<String, Object> expected,
            String matchMode,
            boolean enabled,
            String mode) {}

    /** P1 TestCaseWrite契约，空间写入必须携带预期修订。 */
    public record TestCaseWrite(
            String spaceId,
            Long expectedDraftRev,
            String name,
            String question,
            String operationId,
            @io.swagger.v3.oas.annotations.media.Schema(
                            types = {"object", "array", "string", "number", "boolean", "null"})
                    Object input,
            Map<String, Object> expected,
            String matchMode,
            Boolean enabled,
            String mode) {}

    /** P1 Impact契约，空间写入必须携带预期修订。 */
    public record Impact(List<Reference> references) {}

    /** P1 Reference契约，空间写入必须携带预期修订。 */
    public record Reference(String kind, String id, String name) {}

    /** P1 Conflict契约，空间写入必须携带预期修订。 */
    public record Conflict(String kind, String message, List<String> entityIds) {}

    /** P1 Conflicts契约，空间写入必须携带预期修订。 */
    public record Conflicts(List<Conflict> errors, List<Conflict> warnings) {}

    /** P1 ImportRequest契约，空间写入必须携带预期修订。 */
    public record ImportRequest(String kind, String content, Long expectedDraftRev) {}

    /** P1 ImportIssue契约，空间写入必须携带预期修订。 */
    public record ImportIssue(int line, String message) {}

    /** P1 Unsupported契约，空间写入必须携带预期修订。 */
    public record Unsupported(int line, String construct, String message) {}

    /** P1 ImportPreview契约，空间写入必须携带预期修订。 */
    public record ImportPreview(
            String id,
            String status,
            long targetDraftRev,
            int added,
            int updated,
            List<ImportIssue> conflicts,
            List<Unsupported> unsupported,
            List<ImportIssue> errors) {}

    /** P1 Export契约，空间写入必须携带预期修订。 */
    public record Export(String content, String format) {}

    /** P1 TestResult契约，空间写入必须携带预期修订。 */
    public record TestResult(
            String caseId, String name, boolean passed, String executionId, String message) {}

    /** P1 Tests契约，空间写入必须携带预期修订。 */
    public record Tests(String candidateSha256, boolean passed, List<TestResult> results) {}

    /** P1 Check契约，空间写入必须携带预期修订。 */
    public record Check(
            String kind,
            String conclusion,
            String reason,
            String message,
            Map<String, Object> details) {}

    /** P1 Checks契约，空间写入必须携带预期修订。 */
    public record Checks(
            String candidateSha256, boolean eligible, List<Check> checks, boolean supplyEligible) {}

    /** P1 WaiverRequest契约，空间写入必须携带预期修订。 */
    public record WaiverRequest(String kind, String reason) {}

    /** P1 Waiver契约，空间写入必须携带预期修订。 */
    public record Waiver(
            String id, String candidateSha256, String kind, String reason, String approver) {}
}
