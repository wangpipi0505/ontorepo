package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.IamDtos.*;
import cn.ontorepo.space.application.SystemAuditService;
import cn.ontorepo.space.application.SystemRoleService;
import cn.ontorepo.space.application.SystemUserService;
import cn.ontorepo.space.domain.PermissionCatalog;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 系统管理控制器只负责传输，写入授权、修订与审计由应用服务在同一事务中完成。 */
@RestController
@RequestMapping("/onto/system")
public class SystemController {
    private final SystemUserService users;
    private final SystemRoleService roles;
    private final SystemAuditService audit;
    private final Transport t;

    public SystemController(
            SystemUserService users,
            SystemRoleService roles,
            SystemAuditService audit,
            Transport t) {
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.t = t;
    }

    /** 返回真实服务端支持的权限目录。 */
    @RequiresPermission("system.permission.read")
    @GetMapping("/permissions")
    public Envelope<List<PermissionCatalog.Permission>> permissions() {
        return Envelope.ok(PermissionCatalog.all());
    }

    /** 用户列表不返回密码哈希、会话令牌或 API 凭证。 */
    @RequiresPermission("system.user.read")
    @GetMapping("/users")
    public Envelope<List<SystemUser>> users(HttpServletRequest r) {
        return Envelope.ok(t.list(users.list(t.user(r)), SystemUser.class));
    }

    /** 创建账号并显式分配角色。 */
    @RequiresPermission("system.user.write")
    @PostMapping("/users")
    public Envelope<SystemUser> createUser(HttpServletRequest r, @RequestBody UserCreate b) {
        return Envelope.ok(t.as(users.create(t.user(r), t.body(b)), SystemUser.class));
    }

    /** 修改用户状态与角色，同时校验预期修订。 */
    @RequiresPermission("system.user.write")
    @PutMapping("/users/{id}")
    public Envelope<SystemUser> updateUser(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody UserUpdate b) {
        return Envelope.ok(t.as(users.update(t.user(r), id, t.body(b)), SystemUser.class));
    }

    /** 重置密码并撤销已有会话。 */
    @RequiresPermission("system.user.write")
    @PostMapping("/users/{id}/password")
    public Envelope<SystemUser> password(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody PasswordReset b) {
        return Envelope.ok(t.as(users.resetPassword(t.user(r), id, t.body(b)), SystemUser.class));
    }

    /** 读取内置角色与自定义角色及实际权限。 */
    @RequiresPermission("system.role.read")
    @GetMapping("/roles")
    public Envelope<List<Role>> roles(HttpServletRequest r) {
        return Envelope.ok(t.list(roles.list(t.user(r)), Role.class));
    }

    /** 创建可配置的自定义角色。 */
    @RequiresPermission("system.role.write")
    @PostMapping("/roles")
    public Envelope<Role> createRole(HttpServletRequest r, @RequestBody RoleCreate b) {
        return Envelope.ok(t.as(roles.create(t.user(r), t.body(b)), Role.class));
    }

    /** 更新角色权限，后续请求立即按新配置判定。 */
    @RequiresPermission("system.role.write")
    @PutMapping("/roles/{id}")
    public Envelope<Role> updateRole(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody RoleUpdate b) {
        return Envelope.ok(t.as(roles.update(t.user(r), id, t.body(b)), Role.class));
    }

    /** 仅允许删除无人引用的自定义角色。 */
    @RequiresPermission("system.role.write")
    @DeleteMapping("/roles/{id}")
    public Envelope<Void> deleteRole(
            HttpServletRequest r, @PathVariable UUID id, @RequestParam long expectedRevision) {
        roles.delete(t.user(r), id, expectedRevision);
        return Envelope.ok(null);
    }

    /** 分页读取管理动作的安全审计快照。 */
    @RequiresPermission("system.audit.read")
    @GetMapping("/audit")
    public Envelope<AuditPage> audit(
            HttpServletRequest r,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Envelope.ok(t.as(audit.list(t.user(r), page, pageSize), AuditPage.class));
    }
}
