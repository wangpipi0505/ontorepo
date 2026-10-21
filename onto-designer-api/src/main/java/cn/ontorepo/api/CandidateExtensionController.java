package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.P1Dtos.*;
import cn.ontorepo.trace.application.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 候选用例与检查入口；检查读取冻结载荷，豁免仅适用于实际超范围并要求OWNER。 */
@RestController
@RequestMapping("/onto/candidates")
public class CandidateExtensionController {
    private final CandidateTestService tests;
    private final CandidateCheckService checks;
    private final Transport t;
    private final cn.ontorepo.release.application.PublicationService publication;

    public CandidateExtensionController(
            CandidateTestService tests,
            CandidateCheckService checks,
            Transport t,
            cn.ontorepo.release.application.PublicationService publication) {
        this.tests = tests;
        this.checks = checks;
        this.t = t;
        this.publication = publication;
    }

    /** 只执行候选冻结时启用的用例。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "执行冻结候选用例")
    @RequiresPermission("test.execute")
    @PostMapping("/{id}/tests")
    public Envelope<Tests> tests(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(publication.tests(t.user(r), id), Tests.class));
    }

    /** 执行初期冲突、一致性、用例检查，eligible不代表正式发布资格。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "执行候选初期三项检查")
    @RequiresPermission("candidate.write")
    @PostMapping("/{id}/checks")
    public Envelope<Checks> checks(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(checks.checks(t.user(r), id), Checks.class));
    }

    /** 审批真实NOT_CHECKED(SUBSET_EXCEEDED)，不允许豁免超时、不一致和引擎错误。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "OWNER审批超范围构造豁免")
    @RequiresPermission("release.write")
    @PostMapping("/{id}/waivers")
    public Envelope<Waiver> waiver(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody WaiverRequest b) {
        return Envelope.ok(t.as(checks.waiver(t.user(r), id, t.body(b)), Waiver.class));
    }
}
