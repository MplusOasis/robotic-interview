package interview.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置。
 *
 * <p>以 {@link CorsConfigurationSource} 形式暴露，交由 Spring Security 的
 * {@code http.cors()} 统一处理，保证预检请求（OPTIONS）在过滤链中被正确放行，
 * 避免 Security 先于 CORS 生效导致跨域请求 401。</p>
 *
 * <p>{@code allowCredentials(true)} 与通配符 {@code *} 互斥，因此 origin 必须显式配置。</p>
 */
@Configuration
public class CorsConfig {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        String allowedOrigins = corsProperties.getAllowedOrigins();
        Arrays.stream(allowedOrigins.split(","))
              .map(String::trim)
              .filter(origin -> !origin.isEmpty())
              .forEach(config::addAllowedOrigin);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }
}
