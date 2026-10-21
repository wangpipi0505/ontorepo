package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.*;
import cn.ontorepo.api.P1Dtos.Constraint;
import cn.ontorepo.api.P1Dtos.ConstraintWrite;
import cn.ontorepo.model.application.ConstraintService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Constraint HTTP 边界；身份只从已验证会话读取，控制器不直接访问数据库。 */
@RestController
@RequestMapping("/onto/constraints")
public class ConstraintController {
    private final ConstraintService service;
    private final Transport t;

    public ConstraintController(ConstraintService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    /** 列表受空间成员权限限制。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取Constraint列表")
    @RequiresPermission("rule.read")
    @GetMapping
    public Envelope<List<Constraint>> list(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(service.list(t.user(r), spaceId), Constraint.class));
    }

    /** 创建内容与修订、审计同事务提交。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建Constraint")
    @RequiresPermission("rule.write")
    @PostMapping
    public Envelope<Space> create(HttpServletRequest r, @RequestBody ConstraintWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新失败保留原草稿。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新Constraint")
    @RequiresPermission("rule.write")
    @PutMapping("/{id}")
    public Envelope<Space> update(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody ConstraintWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除校验所属空间和引用。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除Constraint")
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
