package cn.ontorepo.artifact;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;

import okhttp3.OkHttpClient;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 候选包及正式清单的对象存储边界。
 *
 * <p>相同键只允许首次写入或相同字节的幂等重试。数据库中的摘要和长度是读取的信任根； 对象存储中即使有同名对象也必须重新校验内容，不能把“文件存在”作为发布就绪依据。
 * 本类没有删除、覆盖或跳过摘要读取的业务接口。
 */
public final class ArtifactStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final MinioClient client;
    private final String bucket;

    public ArtifactStore(String endpoint, String accessKey, String secretKey, String bucket) {
        this.bucket = bucket;
        this.client =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .httpClient(
                                new OkHttpClient.Builder()
                                        .connectTimeout(5, TimeUnit.SECONDS)
                                        .readTimeout(30, TimeUnit.SECONDS)
                                        .writeTimeout(30, TimeUnit.SECONDS)
                                        .callTimeout(45, TimeUnit.SECONDS)
                                        .build())
                        .build();
    }

    /** 初始化独立业务桶；并发启动时另一进程已创建同桶可视为成功。 */
    public void initialize() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                } catch (ErrorResponseException e) {
                    if (!"BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) throw e;
                }
            }
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /** 原子创建制品。条件 PUT 由存储服务执行，禁止“先查再写”的竞态。 制品上限低于 multipart 分片阈值配置，使条件写作用于最终的单次 PUT。 */
    public ArtifactRef storeImmutable(String key, byte[] bytes, String contentType) {
        validateKey(key);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new ArtifactProblem("INVALID_ARTIFACT_SIZE", "制品必须非空且不超过 16 MiB");
        // 复制调用者数组，避免计算摘要后发生外部修改，导致上传内容与证明不一致。
        byte[] payload = bytes.clone();
        ArtifactRef ref = new ArtifactRef(key, digest(payload), payload.length);
        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .contentType(
                                    contentType == null ? "application/octet-stream" : contentType)
                            .extraHeaders(Map.of("If-None-Match", "*"))
                            .stream(
                                    new ByteArrayInputStream(payload),
                                    (long) payload.length,
                                    (long) MAX_BYTES)
                            .build());
        } catch (ErrorResponseException e) {
            if ("PreconditionFailed".equals(e.errorResponse().code())
                    || "ConditionalRequestConflict".equals(e.errorResponse().code())) {
                return reconcileUpload(ref, payload);
            }
            throw unavailable();
        } catch (Exception e) {
            // 条件写在大请求体发送完前就被拒绝时，HTTP 客户端可能只看到连接重置。
            // 也可能写入已完成而响应丢失；两种不确定结果都必须读取实际对象核对，不能盲目覆盖重试。
            return reconcileUpload(ref, payload);
        }
        loadVerified(ref);
        return ref;
    }

    private ArtifactRef reconcileUpload(ArtifactRef ref, byte[] payload) {
        byte[] existing;
        try {
            existing = readBounded(ref.key());
        } catch (ArtifactProblem e) {
            if (e.code().equals("ARTIFACT_MISSING")) throw unavailable();
            throw e;
        }
        // 幂等重试仅在已有对象的实际字节相同才成功，不相信可被改写的 metadata。
        if (existing.length == ref.size() && MessageDigest.isEqual(existing, payload)) return ref;
        throw new ArtifactProblem("ARTIFACT_EXISTS", "同一制品键已存在不同内容，禁止覆盖");
    }

    /** 每次读取都复核可信摘要和长度；不存在、损坏或不可访问均不能向执行器返回字节。 */
    public byte[] loadVerified(ArtifactRef ref) {
        if (ref == null
                || ref.sha256() == null
                || !ref.sha256().matches("[0-9a-f]{64}")
                || ref.size() <= 0
                || ref.size() > MAX_BYTES)
            throw new ArtifactProblem("INVALID_ARTIFACT_REF", "制品内容证明无效");
        validateKey(ref.key());
        byte[] bytes = readBounded(ref.key());
        if (bytes.length != ref.size()
                || !MessageDigest.isEqual(
                        HexFormat.of().parseHex(ref.sha256()),
                        HexFormat.of().parseHex(digest(bytes))))
            throw new ArtifactProblem("ARTIFACT_CORRUPT", "制品内容与归档摘要不一致，已拒绝读取");
        return bytes;
    }

    private byte[] readBounded(String key) {
        try (var response =
                client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = response.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new ArtifactProblem("ARTIFACT_CORRUPT", "归档对象异常增大，已拒绝读取");
            return bytes;
        } catch (ArtifactProblem e) {
            throw e;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()))
                throw new ArtifactProblem("ARTIFACT_MISSING", "归档制品不存在");
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static void validateKey(String key) {
        if (key == null
                || key.length() > 512
                || !key.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*")
                || java.util.Arrays.stream(key.split("/"))
                        .anyMatch(part -> part.equals(".") || part.equals("..")))
            throw new ArtifactProblem("INVALID_ARTIFACT_KEY", "制品键必须为受限的相对路径");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", e);
        }
    }

    private static ArtifactProblem unavailable() {
        return new ArtifactProblem("ARTIFACT_STORAGE_UNAVAILABLE", "对象存储暂时不可用，请修复后重试");
    }
}
