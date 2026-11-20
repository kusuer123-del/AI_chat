package com.mdkj.dto;

/**
 * Agent 执行请求。
 *
 * @param conversationId 会话 id，为空则每次都是独立会话
 * @param message        用户问题
 * @param rag            是否启用知识库检索
 * @param maxSteps       本轮的步数上限（不超过配置上限，用于压测或演示预算控制）
 */
public record AgentRequest(String conversationId, String message, Boolean rag, Integer maxSteps) {
}