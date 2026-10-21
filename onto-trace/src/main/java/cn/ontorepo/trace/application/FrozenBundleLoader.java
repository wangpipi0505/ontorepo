package cn.ontorepo.trace.application;

import cn.ontorepo.artifact.*;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.springframework.stereotype.Service;

import java.util.*;

/** 新候选与正式版本唯一执行权威为经真实对象摘要校验的bundle，PG原字节仅兼容旧候选。 */
@Service
public class FrozenBundleLoader {
    private final Db db;
    private final ArtifactStore artifacts;
    private final CanonicalPayload codec;

    public FrozenBundleLoader(Db db, ArtifactStore artifacts, CanonicalPayload codec) {
        this.db = db;
        this.artifacts = artifacts;
        this.codec = codec;
    }

    public Map<String, Object> release(UUID id, boolean readyOnly) {
        var row = db.one("SELECT * FROM ontology_release WHERE id=?", id);
        if (readyOnly && !"READY".equals(row.get("status")))
            throw new Problem(409, "RELEASE_NOT_READY", "该版本已不可消费");
        return row;
    }

    /** 调用方负责身份/空间授权；本边界只处理可信引用、摘要和原字节。 */
    public Map<String, Object> payload(Map<String, Object> row) {
        return codec.decode(bytes(row));
    }

    public byte[] bytes(Map<String, Object> row) {
        Object cached = row.get("payload");
        String expected = Objects.toString(row.getOrDefault("bundleSha256", row.get("sha256")), "");
        if (cached instanceof byte[] original && !AuthService.digest(original).equals(expected))
            throw new Problem(409, "CANDIDATE_CORRUPT", "冻结载荷缓存摘要不匹配");
        if (row.get("bundleRef") instanceof Map<?, ?> ref) {
            if (!expected.equals(ref.get("sha256")))
                throw new Problem(409, "CANDIDATE_CORRUPT", "冻结制品引用摘要不匹配");
            return verified(ref);
        }
        if (row.get("preReleaseId") != null
                || ((Number) row.getOrDefault("formatVersion", 1)).intValue() >= 3)
            throw new Problem(409, "CANDIDATE_BUNDLE_NOT_READY", "候选制品尚未归档，请查看失败报告");
        if (cached instanceof byte[] original) return original;
        throw new Problem(409, "ARTIFACT_CORRUPT", "冻结制品引用缺失");
    }

    public byte[] verified(Map<?, ?> ref) {
        try {
            return artifacts.loadVerified(
                    new ArtifactRef(
                            ref.get("key").toString(),
                            ref.get("sha256").toString(),
                            ((Number) ref.get("size")).longValue()));
        } catch (ArtifactProblem e) {
            throw new Problem(
                    "ARTIFACT_STORAGE_UNAVAILABLE".equals(e.code()) ? 503 : 409,
                    e.code(),
                    e.getMessage());
        }
    }
}
