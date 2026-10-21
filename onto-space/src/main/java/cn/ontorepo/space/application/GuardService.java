package cn.ontorepo.space.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.contract.GuardAttributeName;
import cn.ontorepo.contract.GuardValidator;
import cn.ontorepo.data.domain.LabelScope;

import com.fasterxml.jackson.databind.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 空间守卫定义属于草稿，授权属性属于实时主体；两者不能互相代替。 */
@Service
public class GuardService {
    private final Db db;
    private final SpaceService spaces;
    private final cn.ontorepo.space.infra.SpaceAuthorizationGate authorization;
    private static final ObjectMapper JSON = new ObjectMapper();

    public GuardService(
            Db db,
            SpaceService spaces,
            cn.ontorepo.space.infra.SpaceAuthorizationGate authorization) {
        this.db = db;
        this.spaces = spaces;
        this.authorization = authorization;
    }

    private void owner(UUID u, UUID s) {
        if (!"OWNER".equals(spaces.role(u, s)))
            throw new Problem(403, "FORBIDDEN", "仅OWNER可管理守卫和授权");
    }

    public Map<String, Object> guards(UUID u, UUID s) {
        spaces.role(u, s);
        return Map.of(
                "guardCfg",
                db.one("SELECT guard_cfg FROM ontology_space WHERE id=?", s).get("guardCfg"));
    }

    @Transactional
    public Map<String, Object> saveGuards(UUID u, UUID s, Map<String, Object> b) {
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "GUARD_SAVE",
                () -> {
                    owner(u, s);
                    JsonNode cfg = JSON.valueToTree(b.get("guardCfg"));
                    if (!cfg.isObject() || cfg.size() > 64) throw Problem.bad("守卫配置必须为最多64项的对象");
                    cfg.fields()
                            .forEachRemaining(
                                    e -> {
                                        JsonNode d = e.getValue();
                                        if (!GuardAttributeName.valid(e.getKey())
                                                || !d.isObject()
                                                || !Set.of(
                                                                "STRING", "INTEGER", "DECIMAL",
                                                                "BOOLEAN")
                                                        .contains(d.path("type").asText()))
                                            throw Problem.bad("守卫名称或类型无效");
                                        // 草稿允许缺绑定，但显式提交的绑定必须结构正确，不能把任意代码当作绑定。
                                        if (d.has("authorization_rule")) {
                                            var r = d.path("authorization_rule");
                                            if (!r.isObject()
                                                    || !"PRINCIPAL_ATTRIBUTE"
                                                            .equals(r.path("kind").asText())
                                                    || !r.path("attribute").isTextual()
                                                    || !GuardAttributeName.valid(
                                                            r.path("attribute").asText()))
                                                throw Problem.bad("主体属性绑定无效");
                                        }
                                        if (d.has("filter_binding") || d.has("filter_bindings")) {
                                            if (!GuardValidator.validFilterBindings(d))
                                                throw Problem.bad("数据过滤绑定无效");
                                        }
                                    });
                    db.update(
                            "UPDATE ontology_space SET guard_cfg=?::jsonb WHERE id=?",
                            db.json(b.get("guardCfg")),
                            s);
                });
    }

    public List<Map<String, Object>> grants(UUID u, UUID s) {
        owner(u, s);
        return db.list(
                "SELECT g.user_id,u.username,u.display_name,g.role,g.data_scope,g.attributes FROM"
                    + " space_grant g JOIN local_user u ON u.id=g.user_id WHERE g.space_id=? ORDER"
                    + " BY u.username",
                s);
    }

    public List<Map<String, Object>> users(UUID u, UUID s) {
        owner(u, s);
        return db.list("SELECT id user_id,username,display_name FROM local_user ORDER BY username");
    }

    @Transactional
    public Map<String, Object> saveGrant(UUID u, UUID s, UUID target, Map<String, Object> b) {
        authorization.exclusive(s);
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "GRANT_SAVE",
                () -> {
                    owner(u, s);
                    db.one("SELECT id FROM local_user WHERE id=?", target);
                    String role = Input.text(b, "role", 10);
                    if (!Set.of("OWNER", "EDITOR", "VIEWER").contains(role))
                        throw Problem.bad("授权角色无效");
                    if (!role.equals("OWNER")
                            && !db.list(
                                            "SELECT 1 FROM space_grant WHERE space_id=? AND"
                                                    + " user_id=? AND role='OWNER'",
                                            s,
                                            target)
                                    .isEmpty()
                            && db.list(
                                                    "SELECT 1 FROM space_grant WHERE space_id=? AND"
                                                            + " role='OWNER'",
                                                    s)
                                            .size()
                                    == 1) throw new Problem(409, "LAST_OWNER", "不能移除最后一个OWNER");
                    JsonNode attrs = JSON.valueToTree(b.getOrDefault("attributes", Map.of()));
                    if (!attrs.isObject() || attrs.size() > 64) throw Problem.bad("主体属性必须为对象");
                    JsonNode cfg =
                            JSON.valueToTree(
                                    db.one("SELECT guard_cfg FROM ontology_space WHERE id=?", s)
                                            .get("guardCfg"));
                    attrs.fields()
                            .forEachRemaining(
                                    e -> {
                                        if (!GuardAttributeName.valid(e.getKey())
                                                || !e.getValue().isValueNode()
                                                || e.getValue().isNull()
                                                || e.getValue().isTextual()
                                                        && e.getValue().asText().isBlank())
                                            throw Problem.bad("主体属性必须是非空标量");
                                    });
                    cfg.fields()
                            .forEachRemaining(
                                    e -> {
                                        JsonNode d = e.getValue();
                                        String attr =
                                                d.path("authorization_rule")
                                                        .path("attribute")
                                                        .asText();
                                        if (attrs.has(attr))
                                            typed(d.path("type").asText(), attrs.path(attr));
                                    });
                    LabelScope.parse(JSON.valueToTree(b.get("dataScope")));
                    db.update(
                            "INSERT INTO space_grant(space_id,user_id,role,data_scope,attributes)"
                                + " VALUES(?,?,?,?::jsonb,?::jsonb) ON CONFLICT(space_id,user_id)"
                                + " DO UPDATE SET"
                                + " role=excluded.role,data_scope=excluded.data_scope,attributes=excluded.attributes",
                            s,
                            target,
                            role,
                            b.get("dataScope") == null ? null : db.json(b.get("dataScope")),
                            db.json(b.getOrDefault("attributes", Map.of())));
                });
    }

    /** 只有空间授权行存在时null范围才不限；读取发生在每次调用的空间锁内。 */
    public Map<String, Object> grant(UUID u, UUID s) {
        spaces.editor(u, s);
        return readGrant(u, s);
    }

    /** 正式端点另有显式授予，空间层只要求可读；VIEWER不能因此获得内部试执行权限。 */
    public Map<String, Object> readGrant(UUID u, UUID s) {
        spaces.role(u, s);
        return db.one(
                "SELECT data_scope,attributes FROM space_grant WHERE space_id=? AND user_id=?",
                s,
                u);
    }

    /** 严格JSON标量类型检查，禁止字符串数字或布尔值冒充受信属性。 */
    public static void typed(String type, JsonNode v) {
        boolean valid =
                switch (type) {
                    case "STRING" -> v.isTextual() && !v.asText().isBlank();
                    case "INTEGER" -> v.isIntegralNumber();
                    case "DECIMAL" -> v.isNumber();
                    case "BOOLEAN" -> v.isBoolean();
                    default -> false;
                };
        if (!valid) throw new Problem(403, "GUARD_ATTRIBUTE_INVALID", "主体守卫属性缺失或类型错误");
    }
}
