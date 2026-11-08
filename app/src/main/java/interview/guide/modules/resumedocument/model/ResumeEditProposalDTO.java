package interview.guide.modules.resumedocument.model;

import java.util.List;

/**
 * AI 对话修改提案。AI 只生成提案，绝不直接写库；baseVersion 由后端填入，
 * 用户确认时后端会重新校验身份、版本与提案内容。
 */
public record ResumeEditProposalDTO(
    String reply,
    List<String> changeSummary,
    List<ResumeEditOperationDTO> operations,
    boolean needsClarification,
    String clarificationQuestion,
    Long baseVersion
) {}
