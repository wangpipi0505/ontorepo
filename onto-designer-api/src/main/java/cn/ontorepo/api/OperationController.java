package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.ApiDtos.Operation;
import cn.ontorepo.api.ApiDtos.OperationWrite;
import cn.ontorepo.api.ApiDtos.Space;
import cn.ontorepo.operation.application.OperationService;

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
import java.util.UUID;

/** 本批仅公开模型查询操作，不接受发布或实例执行动作。 */
@RestController
@RequestMapping("/onto/operations")
public class OperationController {
    private final OperationService operations;
    private final Transport t;

    public OperationController(OperationService operations, Transport t) {
        this.operations = operations;
        this.t = t;
    }

    /** 读取草稿操作列表。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取草稿操作列表")
    @RequiresPermission("operation.read")
    @GetMapping
    public Envelope<List<Operation>> list(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(operations.list(t.user(r), spaceId), Operation.class));
    }

    /** 保存经过安全解析的SELECT操作。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "保存经过安全解析的SELECT操作")
    @RequiresPermission("operation.write")
    @PostMapping
    public Envelope<Space> create(HttpServletRequest r, @RequestBody OperationWrite b) {
        return Envelope.ok(t.as(operations.save(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新操作并校验预期修订。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新操作并校验预期修订")
    @RequiresPermission("operation.write")
    @PutMapping("/{id}")
    public Envelope<Space> update(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody OperationWrite b) {
        return Envelope.ok(t.as(operations.save(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除草稿操作但保留既有冻结内容。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除草稿操作但保留既有冻结内容")
    @RequiresPermission("operation.write")
    @DeleteMapping("/{id}")
    public Envelope<Space> delete(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(
                t.as(operations.delete(t.user(r), id, spaceId, expectedDraftRev), Space.class));
    }
}
