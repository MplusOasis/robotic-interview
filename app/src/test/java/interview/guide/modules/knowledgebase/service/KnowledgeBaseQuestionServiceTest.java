package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.listener.QuestionGenStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQuestionServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private KnowledgeBaseQuestionRepository questionRepository;
  @Mock
  private QuestionGenStreamProducer questionGenStreamProducer;
  @Mock
  private QuestionGenerationStateService questionGenerationStateService;
  @Mock
  private interview.guide.common.security.CurrentUserProvider currentUserProvider;

  private static final Long TEST_USER_ID = 100L;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private KnowledgeBaseQuestionService service;

  @BeforeEach
  void setUp() {
    lenient().when(currentUserProvider.requireUserId()).thenReturn(TEST_USER_ID);
    // 归属查询委托到既有 findById 桩，保持原有用例语义
    lenient().when(knowledgeBaseRepository.findByIdAndUserId(anyLong(), anyLong()))
        .thenAnswer(inv -> knowledgeBaseRepository.findById(inv.getArgument(0)));
    // 未显式桩 findById 的用例默认返回一个属于测试用户的知识库，便于通过归属校验
    lenient().when(knowledgeBaseRepository.findById(anyLong())).thenAnswer(inv -> {
      interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity kb =
          new interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity();
      kb.setId(inv.getArgument(0));
      kb.setUserId(TEST_USER_ID);
      return java.util.Optional.of(kb);
    });
    service = new KnowledgeBaseQuestionService(
        knowledgeBaseRepository,
        questionRepository,
        objectMapper,
        questionGenStreamProducer,
        questionGenerationStateService,
        currentUserProvider
    );
  }

  @Nested
  @DisplayName("listQuestions 筛选")
  class ListFiltering {

    @Test
    @DisplayName("按 category 筛选时只返回该方向的题")
    void shouldFilterByCategory() {
      KnowledgeBaseQuestionEntity redis = entity("Redis 主问题", "Redis");
      KnowledgeBaseQuestionEntity jvm = entity("JVM 主问题", "JVM");
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(redis, jvm));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, "Redis", null, null);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).category()).isEqualTo("Redis");
    }

    @Test
    @DisplayName("category 为空白时返回全部")
    void shouldReturnAllWhenCategoryIsBlank() {
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(entity("Q1", "JVM"), entity("Q2", "Redis")));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, "  ", null, null);

      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("兼容字符串数组格式的历史追问")
    void shouldReadLegacyStringFollowUps() throws Exception {
      KnowledgeBaseQuestionEntity question = entity("Q1", "JVM");
      question.setFollowUpsJson(objectMapper.writeValueAsString(List.of("追问1")));
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(question));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, null, null, null);

      assertThat(result.get(0).followUps()).hasSize(1);
      assertThat(result.get(0).followUps().get(0).question()).isEqualTo("追问1");
    }
  }

  private KnowledgeBaseQuestionEntity entity(String question, String category) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(1L);
    kb.setName("Spring Boot 实战");
    KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
    entity.setKnowledgeBase(kb);
    entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setCategory(category);
    entity.setQuestion(question);
    entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
    return entity;
  }

}
