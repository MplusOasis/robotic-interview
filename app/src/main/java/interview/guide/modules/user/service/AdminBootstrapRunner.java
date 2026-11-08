package interview.guide.modules.user.service;

import interview.guide.common.security.AuthProperties;
import interview.guide.modules.user.model.AppUserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 管理员账号受控初始化。
 *
 * <p>只有在环境变量显式提供 {@code APP_AUTH_ADMIN_ENABLED=true} 且
 * {@code APP_AUTH_ADMIN_USERNAME} / {@code APP_AUTH_ADMIN_PASSWORD} 均已提供时才会执行。
 * 源码、配置文件与数据库迁移脚本中<b>不存在</b>任何默认管理员密码。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AuthProperties authProperties;
    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.AdminBootstrap config = authProperties.getAdminBootstrap();
        if (!config.isEnabled()) {
            return;
        }
        String username = config.getUsername();
        String password = config.getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("管理员初始化已启用但缺少 APP_AUTH_ADMIN_USERNAME / APP_AUTH_ADMIN_PASSWORD，已跳过");
            return;
        }
        if (password.trim().length() < MIN_PASSWORD_LENGTH) {
            log.warn("管理员初始化密码长度不足 {} 位，已拒绝创建（请使用更强的随机密码）", MIN_PASSWORD_LENGTH);
            return;
        }
        AppUserEntity admin = authService.createOrUpdateAdmin(username, password, config.getEmail());
        // 只记录账号标识，绝不记录密码。
        log.info("管理员账号已就绪: userId={}, username={}", admin.getId(), admin.getUsername());
    }
}
