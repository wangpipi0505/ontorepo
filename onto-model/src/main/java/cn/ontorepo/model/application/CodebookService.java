package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 码表维护在空间锁内验证引用，已使用条目不得因删除或改码失效。 */
@Service
public class CodebookService {
    private final Db db;
    private final SpaceService spaces;
    private final PropertyPolicy policy;

    public CodebookService(Db db, SpaceService spaces, PropertyPolicy policy) {
        this.db = db;
        this.spaces = spaces;
        this.policy = policy;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_codebook WHERE space_id=? ORDER BY code,id", s);
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        String code = Input.text(b, "code", 80),
                name = Input.text(b, "name", 200),
                kind = Input.text(b, "kind", 10);
        if (!Set.of("UNIT", "VALUE").contains(kind)) throw Problem.bad("码表种类无效");
        if (!(b.get("entries") instanceof List<?> entries) || entries.isEmpty())
            throw Problem.bad("码表条目不能为空");
        Set<String> seen = new HashSet<>();
        for (Object e : (List<?>) b.get("entries")) {
            if (!(e instanceof Map<?, ?> row)
                    || !(row.get("code") instanceof String c)
                    || c.isBlank()
                    || !(row.get("label") instanceof String l)
                    || l.isBlank()
                    || !seen.add(c)) throw Problem.bad("码表条目非法或重复");
        }
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "CODEBOOK_SAVE",
                () -> {
                    if (id == null)
                        db.update(
                                "INSERT INTO ont_codebook VALUES(?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                s,
                                code,
                                name,
                                kind,
                                db.json(b.get("entries")));
                    else {
                        policy.owned("ont_codebook", id, s);
                        db.update(
                                "UPDATE ont_codebook SET code=?,name=?,kind=?,entries=?::jsonb"
                                        + " WHERE id=?",
                                code,
                                name,
                                kind,
                                db.json(b.get("entries")),
                                id);
                        for (var property :
                                db.list(
                                        "SELECT * FROM ont_property WHERE space_id=? AND"
                                                + " (lower(facets->>'codebookId')=? OR"
                                                + " lower(facets->>'unitCodebookId')=?)",
                                        s,
                                        id.toString(),
                                        id.toString())) {
                            @SuppressWarnings("unchecked")
                            var facets = (Map<String, Object>) property.get("facets");
                            policy.facets(
                                    s,
                                    Input.uuid(property.get("id")),
                                    Input.uuid(property.get("domainClassId")),
                                    property.get("rangeType").toString(),
                                    Boolean.TRUE.equals(property.get("required")),
                                    facets);
                        }
                    }
                });
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "CODEBOOK_DELETE",
                () -> {
                    policy.owned("ont_codebook", id, s);
                    // 映射转换也持有当前码表引用；只检查属性分面会让新候选失去可执行转换。
                    if (!db.list(
                                    "SELECT id FROM data_mapping WHERE space_id=? AND"
                                            + " lower(definition->'transform'->>'codebookId')=?",
                                    s,
                                    id.toString())
                            .isEmpty()) throw new Problem(409, "CODEBOOK_IN_USE", "码表仍被映射转换引用");
                    if (!db.list(
                                    "SELECT id FROM ont_property WHERE space_id=? AND"
                                            + " (lower(facets->>'codebookId')=? OR"
                                            + " lower(facets->>'unitCodebookId')=?)",
                                    s,
                                    id.toString(),
                                    id.toString())
                            .isEmpty()) throw new Problem(409, "CODEBOOK_IN_USE", "码表仍被引用");
                    db.update("DELETE FROM ont_codebook WHERE id=?", id);
                });
    }
}
