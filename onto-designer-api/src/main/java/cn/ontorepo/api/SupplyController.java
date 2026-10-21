package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.contract.ContractValidator;
import cn.ontorepo.operation.application.AssistantService;
import cn.ontorepo.trace.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 助手声明、冻结契约及内部调用边界；消费校验仅验证，不创建任何正式发布数据。 */
@RestController
@RequestMapping("/onto")
public class SupplyController {
    private final AssistantService assistants;
    private final CandidateSupplyService previews;
    private final CandidateService candidates;
    private final Transport t;

    public SupplyController(
            AssistantService assistants,
            CandidateSupplyService previews,
            CandidateService candidates,
            Transport t) {
        this.assistants = assistants;
        this.previews = previews;
        this.candidates = candidates;
        this.t = t;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "读取独立助手三段声明")
    @RequiresPermission("operation.read")
    @GetMapping("/operations/{id}/assistant")
    public Envelope<P2Dtos.AssistantDeclaration> assistant(
            HttpServletRequest r, @PathVariable UUID id, @RequestParam UUID spaceId) {
        return Envelope.ok(
                t.as(assistants.get(t.user(r), id, spaceId), P2Dtos.AssistantDeclaration.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "保存助手声明草稿")
    @RequiresPermission("operation.write")
    @PutMapping("/operations/{id}/assistant")
    public Envelope<ApiDtos.Space> save(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P2Dtos.AssistantWrite b) {
        return Envelope.ok(t.as(assistants.save(t.user(r), id, t.body(b)), ApiDtos.Space.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "编译冻结候选契约预览")
    @RequiresPermission("candidate.read")
    @GetMapping("/candidates/{id}/contracts")
    public Envelope<P2Dtos.CandidateContracts> contracts(
            HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(
                t.as(previews.contracts(t.user(r), id), P2Dtos.CandidateContracts.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "校验完整正式消费契约")
    @RequiresPermission("operation.read")
    @PostMapping("/contracts/validate")
    public Envelope<P2Dtos.ValidationResult> validate(@RequestBody JsonNode manifest) {
        ContractValidator.validateImport(manifest);
        return Envelope.ok(new P2Dtos.ValidationResult(true));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "按冻结操作编码调用候选服务")
    @RequiresPermission("service.execute")
    @PostMapping("/internal/candidates/{id}/ops/{opCode}")
    public P2Dtos.ServiceEnvelope invoke(
            HttpServletRequest r,
            @PathVariable UUID id,
            @PathVariable String opCode,
            @RequestBody
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @io.swagger.v3.oas.annotations.media.Schema(
                                                            implementation = Object.class,
                                                            types = {
                                                                "object", "array", "string",
                                                                "number", "boolean", "null"
                                                            })))
                    JsonNode input) {
        return t.as(
                candidates.service(t.user(r), id, opCode, t.as(input, Object.class)),
                P2Dtos.ServiceEnvelope.class);
    }
}
