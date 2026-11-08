package interview.guide.common.security;

/**
 * 认证上下文中的当前用户主体（Principal）。
 *
 * <p>只保存身份标识，不保存任何凭证。userId 唯一来源是服务端签发的 JWT，
 * 任何来自请求参数 / Header 的用户标识都不允许构造该对象。</p>
 *
 * @param userId   用户主键
 * @param username 用户名
 * @param role     角色，取值 {@code USER} / {@code ADMIN}
 */
public record AuthenticatedUser(Long userId, String username, String role) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
