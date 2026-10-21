package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.space.application.GuardService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 守卫与授权的OWNER管理边界，目录永不返回密码或令牌信息。 */
@RestController
@RequestMapping("/onto/spaces/{id}")
public class GuardController {
    private final GuardService service;
    private final Transport t;

    public GuardController(GuardService service, Transport t) {
        this.service = service;
        this.t = t;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "读取空间守卫定义")
    @RequiresPermission("grant.read")
    @GetMapping("/guards")
    public Envelope<P2Dtos.Guards> guards(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.as(service.guards(t.user(r), id), P2Dtos.Guards.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "OWNER保存守卫定义")
    @RequiresPermission("grant.write")
    @PutMapping("/guards")
    public Envelope<ApiDtos.Space> guards(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody P2Dtos.GuardWrite b) {
        return Envelope.ok(t.as(service.saveGuards(t.user(r), id, t.body(b)), ApiDtos.Space.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "OWNER读取授权主体")
    @RequiresPermission("grant.read")
    @GetMapping("/grants")
    public Envelope<List<P2Dtos.SpaceGrant>> grants(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.list(service.grants(t.user(r), id), P2Dtos.SpaceGrant.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "OWNER读取本地可授权用户目录")
    @RequiresPermission("grant.read")
    @GetMapping("/grant-users")
    public Envelope<List<P2Dtos.GrantUser>> users(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.list(service.users(t.user(r), id), P2Dtos.GrantUser.class));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "OWNER修改授权和可信主体属性")
    @RequiresPermission("grant.write")
    @PutMapping("/grants/{userId}")
    public Envelope<ApiDtos.Space> grant(
            HttpServletRequest r,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestBody P2Dtos.GrantWrite b) {
        return Envelope.ok(
                t.as(service.saveGrant(t.user(r), id, userId, t.body(b)), ApiDtos.Space.class));
    }
}
