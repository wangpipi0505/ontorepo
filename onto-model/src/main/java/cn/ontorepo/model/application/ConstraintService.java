package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 四种约束在空间锁内验证目标类型、集合和跨空间引用。 */
@Service
public class ConstraintService {
    private final Db db;
    private final SpaceService spaces;
    private final PropertyPolicy policy;

    public ConstraintService(Db db, SpaceService spaces, PropertyPolicy policy) {
        this.db = db;
        this.spaces = spaces;
        this.policy = policy;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_constraint WHERE space_id=? ORDER BY name,id", s);
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId")), target = Input.uuid(b.get("targetRef"));
        String name = Input.text(b, "name", 200),
                kind = Input.text(b, "kind", 20),
                type = Input.text(b, "targetType", 20);
        if (!(b.get("expr") instanceof Map<?, ?>)) throw Problem.bad("约束表达式必须为对象");
        @SuppressWarnings("unchecked")
        Map<String, Object> expr = new LinkedHashMap<>((Map<String, Object>) b.get("expr"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "CONSTRAINT_SAVE",
                () -> {
                    if (id != null) policy.owned("ont_constraint", id, s);
                    String expected =
                            switch (kind) {
                                case "RANGE", "DEPENDENCY" -> "PROPERTY";
                                case "CARDINALITY" -> "RELATION";
                                case "MUTEX" -> "CLASS";
                                default -> throw Problem.bad("约束种类非法");
                            };
                    if (!type.equals(expected)) throw Problem.bad("约束目标类型不匹配");
                    var t = policy.owned("ont_" + type.toLowerCase(Locale.ROOT), target, s);
                    if (kind.equals("RANGE") || kind.equals("CARDINALITY")) {
                        if (!(expr.get("min") instanceof Number lo)
                                || !(expr.get("max") instanceof Number hi)
                                || lo.doubleValue() > hi.doubleValue()) throw Problem.bad("约束范围无效");
                        if (kind.equals("RANGE")
                                && !Set.of("INTEGER", "DECIMAL").contains(t.get("rangeType")))
                            throw Problem.bad("值域约束要求数值属性");
                        if (kind.equals("CARDINALITY")
                                && (lo.doubleValue() < 0
                                        || lo.doubleValue() != lo.longValue()
                                        || hi.doubleValue() != hi.longValue()))
                            throw Problem.bad("基数必须为非负整数");
                    } else {
                        String key = kind.equals("MUTEX") ? "classIds" : "requires";
                        if (!(expr.get(key) instanceof List<?> values) || values.isEmpty())
                            throw Problem.bad("约束引用集合不能为空");
                        expr.put(key, Input.uuids(expr.get(key)));
                        Set<UUID> seen = new HashSet<>();
                        for (Object value : (List<?>) expr.get(key)) {
                            UUID ref = Input.uuid(value);
                            if (ref.equals(target) || !seen.add(ref))
                                throw Problem.bad("约束不能自引用或重复");
                            var other =
                                    policy.owned(
                                            kind.equals("MUTEX") ? "ont_class" : "ont_property",
                                            ref,
                                            s);
                            if (kind.equals("DEPENDENCY")
                                    && !other.get("domainClassId").equals(t.get("domainClassId")))
                                throw Problem.bad("依赖属性必须属于同类");
                        }
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO ont_constraint VALUES(?,?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                s,
                                name,
                                kind,
                                type,
                                target,
                                db.json(expr));
                    else
                        db.update(
                                "UPDATE ont_constraint SET"
                                        + " name=?,kind=?,target_type=?,target_ref=?,expr=?::jsonb"
                                        + " WHERE id=?",
                                name,
                                kind,
                                type,
                                target,
                                db.json(expr),
                                id);
                });
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "CONSTRAINT_DELETE",
                () -> {
                    policy.owned("ont_constraint", id, s);
                    db.update("DELETE FROM ont_constraint WHERE id=?", id);
                });
    }
}
