package interview.guide.modules.resumedocument.model;

import jakarta.validation.constraints.Size;

/**
 * 新建空白简历。服务端从 SecurityContext 取 user_id，请求体不允许携带归属信息。
 */
public record CreateResumeDocumentRequest(
    @Size(max = ResumeDocumentLimits.MAX_TITLE_LENGTH) String title
) {}
