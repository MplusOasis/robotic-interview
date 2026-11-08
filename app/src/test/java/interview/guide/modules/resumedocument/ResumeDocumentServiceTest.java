package interview.guide.modules.resumedocument;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resumedocument.model.ApplyProposalRequest;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentDTO;
import interview.guide.modules.resumedocument.model.ResumeDocumentEntity;
import interview.guide.modules.resumedocument.model.ResumeEditOperationDTO;
import interview.guide.modules.resumedocument.model.ResumeEditProposalDTO;
import interview.guide.modules.resumedocument.model.UpdateResumeDocumentRequest;
import interview.guide.modules.resumedocument.repository.ResumeDocumentRepository;
import interview.guide.modules.resumedocument.service.ResumeContentCodec;
import interview.guide.modules.resumedocument.service.ResumeDocumentService;
import interview.guide.modules.resumedocument.service.ResumeProposalApplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("在线简历文档服务：归属隔离、乐观锁与提案确认")
class ResumeDocumentServiceTest {

  private static final Long USER_ID = 7L;

  @Mock private ResumeDocumentRepository documentRepository;
  @Mock private ResumeRepository resumeRepository;
  @Mock private CurrentUserProvider currentUserProvider;

  private ResumeContentCodec codec;
  private ResumeDocumentService service;

  @BeforeEach
  void setUp() {
    codec = new ResumeContentCodec(new ObjectMapper());
    service = new ResumeDocumentService(
        documentRepository, resumeRepository, currentUserProvider,
        codec, new ResumeProposalApplier(codec));
  }

  private ResumeDocumentEntity entity(Long id, Long version) {
    ResumeDocumentEntity entity = new ResumeDocumentEntity();
    entity.setId(id);
    entity.setUserId(USER_ID);
    entity.setTitle("我的简历");
    entity.setTemplateCode("default");
    entity.setVersion(version);
    entity.setContent(codec.serialize(codec.blank()));
    entity.setCreatedAt(LocalDateTime.now());
    entity.setUpdatedAt(LocalDateTime.now());
    return entity;
  }

  private void answerSaveWithId(Long id, Long version) {
    when(documentRepository.saveAndFlush(any(ResumeDocumentEntity.class)))
        .thenAnswer(invocation -> {
          ResumeDocumentEntity saved = invocation.getArgument(0);
          saved.setId(id);
          saved.setVersion(version);
          return saved;
        });
  }

  @Test
  @DisplayName("创建空白简历：归属当前用户、默认模板、结构完整")
  void createsBlankDocument() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    answerSaveWithId(11L, 0L);

    ResumeDocumentDTO result = service.createBlank("");

    assertThat(result.title()).isEqualTo("新建简历");
    assertThat(result.sourceResumeId()).isNull();
    assertThat(result.templateCode()).isEqualTo("default");
    assertThat(result.content().getProjects()).isEmpty();
    ArgumentCaptor<ResumeDocumentEntity> captor =
        ArgumentCaptor.forClass(ResumeDocumentEntity.class);
    verify(documentRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
  }

