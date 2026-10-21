package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 正反关系同一事务创建维护，客户端不能直接写 inverseOf。 */
@Service
public class RelationService {
    private final Db db;
    private final SpaceService spaces;
    private final PropertyPolicy policy;

    public RelationService(Db db, SpaceService spaces, PropertyPolicy policy) {
        this.db = db;
        this.spaces = spaces;
        this.policy = policy;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_relation WHERE space_id=? ORDER BY name,id", s);
    }

    private String inverse(String c) {
        return switch (c) {
            case "ONE_TO_MANY" -> "MANY_TO_ONE";
            case "MANY_TO_ONE" -> "ONE_TO_MANY";
            default -> c;
        };
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId")),
                from = Input.uuid(b.get("fromClassId")),
                to = Input.uuid(b.get("toClassId"));
        String n = Input.text(b, "name", 200),
                c = Input.text(b, "cardinality", 20),
                desc = Input.description(b),
                inv = Input.optionalText(b, "inverseName", 200);
        boolean tr = Input.bool(b, "transitive"), sym = Input.bool(b, "symmetric");
        if (from.equals(to)
                || !Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY").contains(c))
            throw Problem.bad("关系端点或基数非法");
        if (b.get("inverseOf") != null) throw Problem.bad("反向关系仅可由 inverseName 创建");
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "RELATION_SAVE",
                () -> {
                    policy.owned("ont_class", from, s);
                    policy.owned("ont_class", to, s);
                    UUID actual = id == null ? UUID.randomUUID() : id, back = null;
                    if (id != null) {
                        var old = policy.owned("ont_relation", id, s);
                        if (old.get("inverseOf") != null) back = Input.uuid(old.get("inverseOf"));
                        if (!old.get("fromClassId").equals(from.toString())
                                || !old.get("toClassId").equals(to.toString())
                                || !old.get("cardinality").equals(c)) inUse(s, id, back);
                    }
                    if (back == null && !inv.isEmpty()) back = UUID.randomUUID();
                    String reverseName =
                            inv.isEmpty() && back != null
                                    ? db.one("SELECT name FROM ont_relation WHERE id=?", back)
                                            .get("name")
                                            .toString()
                                    : inv;
                    upsert(actual, s, n, from, to, c, back, tr, sym, desc);
                    if (back != null)
                        upsert(back, s, reverseName, to, from, inverse(c), actual, tr, sym, desc);
                });
    }

    private void upsert(
            UUID id,
            UUID s,
            String n,
            UUID f,
            UUID t,
            String c,
            UUID inv,
            boolean tr,
            boolean sy,
            String d) {
        db.update(
                "INSERT INTO"
                    + " ont_relation(id,space_id,name,from_class_id,to_class_id,cardinality,inverse_of,transitive,\"symmetric\",description)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                    + " name=excluded.name,from_class_id=excluded.from_class_id,to_class_id=excluded.to_class_id,cardinality=excluded.cardinality,inverse_of=excluded.inverse_of,transitive=excluded.transitive,\"symmetric\"=excluded.\"symmetric\",description=excluded.description",
                id,
                s,
                n,
                f,
                t,
                c,
                inv,
                tr,
                sy,
                d);
    }

    private void inUse(UUID s, UUID id, UUID back) {
        if (!db.list(
                        "SELECT id FROM ont_constraint WHERE space_id=? AND (target_ref=? OR"
                                + " target_ref=?)",
                        s,
                        id,
                        back == null ? id : back)
                .isEmpty()) throw new Problem(409, "RELATION_IN_USE", "关系仍被约束引用");
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "RELATION_DELETE",
                () -> {
                    var row = policy.owned("ont_relation", id, s);
                    UUID inv =
                            row.get("inverseOf") == null ? null : Input.uuid(row.get("inverseOf"));
                    inUse(s, id, inv);
                    policy.sourceDelete("ont_relation", s, id);
                    if (inv != null) policy.sourceDelete("ont_relation", s, inv);
                    db.update(
                            "DELETE FROM ont_relation WHERE id=? OR id=?",
                            id,
                            inv == null ? id : inv);
                });
    }
}
