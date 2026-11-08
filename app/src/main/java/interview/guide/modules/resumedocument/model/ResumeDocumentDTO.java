package interview.guide.modules.resumedocument.model;

import java.time.LocalDateTime;

/**
 * 在线简历文档详情响应。content 为结构化对象，不返回原始 Entity。
 */
public record ResumeDocumentDTO(
    Long id,
    String title,
    Long sourceResumeId,
    ResumeContent content,
    String templateCode,
    Long version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
