package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.ApiDtos.Revision;
import cn.ontorepo.api.ApiDtos.Space;
import cn.ontorepo.api.ApiDtos.SpaceCreate;
import cn.ontorepo.api.ApiDtos.SpaceUpdate;
import cn.ontorepo.common.domain.Input;
import cn.ontorepo.space.application.SpaceService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 控制器仅处理传输类型和会话身份，写事务由应用服务管理。 */
@RestController
@RequestMapping("/onto/spaces")
public class SpaceController {
    private final SpaceService spaces;
    private final Transport t;

    public SpaceController(SpaceService spaces, Transport t) {
        this.spaces = spaces;
        this.t = t;
    }

    /** 列出当前用户可见的空间。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "列出当前用户可见的空间")
    @RequiresPermission("space.read")
    @GetMapping
    public Envelope<List<Space>> list(HttpServletRequest r) {
        return Envelope.ok(t.list(spaces.list(t.user(r)), Space.class));
    }

    /** 读取空间与模型计数。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取空间与模型计数")
    @RequiresPermission("space.read")
    @GetMapping("/{id}")
    public Envelope<Space> get(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(spaces.get(t.user(r), id), Space.class));
    }

    /** 创建命名空间不可变的空间。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建命名空间不可变的空间")
    @RequiresPermission("space.create")
    @PostMapping
    public Envelope<Space> create(HttpServletRequest r, @RequestBody SpaceCreate b) {
        return Envelope.ok(t.as(spaces.create(t.user(r), t.body(b)), Space.class));
    }

    /** 使用预期修订更新空间资料。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "使用预期修订更新空间资料")
    @RequiresPermission("space.write")
    @PutMapping("/{id}")
    public Envelope<Space> update(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody SpaceUpdate b) {
        return Envelope.ok(t.as(spaces.update(t.user(r), id, t.body(b)), Space.class));
    }

    /** 所有者归档空间并递增修订。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "所有者归档空间并递增修订")
    @RequiresPermission("space.write")
    @PostMapping("/{id}/archive")
    public Envelope<Space> archive(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody Revision b) {
        return Envelope.ok(
                t.as(
                        spaces.archive(t.user(r), id, Input.number(t.body(b), "expectedDraftRev")),
                        Space.class));
    }
}
