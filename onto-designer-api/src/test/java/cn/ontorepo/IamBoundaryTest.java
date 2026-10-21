package cn.ontorepo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;

/** 系统管理入口必须真实存在且默认拒绝普通业务成员，不能仅靠前端隐藏菜单。 */
class IamBoundaryTest extends WorkflowFixture {
    @Test
    void ordinaryMemberCannotReadSystemRoles() throws Exception {
        call(get("/onto/system/roles"), ownerToken, null, 403);
    }
}
