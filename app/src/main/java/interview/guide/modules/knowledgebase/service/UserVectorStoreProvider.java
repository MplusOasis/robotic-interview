package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiExecutionContext;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 用户级向量库提供者。
 *
 * <p>抽象出来是为了让 {@link KnowledgeBaseVectorService} 在测试中可注入固定向量库，
 * 同时保证生产实现（{@link UserVectorStoreRegistry}）始终按用户凭证构建。</p>
 */
public interface UserVectorStoreProvider {

    /**
     * 获取该用户的向量库实例。
     */
    VectorStore get(AiExecutionContext context);

    /**
     * 该用户 Embedding 模型签名（用于校验入库与检索使用同一模型）；未知时返回 null。
     */
    String embeddingSignature(AiExecutionContext context);
}
