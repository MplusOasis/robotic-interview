package interview.guide.common.ai;

/**
 * 用户凭证变更事件。
 *
 * <p>凭证写操作（保存 / 改模型 / 删除）后发布，用于主动失效该用户的模型客户端缓存。
 * 事件只携带定位信息，<b>不含</b>任何明文或密文凭证。</p>
 *
 * @param userId      凭证所属用户
 * @param providerKey 变更的供应商标识
 */
public record UserCredentialChangedEvent(Long userId, String providerKey) {

    public static UserCredentialChangedEvent of(Long userId, String providerKey) {
        return new UserCredentialChangedEvent(userId, providerKey);
    }
}
