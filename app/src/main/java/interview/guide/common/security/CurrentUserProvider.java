package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户上下文。
 *
 * <p><b>唯一可信来源</b>是 {@link SecurityContextHolder}，由
 * {@link JwtAuthenticationFilter} 在请求进入时根据服务端签发的 JWT 写入。
 * 严禁从请求参数、请求头（如历史遗留的 {@code X-User-Id}）或请求体读取用户身份。</p>
 */
@Component
public class CurrentUserProvider {

    /**
     * 获取当前登录用户；未认证返回 {@code null}（供异步/可选场景使用）。
     */
    public AuthenticatedUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    /**
     * 获取当前登录用户 ID；未认证直接抛出未授权，避免出现"无用户"的匿名数据归属。
     */
    public Long requireUserId() {
        AuthenticatedUser user = currentUserOrNull();
        if (user == null || user.userId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未获取到登录用户，请重新登录");
        }
        return user.userId();
    }

    /**
     * 获取当前登录用户 ID；未认证返回 {@code null}（仅限后台任务/可选鉴权场景）。
     */
    public Long userIdOrNull() {
        AuthenticatedUser user = currentUserOrNull();
        return user == null ? null : user.userId();
    }

    public boolean isAdmin() {
        AuthenticatedUser user = currentUserOrNull();
        return user != null && user.isAdmin();
    }

    public String requireUsername() {
        AuthenticatedUser user = currentUserOrNull();
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未获取到登录用户，请重新登录");
        }
        return user.username();
    }
}
