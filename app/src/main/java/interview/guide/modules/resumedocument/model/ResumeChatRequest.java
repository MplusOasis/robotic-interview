package interview.guide.modules.resumedocument.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 对话输入。history 只用于让模型理解「刚才那条」等指代，不做服务端会话状态。
 */
public record ResumeChatRequest(
    @NotBlank @Size(max = ResumeDocumentLimits.MAX_MESSAGE_LENGTH) String message,
    @NotNull @Size(max = ResumeDocumentLimits.MAX_HISTORY_TURNS)
    List<@Valid ChatTurnDTO> history
) {}
