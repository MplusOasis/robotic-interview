package interview.guide.modules.usercredential.dto;

/**
 * 模型连接测试结果。
 *
 * <p><b>字段白名单</b>：只返回是否成功、供应商、模型、耗时与<b>已脱敏</b>的错误信息。
 * 禁止返回 API Key、密文、Base URL 或原始堆栈。</p>
 */
public record CredentialTestResultDTO(
    boolean success,
    String providerKey,
    String modelName,
    long latencyMs,
    String message,
    String errorCode
) {
}
