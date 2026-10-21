package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;

import java.util.*;

/** 操作领域的同空间引用校验不依赖建模控制器，禁止借 UUID 引用别的空间。 */
public final class OperationRefs {
    private OperationRefs() {}

    public static Map<String, Object> owned(Db db, String table, UUID id, UUID s) {
        return db.list("SELECT * FROM " + table + " WHERE id=? AND space_id=?", id, s).stream()
                .findFirst()
                .orElseThrow(() -> new Problem(400, "CROSS_SPACE_REFERENCE", "引用资源不存在或不属于当前空间"));
    }

    public static Map<String, String> types(Db db, UUID s, UUID cl) {
        Map<String, String> m = new HashMap<>();
        for (var p :
                db.list(
                        "SELECT id,range_type FROM ont_property WHERE space_id=? AND"
                                + " domain_class_id=?",
                        s,
                        cl)) m.put(p.get("id").toString(), p.get("rangeType").toString());
        return m;
    }
}
