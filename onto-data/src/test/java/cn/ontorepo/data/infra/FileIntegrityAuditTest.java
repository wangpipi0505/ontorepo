package cn.ontorepo.data.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 冻结摘要必须校验实际进入解析器的字节，而不能只依赖先前路径检查。 */
class FileIntegrityAuditTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();

  @Test
  void changedCsvJsonAndExcelBytesCannotUseEarlierApprovedDigest() throws Exception {
    for (String dialect : List.of("csv", "json", "excel")) {
      String resource = "data." + (dialect.equals("excel") ? "xlsx" : dialect);
      byte[] original = bytes(dialect, "approved");
      Files.write(root.resolve(resource), original);
      var cfg =
          json.createObjectNode()
              .put("root", root.toString())
              .put("dialect", dialect)
              .put(
                  "sha256",
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
      var source = new SourceSpec("managed", "FILE", cfg, Set.of(resource));
      var query =
          new QuerySpec(
              resource,
              List.of("id"),
              List.of(),
              List.of(),
              1,
              1000,
              Map.of(),
              LabelScope.unrestricted(),
              Map.of("region", "region"));
      var adapter = new FileSourceAdapter();
      assertEquals("approved", adapter.query(source, query).rows().getFirst().get("id"));
      Files.write(root.resolve(resource), bytes(dialect, "replaced"));
      assertEquals(
          "FILE_INTEGRITY_FAILED",
          assertThrows(DataProblem.class, () -> adapter.query(source, query), dialect).code());
      assertEquals(
          "FILE_INTEGRITY_FAILED",
          assertThrows(DataProblem.class, () -> adapter.metadata(source), dialect).code());
    }
  }

  byte[] bytes(String dialect, String id) throws Exception {
    if (dialect.equals("csv"))
      return ("id,region\n" + id + ",A\n").getBytes(StandardCharsets.UTF_8);
    if (dialect.equals("json"))
      return ("[{\"id\":\"" + id + "\",\"region\":\"A\"}]").getBytes(StandardCharsets.UTF_8);
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var output = new java.io.ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("data");
      sheet.createRow(0).createCell(0).setCellValue("id");
      sheet.getRow(0).createCell(1).setCellValue("region");
      sheet.createRow(1).createCell(0).setCellValue(id);
      sheet.getRow(1).createCell(1).setCellValue("A");
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
