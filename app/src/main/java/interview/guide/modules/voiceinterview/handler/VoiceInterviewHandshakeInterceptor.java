package interview.guide.modules.voiceinterview.handler;

import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 语音面试 WebSocket 握手鉴权。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>握手阶段就完成身份认证，<b>不依赖</b>客户端在消息体里声明的 userId。</li>
 *   <li>建立连接前校验目标会话归属，订阅他人会话直接拒绝（404）。</li>
 *   <li>把可信的 userId / sessionId 写入 WebSocket 会话属性，供后续消息处理使用。</li>
 * </ul>
 *
 * <p>身份来源：Spring Security 过滤器链已在握手 HTTP 请求上完成 JWT 解析，
 * 因此这里只从 {@link CurrentUserProvider} 读取，不解析任何客户端参数。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {

    /** 会话属性键：认证后的用户 ID。 */
    public static final String ATTR_USER_ID = "authUserId";

    /** 会话属性键：已校验归属的语音会话 ID。 */
    public static final String ATTR_VOICE_SESSION_ID = "voiceSessionId";

    private final VoiceInterviewSessionRepository sessionRepository;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {
        Long userId = currentUserProvider.userIdOrNull();
        if (userId == null) {
            log.warn("WebSocket 握手被拒绝：未认证, path={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long voiceSessionId = extractSessionId(request.getURI().getPath());
        if (voiceSessionId == null) {
            log.warn("WebSocket 握手被拒绝：会话 ID 非法, path={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        boolean owned = sessionRepository.findByIdAndUserId(voiceSessionId, userId).isPresent();
        if (!owned) {
            // 跨用户订阅与"不存在"返回同一状态码，避免资源枚举
            log.warn("WebSocket 握手被拒绝：会话不属于当前用户, userId={}, sessionId={}",
                userId, voiceSessionId);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_VOICE_SESSION_ID, voiceSessionId);
        log.info("WebSocket 握手通过: userId={}, sessionId={}", userId, voiceSessionId);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler,
                               Exception exception) {
        // 无额外处理
    }

    private Long extractSessionId(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(path.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
