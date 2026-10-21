package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 确定性用例保存实际操作和输入；运行时仅从候选副本装载。 */
@Service
public class TestCaseService {
    private final Db db;
    private final SpaceService spaces;

    public TestCaseService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list("SELECT * FROM ont_test_case WHERE space_id=? ORDER BY name,id", s);
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId")), op = Input.uuid(b.get("operationId"));
        String n = Input.text(b, "name", 200),
                q = Input.text(b, "question", 4000),
                mode = b.get("matchMode") == null ? "EXACT" : Input.text(b, "matchMode", 10);
        boolean en = Input.bool(b, "enabled");
        String executionMode = b.get("mode") == null ? "DIRECT" : Input.text(b, "mode", 10);
        if (!Set.of("DIRECT", "NL").contains(executionMode)) throw Problem.bad("用例模式必须为DIRECT或NL");
        if (!Set.of("EXACT", "CONTAINS").contains(mode)
                || !b.containsKey("input")
                || !(b.get("expected") instanceof Map<?, ?>)) throw Problem.bad("用例匹配或输入断言无效");
        Map<?, ?> expected = (Map<?, ?>) b.get("expected");
        if (expected.isEmpty()
                || !Set.of("status", "errorCode", "opCode", "columns", "rows", "explanationKeywords")
                        .containsAll(expected.keySet())
                || mode.equals("EXACT") && !expected.containsKey("rows"))
            throw Problem.bad("精确匹配须提供完整rows，包含匹配也必须有明确断言");
        if (expected.containsKey("explanationKeywords")) {
            if (!(expected.get("explanationKeywords") instanceof List<?> keywords)
                    || keywords.isEmpty()
                    || keywords.size() > 20
                    || keywords.stream()
                            .anyMatch(
                                    item ->
                                            !(item instanceof String text)
                                                    || text.isBlank()
                                                    || text.length() > 200))
                throw Problem.bad("解释关键词必须为1~20个非空字符串");
        }
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "TEST_CASE_SAVE",
                () -> {
                    if (id != null) OperationRefs.owned(db, "ont_test_case", id, s);
                    OperationRefs.owned(db, "ont_operation", op, s);
                    if (id == null)
                        db.update(
                                "INSERT INTO"
                                    + " ont_test_case(id,space_id,name,question,operation_id,input,expected,match_mode,enabled,mode)"
                                    + " VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
                                UUID.randomUUID(),
                                s,
                                n,
                                q,
                                op,
                                db.json(b.get("input")),
                                db.json(b.get("expected")),
                                mode,
                                en,
                                executionMode);
                    else
                        db.update(
                                "UPDATE ont_test_case SET"
                                    + " name=?,question=?,operation_id=?,input=?::jsonb,expected=?::jsonb,match_mode=?,enabled=?,mode=?"
                                    + " WHERE id=?",
                                n,
                                q,
                                op,
                                db.json(b.get("input")),
                                db.json(b.get("expected")),
                                mode,
                                en,
                                executionMode,
                                id);
                });
    }

    public Map<String, Object> delete(UUID u, UUID id, UUID s, long r) {
        return spaces.write(
                u,
                s,
                r,
                "TEST_CASE_DELETE",
                () -> {
                    OperationRefs.owned(db, "ont_test_case", id, s);
                    db.update("DELETE FROM ont_test_case WHERE id=?", id);
                });
    }
}
