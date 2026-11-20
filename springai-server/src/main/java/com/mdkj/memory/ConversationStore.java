package com.mdkj.memory;

import java.util.List;

/**
 * 会话存储抽象：内存 / 文件 / Redis 三种实现，ChatMemory 只依赖这个接口。
 */
public interface ConversationStore {

    List<Turn> load(String conversationId);

    void append(String conversationId, List<Turn> turns);

    void clear(String conversationId);
}