package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Candidate;
import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.ApiDtos.Execution;
import cn.ontorepo.api.ApiDtos.Operation;
import cn.ontorepo.api.ApiDtos.Revision;
import cn.ontorepo.api.ApiDtos.Trial;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.trace.application.CandidateService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 候选读操作列表来自冻结载荷，保证前端切换旧候选时不混用新草稿。 */
@RestController
@RequestMapping("/onto")
public class CandidateController {
    private final CandidateService candidates;
    private final Transport t;

    public CandidateController(CandidateService candidates, Transport t) {
        this.candidates = candidates;
        this.t = t;
    }

    /** 列出可见冻结候选。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "列出可见冻结候选")
    @RequiresPermission("candidate.read")
    @GetMapping("/spaces/{id}/candidates")
    public Envelope<List<Candidate>> list(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.list(candidates.list(t.user(r), id), Candidate.class));
    }

    /** 冻结完整草稿载荷并计算摘要。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "冻结完整草稿载荷并计算摘要")
    @RequiresPermission("candidate.write")
    @PostMapping("/spaces/{id}/candidates")
    public Envelope<Candidate> freeze(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Revision b) {
        return Envelope.ok(
                t.as(
                        candidates.freeze(
                                t.user(r), id, Input.number(t.body(b), "expectedDraftRev")),
                        Candidate.class));
    }

    /** 从摘要复核后的候选读取操作。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "从摘要复核后的候选读取操作")
    @RequiresPermission("candidate.read")
    @GetMapping("/candidates/{id}/operations")
    public Envelope<List<Operation>> operations(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.list(candidates.operations(t.user(r), id), Operation.class));
    }

    /** 对冻结模型试执行并记录完整执行事实。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "对冻结模型试执行并记录完整执行事实")
    @RequiresPermission("candidate.execute")
    @PostMapping("/candidates/{id}/trial")
    public Envelope<Execution> trial(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Trial b) {
        return Envelope.ok(
                t.as(
                        candidates.trial(
                                t.user(r),
                                id,
                                Input.uuid(b.operationId()),
                                b.input() == null
                                        ? java.util.Map.of()
                                        : t.as(b.input(), Object.class)),
                        Execution.class));
    }

    /** 查询空间执行记录，可按入口/结果/操作筛选并分页。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "查询空间执行记录")
    @RequiresPermission("trace.read")
    @GetMapping("/spaces/{id}/executions")
    public Envelope<List<Execution>> executions(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam(required = false) String caller,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String opCode,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return Envelope.ok(
                t.list(
                        candidates.executions(t.user(r), id, caller, status, opCode, limit, offset),
                        Execution.class));
    }
}
