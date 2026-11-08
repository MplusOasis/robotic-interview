package interview.guide.common.ai;

/**
 * 用户级 AI 调用上下文。
 *
 * <p><b>安全约定</b>：本对象会被传入业务方法、异步任务与缓存 Key 计算，因此</p>
 * <ul>
 *   <li><b>绝不</b>持有明文 API Key、密文、Base URL 或任何模型客户端对象；</li>
 *   <li>只保存「谁、用哪个供应商、哪个模型、凭证第几版」这四项定位信息；</li>
 *   <li>可以安全地出现在日志、异常信息与线程间传递中。</li>
 * </ul>
 *
 * <p>真正的解密动作只发生在 {@code LlmProviderRegistry} 构建客户端的那一次，
 * 且仅在缓存未命中时执行。</p>
 *
 * @param userId            业务数据所有者 ID（同步请求来自 SecurityContext，异步任务来自业务资源）
 * @param providerKey       用户配置的供应商标识（如 {@code deepseek}）
 * @param modelName         用户配置的模型名称
 * @param credentialVersion 用户凭证版本号；写操作递增，用于缓存失效
 */
public record AiExecutionContext(
    Long userId,
    String providerKey,
    String modelName,
    long credentialVersion
) {

    public AiExecutionContext {
        if (userId == null) {
            throw new IllegalArgumentException("AiExecutionContext.userId 不能为空");
        }
        if (providerKey == null || providerKey.isBlank()) {
            throw new IllegalArgumentException("AiExecutionContext.providerKey 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("AiExecutionContext.modelName 不能为空");
        }
    }

    /**
     * 缓存作用域前缀：{@code userId::providerKey::modelName::credentialVersion}。
     *
     * <p>四个维度缺一不可：只用 providerKey 会让不同用户共享客户端；
     * 只用 userId 会让用户改 Key 后继续命中旧客户端。</p>
     */
    public String cacheScope() {
        return userId + "::" + providerKey + "::" + modelName + "::" + credentialVersion;
    }

    /** 缓存失效时按用户批量清理用的前缀。 */
    public static String userPrefix(Long userId) {
        return userId + "::";
    }

    /** 缓存失效时按「用户 + 供应商」批量清理用的前缀。 */
    public static String userProviderPrefix(Long userId, String providerKey) {
        return userId + "::" + providerKey + "::";
    }
}
