package cn.ontorepo.knowledge.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 知识草稿写入复用空间锁；来源和同空间概念引用是可追溯性的最低要求。 */
@Service
public class KnowledgeService {
    private final Db db;
    private final SpaceService spaces;

    public KnowledgeService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_knowledge WHERE space_id=? ORDER BY term,id", s);
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        String term = Input.text(b, "term", 200),
                definition = Input.text(b, "definition", 20000),
                source = Input.text(b, "sourceRef", 1000);
        if (!(b.get("aliases") instanceof List<?> values) || values.size() > 100)
            throw Problem.bad("别名必须为最多100项的数组");
        Set<String> aliases = new LinkedHashSet<>();
        for (Object a : values) {
            if (!(a instanceof String text) || text.isBlank() || text.length() > 200)
                throw Problem.bad("别名不能为空或超过200字");
            aliases.add(text.trim());
        }
        UUID concept = b.get("conceptRef") == null ? null : Input.uuid(b.get("conceptRef"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "KNOWLEDGE_SAVE",
                () -> {
                    if (id != null) owned(id, s);
                    if (concept != null
                            && db.list(
                                            "SELECT 1 FROM ont_class WHERE id=? AND space_id=?",
                                            concept,
                                            s)
                                    .isEmpty())
                        throw new Problem(400, "CROSS_SPACE_REFERENCE", "知识概念不属于当前空间");
                    if (id == null)
                        db.update(
                                "INSERT INTO"
                                    + " ont_knowledge(id,space_id,term,aliases,definition,concept_ref,source_ref)"
                                    + " VALUES(?,?,?,?::jsonb,?,?,?)",
                                UUID.randomUUID(),
                                s,
                                term,
                                db.json(aliases),
                                definition,
                                concept,
                                source);
                    else
                        db.update(
                                "UPDATE ont_knowledge SET"
                                    + " term=?,aliases=?::jsonb,definition=?,concept_ref=?,source_ref=?"
                                    + " WHERE id=?",
                                term,
                                db.json(aliases),
                                definition,
                                concept,
                                source,
                                id);
                });
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long rev) {
        return spaces.write(
                u,
                s,
                rev,
                "KNOWLEDGE_DELETE",
                () -> {
                    owned(id, s);
                    db.update("DELETE FROM ont_knowledge WHERE id=?", id);
                });
    }

    private void owned(UUID id, UUID s) {
        if (db.list("SELECT 1 FROM ont_knowledge WHERE id=? AND space_id=?", id, s).isEmpty())
            throw new Problem(400, "CROSS_SPACE_REFERENCE", "知识不属于当前空间");
    }
}
