package com.mdkj.dto;

/**
 * 对话请求。
 *
 * @param conversationId 会话 ID，为空则由服务端生成；同一 ID 共享多轮上下文
 * @param message        用户问题
 * @param rag            是否走知识库检索（false 即"无检索链路"，用于 A/B 对照）
 * @param tools          是否挂载工具（天气 / 快递）
 */
public record ChatRequest(String conversationId, String message, Boolean rag, Boolean tools) {
}