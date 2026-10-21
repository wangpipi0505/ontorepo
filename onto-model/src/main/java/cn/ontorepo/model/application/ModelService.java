package cn.ontorepo.model.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 对象树和属性变更共享空间写锁，先验证同空间归属再校验层级环。 */
@Service
public class ModelService {
    private final Db db;
    private final SpaceService spaces;
    private final PropertyPolicy policy;

    public ModelService(Db db, SpaceService spaces, PropertyPolicy policy) {
        this.db = db;
        this.policy = policy;
        this.spaces = spaces;
    }

    /** 读取整个空间对象树，层级组装由前端执行，服务端保留稳定标识。 */
    public List<Map<String, Object>> classes(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list("SELECT * FROM ont_class WHERE space_id=? ORDER BY name,id", space);
    }

    /** 属性列表要求所属类与空间一致，避免跨空间探查资源。 */
    public List<Map<String, Object>> properties(UUID user, UUID space, UUID cl) {
        spaces.role(user, space);
        owned("ont_class", cl, space);
        return db.list(
                "SELECT * FROM ont_property WHERE space_id=? AND domain_class_id=? ORDER BY"
                        + " name,id",
                space,
                cl);
    }

    /** 全空间属性仅供规则和约束绑定，仍经服务端空间授权。 */
    public List<Map<String, Object>> allProperties(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list("SELECT * FROM ont_property WHERE space_id=? ORDER BY name,id", space);
    }

    private void owned(String table, UUID id, UUID space) {
        if (db.list("SELECT id FROM " + table + " WHERE id=? AND space_id=?", id, space).isEmpty())
            throw new Problem(400, "CROSS_SPACE_REFERENCE", "资源不存在或不属于当前空间");
    }

    /** 创建或移动对象：同空间父级、无环和数据库同级唯一约束在同一写锁内成立。 */
    public Map<String, Object> saveClass(UUID user, UUID id, Map<String, Object> b) {
        UUID space = Input.uuid(b.get("spaceId"));
        String name = Input.text(b, "name", 200), description = Input.description(b);
        UUID parent = b.get("parentId") == null ? null : Input.uuid(b.get("parentId"));
        boolean deprecated = Input.bool(b, "deprecated");
        return spaces.write(
                user,
                space,
                Input.number(b, "expectedDraftRev"),
                id == null ? "CLASS_CREATE" : "CLASS_UPDATE",
                () -> {
                    if (id != null) owned("ont_class", id, space);
                    if (parent != null) {
                        owned("ont_class", parent, space);
                        // 在空间写锁中沿祖先链检查，防止并发移动共同绕过环检测。
                        // seen 同时保护历史损坏数据，不能因已有环导致服务线程无限循环。
                        UUID cursor = parent;
                        Set<UUID> seen = new HashSet<>();
                        while (cursor != null) {
                            if (cursor.equals(id) || !seen.add(cursor))
                                throw new Problem(400, "HIERARCHY_CYCLE", "父级调整会形成层级环");
                            var row =
                                    db.one(
                                            "SELECT parent_id FROM ont_class WHERE id=? AND"
                                                    + " space_id=?",
                                            cursor,
                                            space);
                            cursor =
                                    row.get("parentId") == null
                                            ? null
                                            : Input.uuid(row.get("parentId"));
                        }
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO"
                                    + " ont_class(id,space_id,name,parent_id,description,deprecated)"
                                    + " VALUES(?,?,?,?,?,?)",
                                UUID.randomUUID(),
                                space,
                                name,
                                parent,
                                description,
                                deprecated);
                    else
                        db.update(
                                "UPDATE ont_class SET name=?,parent_id=?,description=?,deprecated=?"
                                        + " WHERE id=?",
                                name,
                                parent,
                                description,
                                deprecated,
                                id);
                });
    }

