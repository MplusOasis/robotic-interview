package interview.guide.modules.usercredential.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存/更新用户凭证的请求体。
 *
 * <p>刻意<b>不</b>包含 {@code userId} 与 {@code baseUrl}：
 * <ul>
 *   <li>userId 只能来自服务端认证上下文，前端无权指定凭证归属；</li>
 *   <li>baseUrl 由服务端白名单解析，不接受用户输入。</li>
 * </ul>
 * </p>
 */
public record SaveCredentialRequest(
    @NotBlank(message = "供应商不能为空")
    @Size(max = 64, message = "供应商标识过长")
    String providerKey,

    @NotBlank(message = "API Key 不能为空")
    @Size(max = 512, message = "API Key 过长")
    String apiKey,

    @NotBlank(message = "模型名称不能为空")
    @Size(max = 128, message = "模型名称过长")
    String modelName
) {
}
