package interview.guide.modules.resumedocument.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 在线简历 AI 对话的 Prompt 路径配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.resume.document")
public class ResumeDocumentProperties {

    private String systemPromptPath = "classpath:prompts/resume-edit-system.st";
    private String userPromptPath = "classpath:prompts/resume-edit-user.st";
}
