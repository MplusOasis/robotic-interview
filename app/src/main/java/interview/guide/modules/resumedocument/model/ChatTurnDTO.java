package interview.guide.modules.resumedocument.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 最近对话轮次，role 仅允许 user / assistant。
 */
public record ChatTurnDTO(
    @NotBlank @Size(max = 16) String role,
    @Size(max = ResumeDocumentLimits.MAX_MESSAGE_LENGTH) String content
) {}
