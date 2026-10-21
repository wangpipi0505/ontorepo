package cn.ontorepo.ingest.application;

import static cn.ontorepo.mapping.domain.MappingValues.*;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.ingest.domain.ExtractionEngine;
import cn.ontorepo.mapping.application.DatasourceService;
import cn.ontorepo.mapping.application.ManagedFileService;
import cn.ontorepo.mapping.application.MappingService;
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

/** 候选抽取与确认分离；批量确认在一个空间锁/修订事务中原子写入真实模型。 */
@Service
public class SuggestionService {
    private final Db db;
    private final SpaceService spaces;
    private final ManagedFileService files;
    private final DatasourceService sources;
    private final MappingService mappings;
    private final List<String> extractionTerms;

    /** 词表仅由服务端配置注入；请求参数不能把抽取规则扩大为任意代码或外部访问。 */
    public SuggestionService(
            Db db,
            SpaceService spaces,
            ManagedFileService files,
            DatasourceService sources,
            MappingService mappings,
            @Value("${ONTO_EXTRACTION_TERMS:}") String extractionTerms) {
        this.db = db;
        this.spaces = spaces;
        this.files = files;
        this.sources = sources;
        this.mappings = mappings;
        this.extractionTerms = ExtractionEngine.vocabulary(extractionTerms);
    }

    /** 只读取当前空间候选，按可选批次缩小范围。 */
    public List<Map<String, Object>> list(UUID u, UUID s, UUID batch) {
        spaces.role(u, s);
        return batch == null
                ? db.list(
                        "SELECT id,batch_id,source_kind,kind,payload,confidence,location,status"
                            + " FROM modeling_suggestion WHERE space_id=? ORDER BY created_at,id",
                        s)
                : db.list(
                        "SELECT id,batch_id,source_kind,kind,payload,confidence,location,status"
                            + " FROM modeling_suggestion WHERE space_id=? AND batch_id=? ORDER BY"
                            + " created_at,id",
                        s,
                        batch);
    }

    @Transactional
    /** 抽取只追加PENDING候选，不自动创建任何本体实体。 */
    public Map<String, Object> extract(UUID u, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId")), batch = UUID.randomUUID();
        int[] count = {0};
        var space =
                spaces.write(
                        u,
                        s,
                        Input.number(b, "expectedDraftRev"),
                        "SUGGESTIONS_EXTRACT",
                        () -> {
                            String kind = Input.text(b, "sourceKind", 10);
                            ExtractionEngine engine = new ExtractionEngine(extractionTerms);
                            List<ExtractionEngine.Suggestion> suggestions;
                            if (kind.equals("TABLE")) {
                                var source = sources.owned(s, b.get("datasourceId"));
                                var snapshot =
                                        sources.snapshot(
                                                s, b.get("datasourceId"), b.get("metaSnapshotId"));
                                suggestions =
                                        engine.table(
                                                source.get("name").toString(),
                                                rows(snapshot.get("resources")),
                                                b.get("resource") == null
                                                        ? null
                                                        : b.get("resource").toString());
                            } else if (kind.equals("FILE") || kind.equals("DOCUMENT")) {
                                var file = files.owned(s, b.get("fileId"));
                                byte[] bytes = files.bytes(file);
                                String resource = file.get("resource").toString(),
                                        ext = resource.substring(resource.lastIndexOf('.') + 1);
                                suggestions =
                                        kind.equals("FILE")
                                                ? engine.file(
                                                        file.get("name").toString(), ext, bytes)
                                                : engine.document(
                                                        file.get("name").toString(), ext, bytes);
                            } else throw Problem.bad("抽取来源无效");
                            for (var suggestion : suggestions)
                                db.update(
                                        "INSERT INTO"
                                            + " modeling_suggestion(id,space_id,batch_id,source_kind,kind,payload,confidence,location)"
                                            + " VALUES(?,?,?,?,?,?::jsonb,?,?::jsonb)",
                                        Input.uuid(suggestion.id()),
                                        s,
                                        batch,
                                        kind,
                                        suggestion.kind(),
                                        db.json(suggestion.payload()),
                                        suggestion.confidence(),
                                        db.json(suggestion.location()));
                            count[0] = suggestions.size();
                        });
        return Map.of("space", space, "batchId", batch.toString(), "count", count[0]);
    }

