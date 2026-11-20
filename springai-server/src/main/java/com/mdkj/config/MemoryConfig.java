package com.mdkj.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.memory.ConversationStore;
import com.mdkj.memory.FileConversationStore;
import com.mdkj.memory.InMemoryConversationStore;
import com.mdkj.memory.RedisConversationStore;
import com.mdkj.memory.SimpleRedisClient;
import com.mdkj.memory.StoreChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 会话记忆装配：memory → file → redis 三种存储，redis 连不上时自动降级，保证本地零依赖可跑。
 */
@Configuration
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    @Bean
    public ConversationStore conversationStore(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.Memory memory = properties.getMemory();
        String store = memory.getStore() == null ? "memory" : memory.getStore().trim().toLowerCase();

        if ("redis".equals(store)) {
            AiProperties.Redis redis = memory.getRedis();
            SimpleRedisClient client = new SimpleRedisClient(
                    redis.getHost(), redis.getPort(), redis.getPassword(), redis.getDatabase(), redis.getTimeoutMs());
            try {
                client.ping();
                log.info("[memory] 会话外置到 Redis {}:{}/db{}，服务无状态、多实例可共享同一 conversationId",
                        redis.getHost(), redis.getPort(), redis.getDatabase());
                return new RedisConversationStore(client, objectMapper, redis.getKeyPrefix(), redis.getTtl());
            } catch (RuntimeException ex) {
                log.warn("[memory] Redis 不可用（{}），本次降级为本地文件存储", ex.getMessage());
                client.close();
            }
        }

        if ("file".equals(store)) {
            log.info("[memory] 会话写入本地文件 {}", memory.getFileDir());
            return new FileConversationStore(Path.of(memory.getFileDir()), objectMapper);
        }

        log.info("[memory] 会话保存在进程内存（重启即失效，生产请切 redis）");
        return new InMemoryConversationStore();
    }

    @Bean
    public StoreChatMemory chatMemory(ConversationStore conversationStore, AiProperties properties) {
        return new StoreChatMemory(conversationStore, properties.getMemory().getWindow());
    }
}