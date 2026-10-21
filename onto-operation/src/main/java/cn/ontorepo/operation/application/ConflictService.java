package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.operation.domain.*;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 基于同一候选/草稿快照检查引用、类型、约束矛盾和条件域重叠，绝不以文本相同冒充蕴含。 */
@Service
public class ConflictService {
    private final Db db;
    private final SpaceService spaces;

    public ConflictService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public Map<String, Object> draft(UUID u, UUID s) {
        spaces.role(u, s);
        Map<String, Object> p = new HashMap<>();
        for (String k : List.of("class", "property", "rule", "constraint", "operation", "strategy"))
            p.put(
                    switch (k) {
                        case "class" -> "classes";
                        case "property" -> "properties";
                        case "strategy" -> "strategies";
                        default -> k + "s";
                    },
                    db.list("SELECT * FROM ont_" + k + " WHERE space_id=? ORDER BY id", s));
        return inspect(p);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> p, String k) {
        return (List<Map<String, Object>>) p.getOrDefault(k, List.of());
    }

    private void add(List<Map<String, Object>> out, String kind, String message, Object... ids) {
        out.add(
                Map.of(
                        "kind",
                        kind,
                        "message",
                        message,
                        "entityIds",
                        Arrays.stream(ids).map(Object::toString).toList()));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inspect(Map<String, Object> p) {
        List<Map<String, Object>> errors = new ArrayList<>(), warnings = new ArrayList<>();
        Map<String, Map<String, Object>> classes = new HashMap<>(), properties = new HashMap<>();
        for (var c : rows(p, "classes")) classes.put(c.get("id").toString(), c);
        for (var c : rows(p, "properties")) properties.put(c.get("id").toString(), c);
        List<Map<String, Object>> valid = new ArrayList<>();
        for (var r : rows(p, "rules")) {
            String cl = r.get("applicableClassId").toString();
            Map<String, String> types = new HashMap<>();
            for (var prop : properties.values())
                if (cl.equals(prop.get("domainClassId")))
                    types.put(prop.get("id").toString(), prop.get("rangeType").toString());
            boolean reference = false;
            if (!classes.containsKey(cl)
                    || Boolean.TRUE.equals(classes.get(cl).get("deprecated"))) {
                add(errors, "REFERENCE", "规则引用不存在或已弃用对象", r.get("id"), cl);
                reference = true;
            }
            try {
                for (String ref : RuleExpression.references(r.get("condition").toString()))
                    if (!types.containsKey(ref)) {
                        add(errors, "REFERENCE", "规则属性引用不存在或不属于适用对象", r.get("id"), ref);
                        reference = true;
                    }
                if (!reference) {
                    RuleExpression.validate(r.get("condition").toString(), types);
                    if ("SCORING".equals(r.get("kind")))
                        ScoringPolicy.validate((Map<String, Object>) r.get("scoring"), types);
                    valid.add(r);
                }
            } catch (Problem e) {
                add(errors, "TYPE", e.getMessage(), r.get("id"));
            }
        }
        for (int i = 0; i < valid.size(); i++)
            for (int j = i + 1; j < valid.size(); j++) {
                var a = valid.get(i);
                var b = valid.get(j);
                if (!a.get("applicableClassId").equals(b.get("applicableClassId"))
                        || !"VALIDATION".equals(a.get("kind"))
                        || !"VALIDATION".equals(b.get("kind"))) continue;
                var aa = (Map<String, Object>) a.get("action");
                var ba = (Map<String, Object>) b.get("action");
                if (Objects.equals(aa.get("type"), ba.get("type"))) continue;
                String ac = a.get("condition").toString(), bc = b.get("condition").toString();
                try {
                    if (RuleExpression.overlap(ac, bc)) {
                        if (RuleExpression.implies(ac, bc) || RuleExpression.implies(bc, ac))
                            add(errors, "CONSTRAINT", "规则结论互斥且条件存在包含关系", a.get("id"), b.get("id"));
                        else add(warnings, "LOGIC", "规则条件有交集且结论不同", a.get("id"), b.get("id"));
                    }
                } catch (Problem e) {
                    add(
                            errors,
                            "LOGIC",
                            "条件域暂无法完成安全求交，门禁阻断：" + e.getMessage(),
                            a.get("id"),
                            b.get("id"));
                }
            }
        Map<String, Map<String, Object>> ruleIndex = new HashMap<>();
        for (var r : rows(p, "rules")) ruleIndex.put(r.get("id").toString(), r);
        Set<String> strategyIds = new HashSet<>(), operationIds = new HashSet<>();
        for (var st : rows(p, "strategies")) strategyIds.add(st.get("id").toString());
        for (var op : rows(p, "operations")) operationIds.add(op.get("id").toString());
        for (var op : rows(p, "operations")) {
            if (op.get("strategyRef") != null
                    && !strategyIds.contains(
                            op.get("strategyRef").toString().toLowerCase(Locale.ROOT)))
                add(errors, "REFERENCE", "操作策略引用缺失", op.get("id"), op.get("strategyRef"));
            for (Object rid : (List<?>) op.getOrDefault("ruleIds", List.of())) {
                var r = ruleIndex.get(rid.toString().toLowerCase(Locale.ROOT));
                if (r == null) add(errors, "REFERENCE", "操作规则引用缺失", op.get("id"), rid);
                else if ("RULE_CHECK".equals(op.get("kind")) && !"VALIDATION".equals(r.get("kind"))
                        || "SCORING".equals(op.get("kind")) && !"SCORING".equals(r.get("kind")))
                    add(errors, "TYPE", "操作与规则种类不匹配", op.get("id"), rid);
            }
        }
        for (var st : rows(p, "strategies"))
            for (Object oid : (List<?>) st.getOrDefault("boundOps", List.of()))
                if (!operationIds.contains(oid.toString().toLowerCase(Locale.ROOT)))
                    add(errors, "REFERENCE", "策略绑定操作缺失", st.get("id"), oid);
        List<Map<String, Object>> constraints = rows(p, "constraints");
        for (int i = 0; i < constraints.size(); i++) {
            var a = constraints.get(i);
            var ae = (Map<String, Object>) a.get("expr");
            if ("MUTEX".equals(a.get("kind"))) {
                String target = a.get("targetRef").toString();
                for (Object other : (List<?>) ae.get("classIds")) {
                    String cur = target;
                    Set<String> seen = new HashSet<>();
                    while (cur != null && seen.add(cur)) {
                        if (cur.equalsIgnoreCase(other.toString()))
                            add(errors, "CONSTRAINT", "对象继承链与互斥约束矛盾", a.get("id"), target, other);
                        var c = classes.get(cur);
                        cur =
                                c == null || c.get("parentId") == null
                                        ? null
                                        : c.get("parentId").toString();
                    }
                }
            }
            if (Set.of("RANGE", "CARDINALITY").contains(a.get("kind")))
                for (int j = i + 1; j < constraints.size(); j++) {
                    var b = constraints.get(j);
                    if (!a.get("kind").equals(b.get("kind"))
                            || !a.get("targetRef").equals(b.get("targetRef"))) continue;
                    var be = (Map<String, Object>) b.get("expr");
                    if (((Number) ae.get("max")).doubleValue()
                                    < ((Number) be.get("min")).doubleValue()
                            || ((Number) be.get("max")).doubleValue()
                                    < ((Number) ae.get("min")).doubleValue())
                        add(errors, "CONSTRAINT", "同一目标的约束范围没有交集", a.get("id"), b.get("id"));
                }
        }
        return Map.of("errors", errors, "warnings", warnings);
    }
}
