package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 JWT（HS256）签发与校验。
 *
 * <p>不引入额外 JWT 依赖，使用 JDK 自带 HMAC-SHA256 实现，避免与 Spring Boot 4 /
 * Spring Security 7 的依赖版本产生冲突。令牌只承载身份标识（sub/usr/rol），
 * 不承载任何凭证、权限细节或业务数据。</p>
 */
@Slf4j
@Component
public class JwtTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final int MIN_SECRET_LENGTH = 32;

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final byte[] secretBytes;

    public JwtTokenService(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.secretBytes = resolveSecret(authProperties.getJwt().getSecret());
    }

    private byte[] resolveSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            // 不提供任何默认值：缺失密钥必须让应用启动失败，避免用弱密钥签发令牌。
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "APP_AUTH_JWT_SECRET 未配置，无法初始化 JWT 令牌服务");
        }
        String trimmed = secret.trim();
        if (trimmed.length() < MIN_SECRET_LENGTH) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "APP_AUTH_JWT_SECRET 长度不足 " + MIN_SECRET_LENGTH + " 字符，请使用随机长字符串");
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 签发访问令牌。
     */
    public String issue(Long userId, String username, String role) {
        AuthProperties.Jwt jwt = authProperties.getJwt();
        long now = Instant.now().getEpochSecond();
        long exp = now + Math.max(1, jwt.getTtl().toSeconds());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("usr", username);
        payload.put("rol", role);
        payload.put("iss", jwt.getIssuer());
        payload.put("iat", now);
        payload.put("exp", exp);

        String header = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String body = URL_ENCODER.encodeToString(
            objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        String signature = URL_ENCODER.encodeToString(hmac(signingInput));
        return signingInput + "." + signature;
    }

    /**
     * 校验并解析令牌。任何异常都归一化为 {@link ErrorCode#AUTH_TOKEN_INVALID}，
     * 不把底层异常信息（可能包含令牌片段）透传给调用方。
     */
    public AuthenticatedUser parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] actual;
        try {
            actual = URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        Map<String, Object> payload;
        try {
            String json = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            payload = objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        Object exp = payload.get("exp");
        if (!(exp instanceof Number expNumber)
            || expNumber.longValue() <= Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        Object sub = payload.get("sub");
        Object usr = payload.get("usr");
        Object rol = payload.get("rol");
        if (sub == null || usr == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        Long userId;
        try {
            userId = Long.valueOf(String.valueOf(sub));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        String role = rol == null ? AuthenticatedUser.ROLE_USER : String.valueOf(rol);
        return new AuthenticatedUser(userId, String.valueOf(usr), role);
    }

    public long ttlSeconds() {
        return Math.max(1, authProperties.getJwt().getTtl().toSeconds());
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "JWT 签名计算失败");
        }
    }
}
