package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.operation.domain.*;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 保存时做白名单解析和同类引用校验；执行时再对冻结内容使用同一计算器。 */
@Service
public class RuleService {
    private final Db db;
    private final SpaceService spaces;

    public RuleService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_rule WHERE space_id=? ORDER BY name,id", s);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId")), cl = Input.uuid(b.get("applicableClassId"));
        String n = Input.text(b, "name", 200),
                k = Input.text(b, "kind", 20),
                condition = Input.optionalText(b, "condition", 10000),
                severity = Input.text(b, "severity", 10);
        if (!Set.of("VALIDATION", "SCORING").contains(k)
                || !Set.of("WARN", "ERROR").contains(severity)) throw Problem.bad("规则种类或严重程度无效");
        Map<String, Object>
                action = b.get("action") == null ? Map.of() : (Map<String, Object>) b.get("action"),
                scoring =
                        b.get("scoring") == null
                                ? null
                                : ScoringPolicy.normalized((Map<String, Object>) b.get("scoring"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "RULE_SAVE",
                () -> {
                    if (id != null) {
                        OperationRefs.owned(db, "ont_rule", id, s);
                        for (var op :
                                db.list(
                                        "SELECT id,kind FROM ont_operation WHERE space_id=? AND"
                                                + " EXISTS (SELECT 1 FROM"
                                                + " jsonb_array_elements_text(rule_ids) ref WHERE"
                                                + " lower(ref.value)=?)",
                                        s,
                                        id.toString()))
                            if ("RULE_CHECK".equals(op.get("kind")) && !k.equals("VALIDATION")
                                    || "SCORING".equals(op.get("kind")) && !k.equals("SCORING"))
                                throw new Problem(409, "RULE_IN_USE", "规则种类变更会破坏已绑定操作");
                    }
                    var cls = OperationRefs.owned(db, "ont_class", cl, s);
                    if (Boolean.TRUE.equals(cls.get("deprecated")))
                        throw Problem.bad("规则不能绑定已弃用对象");
                    var types = OperationRefs.types(db, s, cl);
                    RuleExpression.validate(condition, types);
                    if (k.equals("VALIDATION")) {
                        if (!Set.of("ACCEPT", "REJECT")
                                        .contains(Objects.toString(action.get("type"), ""))
                                || !(action.get("message") instanceof String))
                            throw Problem.bad("校验规则动作无效");
                        if (scoring != null) throw Problem.bad("校验规则不能携带评分配置");
                    } else {
                        if (scoring == null) throw Problem.bad("评分配置不能为空");
                        ScoringPolicy.validate(scoring, types);
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO ont_rule VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                                UUID.randomUUID(),
                                s,
                                n,
                                cl,
                                k,
                                condition,
                                db.json(action),
                                scoring == null ? null : db.json(scoring),
                                severity);
                    else
                        db.update(
                                "UPDATE ont_rule SET"
                                    + " name=?,applicable_class_id=?,kind=?,condition=?,action=?::jsonb,scoring=?::jsonb,severity=?"
                                    + " WHERE id=?",
                                n,
                                cl,
                                k,
                                condition,
                                db.json(action),
                                scoring == null ? null : db.json(scoring),
                                severity,
                                id);
                });
    }

    /** 规则被哪些操作以 rule_ids 引用；只读视图，不改变删除/改类的阻断规则。 */
    public List<Map<String, Object>> references(UUID u, UUID id) {
        var rule = db.one("SELECT space_id FROM ont_rule WHERE id=?", id);
        if (rule == null) throw Problem.missing();
        UUID space = Input.uuid(rule.get("spaceId"));
        spaces.role(u, space);
        return db.list(
                "SELECT o.id,o.code,o.name,o.kind FROM ont_operation o WHERE o.space_id=?"
                        + " AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(o.rule_ids) ref"
                        + " WHERE lower(ref.value)=?) ORDER BY o.code",
                space,
                id.toString());
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "RULE_DELETE",
                () -> {
                    OperationRefs.owned(db, "ont_rule", id, s);
                    if (!db.list(
                                    "SELECT id FROM ont_operation WHERE space_id=? AND EXISTS"
                                        + " (SELECT 1 FROM jsonb_array_elements_text(rule_ids) ref"
                                        + " WHERE lower(ref.value)=?)",
                                    s,
                                    id.toString())
                            .isEmpty()) throw new Problem(409, "RULE_IN_USE", "规则仍被操作引用");
                    db.update("DELETE FROM ont_rule WHERE id=?", id);
                });
    }
}
