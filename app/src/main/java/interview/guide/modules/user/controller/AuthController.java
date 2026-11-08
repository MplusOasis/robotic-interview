package interview.guide.modules.user.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.common.security.AuthProperties;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.user.dto.AuthResult;
import interview.guide.modules.user.dto.LoginRequest;
import interview.guide.modules.user.dto.RegisterRequest;
import interview.guide.modules.user.dto.UserDTO;
import interview.guide.modules.user.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口：注册、登录、退出、当前用户、CSRF 令牌。
 *
 * <p>令牌传输：默认写入 HttpOnly + SameSite Cookie（JS 不可读），
 * 仅在服务端关闭 Cookie 模式（{@code app.auth.jwt.cookie-enabled=false}）时
 * 才把 accessToken 放进响应体，供脚本/移动端使用。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthProperties authProperties;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/register")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<UserDTO> register(@Valid @RequestBody RegisterRequest request,
                                    HttpServletResponse response) {
        AuthResult result = authService.register(request);
        writeAuthCookie(response, result, Duration.ofSeconds(result.expiresInSeconds()));
        return Result.success("注册成功", result.user());
    }

    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<UserDTO> login(@Valid @RequestBody LoginRequest request,
                                 HttpServletResponse response) {
        AuthResult result = authService.login(request);
        writeAuthCookie(response, result, Duration.ofSeconds(result.expiresInSeconds()));
        return Result.success("登录成功", result.user());
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        clearAuthCookie(response);
        return Result.success("已退出登录", null);
    }

    @GetMapping("/me")
    public Result<UserDTO> me() {
        Long userId = currentUserProvider.requireUserId();
        return Result.success(authService.requireCurrentUser(userId));
    }

    /**
     * 获取（或刷新）CSRF 令牌。
     *
     * <p>通过方法参数注入 {@link CsrfToken} 强制 Spring Security 立即解析令牌，
     * 从而在首次访问时就下发 XSRF-TOKEN Cookie，避免首个 POST 请求因缺少令牌被拒绝。</p>
     */
    @GetMapping("/csrf")
    public Result<Void> csrf(CsrfToken csrfToken) {
        // 触发令牌解析与 Cookie 写入，响应体不需要返回令牌本身。
        return Result.success();
    }

    private void writeAuthCookie(HttpServletResponse response, AuthResult result, Duration maxAge) {
        if (!authProperties.getJwt().isCookieEnabled()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(result.accessToken(), maxAge).toString());
    }

    private void clearAuthCookie(HttpServletResponse response) {
        if (!authProperties.getJwt().isCookieEnabled()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        AuthProperties.Jwt jwt = authProperties.getJwt();
        return ResponseCookie.from(jwt.getCookieName(), value)
            .httpOnly(true)
            .secure(jwt.isCookieSecure())
            .sameSite(jwt.getCookieSameSite())
            .path(jwt.getCookiePath())
            .maxAge(maxAge)
            .build();
    }
}
