package interview.guide.modules.usercredential.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 使用「未保存」的临时凭证做连接测试。
 *
 * <p>apiKey 仅用于本次请求，服务端不落库、不写日志、不回显。</p>
 */
public record TestCredentialRequest(
    @NotBlank(message = "供应商不能为空") String providerKey,
    @NotBlank(message = "API Key 不能为空") String apiKey,
    String modelName
) {
    @Override
    public String toString() {
        // 防止被误打印到日志
        return "TestCredentialRequest[providerKey=" + providerKey
            + ", modelName=" + modelName + ", apiKey=***]";
    }
}
