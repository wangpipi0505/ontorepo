package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.mapping.application.MappingService;

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

import java.util.Map;
import java.util.UUID;

/** 三层映射与实例操作模板边界，控制器不生成SQL、不处理授权范围。 */
@RestController
@RequestMapping("/onto")
public class MappingController {
    private final MappingService service;
    private final Transport t;

    public MappingController(MappingService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    @RequiresPermission("mapping.read")
    @GetMapping("/mappings")
    public Envelope<Map<String, Object>> list(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(service.list(t.user(r), spaceId));
    }

    @RequiresPermission("mapping.read")
    @GetMapping("/mappings/validate")
    public Envelope<Map<String, Object>> validate(
            HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(service.validate(t.user(r), spaceId));
    }

    @RequiresPermission("mapping.read")
    @GetMapping("/mappings/impact")
    public Envelope<Map<String, Object>> impact(
            HttpServletRequest r,
            @RequestParam UUID spaceId,
            @RequestParam String kind,
            @RequestParam UUID id) {
        return Envelope.ok(service.impact(t.user(r), spaceId, kind, id));
    }

    @RequiresPermission("mapping.read")
    @GetMapping("/data-catalog")
    public Envelope<Map<String, Object>> catalog(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(service.catalog(t.user(r), spaceId));
    }

    /** 按空间路径提供目录别名，语义与 /data-catalog 完全一致。 */
    @RequiresPermission("mapping.read")
    @GetMapping("/spaces/{id}/catalog")
    public Envelope<Map<String, Object>> catalogBySpace(
            HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(service.catalog(t.user(r), id));
    }

    @RequiresPermission("mapping.write")
    @PostMapping("/mappings/objects")
    public Envelope<Map<String, Object>> object(
            HttpServletRequest r, @RequestBody P3Dtos.ObjectWrite b) {
        return Envelope.ok(service.save(t.user(r), null, "objects", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @PutMapping("/mappings/objects/{id}")
    public Envelope<Map<String, Object>> object(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.ObjectWrite b) {
        return Envelope.ok(service.save(t.user(r), id, "objects", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @PostMapping("/mappings/properties")
    public Envelope<Map<String, Object>> property(
            HttpServletRequest r, @RequestBody P3Dtos.PropertyWrite b) {
        return Envelope.ok(service.save(t.user(r), null, "properties", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @PutMapping("/mappings/properties/{id}")
    public Envelope<Map<String, Object>> property(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.PropertyWrite b) {
        return Envelope.ok(service.save(t.user(r), id, "properties", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @PostMapping("/mappings/relations")
    public Envelope<Map<String, Object>> relation(
            HttpServletRequest r, @RequestBody P3Dtos.RelationWrite b) {
        return Envelope.ok(service.save(t.user(r), null, "relations", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @PutMapping("/mappings/relations/{id}")
    public Envelope<Map<String, Object>> relation(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.RelationWrite b) {
        return Envelope.ok(service.save(t.user(r), id, "relations", t.body(b)));
    }

    @RequiresPermission("mapping.write")
    @DeleteMapping("/mappings/{category}/{id}")
    public Envelope<Map<String, Object>> delete(
            HttpServletRequest r,
            @PathVariable String category,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(service.delete(t.user(r), id, category, spaceId, expectedDraftRev));
    }

    @RequiresPermission("mapping.read")
    @GetMapping("/operations/{id}/data-query")
    public Envelope<Map<String, Object>> query(
            HttpServletRequest r, @PathVariable UUID id, @RequestParam UUID spaceId) {
        return Envelope.ok(service.dataQuery(t.user(r), spaceId, id));
    }

    @RequiresPermission("mapping.write")
    @PutMapping("/operations/{id}/data-query")
    public Envelope<Map<String, Object>> query(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P3Dtos.DataQueryWrite b) {
        return Envelope.ok(service.saveDataQuery(t.user(r), id, t.body(b)));
    }
}
