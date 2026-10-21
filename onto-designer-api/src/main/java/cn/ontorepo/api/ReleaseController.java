package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.release.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 发布、消费、追溯传输边界；业务JSON根类型不在HTTP层收窄。 */
@RestController
@RequestMapping("/onto")
public class ReleaseController {
    private final PublicationService publication;
    private final ReleaseHistoryService history;
    private final ReleaseLifecycleService lifecycle;
    private final ReleaseGatewayService gateway;
    private final ReleaseEventService events;
    private final TraceAuditService trace;
    private final Transport t;

    public ReleaseController(
            PublicationService publication,
            ReleaseHistoryService history,
            ReleaseLifecycleService lifecycle,
            ReleaseGatewayService gateway,
            ReleaseEventService events,
            TraceAuditService trace,
            Transport t) {
        this.publication = publication;
        this.history = history;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.events = events;
        this.trace = trace;
        this.t = t;
    }

    @RequiresPermission("release.read")
    @GetMapping("/candidates/{id}/publication")
    public Envelope<?> publication(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(publication.publication(t.user(r), id));
    }

    @RequiresPermission("release.write")
    @PostMapping("/candidates/{id}/validate")
    public Envelope<?> validate(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(publication.validate(t.user(r), id));
    }

    @RequiresPermission("release.write")
    @PostMapping("/candidates/{id}/review")
    public Envelope<?> review(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(publication.review(t.user(r), id, b));
    }

    @RequiresPermission("release.write")
    @PostMapping("/candidates/{id}/prepare")
    public Envelope<?> prepare(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(publication.prepare(t.user(r), id, b));
    }

    @RequiresPermission("release.write")
    @PostMapping("/candidates/{id}/commit")
    public Envelope<?> commit(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(publication.commit(t.user(r), id, b));
    }

    @RequiresPermission("release.write")
    @PostMapping("/candidates/{id}/cancel")
    public Envelope<?> cancel(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(publication.cancel(t.user(r), id, b));
    }

