package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

/** 全局发布/溯源接口：跨可见空间聚合，权限仍由单空间服务复核。 */
class GlobalModuleTest extends WorkflowFixture {
    @Test
    void globalDeliveryAndTraceAggregateVisibleSpaces() throws Exception {
        String space = createSpace();
        createClass(space, "任务", null);
        freeze(space);
        var delivery = data(get("/onto/global/delivery"), null);
        assertTrue(delivery.path("candidates").size() >= 1);
        assertTrue(delivery.path("releases").isArray());
        var trace = data(get("/onto/global/trace"), null);
        assertTrue(trace.path("executions").isArray());
        assertTrue(trace.path("audit").size() >= 1);
    }
}
