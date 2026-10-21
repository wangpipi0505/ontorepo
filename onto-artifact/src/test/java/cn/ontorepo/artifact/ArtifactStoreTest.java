package cn.ontorepo.artifact;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

/** 真实独立 MinIO 验证：测试数据位于随机 test 前缀，不清理或复用其他项目制品。 */
class ArtifactStoreTest {
    private static ArtifactStore store;
    private static MinioClient admin;
    private static String bucket;

    @BeforeAll
    static void connect() {
        String endpoint = required("ONTO_STORAGE_ENDPOINT");
        String access = required("ONTO_STORAGE_ACCESS_KEY");
        String secret = required("ONTO_STORAGE_SECRET_KEY");
        bucket = required("ONTO_STORAGE_BUCKET");
        store = new ArtifactStore(endpoint, access, secret, bucket);
        admin = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
        store.initialize();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少独立对象存储环境变量：" + name);
        return value;
    }

    @Test
    void roundtripAndIdempotentUploadUseActualDigest() {
        byte[] payload = "{\"对象\":\"任务\"}".getBytes(StandardCharsets.UTF_8);
        String key = "test/" + UUID.randomUUID() + "/bundle.json";
        var ref = store.storeImmutable(key, payload, "application/json");
        assertEquals(64, ref.sha256().length());
        assertEquals(ref, store.storeImmutable(key, payload, "application/json"));
        assertArrayEquals(payload, store.loadVerified(ref));
    }

    @Test
    void differentPayloadCannotOverwriteExistingObject() {
        String key = "test/" + UUID.randomUUID() + "/bundle.json";
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        var ref = store.storeImmutable(key, original, "application/json");
        assertEquals(
                "ARTIFACT_EXISTS",
                assertThrows(
                                ArtifactProblem.class,
                                () ->
                                        store.storeImmutable(
                                                key,
                                                "changed".getBytes(StandardCharsets.UTF_8),
                                                "application/json"))
                        .code());
        assertArrayEquals(original, store.loadVerified(ref));
    }

    @Test
    void physicalCorruptionIsDetectedBeforeUse() throws Exception {
        String key = "test/" + UUID.randomUUID() + "/bundle.json";
        var ref =
                store.storeImmutable(
                        key, "original".getBytes(StandardCharsets.UTF_8), "application/json");
        byte[] corrupted = "tampered".getBytes(StandardCharsets.UTF_8);
        admin.putObject(
                PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                new ByteArrayInputStream(corrupted), (long) corrupted.length, -1L)
                        .build());
        assertEquals(
                "ARTIFACT_CORRUPT",
                assertThrows(ArtifactProblem.class, () -> store.loadVerified(ref)).code());
    }

    @Test
    void concurrentDifferentUploadsHaveOnlyOneWinner() throws Exception {
        String key = "test/" + UUID.randomUUID() + "/manifest.json";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> upload(key, "first"));
            var second = pool.submit(() -> upload(key, "second"));
            assertNotEquals(first.get(), second.get(), "同键不同内容必须只允许一个写入成功");
        }
    }

    private boolean upload(String key, String value) {
        try {
            store.storeImmutable(key, value.getBytes(StandardCharsets.UTF_8), "application/json");
            return true;
        } catch (ArtifactProblem e) {
            assertEquals("ARTIFACT_EXISTS", e.code());
            return false;
        }
    }

    @Test
    void invalidKeysAndMissingArtifactsFailClosed() {
        for (String key :
                new String[] {"../secrets", "/absolute", "http://evil.invalid/x", "a/../b"})
            assertEquals(
                    "INVALID_ARTIFACT_KEY",
                    assertThrows(
                                    ArtifactProblem.class,
                                    () ->
                                            store.storeImmutable(
                                                    key, new byte[] {1}, "application/json"))
                            .code());
        var missing =
                new ArtifactRef("test/" + UUID.randomUUID() + "/missing.json", "0".repeat(64), 1);
        assertEquals(
                "ARTIFACT_MISSING",
                assertThrows(ArtifactProblem.class, () -> store.loadVerified(missing)).code());
    }

    @Test
    void payloadAboveDefaultMultipartThresholdStillCannotBeOverwritten() {
        // 超过 SDK 通常使用的 5 MiB 阈值，验证条件写没有因上传模式改变而失效。
        byte[] large = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(large, (byte) 7);
        String key = "test/" + UUID.randomUUID() + "/large.json";
        var ref = store.storeImmutable(key, large, "application/octet-stream");
        large[0] = 8;
        assertEquals(
                "ARTIFACT_EXISTS",
                assertThrows(
                                ArtifactProblem.class,
                                () -> store.storeImmutable(key, large, "application/octet-stream"))
                        .code());
        assertEquals(7, store.loadVerified(ref)[0]);
    }
}
