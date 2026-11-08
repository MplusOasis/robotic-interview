package interview.guide.modules.resumedocument;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.LlmProposalDTO;
import interview.guide.modules.resumedocument.model.ResumeChatRequest;
import interview.guide.modules.resumedocument.model.ResumeDocumentEntity;
import interview.guide.modules.resumedocument.model.ResumeEditOperationDTO;
import interview.guide.modules.resumedocument.service.ResumeContentCodec;
import interview.guide.modules.resumedocument.service.ResumeDocumentAiService;
import interview.guide.modules.resumedocument.service.ResumeDocumentProperties;
import interview.guide.modules.resumedocument.service.ResumeDocumentService;
import interview.guide.modules.resumedocument.service.ResumeProposalApplier;
import interview.guide.modules.usercredential.service.UserCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历 AI 对话修改提案：BYOK 与输出校验")
class ResumeDocumentAiServiceTest {

  private static final Long USER_ID = 7L;
  private static final Long DOCUMENT_ID = 11L;

  @Mock private UserCredentialService userCredentialService;
  @Mock private LlmProviderRegistry llmProviderRegistry;
  @Mock private StructuredOutputInvoker structuredOutputInvoker;
  @Mock private ResumeDocumentService documentService;
  @Mock private PromptSanitizer promptSanitizer;
  @Mock private ResourceLoader resourceLoader;
  @Mock private ChatClient chatClient;
  @Mock private BeanOutputConverter<LlmProposalDTO> outputConverter;

  private ResumeContentCodec codec;
  private ResumeDocumentAiService service;
  private AiExecutionContext context;

  @BeforeEach
  void setUp() {
    codec = new ResumeContentCodec(new ObjectMapper());
    context = new AiExecutionContext(USER_ID, "dashscope", "qwen-plus", 1L);
    service = new ResumeDocumentAiService(
        userCredentialService, llmProviderRegistry, structuredOutputInvoker,
        documentService, codec, new ResumeProposalApplier(codec), promptSanitizer,
        new ResumeDocumentProperties(), resourceLoader);
    ReflectionTestUtils.setField(service, "systemPromptTemplate",
        new PromptTemplate("# 系统提示词"));
    ReflectionTestUtils.setField(service, "userPromptTemplate",
        new PromptTemplate("{currentResume}\n{sourceResumeExcerpt}\n{chatHistory}\n{userMessage}"));
    ReflectionTestUtils.setField(service, "outputConverter", outputConverter);
  }

  private ResumeDocumentEntity document() {
    ResumeDocumentEntity entity = new ResumeDocumentEntity();
    entity.setId(DOCUMENT_ID);
    entity.setUserId(USER_ID);
    entity.setVersion(4L);
    entity.setSourceResumeId(null);
    entity.setContent(codec.serialize(codec.blank()));
    return entity;
  }

  private void stubCommon(ResumeDocumentEntity document) {
    when(documentService.requireOwned(DOCUMENT_ID)).thenReturn(document);
    lenient().when(documentService.sourceExcerpt(document)).thenReturn("");
    lenient().when(outputConverter.getFormat()).thenReturn("{}");
    lenient().when(promptSanitizer.sanitize(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1));
  }

  private void stubCredential() {
    when(userCredentialService.requireChatContext()).thenReturn(context);
    when(llmProviderRegistry.getPlainChatClient(context)).thenReturn(chatClient);
  }

  private void stubInvoke(LlmProposalDTO result) {
    doReturn(result).when(structuredOutputInvoker).invoke(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any());
  }

  private LlmProposalDTO validProposal() {
    var operation = new ResumeEditOperationDTO(
        "UPDATE", "BASIC_INFO", null, null, "name", "王五", null, null, null, null);
    return new LlmProposalDTO(
        "已修改姓名", List.of("修改姓名"), false, null, List.of(operation));
  }

  @Test
  @DisplayName("生成修改提案：只使用用户自己的凭证，并填入当前简历版本")
  void generatesProposalWithUserCredential() {
    ResumeDocumentEntity document = document();
    stubCommon(document);
    stubCredential();
    stubInvoke(validProposal());

    var result = service.generate(DOCUMENT_ID, new ResumeChatRequest("我叫王五", List.of()));

    assertThat(result.baseVersion()).isEqualTo(4L);
    assertThat(result.operations()).hasSize(1);
    assertThat(result.reply()).isEqualTo("已修改姓名");
    verify(userCredentialService).requireChatContext();
    verify(llmProviderRegistry).getPlainChatClient(context);
    verify(llmProviderRegistry, never()).getChatClientOrDefault(anyString());
  }

  @Test
  @DisplayName("用户未配置 API Key 时明确拒绝，不回退平台 Key")
  void rejectsWhenCredentialMissing() {
    stubCommon(document());
    when(userCredentialService.requireChatContext())
        .thenThrow(new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED));

    assertThatThrownBy(() -> service.generate(DOCUMENT_ID,
        new ResumeChatRequest("帮我改简历", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED.getCode());
    verify(llmProviderRegistry, never()).getPlainChatClient(any(AiExecutionContext.class));
  }

  @Test
  @DisplayName("AI 返回无效结构化数据（无操作且非追问）时拒绝提案")
  void rejectsInvalidStructuredOutput() {
    stubCommon(document());
    stubCredential();
    stubInvoke(new LlmProposalDTO(
        "我不知道该改什么", List.of(), false, null, List.of()));

    assertThatThrownBy(() -> service.generate(DOCUMENT_ID,
        new ResumeChatRequest("帮我看看", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_EDIT_PROPOSAL_INVALID.getCode());
  }

  @Test
  @DisplayName("AI 提案试图删除不存在的记录时被后端校验拦截")
  void rejectsProposalTargetingUnknownRecord() {
    stubCommon(document());
    stubCredential();
    var operation = new ResumeEditOperationDTO(
        "DELETE", "PROJECT", "not-exist", null, null, null, null, null, null, null);
    stubInvoke(new LlmProposalDTO(
        "已删除项目", List.of("删除项目"), false, null, List.of(operation)));

    assertThatThrownBy(() -> service.generate(DOCUMENT_ID,
        new ResumeChatRequest("删除那个项目", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_EDIT_PROPOSAL_INVALID.getCode());
  }

  @Test
  @DisplayName("模型调用失败时保持原有错误码，不降级成空提案")
  void propagatesAiFailure() {
    stubCommon(document());
    stubCredential();
    doThrow(new BusinessException(ErrorCode.RESUME_EDIT_AI_FAILED))
        .when(structuredOutputInvoker).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.generate(DOCUMENT_ID,
        new ResumeChatRequest("润色一下", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_EDIT_AI_FAILED.getCode());
  }

  @Test
  @DisplayName("信息不足时返回追问型提案且不含任何写操作")
  void returnsClarificationWithoutOperations() {
    stubCommon(document());
    stubCredential();
    var operation = new ResumeEditOperationDTO(
        "ADD", "PROJECT", null, null, null, null, null, null, null, null);
    stubInvoke(new LlmProposalDTO(
        "还需要信息", List.of(), true, "请补充项目名称", List.of(operation)));

    var result = service.generate(DOCUMENT_ID,
        new ResumeChatRequest("加个项目", List.of()));

    assertThat(result.needsClarification()).isTrue();
    assertThat(result.operations()).isEmpty();
    assertThat(result.clarificationQuestion()).isEqualTo("请补充项目名称");
    // 用户取消追问后前端丢弃提案，不调用确认接口，因此这里也没有写库入口
    verify(documentService, never()).applyProposal(any(), any());
  }
}
