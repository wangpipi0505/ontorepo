package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.*;
import cn.ontorepo.api.P1Dtos.*;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.model.application.*;
import cn.ontorepo.operation.application.ConflictService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 模型扩展只负责 DTO 转换和已校验身份传递。 */
@RestController
@RequestMapping("/onto")
public class ModelExtensionController {
    private final PropertyPolicy properties;
    private final ImportService imports;
    private final ConflictService conflicts;
    private final Transport t;

    public ModelExtensionController(
            PropertyPolicy properties,
            ImportService imports,
            ConflictService conflicts,
            Transport t) {
        this.properties = properties;
        this.imports = imports;
        this.conflicts = conflicts;
        this.t = t;
    }

    /** 检查属性变更/删除的现有引用。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取属性引用影响")
    @RequiresPermission("model.read")
    @GetMapping("/properties/{id}/impact")
    public Envelope<Impact> impact(
            HttpServletRequest r, @PathVariable UUID id, @RequestParam UUID spaceId) {
        return Envelope.ok(t.as(properties.impact(t.user(r), spaceId, id), Impact.class));
    }

    /** 预览不修改草稿，确认前允许核对错误和超范围构造。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "预览TTL或OWL导入")
    @RequiresPermission("model.write")
    @PostMapping("/spaces/{id}/imports/preview")
    public Envelope<ImportPreview> preview(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody ImportRequest b) {
        return Envelope.ok(t.as(imports.preview(t.user(r), id, t.body(b)), ImportPreview.class));
    }

    /** 确认依原预览修订应用，过期必须重新预览。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "确认应用导入预览")
    @RequiresPermission("model.write")
    @PostMapping("/imports/{id}/apply")
    public Envelope<Space> apply(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Revision b) {
        return Envelope.ok(
                t.as(
                        imports.apply(t.user(r), id, Input.number(t.body(b), "expectedDraftRev")),
                        Space.class));
    }

    /** 导出保留不支持构造，不能静默丢弃原图。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "导出完整TTL图")
    @RequiresPermission("model.read")
    @GetMapping("/spaces/{id}/export")
    public Envelope<Export> export(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(imports.export(t.user(r), id), Export.class));
    }

    /** 草稿冲突报告；候选检查另从不可变载荷读取。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "检查草稿四类冲突")
    @RequiresPermission("rule.read")
    @GetMapping("/spaces/{id}/conflicts")
    public Envelope<Conflicts> conflicts(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(conflicts.draft(t.user(r), id), Conflicts.class));
    }
}
