package interview.guide.common.config;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 EmbeddingModel 占位 Bean（<b>失效安全</b>）。
 *
 * <p>BYOK 阶段二起，知识库向量化与检索必须使用<b>用户自己的</b> Embedding 凭证，
 * 由 {@code LlmProviderRegistry#getEmbeddingModel(AiExecutionContext)} 与
 * {@code UserVectorStoreRegistry} 提供用户级实例。</p>
 *
 * <p>该 Bean 之所以仍然存在，是因为 Spring AI 的 PgVectorStore 自动配置需要一个
 * {@code EmbeddingModel} Bean 才能完成上下文装配（业务代码不使用该 VectorStore）。
 * 这里刻意做成 <b>fail-closed</b>：任何一次真实调用都会抛出明确错误，
 * 从而彻底消除「悄悄使用开发者全局 Embedding Key」的可能。</p>
 */
@Configuration
@Slf4j
public class LlmEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("全局 EmbeddingModel 已失效（BYOK）：所有向量化/检索必须使用用户级凭证");
        return new FailClosedEmbeddingModel();
    }

    /** 任何调用都会被拒绝，避免全局 Key 静默回退。 */
    static final class FailClosedEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new BusinessException(ErrorCode.EMBEDDING_CREDENTIAL_NOT_CONFIGURED,
                "全局 Embedding 已停用，请使用用户级 Embedding 凭证");
        }

        @Override
        public float[] embed(Document document) {
            throw new BusinessException(ErrorCode.EMBEDDING_CREDENTIAL_NOT_CONFIGURED,
                "全局 Embedding 已停用，请使用用户级 Embedding 凭证");
        }
    }
}
