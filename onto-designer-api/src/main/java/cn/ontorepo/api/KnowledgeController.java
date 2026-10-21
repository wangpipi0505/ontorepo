package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.knowledge.application.KnowledgeService;
import cn.ontorepo.trace.application.CandidateSupplyService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 知识草稿和冻结检索边界；候选API只消费摘要校验后的不可变知识。 */
@RestController
@RequestMapping("/onto")
public class KnowledgeController {
    private final KnowledgeService service;
    private final CandidateSupplyService candidates;
    private final Transport t;

    public KnowledgeController(
            KnowledgeService service, CandidateSupplyService candidates, Transport t) {
        this.service = service;
        this.candidates = candidates;
        this.t = t;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "读取空间知识词条")
    @RequiresPermission("knowledge.read")
    @GetMapping("/knowledge")
    public Envelope<List<P2Dtos.KnowledgeEntry>> list(
            HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(service.list(t.user(r), spaceId), P2Dtos.KnowledgeEntry.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "创建知识词条")
    @RequiresPermission("knowledge.write")
    @PostMapping("/knowledge")
    public Envelope<ApiDtos.Space> create(
            HttpServletRequest r, @RequestBody P2Dtos.KnowledgeWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), null, t.body(b)), ApiDtos.Space.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "更新知识词条")
    @RequiresPermission("knowledge.write")
    @PutMapping("/knowledge/{id}")
    public Envelope<ApiDtos.Space> save(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P2Dtos.KnowledgeWrite b) {
        return Envelope.ok(t.as(service.save(t.user(r), id, t.body(b)), ApiDtos.Space.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "删除知识草稿词条")
    @RequiresPermission("knowledge.write")
    @DeleteMapping("/knowledge/{id}")
    public Envelope<ApiDtos.Space> delete(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(
                t.as(
                        service.delete(t.user(r), id, spaceId, expectedDraftRev),
                        ApiDtos.Space.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "读取冻结候选上下文包")
    @RequiresPermission("knowledge.read")
    @GetMapping("/candidates/{id}/context-pack")
    public Envelope<P2Dtos.ContextPack> context(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(candidates.context(t.user(r), id), P2Dtos.ContextPack.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lucene中文检索冻结知识")
    @RequiresPermission("knowledge.read")
    @GetMapping("/candidates/{id}/knowledge-search")
    public Envelope<P2Dtos.KnowledgeSearch> search(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        return Envelope.ok(
                t.as(candidates.search(t.user(r), id, q, limit), P2Dtos.KnowledgeSearch.class));
    }
}
