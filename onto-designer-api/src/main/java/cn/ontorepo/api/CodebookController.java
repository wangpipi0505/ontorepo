package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.*;
import cn.ontorepo.api.P1Dtos.Codebook;
import cn.ontorepo.api.P1Dtos.CodebookWrite;
import cn.ontorepo.model.application.CodebookService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Codebook HTTP 边界；身份只从已验证会话读取，控制器不直接访问数据库。 */
@RestController
@RequestMapping("/onto/codebooks")
public class CodebookController {
    private final CodebookService service;
    private final Transport t;

    public CodebookController(CodebookService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    /** 列表受空间成员权限限制。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取Codebook列表")
    @RequiresPermission("model.read")
    @GetMapping
    public Envelope<List<Codebook>> list(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(service.list(t.user(r), spaceId), Codebook.class));
    }

    /** 创建内容与修订、审计同事务提交。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建Codebook")
    @RequiresPermission("model.write")
    @PostMapping
    public Envelope<Space> create(HttpServletRequest r, @RequestBody CodebookWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新失败保留原草稿。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新Codebook")
    @RequiresPermission("model.write")
    @PutMapping("/{id}")
    public Envelope<Space> update(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody CodebookWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除校验所属空间和引用。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除Codebook")
    @RequiresPermission("model.write")
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
