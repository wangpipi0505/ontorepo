package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import io.minio.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.*;
import org.apache.parquet.hadoop.*;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.*;

/** 真正 Parquet：先按桶/前缀取有界对象，再做列裁剪与谓词下推；不解释 JSON 替代品。 */
public final class S3ParquetSourceAdapter extends AdapterSupport {
  private static final okhttp3.OkHttpClient HTTP =
      new okhttp3.OkHttpClient.Builder()
          .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
          .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
          .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
          .build();

  @Override
  void verifyScope(SourceSpec s, SourceMetadata m) {
    for (String prefix : s.accessScope())
      if (m.resources().stream().noneMatch(r -> r.name().startsWith(prefix)))
        throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
  }

  @Override
  public Set<String> capabilities() {
    return Set.of(
        "S3_PREFIX",
        "PARQUET_PRIMITIVE_SCHEMA",
        "PROJECTED_PARQUET_READ",
        "PREDICATE_PUSHDOWN_EQ_IN",
        "MAX_16_MIB_OBJECT",
        "MAX_1000_OBJECTS",
        "NO_JOIN");
  }

  MinioClient open(SourceSpec s) {
    return open(s, 10000);
  }

  MinioClient open(SourceSpec s, int timeoutMs) {
    return MinioClient.builder()
        .endpoint(text(s, "endpoint"))
        .credentials(text(s, "accessKey"), text(s, "secretKey"))
        .httpClient(
            HTTP.newBuilder()
                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .connectTimeout(
                    Math.min(2000, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build())
        .build();
  }

  /** 同一次查询的网络阶段共用剩余预算，不能每个请求重新获得完整timeout。 */
  int remaining(long started, QuerySpec q) {
    long left = q.timeoutMs() - (System.nanoTime() - started) / 1_000_000;
    if (left <= 0) throw QueryPlanner.problem("QUERY_TIMEOUT");
    return (int) left;
  }

  void resource(SourceSpec s, String key) {
    for (String prefix : s.accessScope())
      if (prefix.isBlank() || prefix.contains("..") || prefix.startsWith("/"))
        throw QueryPlanner.problem("INVALID_PREFIX");
    if (key == null
        || !key.endsWith(".parquet")
        || key.contains("..")
        || key.startsWith("/")
        || s.accessScope().stream().noneMatch(key::startsWith))
      throw QueryPlanner.problem("SCOPE_EXCEEDED");
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    uriSocket(text(s, "endpoint"), text(s, "endpoint").startsWith("https:") ? 443 : 80);
  }

  @Override
  void authenticate(SourceSpec s) throws Exception {
    if (!open(s).bucketExists(BucketExistsArgs.builder().bucket(text(s, "bucket")).build()))
      throw QueryPlanner.problem("BUCKET_UNAVAILABLE");
  }

  byte[] bytes(SourceSpec s, String key) throws Exception {
    return bytes(s, key, null, 0);
  }

  byte[] bytes(SourceSpec s, String key, QuerySpec query, long started) throws Exception {
    resource(s, key);
    MinioClient client = open(s, query == null ? 10000 : remaining(started, query));
    var stat =
        client.statObject(StatObjectArgs.builder().bucket(text(s, "bucket")).object(key).build());
    if (stat.size() > MAX_BYTES) throw QueryPlanner.problem("OBJECT_TOO_LARGE");
    if (query != null) client = open(s, remaining(started, query));
    try (InputStream in =
        client.getObject(GetObjectArgs.builder().bucket(text(s, "bucket")).object(key).build())) {
      byte[] bytes = in.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw QueryPlanner.problem("OBJECT_TOO_LARGE");
      return bytes;
    }
  }

  SourceMetadata.Resource schema(String r, byte[] data) throws Exception {
    try (ParquetFileReader reader = ParquetFileReader.open(new BytesFile(data))) {
      MessageType schema = reader.getFooter().getFileMetaData().getSchema();
      List<SourceMetadata.Field> fields = new ArrayList<>();
      for (Type t : schema.getFields()) {
        if (!t.isPrimitive() || t.isRepetition(Type.Repetition.REPEATED))
          throw QueryPlanner.problem("PARQUET_NESTED_UNSUPPORTED");
        var logical = t.getLogicalTypeAnnotation();
        var primitive = t.asPrimitiveType().getPrimitiveTypeName();
        if (logical != null
                && !(logical instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation)
            || primitive == PrimitiveType.PrimitiveTypeName.INT96
            || primitive == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
            || primitive == PrimitiveType.PrimitiveTypeName.BINARY
                && !(logical instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation))
          throw QueryPlanner.problem("PARQUET_TYPE_UNSUPPORTED");
        fields.add(
            new SourceMetadata.Field(
                t.getName(),
                t.asPrimitiveType().getPrimitiveTypeName().name(),
                t.isRepetition(Type.Repetition.OPTIONAL)));
      }
      return new SourceMetadata.Resource(r, fields, List.of(), List.of(), List.of());
    }
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) throws Exception {
    List<SourceMetadata.Resource> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    MinioClient client = open(s);
    for (String prefix : s.accessScope()) {
      if (prefix.isBlank() || prefix.contains("..") || prefix.startsWith("/"))
        throw QueryPlanner.problem("INVALID_PREFIX");
      boolean found = false;
      int scanned = 0;
      for (var result :
          client.listObjects(
              ListObjectsArgs.builder()
                  .bucket(text(s, "bucket"))
                  .prefix(prefix)
                  .recursive(true)
                  .build())) {
        if (++scanned > 1000) throw QueryPlanner.problem("OBJECT_SCAN_LIMIT");
        String key = result.get().objectName();
        if (!key.startsWith(prefix)) throw QueryPlanner.problem("SCOPE_EXCEEDED");
        if (!key.endsWith(".parquet")) continue;
        found = true;
        if (seen.add(key)) out.add(schema(key, bytes(s, key)));
      }
      if (!found) throw QueryPlanner.problem("SCOPE_UNAVAILABLE");
    }
    return new SourceMetadata(out);
  }

  static final class ReaderBuilder extends ParquetReader.Builder<Group> {
    ReaderBuilder(InputFile input) {
      super(input);
    }

    @Override
    protected ReadSupport<Group> getReadSupport() {
      return new GroupReadSupport();
    }
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) throws Exception {
    // 先拦截 JSON 内存节点中的 NaN/Infinity，避免通用标量检查将其变成无关协议错误。
    for (var filter : q.filters())
      for (var value : filter.values()) if (value != null && value.isNumber()) numeric(value);
    flat(q);
    resource(s, q.resource());
    long started = System.nanoTime();
    byte[] data = bytes(s, q.resource(), q, started);
    MessageType schema;
    try (ParquetFileReader r = ParquetFileReader.open(new BytesFile(data))) {
      schema = r.getFooter().getFileMetaData().getSchema();
    }
    var meta = schema(q.resource(), data);
    fields(
        q,
        meta.fields().stream()
            .map(SourceMetadata.Field::name)
            .collect(java.util.stream.Collectors.toSet()));
    Set<String> wanted = new LinkedHashSet<>(q.projection());
    wanted.addAll(q.guardBindings().values());
    q.filters().forEach(f -> wanted.add(f.column()));
    MessageType projection =
        new MessageType(schema.getName(), wanted.stream().map(schema::getType).toList());
    Configuration config = new Configuration(false);
    config.set("parquet.read.schema", projection.toString());
    FilterPredicate predicate = null;
    for (var b : q.guardBindings().entrySet()) {
      var values = q.scope().parameters().get(b.getKey());
      if (values != null) {
        if (values.isEmpty()) return new QueryResult(meta.fields(), List.of(), List.of());
        predicate = and(predicate, in(schema, b.getValue(), values));
      }
    }
    boolean transformed = QueryPlanner.transformed(q);
    for (var f : q.filters()) {
      // 码表和转换后的比较值不是源列值；保持授权下推，仅在授权后的matches执行业务过滤。
      if (transformed) continue;
      if (f.operator() != QuerySpec.Operator.EQ && f.operator() != QuerySpec.Operator.IN)
        throw QueryPlanner.problem("PARQUET_OPERATOR_UNSUPPORTED");
      if (f.values().isEmpty()) return new QueryResult(meta.fields(), List.of(), List.of());
      predicate = and(predicate, in(schema, f.column(), f.values()));
    }
    ReaderBuilder builder = new ReaderBuilder(new BytesFile(data));
    builder.withConf(config);
    if (predicate != null) builder.withFilter(FilterCompat.get(predicate));
    List<Map<String, Object>> out = new ArrayList<>();
    List<QueryResult.SourceRecord> evidence = new ArrayList<>();
    try (ParquetReader<Group> reader = builder.build()) {
      Group row;
      long ordinal = 0;
      while ((row = reader.read()) != null) {
        deadline(started, q);
        if (++ordinal > MAX_SCAN) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : wanted) {
          int index = projection.getFieldIndex(field);
          Object value = null;
          if (row.getFieldRepetitionCount(index) > 0)
            value =
                switch (projection.getType(index).asPrimitiveType().getPrimitiveTypeName()) {
                  case INT32 -> row.getInteger(index, 0);
                  case INT64 -> row.getLong(index, 0);
                  case FLOAT -> row.getFloat(index, 0);
                  case DOUBLE -> row.getDouble(index, 0);
                  case BOOLEAN -> row.getBoolean(index, 0);
                  case BINARY -> row.getBinary(index, 0).toStringUsingUTF8();
                  default -> throw QueryPlanner.problem("PARQUET_TYPE_UNSUPPORTED");
                };
          values.put(field, value);
        }
        if (matches(q, values) && out.size() < q.limit()) {
          out.add(project(q, values));
          evidence.add(
              new QueryResult.SourceRecord(
                  s.id(), q.resource(), Map.of("rowIndex", reader.getCurrentRowIndex())));
          if (out.size() >= q.limit() && !transformed) break;
        }
      }
    }
    return new QueryResult(
        meta.fields().stream().filter(f -> q.projection().contains(f.name())).toList(),
        out,
        evidence);
  }

  private FilterPredicate and(FilterPredicate a, FilterPredicate b) {
    return a == null ? b : FilterApi.and(a, b);
  }

  private FilterPredicate in(
      MessageType schema, String field, List<com.fasterxml.jackson.databind.JsonNode> values) {
    FilterPredicate result = null;
    for (var value : values) {
      FilterPredicate next =
          switch (schema.getType(field).asPrimitiveType().getPrimitiveTypeName()) {
            case INT32 -> {
              var number = integral(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
              yield FilterApi.eq(FilterApi.intColumn(field), number.intValueExact());
            }
            case INT64 -> {
              var number = integral(value, Long.MIN_VALUE, Long.MAX_VALUE);
              yield FilterApi.eq(FilterApi.longColumn(field), number.longValueExact());
            }
            case FLOAT -> {
              float number = numeric(value).floatValue();
              if (!Float.isFinite(number)) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
              yield FilterApi.eq(FilterApi.floatColumn(field), number);
            }
            case DOUBLE -> {
              double number = numeric(value).doubleValue();
              if (!Double.isFinite(number)) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
              yield FilterApi.eq(FilterApi.doubleColumn(field), number);
            }
            case BOOLEAN -> {
              if (!value.isBoolean()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
              yield FilterApi.eq(FilterApi.booleanColumn(field), value.booleanValue());
            }
            case BINARY -> {
              if (!value.isTextual()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
              yield FilterApi.eq(
                  FilterApi.binaryColumn(field), Binary.fromString(value.textValue()));
            }
            default -> throw QueryPlanner.problem("PARQUET_TYPE_UNSUPPORTED");
          };
      result = result == null ? next : FilterApi.or(result, next);
    }
    return result;
  }

  /** 谓词构建前验证原始标量，禁止 Jackson 将字符串/布尔静默强制成零。 */
  private java.math.BigDecimal numeric(com.fasterxml.jackson.databind.JsonNode value) {
    if (value == null || !value.isNumber()) throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
    try {
      return value.decimalValue();
    } catch (NumberFormatException failure) {
      throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
    }
  }

  /** 标签范围会规范成 DecimalNode；允许数值等价整数，但绝不截断小数或溢出。 */
  private java.math.BigDecimal integral(
      com.fasterxml.jackson.databind.JsonNode value, long min, long max) {
    var number = numeric(value);
    if (number.stripTrailingZeros().scale() > 0
        || number.compareTo(java.math.BigDecimal.valueOf(min)) < 0
        || number.compareTo(java.math.BigDecimal.valueOf(max)) > 0)
      throw QueryPlanner.problem("FILTER_TYPE_MISMATCH");
    return number;
  }

  /** 有界内存对象的随机读取，不落盘明文，不接受路径配置。 */
  static final class BytesFile implements InputFile {
    private final byte[] bytes;

    BytesFile(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long getLength() {
      return bytes.length;
    }

    @Override
    public SeekableInputStream newStream() {
      return new SeekableInputStream() {
        int pos;

        @Override
        public long getPos() {
          return pos;
        }

        @Override
        public void seek(long p) throws IOException {
          if (p < 0 || p > bytes.length) throw new EOFException();
          pos = (int) p;
        }

        @Override
        public int read() {
          return pos == bytes.length ? -1 : bytes[pos++] & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) {
          if (pos == bytes.length) return -1;
          int n = Math.min(len, bytes.length - pos);
          System.arraycopy(bytes, pos, b, off, n);
          pos += n;
          return n;
        }

        @Override
        public void readFully(byte[] b) throws IOException {
          readFully(b, 0, b.length);
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
          if (bytes.length - pos < len) throw new EOFException();
          System.arraycopy(bytes, pos, b, off, len);
          pos += len;
        }

        @Override
        public int read(ByteBuffer b) {
          if (pos == bytes.length) return -1;
          int n = Math.min(b.remaining(), bytes.length - pos);
          b.put(bytes, pos, n);
          pos += n;
          return n;
        }

        @Override
        public void readFully(ByteBuffer b) throws IOException {
          if (b.remaining() > bytes.length - pos) throw new EOFException();
          int n = b.remaining();
          b.put(bytes, pos, n);
          pos += n;
        }
      };
    }
  }
}