    @RequiresPermission("release.read")
    @GetMapping("/candidates/{id}/diff")
    public Envelope<?> candidateDiff(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(history.candidateDiff(t.user(r), id));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases")
    public Envelope<?> releases(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(history.list(t.user(r), spaceId));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/{id}")
    public Envelope<?> release(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(history.detail(t.user(r), id));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/{id}/entities")
    public Envelope<?> entities(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam(required = false) String kind) {
        return Envelope.ok(history.entities(t.user(r), id, kind));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/diff")
    public Envelope<?> diff(
            HttpServletRequest r,
            @RequestParam UUID spaceId,
            @RequestParam UUID fromReleaseId,
            @RequestParam UUID toReleaseId) {
        return Envelope.ok(history.diff(t.user(r), spaceId, fromReleaseId, toReleaseId));
    }

    @RequiresPermission("release.write")
    @PostMapping("/releases/{id}/restore-draft")
    public Envelope<?> restore(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(
                history.restore(
                        t.user(r),
                        id,
                        Input.number(b, "expectedDraftRev"),
                        Input.text(b, "reason", 4000)));
    }

    @RequiresPermission("release.write")
    @PostMapping("/releases/{id}/deprecate")
    public Envelope<?> deprecate(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(lifecycle.deprecate(t.user(r), id, Input.text(b, "reason", 4000)));
    }

    @RequiresPermission("release.write")
    @PostMapping("/releases/{id}/integrity-check")
    public Envelope<?> integrity(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(lifecycle.integrity(t.user(r), id));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/{id}/manifest")
    public Envelope<?> manifest(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(lifecycle.consume(t.user(r), id, "manifestRef"));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/{id}/context-pack")
    public Envelope<?> context(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(lifecycle.consume(t.user(r), id, "contextPackRef"));
    }

    @RequiresPermission("release.read")
    @GetMapping("/releases/{id}/endpoints")
    public Envelope<?> endpoints(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(gateway.endpoints(t.user(r), id));
    }

    @RequiresPermission("grant.read")
    @GetMapping("/endpoints/{id}/grants")
    public Envelope<?> grants(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(gateway.grants(t.user(r), id));
    }

    @RequiresPermission("grant.write")
    @PutMapping("/endpoints/{id}/grants/{userId}")
    public Envelope<?> grant(
            HttpServletRequest r,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestBody Map<String, Object> b) {
        return Envelope.ok(gateway.saveGrant(t.user(r), id, userId, b));
    }

    @RequiresPermission("grant.read")
    @GetMapping("/api-credentials")
    public Envelope<?> credentials(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(gateway.credentials(t.user(r), spaceId));
    }

    @RequiresPermission("grant.write")
    @PostMapping("/api-credentials")
    public Envelope<?> credential(HttpServletRequest r, @RequestBody Map<String, Object> b) {
        return Envelope.ok(gateway.createCredential(t.user(r), b));
    }

    @RequiresPermission("grant.write")
    @PostMapping("/api-credentials/{id}/revoke")
    public Envelope<?> revoke(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(gateway.revoke(t.user(r), id));
    }

    @RequiresPermission("service.execute")
    @PostMapping("/internal/releases/{releaseId}/ops/{opCode}")
    public Map<String, Object> internal(
            HttpServletRequest r,
            HttpServletResponse response,
            @PathVariable UUID releaseId,
            @PathVariable String opCode,
            @RequestBody(required = false) JsonNode body) {
        return invoke(r, response, releaseId, opCode, null, body);
    }

    @RequiresPermission("service.execute")
    @PostMapping("/r/{spaceCode}/releases/{releaseId}/ops/{opCode}")
    public Map<String, Object> canonical(
            HttpServletRequest r,
            HttpServletResponse response,
            @PathVariable String spaceCode,
            @PathVariable UUID releaseId,
            @PathVariable String opCode,
            @RequestBody(required = false) JsonNode body) {
        return invoke(r, response, releaseId, opCode, spaceCode, body);
    }

    @RequiresPermission("service.execute")
    @PostMapping("/s/{spaceCode}/ops/{opCode}")
    public Map<String, Object> alias(
            HttpServletRequest r,
            HttpServletResponse response,
            @PathVariable String spaceCode,
            @RequestParam UUID releaseId,
            @PathVariable String opCode,
            @RequestBody(required = false) JsonNode body) {
        return invoke(r, response, releaseId, opCode, spaceCode, body);
    }

    private Map<String, Object> invoke(
            HttpServletRequest r,
            HttpServletResponse response,
            UUID release,
            String code,
            String spaceCode,
            JsonNode body) {
        var result =
                gateway.invoke(
                        t.user(r),
                        release,
                        code,
                        t.as(body, Object.class),
                        Objects.toString(r.getAttribute("caller"), "SERVICE"),
                        r.getAttribute("credentialSpaceId"),
                        spaceCode);
        response.setHeader("X-Ontology-Release", release.toString());
        return result;
    }

    @RequiresPermission("trace.read")
    @GetMapping("/events")
    public Envelope<?> events(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(events.list(t.user(r), spaceId));
    }

    @RequiresPermission("trace.read")
    @GetMapping("/events/inbox")
    public Envelope<?> inbox(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(events.inbox(t.user(r), spaceId));
    }

    @RequiresPermission("release.write")
    @PostMapping("/events/{id}/replay")
    public Envelope<?> replay(
            HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
        return Envelope.ok(events.replay(t.user(r), id, b));
    }

    @RequiresPermission("trace.read")
    @GetMapping("/executions/{id}/trace")
    public Envelope<?> trace(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(trace.trace(t.user(r), id));
    }

    @RequiresPermission("trace.read")
    @GetMapping("/audit")
    public Envelope<?> audit(
            HttpServletRequest r,
            @RequestParam UUID spaceId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeId) {
        return Envelope.ok(trace.audit(t.user(r), spaceId, limit, beforeId));
    }
}
