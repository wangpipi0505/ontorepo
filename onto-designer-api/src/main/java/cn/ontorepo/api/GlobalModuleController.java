package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.application.GlobalDeliveryService;
import cn.ontorepo.application.GlobalTraceService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 测试/发布与溯源审计的全局只读入口。 */
@RestController
@RequestMapping("/onto")
public class GlobalModuleController {
    private final GlobalDeliveryService delivery;
    private final GlobalTraceService trace;
    private final Transport t;

    public GlobalModuleController(
            GlobalDeliveryService delivery, GlobalTraceService trace, Transport t) {
        this.delivery = delivery;
        this.trace = trace;
        this.t = t;
    }

    @RequiresPermission("space.read")
    @GetMapping("/global/delivery")
    public Envelope<Map<String, Object>> delivery(HttpServletRequest r) {
        return Envelope.ok(delivery.data(t.user(r)));
    }

    @RequiresPermission("space.read")
    @GetMapping("/global/trace")
    public Envelope<Map<String, Object>> trace(HttpServletRequest r) {
        return Envelope.ok(trace.data(t.user(r)));
    }
}
