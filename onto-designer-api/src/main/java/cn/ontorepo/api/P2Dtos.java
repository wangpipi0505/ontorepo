package cn.ontorepo.api;

import java.util.*;

/** P2独立管理请求；assistantTool内部保留消费协议snake_case。 */
public final class P2Dtos {
    private P2Dtos() {}

    /** 仅声明三段定义；未配置段显式为null，不回传操作SQL。 */
    public record AssistantDeclaration(
            UUID operationId,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> assistantTool) {}

    public record Guards(Map<String, Object> guardCfg) {}

    public record GrantUser(UUID userId, String username, String displayName) {}

    public record SpaceGrant(
            UUID userId,
            String username,
            String displayName,
            String role,
            Object dataScope,
            Map<String, Object> attributes) {}

    public record KnowledgeEntry(
            UUID id,
            UUID spaceId,
            String kind,
            String term,
            List<String> aliases,
            String definition,
            UUID conceptRef,
            String sourceRef,
            String subjectRef,
            String predicate,
            String objectRef) {}

    public record TermEntry(
            UUID id,
            UUID entryId,
            UUID spaceId,
            String term,
            List<String> aliases,
            String definition,
            UUID conceptRef,
            String sourceRef) {}

    public record CandidateContracts(
            UUID candidateId,
            String candidateSha256,
            List<com.fasterxml.jackson.databind.JsonNode> manifests) {}

    public record ContextPack(
            UUID candidateId,
            String candidateSha256,
            List<Map<String, Object>> conceptCards,
            List<TermEntry> termDictionary,
            List<Map<String, Object>> relationSummaries,
            List<Map<String, Object>> exampleQa) {}

    public record KnowledgeHit(
            UUID entryId,
            String kind,
            String term,
            String definition,
            double score,
            String matchedAlias,
            String sourceRef) {}

    public record KnowledgeSearch(
            UUID candidateId, String candidateSha256, List<KnowledgeHit> hits) {}

    public record ValidationResult(boolean valid) {}

    public record CandidateMeta(
            String spaceCode,
            UUID releaseId,
            String version,
            String contentDigest,
            boolean preview) {}

    public record ServiceEnvelope(String code, Map<String, Object> data, CandidateMeta meta) {}

    public record AssistantWrite(
            UUID spaceId,
            Long expectedDraftRev,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> assistantTool) {}

    public record GuardWrite(Long expectedDraftRev, Map<String, Object> guardCfg) {}

    public record GrantWrite(
            Long expectedDraftRev, String role, Object dataScope, Map<String, Object> attributes) {}

    public record KnowledgeWrite(
            UUID spaceId,
            Long expectedDraftRev,
            String kind,
            String term,
            List<String> aliases,
            String definition,
            UUID conceptRef,
            String sourceRef,
            String subjectRef,
            String predicate,
            String objectRef) {}
}
