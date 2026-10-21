package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.ApiDtos.Login;
import cn.ontorepo.api.ApiDtos.LoginRequest;
import cn.ontorepo.api.ApiDtos.User;
import cn.ontorepo.space.application.AuthService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录、当前身份与会话即时撤销。 */
@RestController
@RequestMapping("/onto/auth")
public class AuthController {
    private final AuthService auth;
    private final Transport t;
    private final cn.ontorepo.space.application.PermissionService permissions;

    public AuthController(
            AuthService auth,
            Transport t,
            cn.ontorepo.space.application.PermissionService permissions) {
        this.auth = auth;
        this.t = t;
        this.permissions = permissions;
    }

    /** 登录并创建有限期本地会话。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "登录并创建有限期本地会话")
    @RequiresPermission("")
    @PostMapping("/login")
    public Envelope<Login> login(@RequestBody LoginRequest b) {
        return Envelope.ok(t.as(auth.login(t.body(b)), Login.class));
    }

    /** 读取当前校验通过的用户资料。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取当前校验通过的用户资料")
    @RequiresPermission("")
    @GetMapping("/me")
    public Envelope<User> me(HttpServletRequest r) {
        return Envelope.ok(t.as(auth.me(t.user(r)), User.class));
    }

    /** 撤销当前会话令牌。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "撤销当前会话令牌")
    @RequiresPermission("")
    @PostMapping("/logout")
    public Envelope<Void> logout(HttpServletRequest r) {
        auth.logout(r.getHeader("Authorization"));
        return Envelope.ok(null);
    }

    /** 返回当前有效权限并集，前端菜单与按钮只作体验优化，不能替代服务端检查。 */
    @RequiresPermission("")
    @GetMapping("/permissions")
    public Envelope<java.util.List<String>> permissions(HttpServletRequest r) {
        return Envelope.ok(permissions.effective(t.user(r)));
    }
}