    /** 有子类或属性的对象不可删除，防止产生悬挂层级与属性归属。 */
    public Map<String, Object> deleteClass(UUID user, UUID id, UUID space, long expected) {
        return spaces.write(
                user,
                space,
                expected,
                "CLASS_DELETE",
                () -> {
                    owned("ont_class", id, space);
                    if (!db.list(
                                    "SELECT id FROM ont_class WHERE parent_id=? UNION ALL SELECT id"
                                            + " FROM ont_property WHERE domain_class_id=?",
                                    id,
                                    id)
                            .isEmpty()) throw new Problem(409, "CLASS_IN_USE", "对象仍有子类或属性，不能删除");
                    if (!db.list(
                                    "SELECT id FROM ont_relation WHERE from_class_id=? OR"
                                        + " to_class_id=? UNION ALL SELECT id FROM ont_rule WHERE"
                                        + " applicable_class_id=? UNION ALL SELECT id FROM"
                                        + " ont_constraint WHERE target_ref=?",
                                    id,
                                    id,
                                    id,
                                    id)
                            .isEmpty()) throw new Problem(409, "CLASS_IN_USE", "对象仍被关系、规则或约束引用");
                    for (var c :
                            db.list(
                                    "SELECT id,expr FROM ont_constraint WHERE space_id=? AND"
                                            + " kind='MUTEX'",
                                    space))
                        if (c.get("expr")
                                .toString()
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains(id.toString()))
                            throw new Problem(409, "CLASS_IN_USE", "对象仍被互斥约束引用");
                    policy.sourceDelete("ont_class", space, id);
                    db.update("DELETE FROM ont_class WHERE id=?", id);
                });
    }

    /** 属性基本字段和高级分面同事务校验，破坏已引用定义的变更将阻断。 */
    public Map<String, Object> saveProperty(UUID user, UUID id, Map<String, Object> b) {
        UUID space = Input.uuid(b.get("spaceId")), cl = Input.uuid(b.get("domainClassId"));
        String name = Input.text(b, "name", 200),
                range = Input.text(b, "rangeType", 10),
                description = Input.description(b);
        if (!Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE").contains(range))
            throw Problem.bad("不支持的属性类型");
        boolean required = Input.bool(b, "required");
        @SuppressWarnings("unchecked")
        Map<String, Object> facets =
                b.get("facets") == null
                        ? new java.util.LinkedHashMap<>()
                        : new java.util.LinkedHashMap<>((Map<String, Object>) b.get("facets"));
        for (String key : List.of("codebookId", "unitCodebookId"))
            if (facets.get(key) != null) facets.put(key, Input.uuid(facets.get(key)).toString());
        return spaces.write(
                user,
                space,
                Input.number(b, "expectedDraftRev"),
                id == null ? "PROPERTY_CREATE" : "PROPERTY_UPDATE",
                () -> {
                    owned("ont_class", cl, space);
                    policy.facets(space, id, cl, range, required, facets);
                    if (id != null) {
                        var old = policy.owned("ont_property", id, space);
                        if (!old.get("rangeType").equals(range)
                                || !old.get("domainClassId").equals(cl.toString())
                                || !old.get("facets").equals(facets)
                                || !old.get("required").equals(required)) policy.prevent(space, id);
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO"
                                    + " ont_property(id,space_id,domain_class_id,name,range_type,required,description,facets)"
                                    + " VALUES(?,?,?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                space,
                                cl,
                                name,
                                range,
                                required,
                                description,
                                db.json(facets));
                    else {
                        owned("ont_property", id, space);
                        db.update(
                                "UPDATE ont_property SET"
                                    + " domain_class_id=?,name=?,range_type=?,required=?,description=?,facets=?::jsonb"
                                    + " WHERE id=?",
                                cl,
                                name,
                                range,
                                required,
                                description,
                                db.json(facets),
                                id);
                    }
                });
    }

    /** 属性删除同样消耗一次草稿修订，不能绕过冲突检测。 */
    public Map<String, Object> deleteProperty(UUID user, UUID id, UUID space, long expected) {
        return spaces.write(
                user,
                space,
                expected,
                "PROPERTY_DELETE",
                () -> {
                    owned("ont_property", id, space);
                    policy.prevent(space, id);
                    policy.sourceDelete("ont_property", space, id);
                    db.update("DELETE FROM ont_property WHERE id=?", id);
                });
    }
}
