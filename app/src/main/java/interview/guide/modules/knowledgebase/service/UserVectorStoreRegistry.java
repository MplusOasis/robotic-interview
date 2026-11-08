package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.UserCredentialChangedEvent;
import interview.guide.common.ai.UserScopedCache;
import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户级 PgVectorStore 注册表。
 *
 * <p>为什么不能再用全局 {@code VectorStore} Bean：Spring AI 的 {@code PgVectorStore}
 * 在构造时绑定一个 {@code EmbeddingModel}，而全局单例只能绑定开发者 Key 驱动的模型。
 * 这里按「用户 + 供应商 + 模型 + 凭证版本」为每个用户构建独立的 PgVectorStore，
 * 使其完成向量化与检索时都使用<b>该用户自己的</b> Embedding 凭证。</p>
 *
 * <p>所有用户共用同一张 {@code vector_store} 表（维度固定 1024），
 * 数据隔离依赖 metadata 中的 {@code kb_id} 过滤 + 上层知识库归属校验。</p>
 */
@Slf4j
@Component
public class UserVectorStoreRegistry implements UserVectorStoreProvider {

    private static final String TABLE_NAME = "vector_store";
    private static final String SCHEMA_NAME = "public";
    private static final String CACHE_SUFFIX = ":vec";

    private final LlmProviderRegistry llmProviderRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final int dimensions;
    private final UserScopedCache<VectorStore> cache;

    public UserVectorStoreRegistry(LlmProviderRegistry llmProviderRegistry,
                                   JdbcTemplate jdbcTemplate,
                                   LlmProviderProperties llmProviderProperties,
                                   ByokProperties byokProperties) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.dimensions = llmProviderProperties.getEmbeddingDimensions() == null
            ? 1024 : llmProviderProperties.getEmbeddingDimensions();
        this.cache = new UserScopedCache<>("UserVectorStore",
            byokProperties.getCache().getMaxEntries(), byokProperties.getCache().getTtl());
    }

    /**
     * 获取该用户专属的向量库（使用用户自己的 Embedding 凭证）。
     */
    @Override
    public VectorStore get(AiExecutionContext context) {
        if (context == null || context.userId() == null) {
            throw new BusinessException(ErrorCode.AI_CONTEXT_USER_UNAVAILABLE);
        }
        return cache.get(context.cacheScope() + CACHE_SUFFIX, key -> build(context));
    }

    /**
     * 用户的 Embedding 模型签名，形如 {@code dashscope::text-embedding-v3::1024}。
     *
     * <p>写入知识库的向量配置快照，检索时校验，避免「入库用一种模型、检索用另一种模型」
     * 导致向量空间不一致却静默返回错误结果。</p>
     */
    @Override
    public String embeddingSignature(AiExecutionContext context) {
        return context.providerKey() + "::" + context.modelName() + "::" + dimensions;
    }

    public int dimensions() {
        return dimensions;
    }

    @EventListener
    public void onCredentialChanged(UserCredentialChangedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        String prefix = event.providerKey() == null || event.providerKey().isBlank()
            ? AiExecutionContext.userPrefix(event.userId())
            : AiExecutionContext.userProviderPrefix(event.userId(), event.providerKey());
        cache.invalidate(key -> key.startsWith(prefix));
        log.info("[UserVectorStoreRegistry] 凭证变更，已失效用户级向量库缓存: userId={}, providerKey={}",
            event.userId(), event.providerKey());
    }

    private VectorStore build(AiExecutionContext context) {
        EmbeddingModel embeddingModel = llmProviderRegistry.getEmbeddingModel(context);
        log.info("[UserVectorStoreRegistry] 构建用户级 PgVectorStore: userId={}, provider={}, model={}, dimensions={}",
            context.userId(), context.providerKey(), context.modelName(), dimensions);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .schemaName(SCHEMA_NAME)
            .vectorTableName(TABLE_NAME)
            // 表结构与索引由 Flyway 统一管理，运行时不重建、不改写
            .initializeSchema(false)
            .removeExistingVectorStoreTable(false)
            .vectorTableValidationsEnabled(false)
            .dimensions(dimensions)
            .build();
    }
}
