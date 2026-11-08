package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.AuthProperties;
import interview.guide.common.security.JwtTokenService;
import interview.guide.modules.user.dto.AuthResult;
import interview.guide.modules.user.dto.LoginRequest;
import interview.guide.modules.user.dto.RegisterRequest;
import interview.guide.modules.user.dto.UserDTO;
import interview.guide.modules.user.model.AppUserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.model.UserStatus;
import interview.guide.modules.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 认证服务：注册、登录、当前用户查询。
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>密码使用 BCrypt 单向哈希，永不回显、永不落日志。</li>
 *   <li>注册角色由服务端固定为 {@code USER}，请求体无法指定角色。</li>
 *   <li>登录失败统一返回 {@link ErrorCode#AUTH_INVALID_CREDENTIALS}，
 *       不区分"用户不存在"与"密码错误"，避免账号枚举。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用于在用户不存在时仍执行一次哈希比对，抹平响应时间差，防止账号枚举。 */
    private static final String DUMMY_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties authProperties;

    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (!authProperties.isRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.AUTH_REGISTER_DISABLED);
        }

        String username = normalizeUsername(request.username());
        if (appUserRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        String email = normalizeEmail(request.email());
        if (email != null && appUserRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.USER_EMAIL_ALREADY_EXISTS);
        }

        AppUserEntity user = AppUserEntity.builder()
            .username(username)
            .passwordHash(passwordEncoder.encode(request.password()))
            .email(email)
            // 关键：角色与状态由服务端决定，注册接口无法提权。
            .role(UserRole.USER)
            .status(UserStatus.ACTIVE)
            .build();
        AppUserEntity saved = appUserRepository.save(user);
        log.info("用户注册成功: userId={}, username={}", saved.getId(), saved.getUsername());
        return issue(saved);
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        String username = normalizeUsername(request.username());
        AppUserEntity user = appUserRepository.findByUsername(username).orElse(null);

        if (user == null) {
            // 执行一次等价开销的哈希比对，避免通过响应时间判断账号是否存在。
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        user.setLastLoginAt(LocalDateTime.now());
        appUserRepository.save(user);
        log.info("用户登录成功: userId={}", user.getId());
        return issue(user);
    }

    @Transactional(readOnly = true)
    public UserDTO requireCurrentUser(Long userId) {
        AppUserEntity user = appUserRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return UserDTO.from(user);
    }

    /**
     * 受控创建管理员账号，仅供 {@code AdminBootstrapRunner} 在启动时调用。
     *
     * <p>密码来自环境变量，源码与配置文件中不存在任何默认管理员密码。</p>
     */
    @Transactional
    public AppUserEntity createOrUpdateAdmin(String username, String rawPassword, String email) {
        String normalized = normalizeUsername(username);
        AppUserEntity existing = appUserRepository.findByUsername(normalized).orElse(null);
        if (existing != null) {
            existing.setRole(UserRole.ADMIN);
            existing.setStatus(UserStatus.ACTIVE);
            if (rawPassword != null && !rawPassword.isBlank()) {
                existing.setPasswordHash(passwordEncoder.encode(rawPassword));
            }
            return appUserRepository.save(existing);
        }
        AppUserEntity admin = AppUserEntity.builder()
            .username(normalized)
            .passwordHash(passwordEncoder.encode(rawPassword))
            .email(normalizeEmail(email))
            .role(UserRole.ADMIN)
            .status(UserStatus.ACTIVE)
            .build();
        return appUserRepository.save(admin);
    }

    private AuthResult issue(AppUserEntity user) {
        String token = jwtTokenService.issue(
            user.getId(), user.getUsername(), user.getRole().name());
        return new AuthResult(token, jwtTokenService.ttlSeconds(), UserDTO.from(user));
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
