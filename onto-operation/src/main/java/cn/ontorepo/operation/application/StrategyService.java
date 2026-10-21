package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 策略的上限只能缩小操作资源，boundOps 同空间校验并进入冻结载荷。 */
@Service
public class StrategyService {
    private final Db db;
    private final SpaceService spaces;

    public StrategyService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_strategy WHERE space_id=? ORDER BY name,id", s);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        String n = Input.text(b, "name", 200);
        if (!(b.get("policy") instanceof Map<?, ?>)) throw Problem.bad("策略配置不能为空");
        Map<String, Object> p = (Map<String, Object>) b.get("policy");
        long max = Input.number(p, "maxRows"),
                timeout = Input.number(p, "timeoutMs"),
                limit = Input.number(p, "resultLimit");
        if (max < 1
                || max > 500
                || timeout < 1
                || timeout > 10000
                || limit < 1
                || limit > 500
                || !"FAIL".equals(p.get("failureMode"))) throw Problem.bad("策略资源上限或失败策略无效");
        if (p.get("readScope") != null && !(p.get("readScope") instanceof Map<?, ?>))
            throw Problem.bad("读取范围必须为对象或null");
        List<String> ops = Input.uuids(b.get("boundOps"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "STRATEGY_SAVE",
                () -> {
                    if (id != null) OperationRefs.owned(db, "ont_strategy", id, s);
                    Set<UUID> seen = new HashSet<>();
                    for (Object op : ops) {
                        UUID ref = Input.uuid(op);
                        OperationRefs.owned(db, "ont_operation", ref, s);
                        if (!seen.add(ref)) throw Problem.bad("绑定操作重复");
                    }
                    if (id == null)
                        db.update(
                                "INSERT INTO ont_strategy VALUES(?,?,?,?::jsonb,?::jsonb)",
                                UUID.randomUUID(),
                                s,
                                n,
                                db.json(p),
                                db.json(ops));
                    else
                        db.update(
                                "UPDATE ont_strategy SET name=?,policy=?::jsonb,bound_ops=?::jsonb"
                                        + " WHERE id=?",
                                n,
                                db.json(p),
                                db.json(ops),
                                id);
                });
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "STRATEGY_DELETE",
                () -> {
                    OperationRefs.owned(db, "ont_strategy", id, s);
                    if (!db.list("SELECT id FROM ont_operation WHERE strategy_ref=?", id).isEmpty())
                        throw new Problem(409, "STRATEGY_IN_USE", "策略仍被操作绑定");
                    db.update("DELETE FROM ont_strategy WHERE id=?", id);
                });
    }
}
