package com.mdkj.config;

import com.mdkj.advisor.AuditLogAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 统一接入层。
 *
 * <p>RAG、工具调用、文生图、多轮对话四条能力线共用同一个 ChatClient：系统提示词与 Advisor 链在这里
 * 一次性装配，新增场景只需在请求侧加组件（.tools(..) / 检索片段 / 图片模型），主流程不用改。</p>
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 AiProperties properties,
                                 ChatMemory chatMemory,
                                 AuditLogAdvisor auditLogAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultSystem(properties.getSystemPrompt())
                .defaultAdvisors(
                        auditLogAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    public AuditLogAdvisor auditLogAdvisor(AiProperties properties,
                                           @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String model) {
        return new AuditLogAdvisor(properties.isAuditEnabled(), model);
    }
}