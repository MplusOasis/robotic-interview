package interview.guide.modules.resumedocument.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 用户确认修改提案。后端会重新校验用户身份、简历版本与提案内容后才写库；
 * 用户取消时前端直接丢弃提案，不调用此接口，数据库不发生任何变化。
 */
public record ApplyProposalRequest(
    @NotNull @Valid ResumeEditProposalDTO proposal,
    @NotNull Long version
) {}
