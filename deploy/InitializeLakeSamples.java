import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.schema.MessageTypeParser;

/**
 * 生成真实Parquet并上传本工程MinIO，供P3公开API及页面联调使用。
 * 每次使用独立前缀，不覆盖旧样例或发布制品；凭据只从进程环境取得，报告只写资源定位。
 */
class InitializeLakeSamples {
    public static void main(String[] args) throws Exception {
        var bytes = new ByteArrayOutputStream();
        OutputFile output = new OutputFile() {
            public PositionOutputStream create(long hint) {
                return new PositionOutputStream() {
                    public long getPos() { return bytes.size(); }
                    public void write(int value) { bytes.write(value); }
                    public void write(byte[] value, int offset, int length) { bytes.write(value, offset, length); }
                };
            }
            public PositionOutputStream createOrOverwrite(long hint) { return create(hint); }
            public boolean supportsBlockSize() { return false; }
            public long defaultBlockSize() { return 0; }
        };
        var schema = MessageTypeParser.parseMessageType(
                "message tasks { required binary task_id (STRING); required binary task_name (STRING);"
                        + " optional binary region (STRING); required int32 priority; }");
        try (var writer = ExampleParquetWriter.builder(output)
                .withConf(new Configuration(false)).withType(schema)
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED).build()) {
            // A不在首行，用于确认授权过滤发生在结果上限之前；无标签行必须被默认拒绝。
            writer.write(new SimpleGroup(schema).append("task_id", "T-B")
                    .append("task_name", "华北检测任务").append("region", "B").append("priority", 2));
            writer.write(new SimpleGroup(schema).append("task_id", "T-NULL")
                    .append("task_name", "未分类任务").append("priority", 3));
            writer.write(new SimpleGroup(schema).append("task_id", "T-A")
                    .append("task_name", "华东分类任务").append("region", "A").append("priority", 1));
        }
        byte[] body = bytes.toByteArray();
        if (body.length < 8 || body[0] != 'P' || body[1] != 'A' || body[2] != 'R' || body[3] != '1')
            throw new IllegalStateException("未生成有效Parquet文件");
        String prefix = "acceptance/" + UUID.randomUUID() + "/";
        String resource = prefix + "tasks.parquet";
        try (MinioClient client = MinioClient.builder()
                .endpoint(required("ONTO_STORAGE_ENDPOINT"))
                .credentials(required("ONTO_STORAGE_ACCESS_KEY"), required("ONTO_STORAGE_SECRET_KEY"))
                .build()) {
            client.putObject(PutObjectArgs.builder().bucket(required("ONTO_STORAGE_BUCKET"))
                    .object(resource).stream(new ByteArrayInputStream(body), (long) body.length, -1L).build());
        }
        var report = Map.of(
                "prefix", prefix, "resource", resource, "size", body.length,
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
                "rows", 3, "purpose", "本工程P3真实MinIO Parquet验收样例");
        Files.writeString(Path.of("docs/evidence/continuation/p3-lake-fixture.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println("真实Parquet样例已上传；安全定位见p3-lake-fixture.json。");
    }

    /** 缺少独立环境配置时直接失败，不猜测或复用机器上的其他服务。 */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量：" + name);
        return value;
    }
}
