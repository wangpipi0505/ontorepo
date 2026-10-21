package cn.ontorepo.common.domain;

/**
 * 处理审计的可选要素。对象/版本/摘要/结果缺省时传 null，不伪造历史事实。
 *
 * @param subjectType 对象类型（如 CLASS/RULE/OPERATION/CANDIDATE/RELEASE/DATASOURCE/MAPPING）
 * @param subjectId 对象稳定标识
 * @param versionRefs 版本引用（草稿修订、快照、候选/发布摘要等），JSON 可序列化对象
 * @param inputDigest 输入摘要
 * @param outputDigest 输出摘要
 * @param result 结果（SUCCEEDED/FAILED/DENIED 等）
 */
public record AuditDetail(
        String subjectType,
        String subjectId,
        Object versionRefs,
        String inputDigest,
        String outputDigest,
        String result) {}
