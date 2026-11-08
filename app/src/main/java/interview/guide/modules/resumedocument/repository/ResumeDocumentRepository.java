package interview.guide.modules.resumedocument.repository;

import interview.guide.modules.resumedocument.model.ResumeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 在线简历文档仓储。对外接口禁止使用裸 findById，必须带 user_id 隔离。
 */
@Repository
public interface ResumeDocumentRepository extends JpaRepository<ResumeDocumentEntity, Long> {

    Optional<ResumeDocumentEntity> findByIdAndUserId(Long id, Long userId);

    List<ResumeDocumentEntity> findAllByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ResumeDocumentEntity> findByUserIdAndSourceResumeId(Long userId, Long sourceResumeId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
