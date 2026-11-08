package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JWT 令牌服务")
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-chars-long!!";

    private AuthProperties properties;
    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getJwt().setTtl(Duration.ofMinutes(30));
        service = new JwtTokenService(properties, new ObjectMapper());
    }

    @Nested
    @DisplayName("签发与解析")
    class IssueAndParse {

        @Test
        @DisplayName("签发的令牌可以被正确解析回用户身份")
        void shouldRoundTrip() {
            String token = service.issue(42L, "alice", AuthenticatedUser.ROLE_USER);

            AuthenticatedUser user = service.parse(token);

            assertThat(user.userId()).isEqualTo(42L);
            assertThat(user.username()).isEqualTo("alice");
            assertThat(user.role()).isEqualTo(AuthenticatedUser.ROLE_USER);
            assertThat(user.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("管理员角色的令牌可识别为 ADMIN")
        void shouldRecognizeAdmin() {
            String token = service.issue(1L, "root", AuthenticatedUser.ROLE_ADMIN);
            assertThat(service.parse(token).isAdmin()).isTrue();
        }

        @Test
        @DisplayName("令牌中不包含任何密钥明文")
        void shouldNotEmbedSecret() {
            String token = service.issue(7L, "bob", AuthenticatedUser.ROLE_USER);
            assertThat(token).doesNotContain(SECRET);
        }
    }

    @Nested
    @DisplayName("安全校验")
    class SecurityChecks {

        @Test
        @DisplayName("被篡改的载荷会被拒绝")
        void shouldRejectTamperedPayload() {
            String token = service.issue(42L, "alice", AuthenticatedUser.ROLE_USER);
            String[] parts = token.split("\\.");
            // 换掉载荷，签名不再匹配
            String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2)
                + "AA." + parts[2];

            assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.getCode());
        }

        @Test
        @DisplayName("使用其他密钥签发的令牌会被拒绝")
        void shouldRejectForeignSignature() {
            AuthProperties other = new AuthProperties();
            other.getJwt().setSecret("another-secret-key-at-least-32-chars-long!!!!");
            JwtTokenService otherService = new JwtTokenService(other, new ObjectMapper());
            String foreignToken = otherService.issue(42L, "alice", AuthenticatedUser.ROLE_ADMIN);

            assertThatThrownBy(() -> service.parse(foreignToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.getCode());
        }

        @Test
        @DisplayName("过期令牌返回 AUTH_TOKEN_EXPIRED")
        void shouldRejectExpiredToken() throws InterruptedException {
            properties.getJwt().setTtl(Duration.ofSeconds(1));
            String expired = service.issue(42L, "alice", AuthenticatedUser.ROLE_USER);

            // 等待令牌真正过期（exp 精度为秒）
            Thread.sleep(1200L);

            assertThatThrownBy(() -> service.parse(expired))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED.getCode());
        }

        @Test
        @DisplayName("空令牌与格式错误的令牌被拒绝")
        void shouldRejectMalformed() {
            assertThatThrownBy(() -> service.parse(null)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.parse("")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.parse("a.b")).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("密钥配置")
    class SecretConfiguration {

        @Test
        @DisplayName("缺少密钥时拒绝启动，不使用任何默认密钥")
        void shouldFailFastWithoutSecret() {
            AuthProperties empty = new AuthProperties();
            empty.getJwt().setSecret("");

            assertThatThrownBy(() -> new JwtTokenService(empty, new ObjectMapper()))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("密钥过短时拒绝启动")
        void shouldRejectShortSecret() {
            AuthProperties weak = new AuthProperties();
            weak.getJwt().setSecret("short");

            assertThatThrownBy(() -> new JwtTokenService(weak, new ObjectMapper()))
                .isInstanceOf(BusinessException.class);
        }
    }
}
