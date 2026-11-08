package interview.guide.modules.resumedocument.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resumedocument.model.ApplyProposalRequest;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentDTO;
import interview.guide.modules.resumedocument.model.ResumeDocumentEntity;
import interview.guide.modules.resumedocument.model.ResumeDocumentLimits;
import interview.guide.modules.resumedocument.model.UpdateResumeDocumentRequest;
import interview.guide.modules.resumedocument.repository.ResumeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在线简历文档的业务编排：创建、读取、保存、删除、确认 AI 修改提案。
 *
 * <p>所有方法都以 {@link CurrentUserProvider#requireUserId()} 为唯一身份来源，
 * 查询条件一律带 user_id，跨用户访问按「不存在」处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDocumentService {

    private final ResumeDocumentRepository documentRepository;
    private final ResumeRepository resumeRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ResumeContentCodec contentCodec;
    private final ResumeProposalApplier proposalApplier;

    @Transactional(readOnly = true)
    public List<ResumeDocumentDTO> list() {
        Long userId = currentUserProvider.requireUserId();
        return documentRepository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(this::toDTO)
            .toList();
    }

    @Transactional
    public ResumeDocumentDTO createBlank(String title) {
        Long userId = currentUserProvider.requireUserId();
        ResumeDocumentEntity entity = new ResumeDocumentEntity();
        entity.setUserId(userId);
        entity.setTitle(resolveTitle(title, "新建简历"));
        entity.setSourceResumeId(null);
        entity.setContent(contentCodec.serialize(contentCodec.blank()));
        entity.setTemplateCode(ResumeDocumentLimits.DEFAULT_TEMPLATE_CODE);
        ResumeDocumentEntity saved = documentRepository.saveAndFlush(entity);
        log.info("新建空白简历: id={}, userId={}", saved.getId(), userId);
        return toDTO(saved);
    }

    /**
     * 由原始上传简历初始化在线编辑版本。幂等：已关联时直接返回已有文档。
     * 只读取已落库的 resume_text 做本地抽取，不重复调用大模型分析。
     */
    @Transactional
    public ResumeDocumentDTO initializeFromResume(Long resumeId, String title) {
        Long userId = currentUserProvider.requireUserId();
        ResumeEntity resume = resumeRepository.findByIdAndUserId(resumeId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));

        Optional<ResumeDocumentEntity> existing =
            documentRepository.findByUserIdAndSourceResumeId(userId, resumeId);
        if (existing.isPresent()) {
            return toDTO(existing.get());
        }

        ResumeContent content = contentCodec.fromResumeText(resume.getResumeText());
        ResumeDocumentEntity entity = new ResumeDocumentEntity();
        entity.setUserId(userId);
        entity.setTitle(resolveTitle(title, stripExtension(resume.getOriginalFilename()) + "（在线版）"));
        entity.setSourceResumeId(resumeId);
        entity.setContent(contentCodec.serialize(content));
        entity.setTemplateCode(ResumeDocumentLimits.DEFAULT_TEMPLATE_CODE);
        try {
            ResumeDocumentEntity saved = documentRepository.saveAndFlush(entity);
            log.info("由原始简历初始化在线简历: id={}, sourceResumeId={}, userId={}",
                saved.getId(), resumeId, userId);
            return toDTO(saved);
        } catch (DataIntegrityViolationException e) {
            return documentRepository.findByUserIdAndSourceResumeId(userId, resumeId)
                .map(this::toDTO)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID,
                    "初始化在线简历失败，请重试"));
        }
    }

    @Transactional(readOnly = true)
    public ResumeDocumentDTO get(Long id) {
        return toDTO(requireOwned(id));
    }

    /**
     * 保存已确认的简历状态（自动保存 / 基本信息快速编辑）。
     * 前端只会提交已确认内容，AI 提案在确认前不会进入这里。
     */
    @Transactional
    public ResumeDocumentDTO update(Long id, UpdateResumeDocumentRequest request) {
        ResumeDocumentEntity entity = requireOwned(id);
        requireVersion(entity, request.version(), null);
        ResumeContent content = contentCodec.normalize(request.content());
        contentCodec.validate(content);
        entity.setTitle(resolveTitle(request.title(), "新建简历"));
        entity.setTemplateCode(normalizeTemplate(request.templateCode()));
        entity.setContent(contentCodec.serialize(content));
        return toDTO(persist(entity));
    }

    /**
     * 用户确认修改提案：重新校验身份、版本与提案，再由后端应用并写库。
     * 用户取消时前端直接丢弃提案、不调用本方法，数据库不发生任何变化。
     */
    @Transactional
    public ResumeDocumentDTO applyProposal(Long id, ApplyProposalRequest request) {
        ResumeDocumentEntity entity = requireOwned(id);
        requireVersion(entity, request.version(), request.proposal().baseVersion());
        ResumeContent current = contentCodec.deserialize(entity.getContent());
        ResumeContent updated = proposalApplier.apply(current, request.proposal());
        contentCodec.validate(updated);
        entity.setContent(contentCodec.serialize(updated));
        ResumeDocumentEntity saved = persist(entity);
        log.info("已确认 AI 修改提案: documentId={}, userId={}, version={}",
            id, entity.getUserId(), saved.getVersion());
        return toDTO(saved);
    }

    @Transactional
    public void delete(Long id) {
        ResumeDocumentEntity entity = requireOwned(id);
        documentRepository.delete(entity);
        log.info("删除在线简历: id={}, userId={}", id, entity.getUserId());
    }

    /** 供 AI 对话服务读取文档（已做 user_id 隔离）。 */
    @Transactional(readOnly = true)
    public ResumeDocumentEntity requireOwned(Long id) {
        Long userId = currentUserProvider.requireUserId();
        return documentRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_DOCUMENT_NOT_FOUND));
    }

    /** 原始上传简历的正文摘录，供 AI 基于真实事实生成提案；空白简历返回空串。 */
    @Transactional(readOnly = true)
    public String sourceExcerpt(ResumeDocumentEntity document) {
        if (document.getSourceResumeId() == null) {
            return "";
        }
        Long userId = currentUserProvider.requireUserId();
        return resumeRepository.findByIdAndUserId(document.getSourceResumeId(), userId)
            .map(ResumeEntity::getResumeText)
            .map(text -> text == null ? "" : clip(text, ResumeDocumentLimits.MAX_SOURCE_EXCERPT))
            .orElse("");
    }

    public ResumeDocumentDTO toDTO(ResumeDocumentEntity entity) {
        return new ResumeDocumentDTO(
            entity.getId(),
            entity.getTitle(),
            entity.getSourceResumeId(),
            contentCodec.deserialize(entity.getContent()),
            entity.getTemplateCode(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private ResumeDocumentEntity persist(ResumeDocumentEntity entity) {
        try {
            return documentRepository.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_VERSION_CONFLICT);
        }
    }

    private void requireVersion(ResumeDocumentEntity entity, Long requestVersion, Long proposalBaseVersion) {
        if (!Objects.equals(entity.getVersion(), requestVersion)) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_VERSION_CONFLICT);
        }
        if (proposalBaseVersion != null && !Objects.equals(entity.getVersion(), proposalBaseVersion)) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_VERSION_CONFLICT);
        }
    }

    private String resolveTitle(String title, String fallback) {
        if (title == null || title.isBlank()) {
            return clip(fallback, ResumeDocumentLimits.MAX_TITLE_LENGTH);
        }
        return clip(title.trim(), ResumeDocumentLimits.MAX_TITLE_LENGTH);
    }

    private String normalizeTemplate(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return ResumeDocumentLimits.DEFAULT_TEMPLATE_CODE;
        }
        String normalized = templateCode.trim();
        if (!ResumeDocumentLimits.DEFAULT_TEMPLATE_CODE.equals(normalized)) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID,
                "第一版仅支持默认简历模板");
        }
        return normalized;
    }

    private String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "简历";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String clip(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
