package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.operation.domain.QueryPolicy;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 操作仅是受限模型 SELECT，保存时和执行时均经同一策略校验。 */
@Service
public class OperationService {
    private final Db db;
    private final SpaceService spaces;

    public OperationService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    /** 当前草稿操作列表只服务操作编辑；候选试执行使用冻结载荷中的操作。 */
    public List<Map<String, Object>> list(UUID user, UUID space) {
        spaces.role(user, space);
        return db.list("SELECT * FROM ont_operation WHERE space_id=? ORDER BY code,id", space);
    }

    /** 操作保存先解析受限 SELECT，再在空间事务中保证编码唯一与修订递增。 */
    public Map<String, Object> save(UUID user, UUID id, Map<String, Object> b) {
        UUID space = Input.uuid(b.get("spaceId"));
        String code = Input.text(b, "code", 80),
                name = Input.text(b, "name", 200),
                description = Input.description(b),
                precondition = Input.optionalText(b, "precondition", 4000),
                query = Input.optionalText(b, "queryTemplate", 10000);
        String kind = b.get("kind") == null ? "OBJECT_QUERY" : Input.text(b, "kind", 30);
        if (!java.util.Set.of("OBJECT_QUERY", "RELATION_QUERY", "RULE_CHECK", "SCORING")
                .contains(kind)) throw Problem.bad("操作种类无效");
        UUID strategy = b.get("strategyRef") == null ? null : Input.uuid(b.get("strategyRef"));
        java.util.List<?> ruleIds =
                b.get("ruleIds") == null ? java.util.List.of() : Input.uuids(b.get("ruleIds"));
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]*")) throw Problem.bad("操作编码格式错误");
        long max = Input.number(b, "maxRows"), timeout = Input.number(b, "timeoutMs");
        if (max < 1 || max > 500 || timeout < 100 || timeout > 10000)
            throw Problem.bad("结果上限或超时范围错误");
        if (kind.equals("OBJECT_QUERY") || kind.equals("RELATION_QUERY"))
            QueryPolicy.validate(query, (int) max, (int) timeout);
        else if (ruleIds.isEmpty()) throw Problem.bad("规则类操作至少绑定一条规则");
        return spaces.write(
                user,
                space,
                Input.number(b, "expectedDraftRev"),
                id == null ? "OPERATION_CREATE" : "OPERATION_UPDATE",
                () -> {
                    if (strategy != null) OperationRefs.owned(db, "ont_strategy", strategy, space);
                    for (Object ref : ruleIds) {
                        var rule = OperationRefs.owned(db, "ont_rule", Input.uuid(ref), space);
                        if (kind.equals("RULE_CHECK") && !rule.get("kind").equals("VALIDATION")
                                || kind.equals("SCORING") && !rule.get("kind").equals("SCORING"))
                            throw Problem.bad("操作与规则种类不匹配");
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO"
                                    + " ont_operation(id,space_id,code,name,description,precondition,query_template,max_rows,timeout_ms,kind,strategy_ref,rule_ids)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                space,
                                code,
                                name,
                                description,
                                precondition,
                                query,
                                max,
                                timeout,
                                kind,
                                strategy,
                                db.json(ruleIds));
                    else {
                        owned(id, space);
                        db.update(
                                "UPDATE ont_operation SET"
                                    + " code=?,name=?,description=?,precondition=?,query_template=?,max_rows=?,timeout_ms=?,kind=?,strategy_ref=?,rule_ids=?::jsonb"
                                    + " WHERE id=?",
                                code,
                                name,
                                description,
                                precondition,
                                query,
                                max,
                                timeout,
                                kind,
                                strategy,
                                db.json(ruleIds),
                                id);
                    }
                });
    }

    /** 删除草稿操作不会改变既有候选中的操作快照。 */
    public Map<String, Object> delete(UUID user, UUID id, UUID space, long rev) {
        return spaces.write(
                user,
                space,
                rev,
                "OPERATION_DELETE",
                () -> {
                    owned(id, space);
                    if (!db.list(
                                    "SELECT id FROM ont_test_case WHERE operation_id=? UNION ALL"
                                        + " SELECT id FROM ont_strategy WHERE space_id=? AND EXISTS"
                                        + " (SELECT 1 FROM jsonb_array_elements_text(bound_ops) ref"
                                        + " WHERE lower(ref.value)=?)",
                                    id,
                                    space,
                                    id.toString())
                            .isEmpty()) throw new Problem(409, "OPERATION_IN_USE", "操作仍被用例或策略绑定");
                    db.update("DELETE FROM ont_operation WHERE id=?", id);
                });
    }

    private void owned(UUID id, UUID space) {
        if (db.list("SELECT id FROM ont_operation WHERE id=? AND space_id=?", id, space).isEmpty())
            throw new Problem(400, "CROSS_SPACE_REFERENCE", "操作不属于当前空间");
    }
}
