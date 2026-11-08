package interview.guide.modules.usercredential.model;

/**
 * 用户凭证状态。
 */
public enum CredentialStatus {
    /** 可用。 */
    ACTIVE,
    /** 调用时被供应商判定无效。 */
    INVALID,
    /** 用户主动停用。 */
    DISABLED
}
