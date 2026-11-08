package interview.guide.modules.usercredential.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新用户凭证。
 *
 * <p>{@code apiKey} 为 {@code null} 或空白时表示"保持原 Key 不变"，
 * 仅更新其他字段；模型名称同理。</p>
 */
public record UpdateCredentialRequest(
    @Size(max = 512, message = "API Key 过长")
    String apiKey,

    @Size(max = 128, message = "模型名称过长")
    String modelName
) {
}
