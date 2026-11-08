package interview.guide.modules.resumedocument.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保存已确认的简历状态。version 用于乐观锁，避免旧数据覆盖新数据。
 */
public record UpdateResumeDocumentRequest(
    @NotBlank @Size(max = ResumeDocumentLimits.MAX_TITLE_LENGTH) String title,
    @Size(max = ResumeDocumentLimits.MAX_TEMPLATE_CODE_LENGTH) String templateCode,
    @NotNull @Valid ResumeContent content,
    @NotNull Long version
) {}
