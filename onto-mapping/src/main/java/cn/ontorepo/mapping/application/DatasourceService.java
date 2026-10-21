package cn.ontorepo.mapping.application;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.data.domain.AdapterRegistry;
import cn.ontorepo.data.domain.CredentialCipher;
import cn.ontorepo.data.domain.ProbeResult;
import cn.ontorepo.data.domain.SourceSpec;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 数据源管理边界：密文持久化、空间授权和不可变元数据，不把凭据可达性当数据授权。 */
@Service
public class DatasourceService {
    private final Db db;
    private final SpaceService spaces;
    private final ManagedFileService files;
    private final CredentialCipher cipher;
    private final AdapterRegistry registry = new AdapterRegistry();
    private static final String SAFE =
            "id,space_id,code,name,kind,dialect,access_scope,status,last_test_at";

    public DatasourceService(
            Db db,
            SpaceService spaces,
            ManagedFileService files,
            @Value("${ONTO_DATASOURCE_KEY:}") String key) {
        this.db = db;
        this.spaces = spaces;
        this.files = files;
        this.cipher = key.isBlank() ? null : new CredentialCipher(key);
    }

    private CredentialCipher cipher() {
        if (cipher == null) throw new Problem(503, "DATASOURCE_KEY_MISSING", "数据源加密主密钥尚未配置");
        return cipher;
    }

