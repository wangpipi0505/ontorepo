package cn.ontorepo.api;

import cn.ontorepo.space.application.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 除登录外接口默认要求真实会话；客户端不能通过身份请求头伪造操作者。 */
@Configuration
public class WebBoundary implements WebMvcConfigurer {
    private final AuthService auth;
    private final cn.ontorepo.space.application.PermissionService permissions;
    private final cn.ontorepo.release.application.ReleaseGatewayService gateway;

    public WebBoundary(
            AuthService auth,
            cn.ontorepo.release.application.ReleaseGatewayService gateway,
            cn.ontorepo.space.application.PermissionService permissions) {
        this.auth = auth;
        this.gateway = gateway;
        this.permissions = permissions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                String path = request.getRequestURI();
                                boolean consumer =
                                        path.matches(
                                                        "/onto/r/[^/]+/releases/[0-9a-fA-F-]{36}/ops/[^/]+")
                                                || path.matches("/onto/s/[^/]+/ops/[^/]+");
                                String key = request.getHeader("X-API-Key");
                                if (consumer && key != null) {
                                    var principal = gateway.authenticateKey(key);
                                    request.setAttribute("userId", principal.get("principalId"));
                                    request.setAttribute(
                                            "credentialSpaceId", principal.get("spaceId"));
                                    request.setAttribute("caller", "EXTERNAL");
                                } else {
                                    request.setAttribute(
                                            "userId",
                                            auth.authenticate(request.getHeader("Authorization")));
                                    request.setAttribute("caller", "SERVICE");
                                }
                                // 以 MVC 实际选中的处理方法判定，不能用原始 URI 前缀作权限开关：
                                // 矩阵参数与百分号编码会被路由归一化，否则同一业务方法可能绕过检查。
                                // springdoc 仍须上方会话认证；其余新处理方法未声明权限则默认拒绝。
                                if (handler
                                                instanceof
                                                org.springframework.web.method.HandlerMethod method
                                        && !method.getBeanType()
                                                .getName()
                                                .startsWith("org.springdoc.")) {
                                    var rule = method.getMethodAnnotation(RequiresPermission.class);
                                    if (rule == null)
                                        throw new cn.ontorepo.common.domain.Problem(
                                                403, "PERMISSION_DENIED", "接口尚未声明功能权限");
                                    if (!rule.value().isEmpty())
                                        permissions.require(
                                                cn.ontorepo.common.domain.Input.uuid(
                                                        request.getAttribute("userId")),
                                                rule.value());
                                }
                                return true;
                            }
                        })
                .addPathPatterns("/onto/**", "/v3/api-docs/**")
                .excludePathPatterns("/onto/auth/login");
    }
}