    @Transactional
    /** 批量决策同事务处理依赖、同名冲突及稳定实体ID，任一失败全部回滚。 */
    public Map<String, Object> decide(UUID u, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "SUGGESTIONS_DECIDE",
                () -> {
                    var decisions = rows(b.get("decisions"));
                    if (decisions.isEmpty() || decisions.size() > 2000)
                        throw Problem.bad("确认批次必须为1至2000项");
                    Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
                    Set<String> seen = new HashSet<>();
                    for (var decision : decisions) {
                        String id = Input.uuid(decision.get("id")).toString();
                        if (!seen.add(id)) throw Problem.bad("同批存在重复决策: " + id);
                        var rows =
                                db.list(
                                        "SELECT * FROM modeling_suggestion WHERE id=? AND"
                                                + " space_id=? FOR UPDATE",
                                        Input.uuid(id),
                                        s);
                        if (rows.isEmpty())
                            throw new Problem(400, "CROSS_SPACE_REFERENCE", "候选不属于当前空间");
                        var candidate = rows.getFirst();
                        if (!candidate.get("status").equals("PENDING"))
                            throw new Problem(409, "SUGGESTION_ALREADY_DECIDED", "候选已确认或拒绝: " + id);
                        String action = Input.text(decision, "decision", 10);
                        if (!Set.of("ACCEPT", "REJECT").contains(action)) throw Problem.bad("决策无效");
                        candidate.put("decision", action);
                        if (decision.get("payload") != null)
                            candidate.put("payload", map(decision.get("payload")));
                        selected.put(id, candidate);
                    }
                    Set<String> pending = new LinkedHashSet<>(selected.keySet());
                    int rounds = 0;
                    while (!pending.isEmpty()) {
                        if (rounds++ > selected.size() + 1)
                            throw new Problem(
                                    400,
                                    "SUGGESTION_DEPENDENCY_UNRESOLVED",
                                    "候选依赖未确认或形成环: " + pending);
                        boolean progress = false;
                        for (String id : new ArrayList<>(pending)) {
                            var c = selected.get(id);
                            if (c.get("decision").equals("REJECT")) {
                                db.update(
                                        "UPDATE modeling_suggestion SET status='REJECTED' WHERE"
                                                + " id=?",
                                        Input.uuid(id));
                                pending.remove(id);
                                progress = true;
                                continue;
                            }
                            var payload = map(c.get("payload"));
                            try {
                                apply(s, c, payload, selected, pending);
                            } catch (PendingDependency e) {
                                continue;
                            }
                            db.update(
                                    "UPDATE modeling_suggestion SET"
                                        + " status='ACCEPTED',payload=?::jsonb,entity_id=? WHERE"
                                        + " id=?",
                                    db.json(payload),
                                    entity(id),
                                    Input.uuid(id));
                            pending.remove(id);
                            progress = true;
                        }
                        if (!progress)
                            throw new Problem(
                                    400,
                                    "SUGGESTION_DEPENDENCY_UNRESOLVED",
                                    "依赖候选未确认、被拒绝或形成环: " + pending);
                    }
                });
    }

    private static UUID entity(String id) {
        return UUID.nameUUIDFromBytes(
                ("ontorepo:suggestion:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class PendingDependency extends RuntimeException {}

    private UUID resolve(
            UUID s,
            Map<String, Object> c,
            Map<String, Object> payload,
            String entityField,
            String suggestionField,
            Map<String, Map<String, Object>> selected,
            Set<String> pending,
            boolean required) {
        Object actual = payload.get(entityField), suggested = payload.get(suggestionField);
        if (actual != null && suggested != null)
            throw Problem.bad("实体ID与候选ID只能选一个: " + entityField);
        if (actual != null) {
            mappings.model(s, "ont_class", actual);
            return Input.uuid(actual);
        }
        if (suggested == null) {
            if (required)
                throw new Problem(
                        400,
                        "SUGGESTION_REFERENCE_MISSING",
                        "候选缺少关联: " + c.get("id") + " " + entityField);
            return null;
        }
        String id = Input.uuid(suggested).toString();
        var rows =
                db.list(
                        "SELECT * FROM modeling_suggestion WHERE id=? AND space_id=? AND"
                                + " batch_id=?",
                        Input.uuid(id),
                        s,
                        Input.uuid(c.get("batchId")));
        if (rows.isEmpty() || !rows.getFirst().get("kind").equals("CLASS"))
            throw new Problem(400, "SUGGESTION_REFERENCE_INVALID", "候选关联必须为同批类: " + id);
        var target = rows.getFirst();
        if (target.get("status").equals("ACCEPTED")) return Input.uuid(target.get("entityId"));
        if (pending.contains(id) && selected.get(id).get("decision").equals("ACCEPT"))
            throw new PendingDependency();
        throw new Problem(400, "SUGGESTION_DEPENDENCY_UNRESOLVED", "关联类尚未接受: " + id);
    }

    private void apply(
            UUID s,
            Map<String, Object> c,
            Map<String, Object> p,
            Map<String, Map<String, Object>> selected,
            Set<String> pending) {
        String id = c.get("id").toString(),
                kind = c.get("kind").toString(),
                name = Input.text(p, "name", 200);
        String desc = Objects.toString(p.get("description"), "");
        if (desc.length() > 10000) throw Problem.bad("候选说明过长");
        UUID entity = entity(id);
        if (kind.equals("CLASS")) {
            UUID parent =
                    resolve(s, c, p, "parentId", "parentSuggestionId", selected, pending, false);
            if (!db.list(
                            "SELECT id FROM ont_class WHERE space_id=? AND name=? AND parent_id IS"
                                    + " NOT DISTINCT FROM ?",
                            s,
                            name,
                            parent)
                    .isEmpty()) conflict(id, name);
            db.update(
                    "INSERT INTO ont_class(id,space_id,name,parent_id,description,deprecated)"
                            + " VALUES(?,?,?,?,?,false)",
                    entity,
                    s,
                    name,
                    parent,
                    desc);
        } else if (kind.equals("PROPERTY")) {
            UUID domain =
                    resolve(
                            s,
                            c,
                            p,
                            "domainClassId",
                            "domainSuggestionId",
                            selected,
                            pending,
                            true);
            String type = Input.text(p, "rangeType", 10);
            if (!Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE").contains(type))
                throw Problem.bad("属性类型无效");
            if (!(p.get("required") instanceof Boolean)) throw Problem.bad("required必须为布尔值");
            if (!db.list(
                            "SELECT id FROM ont_property WHERE space_id=? AND domain_class_id=? AND"
                                    + " name=?",
                            s,
                            domain,
                            name)
                    .isEmpty()) conflict(id, name);
            db.update(
                    "INSERT INTO"
                        + " ont_property(id,space_id,domain_class_id,name,range_type,required,description)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    entity,
                    s,
                    domain,
                    name,
                    type,
                    p.get("required"),
                    desc);
        } else if (kind.equals("RELATION")) {
            UUID
                    from =
                            resolve(
                                    s,
                                    c,
                                    p,
                                    "fromClassId",
                                    "fromSuggestionId",
                                    selected,
                                    pending,
                                    true),
                    to = resolve(s, c, p, "toClassId", "toSuggestionId", selected, pending, true);
            String cardinality = Input.text(p, "cardinality", 20);
            if (!Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY")
                    .contains(cardinality)) throw Problem.bad("关系基数无效");
            for (String field : List.of("transitive", "symmetric"))
                if (!(p.get(field) instanceof Boolean)) throw Problem.bad(field + "必须为布尔值");
            if (!db.list("SELECT id FROM ont_relation WHERE space_id=? AND name=?", s, name)
                    .isEmpty()) conflict(id, name);
            db.update(
                    "INSERT INTO"
                        + " ont_relation(id,space_id,name,from_class_id,to_class_id,cardinality,transitive,\"symmetric\",description)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    entity,
                    s,
                    name,
                    from,
                    to,
                    cardinality,
                    p.get("transitive"),
                    p.get("symmetric"),
                    desc);
        } else throw Problem.bad("候选实体类别无效");
    }

    private static void conflict(String id, String name) {
        throw new Problem(409, "SUGGESTION_NAME_CONFLICT", "同名模型冲突，确认前请修正候选 " + id + ": " + name);
    }
}
