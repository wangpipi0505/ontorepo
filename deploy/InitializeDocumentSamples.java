import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 生成供公开上传接口及人工浏览使用的真实二进制样例，并通过同格式读取器回读核对。
 * 样例只有合成的任务数据；PDF采用内置拉丁字体，使任意机器均能复现，不依赖系统字体。
 */
class InitializeDocumentSamples {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("docs/fixtures/p3");
        Path evidence = Path.of("docs/evidence/continuation");
        Files.createDirectories(fixtures);
        Files.createDirectories(evidence);
        Path xlsx = fixtures.resolve("tasks.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("任务");
            String[] headers = {"task_id", "task_name", "algo_code", "region", "priority"};
            var heading = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) heading.createCell(c).setCellValue(headers[c]);
            // 授权A行置于最后，验证执行先过滤权限，再应用maxRows=1。
            Object[][] values = {
                {"T-B", "华北检测任务", "ALG-B", "B", 2},
                {"T-NULL", "未分类任务", "ALG-A", null, 3},
                {"T-A", "华东分类任务", "ALG-A", "A", 1}
            };
            for (int r = 0; r < values.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < headers.length; c++) {
                    if (values[r][c] instanceof Number value) row.createCell(c).setCellValue(value.doubleValue());
                    else if (values[r][c] != null) row.createCell(c).setCellValue(values[r][c].toString());
                }
            }
            for (int c = 0; c < headers.length; c++) sheet.setColumnWidth(c, 22 * 256);
            sheet.createFreezePane(0, 1);
            try (var output = Files.newOutputStream(xlsx)) { workbook.write(output); }
        }
        try (var input = Files.newInputStream(xlsx); var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            require(sheet.getLastRowNum() == 3, "XLSX行数不符");
            require(sheet.getRow(2).getCell(3) == null, "XLSX未分类区域必须为空");
            require(sheet.getRow(3).getCell(0).getStringCellValue().equals("T-A"), "XLSX任务行回读不符");
        }
        Path docx = fixtures.resolve("domain.docx");
        String[] paragraphs = {
            "算法任务领域说明",
            "任务使用算法。算法用于分类。任务属于流程。",
            "任务编号用于标识任务。标准码用于标识算法。",
            "区域是数据访问标签。未分类数据默认拒绝访问。",
            "上述内容仅生成候选，必须由编排人员确认。"
        };
        try (var document = new XWPFDocument()) {
            for (String text : paragraphs) document.createParagraph().createRun().setText(text);
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }
        try (var input = Files.newInputStream(docx); var document = new XWPFDocument(input)) {
            require(document.getParagraphs().get(1).getText().equals(paragraphs[1]), "DOCX中文段落回读不符");
        }
        Path pdf = fixtures.resolve("domain.pdf");
        String[] lines = {
            "Task and Algorithm Domain",
            "Task uses Algorithm. Algorithm produces Result.",
            "Task has task_id, region and priority.",
            "Algorithm has std_code and algorithm_name.",
            "Unclassified records are denied by default.",
            "Extracted concepts require manual confirmation."
        };
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(25);
                content.newLineAtOffset(48, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(pdf.toFile());
        }
        try (var document = Loader.loadPDF(pdf.toFile())) {
            require(document.getNumberOfPages() == 1, "PDF页数不符");
            String text = new PDFTextStripper().getText(document);
            for (String line : lines) require(text.contains(line), "PDF文本回读不符");
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 100), "png",
                    evidence.resolve("p3-pdf-fixture-preview.png").toFile());
        }
        var report = new LinkedHashMap<String, Object>();
        for (Path file : new Path[] {xlsx, docx, pdf}) {
            byte[] bytes = Files.readAllBytes(file);
            report.put(file.getFileName().toString(), java.util.Map.of(
                    "bytes", bytes.length,
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "roundTrip", "PASSED"));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(evidence.resolve("p3-document-fixtures.json").toFile(), report);
        System.out.println("XLSX/DOCX/PDF真实样例已生成并回读核对；接口验收另行记录。");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
