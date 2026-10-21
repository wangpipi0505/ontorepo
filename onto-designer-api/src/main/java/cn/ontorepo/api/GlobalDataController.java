package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.application.GlobalDataService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 全局知识与数据入口：只读聚合当前用户可见本体，不替代单空间明细。 */
@RestController
@RequestMapping("/onto")
public class GlobalDataController {
    private final GlobalDataService service;
    private final Transport t;

    public GlobalDataController(GlobalDataService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    @RequiresPermission("space.read")
    @GetMapping("/global/data")
    public Envelope<Map<String, Object>> data(HttpServletRequest r) {
        return Envelope.ok(service.data(t.user(r)));
    }
}
