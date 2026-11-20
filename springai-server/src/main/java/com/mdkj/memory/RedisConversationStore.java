package com.mdkj.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 存储：会话以 JSON 字符串落在 aics:chat:{conversationId}，带 TTL。
 * 服务因此无状态，多实例只要连同一个 Redis 就能共享会话。
 */
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final TypeReference<List<Turn>> TURN_LIST = new TypeReference<>() {
    };

    private final SimpleRedisClient client;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisConversationStore(SimpleRedisClient client, ObjectMapper objectMapper, String keyPrefix, Duration ttl) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix == null ? "aics:chat:" : keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public List<Turn> load(String conversationId) {
        String json = client.get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TURN_LIST);
        } catch (JsonProcessingException e) {
            log.warn("[memory] 会话 JSON 解析失败 {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String conversationId, List<Turn> turns) {
        List<Turn> all = new ArrayList<>(load(conversationId));
        all.addAll(turns);
        try {
            client.set(key(conversationId), objectMapper.writeValueAsString(all), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话序列化失败: " + conversationId, e);
        }
    }

    @Override
    public void clear(String conversationId) {
        client.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return keyPrefix + conversationId;
    }
}