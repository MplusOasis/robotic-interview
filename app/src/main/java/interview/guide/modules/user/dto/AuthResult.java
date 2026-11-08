package interview.guide.modules.user.dto;

/**
 * 登录/注册成功后的结果。
 *
 * <p>{@code accessToken} 仅在服务端关闭 Cookie 传输模式时才会被序列化返回
 * （见 {@link interview.guide.modules.user.controller.AuthController}），
 * 默认走 HttpOnly Cookie，前端拿不到令牌字符串。</p>
 */
public record AuthResult(String accessToken, long expiresInSeconds, UserDTO user) {
}
