package interview.guide.modules.resumedocument.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 在线结构化简历文档。与 {@code resumes}（原始上传文件与解析全文）完全分离。
 */
@Entity
@Getter
@Setter
@Table(
    name = "resume_documents",
    indexes = {
        @Index(name = "idx_resume_documents_user", columnList = "user_id"),
        @Index(name = "idx_resume_documents_user_updated", columnList = "user_id, updated_at")
    },
    uniqueConstraints = @UniqueConstraint(
        name = "uk_resume_documents_user_source",
        columnNames = {"user_id", "source_resume_id"})
)
public class ResumeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户，服务端从 SecurityContext 写入，前端不可指定。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    /** 关联的原始上传简历；为空表示用户直接新建的空白简历。 */
    @Column(name = "source_resume_id")
    private Long sourceResumeId;

    /** 结构化简历 JSON 文本。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode = "default";

    /** 乐观锁：每次保存 / 确认提案自动递增。 */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (templateCode == null || templateCode.isBlank()) {
            templateCode = "default";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
