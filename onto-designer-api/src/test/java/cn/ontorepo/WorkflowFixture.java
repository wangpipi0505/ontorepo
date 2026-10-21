package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.ontorepo.space.application.AuthService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 所有业务用例连接真实 PostgreSQL；直接 SQL 仅建立未对外开放的成员授权与损坏场景。 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class WorkflowFixture {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        BoundaryTest.database(r);
    }

    UUID owner, viewer, stranger;
    String ownerToken, viewerToken, strangerToken;
    static final String QUERY =
            "PREFIX owl: <http://www.w3.org/2002/07/owl#> PREFIX rdfs:"
                    + " <http://www.w3.org/2000/01/rdf-schema#> SELECT ?class ?name WHERE {?class a"
                    + " owl:Class; rdfs:label ?name} ORDER BY ?name";

    @BeforeEach
    void setup() {
        db.execute("TRUNCATE local_user,ontology_space CASCADE");
        // 自定义角色没有到用户表的反向外键，不会随用户 TRUNCATE 清理；保留迁移内置角色，
        // 清除上轮测试配置，确保整套与重复执行均从同一权限目录开始。
        db.update("DELETE FROM local_role WHERE NOT built_in");
        owner = UUID.randomUUID();
        viewer = UUID.randomUUID();
        stranger = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(4).encode("test-password-123");
        for (var item :
                List.of(
                        Map.entry(owner, "owner"),
                        Map.entry(viewer, "viewer"),
                        Map.entry(stranger, "stranger")))
            db.update(
                    "INSERT INTO local_user(id,username,display_name,password_hash)"
                        + " VALUES(?,?,?,?)",
                    item.getKey(),
                    item.getValue(),
                    item.getValue(),
                    hash);
        // 这些既有用例测试空间权限；显式授予业务功能，避免把无角色默认拒绝误当成原空间校验。
        db.update(
                "INSERT INTO user_role(user_id,role_id) SELECT u.id,r.id FROM local_user u CROSS"
                    + " JOIN local_role r WHERE r.code='MEMBER'");
        ownerToken = session(owner);
        viewerToken = session(viewer);
        strangerToken = session(stranger);
    }

    String session(UUID id) {
        String token = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,now()+interval"
                        + " '1 hour')",
                AuthService.digest(token.getBytes(StandardCharsets.UTF_8)),
                id);
        return token;
    }

    JsonNode call(MockHttpServletRequestBuilder request, String token, Object body, int expected)
            throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null)
            request.contentType("application/json").content(json.writeValueAsBytes(body));
        MvcResult result = mvc.perform(request).andReturn();
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(expected, result.getResponse().getStatus(), raw);
        return json.readTree(raw);
    }

    JsonNode data(MockHttpServletRequestBuilder r, Object b) throws Exception {
        return call(r, ownerToken, b, 200).get("data");
    }

    String createSpace() throws Exception {
        String code = "s" + UUID.randomUUID().toString().replace("-", "");
        return data(
                        post("/onto/spaces"),
                        Map.of(
                                "code",
                                code,
                                "name",
                                "测试空间",
                                "namespaceUri",
                                "https://example.org/" + code + "/",
                                "domain",
                                "测试"))
                .get("id")
                .asText();
    }

    long rev(String s) throws Exception {
        return data(get("/onto/spaces/" + s), null).get("draftRev").asLong();
    }

    Map<String, Object> cl(String space, String name, String parent, long rev) {
        Map<String, Object> b = new HashMap<>();
        b.put("spaceId", space);
        b.put("name", name);
        b.put("parentId", parent);
        b.put("description", "说明");
        b.put("deprecated", false);
        b.put("expectedDraftRev", rev);
        return b;
    }

    String createClass(String space, String name, String parent) throws Exception {
        data(post("/onto/classes"), cl(space, name, parent, rev(space)));
        for (JsonNode n : data(get("/onto/classes").param("spaceId", space), null))
            if (n.get("name").asText().equals(name)) return n.get("id").asText();
        throw new AssertionError();
    }

    String createOperation(String space, int max) throws Exception {
        data(
                post("/onto/operations"),
                Map.of(
                        "spaceId",
                        space,
                        "code",
                        "listClasses",
                        "name",
                        "列出对象",
                        "queryTemplate",
                        QUERY,
                        "maxRows",
                        max,
                        "timeoutMs",
                        1000,
                        "expectedDraftRev",
                        rev(space)));
        return data(get("/onto/operations").param("spaceId", space), null)
                .get(0)
                .get("id")
                .asText();
    }

    JsonNode freeze(String s) throws Exception {
        return data(post("/onto/spaces/" + s + "/candidates"), Map.of("expectedDraftRev", rev(s)));
    }

    void grant(String s, UUID user, String role) {
        db.update("INSERT INTO space_grant VALUES(?,?,?)", UUID.fromString(s), user, role);
    }
}
