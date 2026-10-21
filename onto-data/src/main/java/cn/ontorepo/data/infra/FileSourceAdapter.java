package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;

/** 小文件受批准真实目录约束，过滤先于结果上限；Excel 只读取缓存值，不执行公式或宏。 */
public final class FileSourceAdapter extends AdapterSupport {
  record FileRows(List<Map<String, Object>> rows, int firstLine) {}

  @Override
  public Set<String> capabilities() {
    return Set.of(
        "CSV",
        "JSON_ARRAY",
        "XLSX",
        "LOCAL_FILTER_BEFORE_LIMIT",
        "LINE_EVIDENCE",
        "MAX_16_MIB",
        "MAX_100000_ROWS");
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    if (!Files.isDirectory(Path.of(text(s, "root")).toRealPath()))
      throw QueryPlanner.problem("FILE_ROOT_UNAVAILABLE");
  }

  @Override
  void authenticate(SourceSpec s) throws Exception {
    if (!Files.isReadable(Path.of(text(s, "root"))))
      throw QueryPlanner.problem("FILE_ACCESS_DENIED");
  }

  Path file(SourceSpec s, String r) throws Exception {
    exact(s, r);
    Path approved = Path.of(text(s, "root")).toRealPath(), rel = Path.of(r);
    if (rel.isAbsolute() || rel.getNameCount() == 0) throw QueryPlanner.problem("FILE_PATH_ESCAPE");
    for (Path part : rel)
      if (part.toString().equals("..")) throw QueryPlanner.problem("FILE_PATH_ESCAPE");
    Path resolved = approved.resolve(rel).toRealPath();
    if (!resolved.startsWith(approved)
        || !Files.isRegularFile(resolved)
        || Files.size(resolved) > MAX_BYTES)
      throw QueryPlanner.problem("FILE_PATH_OR_SIZE_REJECTED");
    return resolved;
  }

  FileRows read(SourceSpec s, String r) throws Exception {
    Path path = file(s, r);
    byte[] bytes;
    // 摘要校验和后续解析共用这批有界字节；路径预检之后的替换不能绕过冻结摘要。
    try (InputStream input = Files.newInputStream(path)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) throw QueryPlanner.problem("FILE_PATH_OR_SIZE_REJECTED");
    if (s.connection().has("sha256")) {
      var expected = s.connection().get("sha256");
      String actual =
          HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!expected.isTextual() || !actual.equals(expected.textValue()))
        throw QueryPlanner.problem("FILE_INTEGRITY_FAILED");
    }
    String dialect = text(s, "dialect");
    List<Map<String, Object>> rows = new ArrayList<>();
    int first = 1;
    switch (dialect) {
      case "json" -> {
        var data = JSON.readTree(bytes);
        if (!data.isArray()) throw QueryPlanner.problem("INVALID_FILE_FORMAT");
        for (var item : data) {
          if (!item.isObject()) throw QueryPlanner.problem("INVALID_FILE_FORMAT");
          rows.add(
              JSON.convertValue(
                  item,
                  new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
          if (rows.size() > MAX_SCAN) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
        }
      }
      case "csv" -> {
        first = 2;
        try (Reader reader =
                new InputStreamReader(
                    new ByteArrayInputStream(bytes),
                    java.nio.charset.StandardCharsets.UTF_8.newDecoder());
            CSVParser parser =
                CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setAllowMissingColumnNames(false)
                    .get()
                    .parse(reader)) {
          if (new HashSet<>(parser.getHeaderNames()).size() != parser.getHeaderNames().size())
            throw QueryPlanner.problem("DUPLICATE_COLUMN");
          for (CSVRecord record : parser) {
            Map<String, Object> row = new LinkedHashMap<>();
            record.toMap().forEach(row::put);
            rows.add(row);
            if (rows.size() > MAX_SCAN) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
          }
        }
      }
      case "excel" -> {
        first = 2;
        if (!r.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
          throw QueryPlanner.problem("EXCEL_FORMAT_UNSUPPORTED");
        try (InputStream in = new ByteArrayInputStream(bytes);
            Workbook workbook = WorkbookFactory.create(in)) {
          Sheet sheet = workbook.getSheetAt(0);
          Row header = sheet.getRow(0);
          if (header == null) throw QueryPlanner.problem("INVALID_FILE_FORMAT");
          Map<String, Integer> fieldIndices = new LinkedHashMap<>();
          for (Cell cell : header) {
            if (cell.getCellType() != CellType.STRING)
              throw QueryPlanner.problem("INVALID_FILE_FORMAT");
            String name = cell.getStringCellValue();
            QueryPlanner.identifier(name);
            if (fieldIndices.putIfAbsent(name, cell.getColumnIndex()) != null)
              throw QueryPlanner.problem("DUPLICATE_COLUMN");
          }
          if (sheet.getLastRowNum() > MAX_SCAN) throw QueryPlanner.problem("SCAN_LIMIT_EXCEEDED");
          for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            Row excel = sheet.getRow(i);
            // POI迭代器跳过空缺表头；字段绑定必须保留物理列号，不能紧缩列下标。
            for (var field : fieldIndices.entrySet())
              row.put(field.getKey(), excel == null ? null : cell(excel.getCell(field.getValue())));
            rows.add(row);
          }
        }
      }
      default -> throw QueryPlanner.problem("UNSUPPORTED_DIALECT");
    }
    return new FileRows(rows, first);
  }

  private Object cell(Cell c) {
    if (c == null) return null;
    CellType type = c.getCellType();
    if (type == CellType.FORMULA) throw QueryPlanner.problem("EXCEL_FORMULA_REJECTED");
    return switch (type) {
      case STRING -> c.getStringCellValue();
      case NUMERIC -> c.getNumericCellValue();
      case BOOLEAN -> c.getBooleanCellValue();
      case BLANK -> _null();
      default -> throw QueryPlanner.problem("EXCEL_CELL_UNSUPPORTED");
    };
  }

  private Object _null() {
    return null;
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) throws Exception {
    List<SourceMetadata.Resource> out = new ArrayList<>();
    for (String r : s.accessScope()) out.add(inferred(r, read(s, r).rows()));
    return new SourceMetadata(out);
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) throws Exception {
    flat(q);
    boolean transformed = QueryPlanner.transformed(q);
    long started = System.nanoTime();
    FileRows data = read(s, q.resource());
    var meta = inferred(q.resource(), data.rows());
    fields(
        q,
        meta.fields().stream()
            .map(SourceMetadata.Field::name)
            .collect(java.util.stream.Collectors.toSet()));
    List<Map<String, Object>> out = new ArrayList<>();
    List<QueryResult.SourceRecord> evidence = new ArrayList<>();
    long line = data.firstLine();
    for (var row : data.rows()) {
      deadline(started, q);
      if (matches(q, row) && out.size() < q.limit()) {
        out.add(project(q, row));
        evidence.add(new QueryResult.SourceRecord(s.id(), q.resource(), Map.of("line", line)));
        // 转换查询必须扫描预算内全部授权行，LIMIT仅限制输出，不能隐藏后续坏值。
        if (out.size() >= q.limit() && !transformed) break;
      }
      line++;
    }
    return new QueryResult(
        meta.fields().stream().filter(f -> q.projection().contains(f.name())).toList(),
        out,
        evidence);
  }
}
