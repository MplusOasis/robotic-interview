package interview.guide.modules.resumedocument.model;

import java.util.List;

/**
 * LLM 结构化输出契约（不含 baseVersion，版本由后端填入，模型不得臆造）。
 * 单独定义而非嵌套私有类型，便于对 AI 输出做可重复的校验测试。
 */
public record LlmProposalDTO(
    String reply,
    List<String> changeSummary,
    boolean needsClarification,
    String clarificationQuestion,
    List<ResumeEditOperationDTO> operations
) {}
