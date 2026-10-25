package cn.ontorepo.api;

import java.util.List;
import java.util.Map;

/** 冻结契约的传输类型，不将数据库行或密码字段暴露给接口文档。 */
public final class ApiDtos {
    private ApiDtos() {}

    /** 统一成功/错误信封；失败data为null，客户端按code处理可恢复错误。 */
    public record Envelope<T>(String code, String message, T data) {
        public static <T> Envelope<T> ok(T value) {
            return new Envelope<>("OK", "", value);
        }
    }

    /** 本地登录凭据，仅用于身份校验，不作为操作者请求参数透传。 */
    public record LoginRequest(String username, String password) {}

    /** 可公开用户资料，禁止包含密码哈希与令牌摘要。 */
    public record User(String id, String username, String displayName) {}

    /** 当前会话令牌和到期时间，令牌仅允许保存在前端内存。 */
    public record Login(String token, String expiresAt, User user) {}

    /** 空间详情及调用者角色；draftRev用于后续全部草稿写入的乐观并发校验。 */
    public record Space(
            String id,
            String code,
            String name,
            String namespaceUri,
            String domain,
            String description,
            String status,
            long draftRev,
            String role,
            long classCount,
            long propertyCount,
            long operationCount,
            String createdAt,
            String updatedAt) {}

    /** 空间创建参数；命名空间创建后不可修改，domain/description允许空字符串。 */
    public record SpaceCreate(
            String code, String name, String namespaceUri, String domain, String description) {}

    /** 空间可修改信息；expectedDraftRev必须等于锁定后的当前修订。 */
    public record SpaceUpdate(
            String name, String domain, String description, Long expectedDraftRev) {}

    /** 仅携带预期修订的归档或候选冻结请求。 */
    public record Revision(Long expectedDraftRev) {}

    /** 稳定UUID对象及可空父级，deprecated表示草稿中停用标记。 */
    public record OntClass(
            String id,
            String spaceId,
            String name,
            String parentId,
            String description,
            boolean deprecated) {}

    /** 对象写请求；parentId为null代表根节点，父级必须属于同空间。 */
    public record ClassWrite(
            String spaceId,
            String name,
            String parentId,
            String description,
            Boolean deprecated,
            /** R12 墓碑审计：false→true 弃用迁移的必填理由（≤500 字）。 */
            String deprecateReason,
            Long expectedDraftRev) {}

    /** 属性定义含单位、码表、默认值、枚举、范围和主键分面。 */
    public record OntProperty(
            String id,
            String spaceId,
            String domainClassId,
            String name,
            String displayName,
            String rangeType,
            boolean required,
            String description,
            Map<String, Object> facets) {}

    /** 属性写请求；domainClassId限定所属类，rangeType为受支持XSD基础类型。 */
    public record PropertyWrite(
            String spaceId,
            String domainClassId,
            String name,
            String displayName,
            String rangeType,
            Boolean required,
            String description,
            Map<String, Object> facets,
            Long expectedDraftRev) {}

    /** 四种候选操作；策略和规则引用随候选冻结，行数与超时由服务端强制。 */
    public record Operation(
            String id,
            String spaceId,
            String code,
            String name,
            String description,
            String precondition,
            String kind,
            String queryTemplate,
            int maxRows,
            int timeoutMs,
            String strategyRef,
            List<String> ruleIds) {}

    /** 受限SELECT操作写请求，禁止外部数据集、网络与扩展函数能力。 */
    public record OperationWrite(
            String spaceId,
            String code,
            String name,
            String description,
            String precondition,
            String queryTemplate,
            Integer maxRows,
            Integer timeoutMs,
            String kind,
            String strategyRef,
            List<String> ruleIds,
            Long expectedDraftRev) {}

    /** 冻结候选的版本、SHA-256和创建时间，TESTING不表示正式发布。 */
    public record Candidate(
            String id,
            String spaceId,
            long draftRev,
            String sha256,
            String status,
            String createdAt,
            Integer formatVersion,
            String capability,
            String preReleaseId,
            Long preVersionNo,
            String bundleSha256) {}

    /** 试执行请求中的操作标识必须来自所选候选，不能使用新草稿操作。 */
    public record Trial(
            String operationId,
            @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = Object.class,
                            schemaResolution =
                                    io.swagger.v3.oas.annotations.media.Schema.SchemaResolution
                                            .INLINE,
                            types = {"object", "array", "string", "number", "boolean", "null"},
                            description = "按冻结Schema校验的JSON输入；省略时沿用P1空对象")
                    com.fasterxml.jackson.databind.JsonNode input) {}

    /** 按执行顺序保存的步骤结果和可公开中文说明。 */
    public record Step(String kind, String status, String detail) {}

    /** 只读执行结果和留痕；列表与详情均返回服务端持久化caller，不以当前请求入口猜历史身份。 */
    public record Execution(
            String id,
            String traceId,
            String subjectType,
            String subjectId,
            String opCode,
            String caller,
            String status,
            String errorCode,
            long elapsedMs,
            String createdAt,
            List<String> columns,
            List<Map<String, Object>> rows,
            String candidateSha256,
            List<Step> steps,
            List<Map<String, Object>> evidence) {}
}
