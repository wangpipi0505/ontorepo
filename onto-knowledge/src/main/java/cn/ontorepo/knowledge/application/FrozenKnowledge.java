package cn.ontorepo.knowledge.application;

import cn.ontorepo.common.domain.Problem;

import jakarta.annotation.PreDestroy;

import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** 摘要缓存不是权威数据；索引随时可由持久候选重建，关闭/淘汰立即释放Lucene资源。 */
@Component
public class FrozenKnowledge {
    private record Index(
            Directory directory, DirectoryReader reader, IndexSearcher searcher, long bytes)
            implements AutoCloseable {
        public void close() throws IOException {
            reader.close();
            directory.close();
        }
    }

    // ponytail: 单进程16个候选的有界LRU；归档索引与跨进程共享在P4物理制品阶段接入。
    private final LinkedHashMap<String, Index> cache = new LinkedHashMap<>(16, .75f, true);
    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
    private long cachedBytes;

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Map<String, Object> p, String key) {
        return (List<Map<String, Object>>) p.getOrDefault(key, List.of());
    }

    public Map<String, Object> context(Map<String, Object> p) {
        Map<Object, List<Map<String, Object>>> propertiesByClass = new HashMap<>();
        for (var property : rows(p, "properties"))
            propertiesByClass
                    .computeIfAbsent(property.get("domainClassId"), key -> new ArrayList<>())
                    .add(property);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (var concept : rows(p, "classes")) {
            var card = new LinkedHashMap<>(concept);
            card.put("properties", propertiesByClass.getOrDefault(concept.get("id"), List.of()));
            cards.add(card);
        }
        List<Map<String, Object>> terms =
                rows(p, "knowledge").stream()
                        .map(
                                e -> {
                                    var v = new LinkedHashMap<>(e);
                                    v.put("entryId", e.get("id"));
                                    return (Map<String, Object>) v;
                                })
                        .toList();
        List<Map<String, Object>> examples = new ArrayList<>();
        // 已知问答来自冻结用例的确定性断言，明确标记为期望答案，不伪造执行结果。
        for (var test : rows(p, "testCases")) {
            var qa = new LinkedHashMap<String, Object>();
            qa.put("operationId", test.get("operationId"));
            qa.put("question", test.get("question"));
            qa.put("input", test.get("input"));
            qa.put("answer", test.get("expected"));
            qa.put("answerKind", "EXPECTED_ASSERTION");
            qa.put("sourceRef", "test-case:" + test.get("id"));
            examples.add(qa);
        }
        for (var op : rows(p, "operations"))
            if (op.get("assistantTool") instanceof Map<?, ?> tool) {
                Object value = tool.get("param_examples");
                if (value instanceof List<?> samples)
                    for (int index = 0; index < samples.size(); index++) {
                        var qa = new LinkedHashMap<String, Object>();
                        qa.put("operationId", op.get("id"));
                        qa.put("question", Objects.toString(tool.get("description"), ""));
                        qa.put("input", samples.get(index));
                        qa.put("answerSchema", op.get("outputSchema"));
                        qa.put("answerKind", "OUTPUT_SCHEMA");
                        qa.put(
                                "sourceRef",
                                "operation:" + op.get("id") + ":param-example:" + index);
                        examples.add(qa);
                    }
            }
        return Map.of(
                "conceptCards",
                cards,
                "termDictionary",
                terms,
                "relationSummaries",
                rows(p, "relations"),
                "exampleQa",
                examples);
    }

    /** 真实Lucene分词与BM25检索；每个别名独立文档，matchedAlias可审计且稳定关联entryId。 */
    public synchronized List<Map<String, Object>> search(
            String sha, Map<String, Object> p, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 1000 || limit < 1 || limit > 50)
            throw Problem.bad("搜索词或条数上限无效");
        try {
            Index idx = cache.get(sha);
            if (idx == null) {
                idx = build(p);
                cache.put(sha, idx);
                cachedBytes += idx.bytes;
                while (cache.size() > 16 || cachedBytes > 64L * 1024 * 1024) {
                    var first = cache.entrySet().iterator();
                    var old = first.next();
                    old.getValue().close();
                    cachedBytes -= old.getValue().bytes;
                    first.remove();
                }
            }
            Query q =
                    new QueryBuilder(analyzer)
                            .createBooleanQuery("text", query, BooleanClause.Occur.SHOULD);
            if (q == null) return List.of();
            var top = idx.searcher.search(q, Math.max(limit * 101, limit));
            Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
            for (var score : top.scoreDocs) {
                var d = idx.searcher.storedFields().document(score.doc);
                String id = d.get("entryId");
                if (hits.containsKey(id)) continue;
                hits.put(
                        id,
                        Map.of(
                                "entryId",
                                id,
                                "kind",
                                d.get("kind"),
                                "term",
                                d.get("term"),
                                "definition",
                                d.get("definition"),
                                "score",
                                score.score,
                                "matchedAlias",
                                d.get("alias"),
                                "sourceRef",
                                d.get("sourceRef")));
                if (hits.size() == limit) break;
            }
            return List.copyOf(hits.values());
        } catch (IOException e) {
            throw new Problem(500, "KNOWLEDGE_INDEX_FAILED", "知识索引暂时不可用，可从冻结候选重建");
        }
    }

    private Index build(Map<String, Object> p) throws IOException {
        // 按候选整包拒绝超限，不静默截断知识；预估UTF-8及重复别名文档开销后再分配索引。
        var entries = rows(p, "knowledge");
        long projected = 0;
        int documents = 0;
        if (entries.size() > 10000)
            throw new Problem(409, "KNOWLEDGE_INDEX_LIMIT", "单候选知识超过10000条编译上限");
        for (var e : entries) {
            var aliases = new ArrayList<Object>();
            aliases.add(e.get("term"));
            aliases.addAll((List<?>) e.getOrDefault("aliases", List.of()));
            for (Object alias : aliases) {
                documents++;
                projected +=
                        3L
                                * (2 * Objects.toString(e.get("definition"), "").length()
                                        + 3 * Objects.toString(e.get("term"), "").length()
                                        + 3 * Objects.toString(alias, "").length()
                                        + Objects.toString(e.get("sourceRef"), "").length()
                                        + 100);
                if (documents > 50000 || projected > 8L * 1024 * 1024)
                    throw new Problem(409, "KNOWLEDGE_INDEX_LIMIT", "单候选知识索引超过8MiB或50000文档编译上限");
            }
        }
        Directory dir = new ByteBuffersDirectory();
        try {
            try (var writer =
                    new IndexWriter(dir, new IndexWriterConfig(analyzer).setRAMBufferSizeMB(4))) {
                for (var e : rows(p, "knowledge")) {
                    String relationText =
                            Objects.toString(e.get("subjectRef"), "")
                                    + " "
                                    + Objects.toString(e.get("predicate"), "")
                                    + " "
                                    + Objects.toString(e.get("objectRef"), "");
                    List<String> aliases = new ArrayList<>();
                    aliases.add(e.get("term").toString());
                    for (Object alias : (List<?>) e.getOrDefault("aliases", List.of()))
                        aliases.add(alias.toString());
                    for (String alias : aliases) {
                        var d = new Document();
                        for (String key :
                                List.of(
                                        "term",
                                        "definition",
                                        "sourceRef",
                                        "kind",
                                        "subjectRef",
                                        "predicate",
                                        "objectRef"))
                            d.add(new StoredField(key, Objects.toString(e.get(key), "")));
                        d.add(new StoredField("entryId", e.get("id").toString()));
                        d.add(new StoredField("alias", alias));
                        d.add(
                                new TextField(
                                        "text",
                                        alias
                                                + " "
                                                + e.get("term")
                                                + " "
                                                + Objects.toString(e.get("definition"), "")
                                                + " "
                                                + relationText,
                                        Field.Store.NO));
                        writer.addDocument(d);
                    }
                }
            }
            var reader = DirectoryReader.open(dir);
            long bytes = 0;
            for (String file : dir.listAll()) bytes += dir.fileLength(file);
            return new Index(dir, reader, new IndexSearcher(reader), bytes);
        } catch (Exception e) {
            dir.close();
            throw e;
        }
    }

    /** 归档真实已提交Lucene段文件，复用候选检索的同一文档编译，不以知识JSON替代物理索引。 */
    public synchronized byte[] archive(Map<String, Object> payload) {
        try (var index = build(payload);
                var bytes = new java.io.ByteArrayOutputStream()) {
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                var names = index.directory.listAll();
                java.util.Arrays.sort(names);
                long total = 0;
                for (String name : names) {
                    long size = index.directory.fileLength(name);
                    total += size;
                    if (total > 16L * 1024 * 1024)
                        throw new Problem(409, "KNOWLEDGE_INDEX_LIMIT", "物理知识索引超过16MiB归档上限");
                    var entry = new java.util.zip.ZipEntry(name);
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    try (var input = index.directory.openInput(name, IOContext.READONCE)) {
                        byte[] buffer = new byte[8192];
                        long remaining = size;
                        while (remaining > 0) {
                            int count = (int) Math.min(buffer.length, remaining);
                            input.readBytes(buffer, 0, count);
                            zip.write(buffer, 0, count);
                            remaining -= count;
                        }
                    }
                    zip.closeEntry();
                }
            }
            byte[] archived = bytes.toByteArray();
            verifyArchive(archived);
            return archived;
        } catch (IOException e) {
            throw new Problem(409, "KNOWLEDGE_INDEX_FAILED", "知识物理索引编译或复核失败");
        }
    }

    /** 解包到有界Lucene目录并真正打开reader，确保上传前归档能被索引引擎读取。 */
    public void verifyArchive(byte[] archived) {
        try (var directory = new ByteBuffersDirectory();
                var zip =
                        new java.util.zip.ZipInputStream(
                                new java.io.ByteArrayInputStream(archived))) {
            java.util.Set<String> names = new java.util.HashSet<>();
            long total = 0;
            int files = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++files > 1000
                        || !entry.getName().matches("[A-Za-z0-9_.-]+")
                        || !names.add(entry.getName()))
                    throw new Problem(409, "KNOWLEDGE_INDEX_FAILED", "索引归档文件目录非法");
                try (var output = directory.createOutput(entry.getName(), IOContext.DEFAULT)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > 16L * 1024 * 1024)
                            throw new Problem(409, "KNOWLEDGE_INDEX_LIMIT", "索引解包超限");
                        output.writeBytes(buffer, 0, count);
                    }
                }
            }
            try (var reader = DirectoryReader.open(directory)) {
                reader.numDocs();
            }
        } catch (IOException e) {
            throw new Problem(409, "KNOWLEDGE_INDEX_FAILED", "归档中缺少有效Lucene物理索引");
        }
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        for (var idx : cache.values()) idx.close();
        cache.clear();
        cachedBytes = 0;
        analyzer.close();
    }
}