  @Test
  @DisplayName("获取详情按 user_id 隔离查询")
  void getsOwnedDocument() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID))
        .thenReturn(Optional.of(entity(11L, 3L)));

    assertThat(service.get(11L).version()).isEqualTo(3L);
    verify(documentRepository).findByIdAndUserId(11L, USER_ID);
  }

  @Test
  @DisplayName("跨用户访问他人简历按「不存在」处理")
  void rejectsCrossUserAccess() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(99L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_NOT_FOUND.getCode());
    verify(documentRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("未登录时由 CurrentUserProvider 拒绝访问")
  void rejectsUnauthenticatedAccess() {
    when(currentUserProvider.requireUserId())
        .thenThrow(new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));

    assertThatThrownBy(() -> service.list())
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.getCode());
  }

  @Test
  @DisplayName("更新简历：保存已确认内容并透传版本号")
  void updatesDocument() {
    ResumeDocumentEntity entity = entity(11L, 3L);
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID)).thenReturn(Optional.of(entity));
    when(documentRepository.saveAndFlush(any(ResumeDocumentEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ResumeContent content = codec.blank();
    content.getBasicInfo().setName("李四");
    ResumeDocumentDTO result = service.update(11L,
        new UpdateResumeDocumentRequest("李四的简历", "default", content, 3L));

    assertThat(result.content().getBasicInfo().getName()).isEqualTo("李四");
    assertThat(entity.getTitle()).isEqualTo("李四的简历");
  }

  @Test
  @DisplayName("版本冲突时拒绝覆盖新数据")
  void rejectsVersionConflict() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID))
        .thenReturn(Optional.of(entity(11L, 5L)));

    assertThatThrownBy(() -> service.update(11L,
        new UpdateResumeDocumentRequest("旧标题", "default", codec.blank(), 4L)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_VERSION_CONFLICT.getCode());
    verify(documentRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("由原始简历初始化：复用已落库解析文本，不重复调用大模型")
  void initializesFromSourceResume() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(3L);
    resume.setUserId(USER_ID);
    resume.setOriginalFilename("resume.pdf");
    resume.setResumeText("王五\n电话：13900139000\n南京大学 本科");
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(resumeRepository.findByIdAndUserId(3L, USER_ID)).thenReturn(Optional.of(resume));
    when(documentRepository.findByUserIdAndSourceResumeId(USER_ID, 3L))
        .thenReturn(Optional.empty());
    answerSaveWithId(12L, 0L);

    ResumeDocumentDTO result = service.initializeFromResume(3L, null);

    assertThat(result.sourceResumeId()).isEqualTo(3L);
    assertThat(result.title()).contains("resume");
    assertThat(result.content().getBasicInfo().getPhone()).isEqualTo("13900139000");
    verify(resumeRepository).findByIdAndUserId(3L, USER_ID);
  }

  @Test
  @DisplayName("已有在线编辑版本时直接返回，不重复创建")
  void returnsExistingDocumentWhenAlreadyInitialized() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(3L);
    resume.setOriginalFilename("resume.pdf");
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(resumeRepository.findByIdAndUserId(3L, USER_ID)).thenReturn(Optional.of(resume));
    when(documentRepository.findByUserIdAndSourceResumeId(USER_ID, 3L))
        .thenReturn(Optional.of(entity(12L, 2L)));

    assertThat(service.initializeFromResume(3L, null).id()).isEqualTo(12L);
    verify(documentRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("用户确认修改提案后由后端应用并写库")
  void appliesConfirmedProposal() {
    ResumeDocumentEntity entity = entity(11L, 1L);
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID)).thenReturn(Optional.of(entity));
    when(documentRepository.saveAndFlush(any(ResumeDocumentEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var operation = new ResumeEditOperationDTO(
        "UPDATE", "BASIC_INFO", null, null, "name", "赵六", null, null, null, null);
    var proposal = new ResumeEditProposalDTO(
        "已修改姓名", List.of("修改姓名"), List.of(operation), false, null, 1L);

    ResumeDocumentDTO result = service.applyProposal(11L, new ApplyProposalRequest(proposal, 1L));

    assertThat(result.content().getBasicInfo().getName()).isEqualTo("赵六");
    verify(documentRepository).saveAndFlush(any(ResumeDocumentEntity.class));
  }

  @Test
  @DisplayName("用户取消修改时不调用确认入口，数据库无写入")
  void cancelDoesNotWriteDatabase() {
    var operation = new ResumeEditOperationDTO(
        "DELETE", "PROJECT", "project-1", null, null, null, null, null, null, null);
    var discarded = new ResumeEditProposalDTO(
        "待确认删除", List.of("删除项目"), List.of(operation), false, null, 1L);
    assertThat(discarded.operations()).hasSize(1);
    verify(documentRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("提案基础版本与当前版本不一致时拒绝应用")
  void rejectsStaleProposal() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID))
        .thenReturn(Optional.of(entity(11L, 2L)));
    var stale = new ResumeEditProposalDTO(
        "旧提案", List.of(), List.of(), false, null, 0L);

    assertThatThrownBy(() -> service.applyProposal(11L, new ApplyProposalRequest(stale, 2L)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_VERSION_CONFLICT.getCode());
  }

  @Test
  @DisplayName("删除在线简历不影响原始上传文件")
  void deletesOwnedDocument() {
    ResumeDocumentEntity entity = entity(11L, 1L);
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(documentRepository.findByIdAndUserId(11L, USER_ID)).thenReturn(Optional.of(entity));

    service.delete(11L);

    verify(documentRepository).delete(entity);
    verify(resumeRepository, never()).delete(any(ResumeEntity.class));
  }
}
