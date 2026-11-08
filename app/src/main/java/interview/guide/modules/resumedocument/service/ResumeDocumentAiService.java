package interview.guide.modules.resumedocument.service;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.resumedocument.model.ChatTurnDTO;
import interview.guide.modules.resumedocument.model.LlmProposalDTO;
import interview.guide.modules.resumedocument.model.ResumeChatRequest;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentEntity;
import interview.guide.modules.resumedocument.model.ResumeDocumentLimits;
import interview.guide.modules.resumedocument.model.ResumeEditOperationDTO;
import interview.guide.modules.resumedocument.model.ResumeEditProposalDTO;
import interview.guide.modules.usercredential.service.UserCredentialService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线简历 AI 对话修改：只生成「增量修改提案」，绝不直接写数据库。
 *
 * <p>模型客户端只来自当前登录用户自己的 BYOK 凭证（requireChatContext →
 * AiExecutionContext → getPlainChatClient），不存在平台/开发者 Key 回退。
 * 生成的提案经 {@link ResumeProposalApplier#validate} 校验后才返回前端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDocumentAiService {

    private final UserCredentialService userCredentialService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final ResumeDocumentService documentService;
    private final ResumeContentCodec contentCodec;
    private final ResumeProposalApplier proposalApplier;
    private final PromptSanitizer promptSanitizer;
    private final ResumeDocumentProperties properties;
    private final ResourceLoader resourceLoader;

    private PromptTemplate systemPromptTemplate;
    private PromptTemplate userPromptTemplate;
    private BeanOutputConverter<LlmProposalDTO> outputConverter;

    @PostConstruct
    void init() throws IOException {
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(LlmProposalDTO.class);
    }

    /**
     * 生成修改提案。用户未配置可用凭证时由 requireChatContext 原样抛出 13001。
     */
    public ResumeEditProposalDTO generate(Long documentId, ResumeChatRequest request) {
        // 1. 身份与数据归属（查询条件带 user_id）
        ResumeDocumentEntity document = documentService.requireOwned(documentId);
        ResumeContent content = contentCodec.normalize(
            contentCodec.deserialize(document.getContent()));

        // 2. 只取当前用户自己的模型凭证，无平台 Key 兜底
        AiExecutionContext context = userCredentialService.requireChatContext();
        ChatClient chatClient = llmProviderRegistry.getPlainChatClient(context);

        // 3. 构造 Prompt（用户输入与数据均做注入清洗和边界包裹）
        String systemPromptWithFormat = systemPromptTemplate.render()
            + "\n\n" + outputConverter.getFormat();
        String userPrompt = buildUserPrompt(document, content, request);

        // 4. 结构化输出（invoker 自带防注入、schema 校验、JSON 修复与重试）
        LlmProposalDTO llmResult;
        try {
            llmResult = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.RESUME_EDIT_AI_FAILED,
                "AI 生成修改提案失败：",
                "简历对话修改",
                log
            );
        } catch (BusinessException e) {
            // 凭证 / 供应商类错误原样上抛，禁止降级成空提案掩盖配置问题
            throw e;
        } catch (Exception e) {
            log.error("简历 AI 对话失败: documentId={}, error={}", documentId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.RESUME_EDIT_AI_FAILED, "AI 生成修改提案失败");
        }
        if (llmResult == null) {
            throw new BusinessException(ErrorCode.RESUME_EDIT_AI_FAILED, "AI 未返回有效的修改提案");
        }

        // 5. 后端校验提案后才返回前端展示；用户确认时还会再校验一次
        ResumeEditProposalDTO proposal = toProposal(llmResult, document.getVersion());
        proposalApplier.validate(content, proposal);
        log.info("已生成简历修改提案: documentId={}, userId={}, operations={}, needsClarification={}",
            documentId, context.userId(),
            proposal.operations() == null ? 0 : proposal.operations().size(),
            proposal.needsClarification());
        return proposal;
    }

    private String buildUserPrompt(ResumeDocumentEntity document, ResumeContent content,
                                   ResumeChatRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("currentResume", wrap("resume", contentCodec.serialize(content)));
        variables.put("sourceResumeExcerpt",
            wrap("source", documentService.sourceExcerpt(document)));
        variables.put("chatHistory", wrap("history", formatHistory(request.history())));
        variables.put("userMessage", wrap("instruction", request.message()));
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION
            + "\n" + userPromptTemplate.render(variables);
    }

    private String wrap(String label, String text) {
        String safe = text == null ? "" : text;
        return promptSanitizer.wrapWithDelimiters(label,
            promptSanitizer.sanitize(clip(safe, ResumeDocumentLimits.MAX_LONG_TEXT * 3)));
    }

    private String formatHistory(List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) {
            return "（无）";
        }
        List<String> lines = new ArrayList<>();
        for (ChatTurnDTO turn : history) {
            if (turn == null) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(turn.role()) ? "助手" : "用户";
            String text = turn.content() == null ? "" : turn.content();
            lines.add(role + "：" + clip(text, ResumeDocumentLimits.MAX_MESSAGE_LENGTH));
        }
        return lines.isEmpty() ? "（无）" : String.join("\n", lines);
    }

    private ResumeEditProposalDTO toProposal(LlmProposalDTO llm, Long baseVersion) {
        boolean clarification = llm.needsClarification();
        List<ResumeEditOperationDTO> operations = clarification || llm.operations() == null
            ? List.of() : llm.operations();
        List<String> summaries = llm.changeSummary() == null
            ? List.of() : llm.changeSummary();
        String reply = isBlank(llm.reply())
            ? (clarification ? "还需要一些信息才能继续。" : "已生成修改提案，请确认后保存。")
            : clip(llm.reply(), ResumeDocumentLimits.MAX_MESSAGE_LENGTH);
        String question = clarification
            ? clip(isBlank(llm.clarificationQuestion())
                ? "请补充必要信息后再试。" : llm.clarificationQuestion(), 1000)
            : null;
        return new ResumeEditProposalDTO(reply, summaries, operations,
            clarification, question, baseVersion);
    }

    private String clip(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
