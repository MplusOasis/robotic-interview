package interview.guide.modules.resumedocument.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 由原始上传简历初始化在线编辑版本。幂等：已存在时直接返回已有文档。
 */
public record InitFromResumeRequest(
    @NotNull Long resumeId,
    @Size(max = ResumeDocumentLimits.MAX_TITLE_LENGTH) String title
) {}
