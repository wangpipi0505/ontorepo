package cn.ontorepo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * R12 墓碑审计：对象弃用（false→true）与恢复（true→false）以独立审计动作留痕
 * （实体 + 理由 + 前后值 + 操作者），普通编辑仍是 CLASS_UPDATE；弃用缺理由被
 * 拒绝；审计与业务变更同事务（rev 匹配）。
 */
class R12TombstoneAuditTest extends WorkflowFixture {

    /** POST /classes 返回 Space 汇总，类 id 需从类树回查（与基线夹具同口径）。 */
    private String createClass(String spaceId, String name, boolean deprecated, Long rev)
            throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("spaceId", spaceId);
        body.put("name", name);
        body.put("deprecated", deprecated);
        // saveClass 的 Input.number 对缺失键同样抛"必须为整数"，创建也必须携带当前修订。
        body.put("expectedDraftRev", rev == null ? rev(spaceId) : rev);
        data(post("/onto/classes"), body);
        for (JsonNode n : data(get("/onto/classes").param("spaceId", spaceId), null))
            if (n.get("name").asText().equals(name)) return n.get("id").asText();
        throw new AssertionError("类创建后未在类树中找到：" + name);
    }

    private Map<String, Object> audit(String action, String classId) {
        return db.queryForMap(
                "SELECT actor_id,action,draft_rev,subject_type,subject_id,version_refs FROM"
                        + " audit_log WHERE action=? AND subject_id=?",
                action,
                classId);
    }

    @org.junit.jupiter.api.Test
    void deprecateWritesTombstoneAuditWithReasonInSameTransaction() throws Exception {
        String spaceId = createSpace();
        String classId = createClass(spaceId, "卫星平台", false, null);

        long rev0 = rev(spaceId);
        // 弃用缺理由 → 400 DEPRECATE_REASON_REQUIRED，且不产生墓碑审计行
        call(
                put("/onto/classes/" + classId),
                ownerToken,
                Map.of("spaceId", spaceId, "name", "卫星平台", "deprecated", true,
                        "expectedDraftRev", rev0),
                400);
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE subject_id=?",
                        Integer.class,
                        classId));

        // 带理由弃用 → 成功，审计落 CLASS_DEPRECATE（操作者/实体/理由/前后值）
        call(
                put("/onto/classes/" + classId),
                ownerToken,
                Map.of("spaceId", spaceId, "name", "卫星平台", "deprecated", true,
                        "deprecateReason", "已由新平台结构替代",
                        "expectedDraftRev", rev0),
                200);
        var tombstone = audit("CLASS_DEPRECATE", classId);
        org.junit.jupiter.api.Assertions.assertEquals(owner, tombstone.get("actor_id"));
        org.junit.jupiter.api.Assertions.assertEquals("CLASS", tombstone.get("subject_type"));
        JsonNode refs = json.readTree(String.valueOf(tombstone.get("version_refs")));
        org.junit.jupiter.api.Assertions.assertEquals("已由新平台结构替代", refs.path("reason").asText());
        org.junit.jupiter.api.Assertions.assertFalse(refs.path("from").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(refs.path("to").asBoolean());
        long deprecateRev = ((Number) tombstone.get("draft_rev")).longValue();
        // 审计 rev 与业务变更后的空间修订一致（同事务证据）
        org.junit.jupiter.api.Assertions.assertEquals(rev(spaceId), deprecateRev);

        // 恢复 → CLASS_RESTORE（无需理由），同实体完整往返可追溯
        long rev1 = rev(spaceId);
        call(
                put("/onto/classes/" + classId),
                ownerToken,
                Map.of("spaceId", spaceId, "name", "卫星平台", "deprecated", false,
                        "expectedDraftRev", rev1),
                200);
        var restore = audit("CLASS_RESTORE", classId);
        org.junit.jupiter.api.Assertions.assertEquals(rev(spaceId),
                ((Number) restore.get("draft_rev")).longValue());
    }

    @org.junit.jupiter.api.Test
    void plainUpdateStaysClassUpdateWithoutTombstoneRow() throws Exception {
        String spaceId = createSpace();
        String classId = createClass(spaceId, "设备", false, null);
        long rev = rev(spaceId);
        call(
                put("/onto/classes/" + classId),
                ownerToken,
                Map.of("spaceId", spaceId, "name", "地面设备", "deprecated", false,
                        "expectedDraftRev", rev),
                200);
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action IN"
                                + " ('CLASS_DEPRECATE','CLASS_RESTORE') AND subject_id=?",
                        Integer.class,
                        classId));
    }
}
