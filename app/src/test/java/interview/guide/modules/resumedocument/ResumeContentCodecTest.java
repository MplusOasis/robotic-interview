package interview.guide.modules.resumedocument;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.service.ResumeContentCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("在线简历结构化内容编解码")
class ResumeContentCodecTest {

  private ResumeContentCodec codec;

  @BeforeEach
  void setUp() {
    codec = new ResumeContentCodec(new ObjectMapper());
  }

  @Test
  @DisplayName("空白简历包含完整结构，所有数组为空且预留实习/获奖扩展位")
  void blankContentHasCompleteStructure() {
    ResumeContent content = codec.blank();
    assertThat(content.getBasicInfo()).isNotNull();
    assertThat(content.getEducation()).isEmpty();
    assertThat(content.getProjects()).isEmpty();
    assertThat(content.getSkills()).isEmpty();
    assertThat(content.getInternships()).isEmpty();
    assertThat(content.getAwards()).isEmpty();

    String json = codec.serialize(content);
    assertThat(json).contains("basicInfo", "education", "projects", "skills", "internships", "awards");
    assertThat(codec.deserialize(json).getBasicInfo().getName()).isEmpty();
  }

  @Test
  @DisplayName("由原始简历解析文本本地抽取基础信息，不重复调用大模型")
  void extractFromResumeTextLocally() {
    String text = """
        张三
        手机：13800138000  邮箱：zhangsan@example.com
        清华大学 计算机科学与技术 本科
        求职意向：Java 后端开发工程师
        专业技能
        Java、Spring Boot、Redis、MySQL
        项目经历
        智能面试平台
        """;
    ResumeContent content = codec.fromResumeText(text);

    assertThat(content.getBasicInfo().getName()).isEqualTo("张三");
    assertThat(content.getBasicInfo().getPhone()).isEqualTo("13800138000");
    assertThat(content.getBasicInfo().getEmail()).isEqualTo("zhangsan@example.com");
    assertThat(content.getBasicInfo().getSchool()).contains("清华大学");
    assertThat(content.getBasicInfo().getDegree()).isEqualTo("本科");
    assertThat(content.getBasicInfo().getTargetPosition()).contains("Java 后端");
    assertThat(content.getSkills()).contains("Java", "Spring Boot", "Redis", "MySQL");
    // 本地规则不臆造项目经历，由用户通过 AI 对话补充
    assertThat(content.getProjects()).isEmpty();
  }

  @Test
  @DisplayName("规范化为缺失记录生成稳定唯一 ID 并重排亮点序号")
  void normalizeGeneratesStableIds() {
    ResumeContent raw = codec.blank();
    ResumeContent.ProjectItem project = new ResumeContent.ProjectItem();
    ResumeContent.HighlightItem first = new ResumeContent.HighlightItem();
    first.setId("duplicate-id");
    first.setContent("亮点一");
    ResumeContent.HighlightItem second = new ResumeContent.HighlightItem();
    second.setId("duplicate-id");
    second.setContent("亮点二");
    project.setHighlights(new java.util.ArrayList<>(java.util.List.of(first, second)));
    raw.setProjects(new java.util.ArrayList<>(java.util.List.of(project)));

    ResumeContent normalized = codec.normalize(raw);
    ResumeContent.ProjectItem result = normalized.getProjects().get(0);
    assertThat(result.getId()).isNotBlank();
    assertThat(result.getHighlights()).hasSize(2);
    assertThat(result.getHighlights().get(0).getId())
        .isNotEqualTo(result.getHighlights().get(1).getId());
    assertThat(result.getHighlights())
        .extracting(ResumeContent.HighlightItem::getSortOrder)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("超长字段在校验阶段拒绝写入")
  void rejectsOversizedText() {
    ResumeContent content = codec.blank();
    content.getBasicInfo().setName("x".repeat(201));
    assertThatThrownBy(() -> codec.validate(content))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID.getCode());
  }

  @Test
  @DisplayName("非法 JSON 内容解析为业务异常而非静默吞掉")
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> codec.deserialize("{not-json"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID.getCode());
  }
}
