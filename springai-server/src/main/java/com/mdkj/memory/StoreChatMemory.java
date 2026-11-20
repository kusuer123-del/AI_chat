package com.mdkj.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemory 实现：把 Message 列表压成 Turn 交给 ConversationStore，读取时按窗口截断。
 *
 * <p>窗口大小同时是"注入的历史条数"，用来控制上下文长度与 token 成本。</p>
 */
public class StoreChatMemory implements ChatMemory {

    private final ConversationStore store;
    private final int window;

    public StoreChatMemory(ConversationStore store, int window) {
        this.store = store;
        this.window = Math.max(2, window);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Turn> turns = new ArrayList<>(messages.size());
        for (Message message : messages) {
            Turn turn = toTurn(message);
            if (turn != null) {
                turns.add(turn);
            }
        }
        if (!turns.isEmpty()) {
            store.append(conversationId, turns);
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Turn> turns = store.load(conversationId);
        int limit = lastN <= 0 ? window : Math.min(lastN, window);
        int from = Math.max(0, turns.size() - limit);
        List<Message> messages = new ArrayList<>(turns.size() - from);
        for (Turn turn : turns.subList(from, turns.size())) {
            messages.add(toMessage(turn));
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        store.clear(conversationId);
    }

    /** 供控制层回显历史，避免把 Message 对象直接暴露给前端。 */
    public List<Turn> history(String conversationId) {
        return store.load(conversationId);
    }

    private static Turn toTurn(Message message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (message instanceof UserMessage) {
            return new Turn(Turn.ROLE_USER, text);
        }
        if (message instanceof AssistantMessage) {
            return new Turn(Turn.ROLE_ASSISTANT, text);
        }
        if (message instanceof SystemMessage) {
            return new Turn(Turn.ROLE_SYSTEM, text);
        }
        return null;
    }

    private static Message toMessage(Turn turn) {
        return switch (turn.role()) {
            case Turn.ROLE_ASSISTANT -> new AssistantMessage(turn.text());
            case Turn.ROLE_SYSTEM -> new SystemMessage(turn.text());
            default -> new UserMessage(turn.text());
        };
    }
}