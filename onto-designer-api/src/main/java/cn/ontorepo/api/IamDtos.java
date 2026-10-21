package cn.ontorepo.api;

import java.util.List;
import java.util.Map;

/** 管理 API 的公开传输模型，密码只存在于写入请求，不能混入用户资料或审计。 */
public final class IamDtos {
    private IamDtos() {}

    public record RoleRef(String id, String code, String name) {}

    public record SystemUser(
            String id,
            String username,
            String displayName,
            String status,
            long revision,
            String createdAt,
            List<RoleRef> roles) {}

    public record UserCreate(
            String username, String displayName, String password, List<String> roleIds) {}

    public record UserUpdate(
            String displayName, String status, List<String> roleIds, Long expectedRevision) {}

    public record PasswordReset(String password, Long expectedRevision) {}

    public record Role(
            String id,
            String code,
            String name,
            String description,
            boolean builtIn,
            String status,
            long revision,
            List<String> permissions,
            long memberCount) {}

    public record RoleCreate(
            String code, String name, String description, List<String> permissions) {}

    public record RoleUpdate(
            String name,
            String description,
            String status,
            List<String> permissions,
            Long expectedRevision) {}

    public record Audit(
            long id,
            String actorId,
            String actorName,
            String action,
            String targetId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            String createdAt) {}

    public record AuditPage(List<Audit> items, int page, int pageSize, long total) {}
}
