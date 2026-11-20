package com.mdkj.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库装配：用 SimpleVectorStore（内存向量库 + 本地 JSON 落盘），
 * 需要换 Redis / Milvus 时只要把这里换成对应的 VectorStore 实现，RagService 不用改。
 */
@Configuration
public class RagConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}