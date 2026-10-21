package cn.ontorepo.ingest.domain;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.mapping.application.ManagedFileService;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性规则抽取：分值是规则先验，不是统计置信概率；所有结果均须人工确认。 */
public final class ExtractionEngine {
    private static final List<String> DEFAULT_TERMS =
            List.of("任务", "算法", "模型", "数据集", "流程", "参数", "结果", "设备", "资源", "用户", "区域", "标准码");
    private final List<String> terms;

    public ExtractionEngine() {
        this(DEFAULT_TERMS);
    }

    public ExtractionEngine(List<String> terms) {
        this.terms = List.copyOf(terms);
    }

    /** 逗号分隔的服务端领域词表；空配置沿用默认词表，预算及通用句式抽取不变。 */
    public static List<String> vocabulary(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT_TERMS;
        if (configured.length() > 8192) throw new IllegalArgumentException("抽取词表超过8192字符");
        var terms = new java.util.LinkedHashSet<String>();
        for (String raw : configured.split("[,，]", -1)) {
            String term = raw.strip();
            if (term.isEmpty()
                    || term.length() > 64
                    || term.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("抽取词条须为1至64字符且不包含控制字符");
            terms.add(term);
        }
        if (terms.size() > 256) throw new IllegalArgumentException("抽取词表超过256项");
        return List.copyOf(terms);
    }

    public record Suggestion(
            String id,
            String kind,
            Map<String, Object> payload,
            double confidence,
            Map<String, Object> location) {}

    private final List<Suggestion> result = new ArrayList<>();
    private final long deadline = System.nanoTime() + 5_000_000_000L;

    private void budget() {
        if (result.size() > 2000 || System.nanoTime() > deadline)
            throw new Problem(400, "EXTRACTION_BUDGET_EXCEEDED", "抽取超过5秒或2000候选预算");
    }

    private String add(
            String kind, Map<String, Object> payload, double score, Map<String, Object> location) {
        budget();
        String id = UUID.randomUUID().toString();
        Map<String, Object> evidence = new LinkedHashMap<>(location);
        // confidence 保留旧接口字段名，但同步携带含义，避免把固定排序分值解释成准确率。
        evidence.put("confidenceKind", "RULE_PRIOR");
        evidence.put("confidenceBasis", kind + "_EXTRACTION_RULE");
        evidence.put("confidenceMeaning", "规则先验分值，仅供候选排序；未经样本校准，不代表准确率");
        result.add(new Suggestion(id, kind, payload, score, evidence));
        return id;
    }

    private String cl(String name, Map<String, Object> location, String parent) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "规则抽取，待人工核对");
        p.put("parentId", null);
        if (parent != null) {
            p.remove("parentId");
            p.put("parentSuggestionId", parent);
        }
        return add("CLASS", p, .80, location);
    }

    private void prop(
            String name,
            String type,
            boolean required,
            String domain,
            Map<String, Object> location) {
        add(
                "PROPERTY",
                Map.of(
                        "name",
                        name,
                        "description",
                        "根据源字段推断，待人工核对",
                        "rangeType",
                        type,
                        "required",
                        required,
                        "domainSuggestionId",
                        domain),
                .85,
                location);
    }

    private void relation(
            String name, String from, String to, String cardinality, Map<String, Object> location) {
        add(
                "RELATION",
                Map.of(
                        "name",
                        name,
                        "fromSuggestionId",
                        from,
                        "toSuggestionId",
                        to,
                        "cardinality",
                        cardinality,
                        "transitive",
                        false,
                        "symmetric",
                        false),
                .75,
                location);
    }

    /** 表来源只读已授权元数据；不连接源取实例样例，外键证据保留原字段与目标资源。 */
    public List<Suggestion> table(
            String sourceName, List<Map<String, Object>> resources, String selected) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (var r : resources)
            if (selected == null || selected.equals(r.get("name")))
                ids.put(
                        r.get("name").toString(),
                        cl(
                                shortName(r.get("name").toString()),
                                Map.of("sourceName", sourceName, "resource", r.get("name")),
                                null));
        if (ids.isEmpty()) throw Problem.bad("所选表不在快照");
        for (var r : resources)
            if (ids.containsKey(r.get("name"))) {
                for (var f : rows(r.get("fields")))
                    prop(
                            f.get("name").toString(),
                            type(f.get("type").toString()),
                            Boolean.FALSE.equals(f.get("nullable")),
                            ids.get(r.get("name")),
                            Map.of(
                                    "sourceName",
                                    sourceName,
                                    "resource",
                                    r.get("name"),
                                    "field",
                                    f.get("name")));
                for (var fk : rows(r.get("relations"))) {
                    String target = fk.get("targetResource").toString();
                    String targetId = ids.get(target);
                    if (targetId == null) {
                        targetId =
                                cl(
                                        shortName(target),
                                        Map.of(
                                                "sourceName",
                                                sourceName,
                                                "resource",
                                                target,
                                                "foreignKey",
                                                fk),
                                        null);
                        ids.put(target, targetId);
                    }
                    relation(
                            shortName(r.get("name").toString()) + "引用" + shortName(target),
                            ids.get(r.get("name")),
                            targetId,
                            "MANY_TO_ONE",
                            Map.of(
                                    "sourceName",
                                    sourceName,
                                    "resource",
                                    r.get("name"),
                                    "field",
                                    fk.get("fromColumn"),
                                    "foreignKey",
                                    fk));
                }
            }
        return result.stream()
                .map(
                        suggestion -> {
                            Map<String, Object> location =
                                    new LinkedHashMap<>(suggestion.location());
                            location.put("evidenceScope", "METADATA_ONLY");
                            location.put("samplesIncluded", false);
                            location.put("sampleOmissionReason", "表抽取只使用已授权元数据；尚未建立实例样例授权及脱敏流程");
                            return new Suggestion(
                                    suggestion.id(),
                                    suggestion.kind(),
                                    suggestion.payload(),
                                    suggestion.confidence(),
                                    location);
                        })
                .toList();
    }

    private static String shortName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** 文件来源按真实格式读取结构；受管上传已施加8MiB/ZIP预算，候选再受5秒/2000项限制。 */
    public List<Suggestion> file(String name, String ext, byte[] bytes) {
        try {
            if (ext.equals("json")) {
                JsonNode root = JSON.readTree(bytes);
                JsonNode sample = root.isArray() && !root.isEmpty() ? root.get(0) : root;
                if (!sample.isObject()) throw Problem.bad("JSON抽取需要对象或非空对象数组");
                jsonObject(name, name.substring(0, name.lastIndexOf('.')), sample, "$", null, 0);
            } else if (ext.equals("csv")) {
                try (var csv =
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                                .get()
                                .parse(new StringReader(ManagedFileService.text(bytes)))) {
                    var iterator = csv.iterator();
                    CSVRecord sample = iterator.hasNext() ? iterator.next() : null;
                    String domain =
                            cl(base(name), Map.of("sourceName", name, "sheet", "CSV"), null);
                    for (String header : csv.getHeaderNames()) {
                        budget();
                        if (header.isBlank()) throw Problem.bad("CSV表头为空");
                        String value = sample == null ? "" : sample.get(header);
                        prop(
                                header,
                                scalar(value),
                                false,
                                domain,
                                Map.of(
                                        "sourceName",
                                        name,
                                        "sheet",
                                        "CSV",
                                        "column",
                                        header,
                                        "sample",
                                        excerpt(value)));
                    }
                }
            } else if (ext.equals("xlsx")) {
                ManagedFileService.zip(bytes);
                try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    if (wb.getNumberOfSheets() > 32) throw Problem.bad("工作表超过32张");
                    for (var sheet : wb) {
                        budget();
                        var header = sheet.getRow(sheet.getFirstRowNum());
                        if (header == null) continue;
                        String domain =
                                cl(
                                        sheet.getSheetName(),
                                        Map.of("sourceName", name, "sheet", sheet.getSheetName()),
                                        null);
                        var sample = sheet.getRow(header.getRowNum() + 1);
                        int count = 0;
                        for (var cell : header) {
                            if (++count > 256) throw Problem.bad("工作表列超过256列");
                            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                                throw Problem.bad("公式单元格不能参与抽取");
                            String field = cell.toString().strip();
                            if (field.isEmpty()) continue;
                            String value = "";
                            if (sample != null && sample.getCell(cell.getColumnIndex()) != null) {
                                var v = sample.getCell(cell.getColumnIndex());
                                if (v.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                                    throw Problem.bad("公式单元格不能参与抽取");
                                value = v.toString();
                            }
                            prop(
                                    field,
                                    scalar(value),
                                    false,
                                    domain,
                                    Map.of(
                                            "sourceName",
                                            name,
                                            "sheet",
                                            sheet.getSheetName(),
                                            "column",
                                            field,
                                            "sample",
                                            excerpt(value)));
                        }
                    }
                }
            } else throw Problem.bad("FILE抽取仅支持CSV/JSON/XLSX");
            return List.copyOf(result);
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(400, "FILE_PARSE_FAILED", "结构化文件解析失败");
        }
    }

    private String jsonObject(
            String source, String name, JsonNode object, String path, String parent, int depth) {
        budget();
        if (depth > 8) throw Problem.bad("JSON嵌套超过8层");
        String domain = cl(name, Map.of("sourceName", source, "jsonPath", path), parent);
        var fields = object.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String loc = path + "." + field.getKey();
            JsonNode value = field.getValue();
            boolean collection = value.isArray();
            if (collection) {
                if (value.isEmpty()) continue;
                value = value.get(0);
            }
            if (value.isObject()) {
                String child = jsonObject(source, field.getKey(), value, loc, domain, depth + 1);
                relation(
                        name + "包含" + field.getKey(),
                        domain,
                        child,
                        collection ? "ONE_TO_MANY" : "ONE_TO_ONE",
                        Map.of("sourceName", source, "jsonPath", loc));
            } else
                prop(
                        field.getKey(),
                        value.isIntegralNumber()
                                ? "INTEGER"
                                : value.isNumber()
                                        ? "DECIMAL"
                                        : value.isBoolean() ? "BOOLEAN" : "STRING",
                        false,
                        domain,
                        Map.of(
                                "sourceName",
                                source,
                                "jsonPath",
                                loc,
                                "sample",
                                excerpt(value.toString())));
        }
        return domain;
    }

    /** PDF使用真实页码，TXT/Word只使用真实段落；不为没有分页信息的格式生成页码。 */
    public List<Suggestion> document(String name, String ext, byte[] bytes) {
        try {
            if (ext.equals("txt")) {
                String text = ManagedFileService.text(bytes);
                int paragraph = 0;
                for (String line : text.split("\\R\\s*\\R|\\R")) {
                    if (line.isBlank()) continue;
                    documentText(
                            name,
                            line,
                            Map.of(
                                    "sourceName",
                                    name,
                                    "paragraph",
                                    ++paragraph,
                                    "excerpt",
                                    excerpt(line)));
                }
            } else if (ext.equals("pdf")) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.isEncrypted() || pdf.getNumberOfPages() > 100)
                        throw Problem.bad("PDF加密或页数超过100页");
                    PDFTextStripper stripper = new PDFTextStripper();
                    for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                        budget();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String text = stripper.getText(pdf);
                        documentText(
                                name,
                                text,
                                Map.of("sourceName", name, "page", page, "excerpt", excerpt(text)));
                    }
                }
            } else if (ext.equals("docx")) {
                ManagedFileService.zip(bytes);
                try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    int paragraph = 0;
                    for (var p : doc.getParagraphs()) {
                        budget();
                        if (!p.getText().isBlank())
                            documentText(
                                    name,
                                    p.getText(),
                                    Map.of(
                                            "sourceName",
                                            name,
                                            "paragraph",
                                            ++paragraph,
                                            "excerpt",
                                            excerpt(p.getText())));
                    }
                    for (var table : doc.getTables())
                        for (var row : table.getRows())
                            for (var cell : row.getTableCells())
                                for (var p : cell.getParagraphs()) {
                                    budget();
                                    if (!p.getText().isBlank())
                                        documentText(
                                                name,
                                                p.getText(),
                                                Map.of(
                                                        "sourceName",
                                                        name,
                                                        "paragraph",
                                                        ++paragraph,
                                                        "excerpt",
                                                        excerpt(p.getText())));
                                }
                }
            } else throw Problem.bad("DOCUMENT抽取仅支持TXT/PDF/DOCX");
            if (result.isEmpty())
                throw new Problem(400, "NO_EXTRACTABLE_TEXT", "文档没有可定位文本；扫描PDF需要OCR后再导入");
            return List.copyOf(result);
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(400, "DOCUMENT_PARSE_FAILED", "文档结构解析失败");
        }
    }

    private final Map<String, String> concepts = new LinkedHashMap<>();

    private String concept(String name, Map<String, Object> location) {
        return concepts.computeIfAbsent(name, x -> cl(x, location, null));
    }

    private void documentText(String source, String text, Map<String, Object> location) {
        budget();
        if (text.length() > 200_000) throw Problem.bad("单页文本超过解析预算");
        Pattern sentence =
                Pattern.compile(
                        "([\\p{IsHan}A-Za-z0-9_]{1,20})\\s*(是|用于|使用|包含|属于)\\s*([\\p{IsHan}A-Za-z0-9_]{1,20})");
        Matcher m = sentence.matcher(text);
        boolean found = false;
        while (m.find()) {
            String from = m.group(1), to = m.group(3);
            String a = concept(from, location), b = concept(to, location);
            relation(from + m.group(2) + to, a, b, "MANY_TO_MANY", location);
            found = true;
        }
        for (String term : terms)
            if (text.contains(term)) {
                concept(term, location);
                found = true;
            }
        if (!found) {
            Matcher term =
                    Pattern.compile("[\\p{IsHan}]{2,8}|[A-Za-z][A-Za-z0-9_]{2,30}").matcher(text);
            int count = 0;
            while (term.find() && count++ < 8) concept(term.group(), location);
        }
    }

    public static String type(String source) {
        String t = source.toUpperCase(Locale.ROOT);
        if (t.contains("BOOL")) return "BOOLEAN";
        if (t.contains("INT") || t.equals("LONG")) return "INTEGER";
        if (t.contains("NUM")
                || t.contains("DECIMAL")
                || t.contains("DOUBLE")
                || t.contains("FLOAT")
                || t.contains("REAL")) return "DECIMAL";
        if (t.contains("DATE") && !t.contains("TIME")) return "DATE";
        return "STRING";
    }

    private static String scalar(String value) {
        if (value.matches("-?\\d+")) return "INTEGER";
        if (value.matches("-?\\d+\\.\\d+")) return "DECIMAL";
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return "BOOLEAN";
        return "STRING";
    }

    private static String base(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String excerpt(String text) {
        return text.length() > 400 ? text.substring(0, 400) : text;
    }
}