    /** 只返回当前空间的安全概要，响应从不包含连接配置。 */
    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT " + SAFE + " FROM datasource WHERE space_id=? ORDER BY code,id", s);
    }

    /** 供事务内引用核验使用，跨空间源统一拒绝。 */
    public Map<String, Object> owned(UUID s, Object id) {
        var rows = db.list("SELECT * FROM datasource WHERE id=? AND space_id=?", Input.uuid(id), s);
        if (rows.isEmpty()) throw new Problem(400, "CROSS_SPACE_REFERENCE", "数据源不属于当前空间");
        return rows.getFirst();
    }

    /** 只解密已验证来源的冻结配置，FILE另核验冻结文件摘要。 */
    public SourceSpec source(Map<String, Object> row) {
        try {
            var spec =
                    new SourceSpec(
                            row.get("id").toString(),
                            row.get("kind").toString(),
                            JSON.readTree(
                                    cipher().decrypt(
                                                    row.get("id").toString(),
                                                    row.get("connCfg").toString())),
                            scope(row.get("accessScope")));
            if (spec.kind().equals("FILE")) {
                var cfg = spec.connection();
                if (spec.accessScope().size() != 1 || !cfg.path("sha256").isTextual())
                    throw new Problem(409, "FILE_INTEGRITY_FAILED", "冻结文件完整性信息缺失");
                files.frozenPath(
                        java.nio.file.Path.of(cfg.path("root").asText()),
                        spec.accessScope().iterator().next(),
                        cfg.path("sha256").asText());
            }
            return spec;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Problem(409, "SOURCE_CONFIG_CORRUPT", "源配置无效");
        }
    }

    private static Set<String> scope(Object value) {
        var n = node(value);
        if (!n.isArray() || n.isEmpty() || n.size() > 1000) throw Problem.bad("数据源范围必须为非空资源数组");
        Set<String> result = new LinkedHashSet<>();
        for (var item : n) {
            if (!item.isTextual()
                    || !cn.ontorepo.contract.GuardValidator.resource(item.textValue())
                    || !result.add(item.textValue())) throw Problem.bad("数据源范围无效或重复");
        }
        return result;
    }

    @Transactional
    /** OWNER在空间锁内新增或替换加密配置，connection=null保留原密文。 */
    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                id == null ? "DATASOURCE_CREATE" : "DATASOURCE_UPDATE",
                () -> {
                    files.owner(u, s);
                    UUID target = id == null ? UUID.randomUUID() : id;
                    Map<String, Object> old = id == null ? null : owned(s, id);
                    String kind = Input.text(b, "kind", 30);
                    Set<String> access = scope(b.get("accessScope"));
                    String encrypted, dialect;
                    if (b.get("connection") == null) {
                        if (old == null) throw Problem.bad("首次创建必须提供连接配置");
                        if (!kind.equals(old.get("kind"))) throw Problem.bad("保留连接时不可修改源类型");
                        encrypted = old.get("connCfg").toString();
                        dialect = old.get("dialect").toString();
                        var prior = source(old);
                        var checked =
                                new SourceSpec(target.toString(), kind, prior.connection(), access);
                        validateFileScope(s, checked);
                        registry.adapter(checked);
                    } else {
                        var cfg = map(b.get("connection"));
                        if (cfg.containsKey("root") || cfg.containsKey("path"))
                            throw Problem.bad("不接受客户端文件目录或路径");
                        if (kind.equals("FILE")) {
                            var f = files.owned(s, cfg.get("fileId"));
                            String ext = f.get("resource").toString();
                            if (!ext.endsWith(".csv")
                                    && !ext.endsWith(".json")
                                    && !ext.endsWith(".xlsx")) throw Problem.bad("文档文件不能用作实例数据源");
                            files.path(f);
                            cfg.put("root", files.root().toString());
                            cfg.put("sha256", f.get("sha256"));
                        }
                        var spec = new SourceSpec(target.toString(), kind, node(cfg), access);
                        validateFileScope(s, spec);
                        registry.adapter(spec);
                        dialect = Objects.toString(cfg.get("dialect"), "");
                        encrypted = cipher().encrypt(target.toString(), db.json(cfg));
                    }
                    String code = Input.text(b, "code", 80);
                    if (!code.matches("[A-Za-z][A-Za-z0-9_-]*")) throw Problem.bad("源编码格式无效");
                    String name = Input.text(b, "name", 200);
                    if (old == null)
                        db.update(
                                "INSERT INTO"
                                    + " datasource(id,space_id,code,name,kind,dialect,conn_cfg,access_scope)"
                                    + " VALUES(?,?,?,?,?,?,?,?::jsonb)",
                                target,
                                s,
                                code,
                                name,
                                kind,
                                dialect,
                                encrypted,
                                db.json(access));
                    else
                        db.update(
                                "UPDATE datasource SET"
                                    + " code=?,name=?,kind=?,dialect=?,conn_cfg=?,access_scope=?::jsonb,status='UNTESTED',last_test_at=null"
                                    + " WHERE id=?",
                                code,
                                name,
                                kind,
                                dialect,
                                encrypted,
                                db.json(access),
                                target);
                });
    }

    private void validateFileScope(UUID s, SourceSpec spec) {
        if (!spec.kind().equals("FILE")) return;
        var f = files.owned(s, spec.connection().path("fileId").asText());
        if (!spec.accessScope().equals(Set.of(f.get("resource").toString())))
            throw Problem.bad("文件源范围必须精确等于受管文件resource");
        String ext = f.get("resource").toString();
        String dialect = spec.connection().path("dialect").asText();
        if (!(ext.endsWith(".csv") && dialect.equals("csv")
                || ext.endsWith(".json") && dialect.equals("json")
                || ext.endsWith(".xlsx") && dialect.equals("excel")))
            throw Problem.bad("文件方言与实际类型不符");
    }

    @Transactional
    /** 删除前阻断草稿映射引用，历史候选原始字节不修改。 */
    public Map<String, Object> delete(UUID u, UUID id, UUID s, long rev) {
        return spaces.write(
                u,
                s,
                rev,
                "DATASOURCE_DELETE",
                () -> {
                    files.owner(u, s);
                    owned(s, id);
                    var refs =
                            db.list(
                                    "SELECT id FROM data_mapping WHERE space_id=? AND"
                                            + " definition->>'datasourceId'=? ORDER BY id",
                                    s,
                                    id.toString());
                    if (!refs.isEmpty())
                        throw new Problem(
                                409,
                                "DATASOURCE_IN_USE",
                                "数据源被映射引用: "
                                        + refs.stream().map(x -> x.get("id").toString()).toList());
                    db.update("DELETE FROM meta_snapshot WHERE datasource_id=?", id);
                    db.update("DELETE FROM datasource WHERE id=?", id);
                });
    }

    @Transactional
    /** OWNER三段探测逐阶段返回事实，失败后阶段不得伪造通过。 */
    public Map<String, Object> probe(UUID u, UUID id, UUID s) {
        var locked = db.one("SELECT draft_rev FROM ontology_space WHERE id=? FOR UPDATE", s);
        spaces.lock(u, s, ((Number) locked.get("draftRev")).longValue(), true);
        files.owner(u, s);
        var spec = source(owned(s, id));
        ProbeResult probe = registry.adapter(spec).probe(spec);
        var stages =
                probe.stages().stream()
                        .map(
                                stage ->
                                        Map.of(
                                                "stage",
                                                stage.name(),
                                                "status",
                                                stage.status(),
                                                "code",
                                                Objects.toString(stage.code(), "OK"),
                                                "message",
                                                stage.status() == ProbeResult.Status.PASSED
                                                        ? "该阶段检查通过"
                                                        : stage.status()
                                                                        == ProbeResult.Status
                                                                                .NOT_RUN
                                                                ? "前置阶段未通过，未执行"
                                                                : "该阶段检查失败，请核对源配置与批准范围"))
                        .toList();
        String tested = java.time.Instant.now().toString();
        db.update(
                "UPDATE datasource SET status=?,last_test_at=?::timestamptz WHERE id=?",
                probe.stages().stream().allMatch(x -> x.status() == ProbeResult.Status.PASSED)
                        ? "READY"
                        : "FAILED",
                tested,
                id);
        spaces.audit(u, s, "DATASOURCE_PROBE", ((Number) locked.get("draftRev")).longValue());
        return Map.of("stages", stages, "testedAt", tested);
    }

    /** 读取同源不可变快照摘要，保持空间授权。 */
    public List<Map<String, Object>> snapshots(UUID u, UUID s, UUID id) {
        spaces.role(u, s);
        owned(s, id);
        return db.list(
                "SELECT id,sha256,captured_at FROM meta_snapshot WHERE datasource_id=? AND"
                        + " space_id=? ORDER BY captured_at DESC,id",
                id,
                s);
    }

    /** 读取前逐项核对空间、数据源与快照归属。 */
    public Map<String, Object> snapshot(UUID s, Object source, Object snapshot) {
        var rows =
                db.list(
                        "SELECT id,datasource_id,sha256,captured_at,resources FROM meta_snapshot"
                                + " WHERE id=? AND datasource_id=? AND space_id=?",
                        Input.uuid(snapshot),
                        Input.uuid(source),
                        s);
        if (rows.isEmpty()) throw new Problem(400, "CROSS_SPACE_REFERENCE", "元数据快照不属于该空间与数据源");
        return rows.getFirst();
    }

    /** 读取前逐项核对空间、数据源与快照归属。 */
    public Map<String, Object> snapshot(UUID u, UUID s, UUID source, UUID snapshot) {
        spaces.role(u, s);
        return snapshot(s, source, snapshot);
    }

    @Transactional
    /** 空间锁内抓取真实元数据、追加快照并与上次内容生成差异。 */
    public Map<String, Object> sync(UUID u, UUID id, UUID s, long rev) {
        Map<String, Object> result = new LinkedHashMap<>();
        var space =
                spaces.write(
                        u,
                        s,
                        rev,
                        "DATASOURCE_SYNC",
                        () -> {
                            files.owner(u, s);
                            var src = source(owned(s, id));
                            var before =
                                    db.list(
                                            "SELECT resources FROM meta_snapshot WHERE"
                                                + " datasource_id=? ORDER BY captured_at DESC,id"
                                                + " DESC LIMIT 1",
                                            id);
                            var metadata = registry.adapter(src).metadata(src);
                            for (var r : metadata.resources())
                                if (!covered(src.kind(), src.accessScope(), r.name()))
                                    throw new Problem(403, "SCOPE_EXCEEDED", "元数据资源超出批准范围");
                            String raw = db.json(metadata.resources());
                            String sha =
                                    AuthService.digest(
                                            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            UUID snap = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " meta_snapshot(id,space_id,datasource_id,sha256,resources)"
                                        + " VALUES(?,?,?,?,?::jsonb)",
                                    snap,
                                    s,
                                    id,
                                    sha,
                                    raw);
                            var saved = snapshot(s, id, snap);
                            result.put("snapshot", saved);
                            result.put(
                                    "diff",
                                    diff(
                                            s,
                                            id,
                                            before.isEmpty()
                                                    ? List.of()
                                                    : before.getFirst().get("resources"),
                                            saved.get("resources")));
                        });
        result.put("space", space);
        return result;
    }

    /** 资源、字段、主键、关系和索引分别比较，影响对象仅使用本空间映射ID。 */
    private Map<String, Object> diff(UUID s, UUID id, Object previous, Object current) {
        List<Map<String, Object>> added = new ArrayList<>(),
                removed = new ArrayList<>(),
                changed = new ArrayList<>();
        Map<String, Map<String, Object>> a = index(previous), b = index(current);
        Set<String> touched = new HashSet<>();
        for (var e : b.entrySet()) {
            String name = e.getKey();
            if (!a.containsKey(name)) {
                added.add(Map.of("path", name, "kind", "RESOURCE", "after", e.getValue()));
                touched.add(name);
                continue;
            }
            var old = a.get(name);
            var now = e.getValue();
            Map<String, Map<String, Object>> af = fieldIndex(old.get("fields")),
                    bf = fieldIndex(now.get("fields"));
            for (var f : bf.entrySet()) {
                String path = name + "." + f.getKey();
                if (!af.containsKey(f.getKey()))
                    added.add(Map.of("path", path, "kind", "FIELD", "after", f.getValue()));
                else if (!af.get(f.getKey()).equals(f.getValue()))
                    changed.add(
                            Map.of(
                                    "path",
                                    path,
                                    "kind",
                                    "FIELD",
                                    "before",
                                    af.get(f.getKey()),
                                    "after",
                                    f.getValue()));
            }
            for (var f : af.entrySet())
                if (!bf.containsKey(f.getKey()))
                    removed.add(
                            Map.of(
                                    "path",
                                    name + "." + f.getKey(),
                                    "kind",
                                    "FIELD",
                                    "before",
                                    f.getValue()));
            for (String[] pair :
                    new String[][] {
                        {"primaryKey", "PRIMARY_KEY"},
                        {"relations", "RELATION"},
                        {"indexes", "INDEX"}
                    })
                if (!Objects.equals(old.get(pair[0]), now.get(pair[0])))
                    changed.add(
                            Map.of(
                                    "path",
                                    name + "/" + pair[0],
                                    "kind",
                                    pair[1],
                                    "before",
                                    old.get(pair[0]),
                                    "after",
                                    now.get(pair[0])));
            if (!old.equals(now)) touched.add(name);
        }
        for (var e : a.entrySet())
            if (!b.containsKey(e.getKey())) {
                removed.add(Map.of("path", e.getKey(), "kind", "RESOURCE", "before", e.getValue()));
                touched.add(e.getKey());
            }
        List<Map<String, Object>> affected = new ArrayList<>();
        Set<String> objectIds = new HashSet<>();
        for (var m : db.list("SELECT id,kind,definition FROM data_mapping WHERE space_id=?", s)) {
            var d = map(m.get("definition"));
            if (id.toString().equals(d.get("datasourceId"))
                    && (touched.contains(d.get("sourceResource"))
                            || !touched.isEmpty() && m.get("kind").equals("RELATION"))) {
                objectIds.add(m.get("id").toString());
                affected.add(
                        Map.of(
                                "id",
                                m.get("id"),
                                "kind",
                                m.get("kind"),
                                "reason",
                                "源元数据发生变化，需重新核验映射",
                                "operationIds",
                                operationIds(s, m.get("id").toString())));
            }
        }
        for (var m :
                db.list(
                        "SELECT id,kind,definition FROM data_mapping WHERE space_id=? AND"
                                + " kind='PROPERTY'",
                        s))
            if (objectIds.contains(map(m.get("definition")).get("objectMappingId")))
                affected.add(
                        Map.of(
                                "id",
                                m.get("id"),
                                "kind",
                                "PROPERTY",
                                "reason",
                                "所属对象资源发生变化",
                                "operationIds",
                                operationIds(
                                        s,
                                        map(m.get("definition"))
                                                .get("objectMappingId")
                                                .toString())));
        return Map.of(
                "added",
                added,
                "removed",
                removed,
                "changed",
                changed,
                "affectedMappings",
                affected);
    }

    private Map<String, Map<String, Object>> index(Object o) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (var r : rows(o)) m.put(r.get("name").toString(), r);
        return m;
    }

    private Map<String, Map<String, Object>> fieldIndex(Object o) {
        return index(o);
    }

    /** 供目录和影响分析定位草稿操作，不用于执行授权。 */
    public List<String> operationIds(UUID s, String mapping) {
        return cn.ontorepo.mapping.domain.MappingDependencies.operations(
                        db.list("SELECT id,kind,definition FROM data_mapping WHERE space_id=?", s),
                        db.list(
                                "SELECT id,code,name,data_query FROM ont_operation WHERE space_id=?"
                                        + " AND data_query IS NOT NULL",
                                s),
                        mapping)
                .stream()
                .map(operation -> operation.get("id").toString())
                .toList();
    }

    /** 区分代码能力、本地真实验证和五类待外部实例验收。 */
    public List<Map<String, Object>> capabilities(UUID u, UUID s) {
        spaces.role(u, s);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] item :
                new String[][] {
                    {"RELATIONAL", "postgresql"},
                    {"WAREHOUSE", "clickhouse"},
                    {"LAKE", "s3-parquet"},
                    {"FILE", "csv"},
                    {"FILE", "json"},
                    {"FILE", "excel"},
                    {"HTTP", "json"},
                    {"DOCUMENT", "mongodb"},
                    {"KEY_VALUE", "redis"},
                    {"WIDE_COLUMN", "cassandra"},
                    {"GRAPH", "neo4j"}
                }) {
            boolean local = Set.of("RELATIONAL", "LAKE", "FILE", "HTTP").contains(item[0]);
            var adapter =
                    registry.adapter(
                            new SourceSpec(
                                    "capabilities",
                                    item[0],
                                    JSON.createObjectNode().put("dialect", item[1]),
                                    Set.of()));
            List<String> limits =
                    switch (item[0]) {
                        case "RELATIONAL" ->
                                List.of("普通主键表；参数化内等值JOIN；最多500行、10秒", "每个参与资源都需显式分类绑定");
                        case "WAREHOUSE" ->
                                List.of("仅MergeTree家族；证据_part/_part_offset会受合并影响", "无本地实例，待外部验收");
                        case "LAKE" ->
                                List.of(
                                        "单对象16MiB、每前缀1000对象；只读基础Parquet列",
                                        "嵌套、Decimal、INT96不支持；守卫与谓词先于输出LIMIT");
                        case "FILE" ->
                                List.of("受管上传8MiB；适配器最多10万行", "CSV、JSON对象数组、XLSX首工作表；拒绝公式/宏和路径逃逸");
                        case "HTTP" ->
                                List.of(
                                        "精确批准端点，GET或固定JSON体POST；禁止重定向",
                                        "分页至多100页、每页1000条、响应16MiB；未穷尽分页明确失败");
                        case "DOCUMENT" ->
                                List.of(
                                        "仅MongoDB原生find；字段元数据来自首条样本，不能声称完整Schema",
                                        "不开放聚合脚本或SQL JOIN；待外部实例验收");
                        case "KEY_VALUE" ->
                                List.of(
                                        "只读STRING中的JSON；_key EQ或批准前缀SCAN",
                                        "最多1000扫描步/10万键；无JOIN；待外部实例验收");
                        case "WIDE_COLUMN" ->
                                List.of(
                                        "完整分区键EQ，守卫须绑定同一分区键；无ALLOW FILTERING/JOIN",
                                        "仅声明的基础分区键类型；待外部实例验收");
                        case "GRAPH" ->
                                List.of("管理映射支持节点属性查询，SQL式关系JOIN明确拒绝", "原生单跳模板仅内核声明能力；待外部实例验收");
                        default -> throw Problem.bad("未知源类别");
                    };
            out.add(
                    Map.of(
                            "kind",
                            item[0],
                            "dialect",
                            item[1],
                            "implemented",
                            true,
                            "verifiedLocally",
                            local,
                            "capabilities",
                            adapter.capabilities().stream().sorted().toList(),
                            "limits",
                            limits,
                            "verificationNote",
                            local ? "内核已完成真实本地协议验证；登记源以probe为准" : "无本地实例，待外部实例验收"));
        }
        return out;
    }
}
