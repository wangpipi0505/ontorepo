package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource source;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("onto.release.relay-enabled", () -> false);
        r.add("onto.release.integrity-enabled", () -> false);
        r.add("onto.release.freeze-recovery-enabled", () -> false);
        // 差异化Mock/Spy会缓存多个Spring上下文；每池默认保留10连接会耗尽真实PG的100连接。
        // P4持锁、等待请求、活动诊断需要3连接；既有Workflow四worker需4，统一上限覆盖二者。
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        r.add("spring.datasource.hikari.minimum-idle", () -> 0);
        for (String part : new String[] {"URL", "USER", "PASSWORD"}) {
            String value = System.getenv("ONTO_TEST_DB_" + part);
            if (value == null || value.isBlank())
                throw new IllegalStateException("真实PG测试必须设置 ONTO_TEST_DB_" + part);
            r.add(
                    "spring.datasource."
                            + (part.equals("URL")
                                    ? "url"
                                    : part.equals("USER") ? "username" : "password"),
                    () -> value);
        }
    }

    @Test
    void cachedTestContextsKeepBoundedDatabasePools() throws Exception {
        var pool = source.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        assertAll(
                () ->
                        assertTrue(
                                pool.getMaximumPoolSize() <= 4,
                                "缓存多个真实PG测试上下文时，每池上限必须受控，实际" + pool.getMaximumPoolSize()),
                () -> assertEquals(0, pool.getMinimumIdle(), "串行测试的闲置上下文不能各自保留默认10条空闲连接"));
    }

    @Test
    void anonymousCannotReadSpaces() throws Exception {
        mvc.perform(get("/onto/spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
