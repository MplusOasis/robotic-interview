package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库计数服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCountService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 批量更新知识库提问计数（使用单条 SQL 批量更新）
     * 每个知识库的 questionCount +1，表示该知识库参与回答的次数
     *
     * @param knowledgeBaseIds 知识库ID列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }

        // 去重
        List<Long> uniqueIds = knowledgeBaseIds.stream().distinct().toList();

        Long userId = currentUserProvider.userIdOrNull();
        // 验证知识库存在且（有登录上下文时）属于当前用户。
        // 离线 RAG 测评没有认证上下文，此时退化为存在性校验，不引入跨用户风险
        // （该路径不对外暴露 HTTP 入口）。
        List<KnowledgeBaseEntity> found = userId == null
            ? knowledgeBaseRepository.findAllById(uniqueIds)
            : knowledgeBaseRepository.findAllByIdInAndUserId(uniqueIds, userId);
        Set<Long> existingIds = new HashSet<>(found.stream().map(KnowledgeBaseEntity::getId).toList());

        for (Long id : uniqueIds) {
            if (!existingIds.contains(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
            }
        }

        // 批量更新（单条 SQL）
        int updated = knowledgeBaseRepository.incrementQuestionCountBatch(uniqueIds);
        log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated);
    }
}
