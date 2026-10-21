package cn.ontorepo.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P3请求DTO；连接配置仅作为入参存在，不出现在任何响应结构。 */
public final class P3Dtos {
    private P3Dtos() {}

    /** 新管理DSL拒绝未知字段，防止raw SQL等被静默忽略而呈现配置成功。 */
    public interface StrictWrite {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        default void unknown(String key, Object value) {
            throw new IllegalArgumentException("未知请求字段");
        }
    }

    public record SourceWrite(
            UUID spaceId,
            Long expectedDraftRev,
            String code,
            String name,
            String kind,
            Map<String, Object> connection,
            List<String> accessScope,
            String description,
            String tenant,
            UUID ownerId)
            implements StrictWrite {}

    public record SpaceRequest(UUID spaceId) implements StrictWrite {}

    public record RevisionRequest(UUID spaceId, Long expectedDraftRev) implements StrictWrite {}

    public record ObjectWrite(
            UUID spaceId,
            Long expectedDraftRev,
            UUID datasourceId,
            String sourceResource,
            UUID targetClassId,
            String objectKeyExpr,
            Map<String, Object> filter,
            UUID metaSnapshotId)
            implements StrictWrite {}

    public record PropertyWrite(
            UUID spaceId,
            Long expectedDraftRev,
            UUID objectMappingId,
            String sourceField,
            UUID targetPropertyId,
            Map<String, Object> transform,
            Boolean required)
            implements StrictWrite {}

    public record RelationWrite(
            UUID spaceId,
            Long expectedDraftRev,
            UUID datasourceId,
            UUID relationId,
            UUID fromObjectMappingId,
            UUID toObjectMappingId,
            String fromKeyExpr,
            String toKeyExpr,
            Map<String, Object> join,
            Boolean cardinalityCheck,
            UUID metaSnapshotId)
            implements StrictWrite {}

    public record DataQueryWrite(UUID spaceId, Long expectedDraftRev, Map<String, Object> dataQuery)
            implements StrictWrite {}

    public record ExtractWrite(
            UUID spaceId,
            Long expectedDraftRev,
            String sourceKind,
            UUID datasourceId,
            UUID metaSnapshotId,
            String resource,
            UUID fileId)
            implements StrictWrite {}

    public record Decision(UUID id, String decision, Map<String, Object> payload)
            implements StrictWrite {}

    public record DecideWrite(UUID spaceId, Long expectedDraftRev, List<Decision> decisions)
            implements StrictWrite {}
}
