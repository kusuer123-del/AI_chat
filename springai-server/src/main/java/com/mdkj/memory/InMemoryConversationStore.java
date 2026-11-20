package com.mdkj.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内存储，默认实现：零依赖、开箱即跑，但重启即失效、多实例不共享。
 */
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, CopyOnWriteArrayList<Turn>> store = new ConcurrentHashMap<>();

    @Override
    public List<Turn> load(String conversationId) {
        return List.copyOf(store.getOrDefault(conversationId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void append(String conversationId, List<Turn> turns) {
        store.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>()).addAll(turns);
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}