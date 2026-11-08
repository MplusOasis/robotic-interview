package interview.guide.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证相关配置。
 *
 * <p>JWT 密钥必须来自环境变量 {@code APP_AUTH_JWT_SECRET}，且与
 * {@code APP_AI_CONFIG_ENCRYPTION_KEY}（Provider API Key 加密主密钥）完全分离：
 * 两者泄露的影响面不同，不能复用同一个密钥。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();

    /** 是否开放自助注册。关闭后仅管理员可创建账号。 */
    private boolean registrationEnabled = true;

    /** 管理员初始化配置，用于安全地创建首个管理员账号（禁止硬编码默认密码）。 */
    private AdminBootstrap adminBootstrap = new AdminBootstrap();

    @Data
    public static class Jwt {
        /** HMAC-SHA256 密钥，必须由环境变量提供，长度至少 32 字节。 */
        private String secret;
        /** 访问令牌有效期。 */
        private Duration ttl = Duration.ofHours(2);
        /** 签发者。 */
        private String issuer = "interview-guide";
        /** 存放 JWT 的 HttpOnly Cookie 名称。 */
        private String cookieName = "IG_ACCESS_TOKEN";
        /** 是否启用 HttpOnly Cookie 传输（关闭后仅接受 Authorization: Bearer）。 */
        private boolean cookieEnabled = true;
        /** Cookie 是否带 Secure 标记；生产必须为 true。 */
        private boolean cookieSecure = true;
        /** Cookie SameSite 策略。 */
        private String cookieSameSite = "Lax";
        /** Cookie 作用路径。 */
        private String cookiePath = "/";
        /**
         * CSRF 令牌 Cookie 名称。使用 Spring Security 约定的 XSRF-TOKEN，
         * 前端 axios 默认会自动读取并回写 X-XSRF-TOKEN 头。
         */
        private String csrfCookieName = "XSRF-TOKEN";
        private String csrfHeaderName = "X-XSRF-TOKEN";
    }

    @Data
    public static class AdminBootstrap {
        /** 是否在启动时按环境变量创建/修复管理员账号。 */
        private boolean enabled = false;
        private String username;
        private String password;
        private String email;
    }
}
