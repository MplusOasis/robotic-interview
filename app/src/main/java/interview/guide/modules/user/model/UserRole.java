package interview.guide.modules.user.model;

/**
 * 平台角色。
 *
 * <p>注册接口只能生成 {@link #USER}；{@link #ADMIN} 仅能通过受控的管理员初始化机制产生。</p>
 */
public enum UserRole {
    USER,
    ADMIN
}
