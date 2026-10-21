package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.*;
import cn.ontorepo.api.P1Dtos.Strategy;
import cn.ontorepo.api.P1Dtos.StrategyWrite;
import cn.ontorepo.operation.application.StrategyService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Strategy HTTP 边界；身份只从已验证会话读取，控制器不直接访问数据库。 */
@RestController
@RequestMapping("/onto/strategies")
public class StrategyController {
    private final StrategyService service;
    private final Transport t;

    public StrategyController(StrategyService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    /** 列表受空间成员权限限制。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取Strategy列表")
    @RequiresPermission("rule.read")
    @GetMapping
    public Envelope<List<Strategy>> list(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(service.list(t.user(r), spaceId), Strategy.class));
    }

    /** 创建内容与修订、审计同事务提交。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建Strategy")
    @RequiresPermission("rule.write")
    @PostMapping
    public Envelope<Space> create(HttpServletRequest r, @RequestBody StrategyWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新失败保留原草稿。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新Strategy")
    @RequiresPermission("rule.write")
    @PutMapping("/{id}")
    public Envelope<Space> update(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody StrategyWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除校验所属空间和引用。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除Strategy")
    @RequiresPermission("rule.write")
    @DeleteMapping("/{id}")
    public Envelope<Space> delete(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(
                t.as(service.delete(t.user(r), id, spaceId, expectedDraftRev), Space.class));
    }
}
