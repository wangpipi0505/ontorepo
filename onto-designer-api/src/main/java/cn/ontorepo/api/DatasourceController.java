package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.mapping.application.DatasourceService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 九类源管理HTTP边界；会话身份由统一拦截器注入。 */
@RestController
@RequestMapping("/onto/datasources")
public class DatasourceController {
    private final DatasourceService service;
    private final Transport t;

    public DatasourceController(DatasourceService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    @RequiresPermission("datasource.read")
    @GetMapping
    public Envelope<List<Map<String, Object>>> list(
            HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(service.list(t.user(r), spaceId));
    }

    @RequiresPermission("datasource.read")
    @GetMapping("/capabilities")
    public Envelope<List<Map<String, Object>>> capabilities(
            HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(service.capabilities(t.user(r), spaceId));
    }

    @RequiresPermission("datasource.write")
    @PostMapping
    public Envelope<Map<String, Object>> create(
            HttpServletRequest r, @RequestBody P3Dtos.SourceWrite b) {
        return Envelope.ok(service.save(t.user(r), null, t.body(b)));
    }

    @RequiresPermission("datasource.write")
    @PutMapping("/{id}")
    public Envelope<Map<String, Object>> save(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.SourceWrite b) {
        return Envelope.ok(service.save(t.user(r), id, t.body(b)));
    }

    @RequiresPermission("datasource.write")
    @DeleteMapping("/{id}")
    public Envelope<Map<String, Object>> delete(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(service.delete(t.user(r), id, spaceId, expectedDraftRev));
    }

    @RequiresPermission("datasource.write")
    @PostMapping("/{id}/probe")
    public Envelope<Map<String, Object>> probe(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.SpaceRequest b) {
        return Envelope.ok(service.probe(t.user(r), id, Input.uuid(b.spaceId())));
    }

    @RequiresPermission("datasource.write")
    @PostMapping("/{id}/sync")
    public Envelope<Map<String, Object>> sync(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.RevisionRequest b) {
        return Envelope.ok(
                service.sync(
                        t.user(r),
                        id,
                        Input.uuid(b.spaceId()),
                        Input.number(t.body(b), "expectedDraftRev")));
    }

    @RequiresPermission("datasource.read")
    @GetMapping("/{id}/snapshots")
    public Envelope<List<Map<String, Object>>> snapshots(
            HttpServletRequest r, @PathVariable UUID id, @RequestParam UUID spaceId) {
        return Envelope.ok(service.snapshots(t.user(r), spaceId, id));
    }

    @RequiresPermission("datasource.read")
    @GetMapping("/{id}/snapshots/{snapshotId}")
    public Envelope<Map<String, Object>> snapshot(
            HttpServletRequest r,
            @PathVariable UUID id,
            @PathVariable UUID snapshotId,
            @RequestParam UUID spaceId) {
        return Envelope.ok(service.snapshot(t.user(r), spaceId, id, snapshotId));
    }
}
