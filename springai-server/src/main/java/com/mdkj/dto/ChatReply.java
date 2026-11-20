package com.mdkj.dto;

import com.mdkj.rag.RetrievedChunk;

import java.util.List;

/**
 * 一次性返回的对话结果，带上检索命中与 token 用量，便于前端展示与成本核算。
 */
public record ChatReply(String conversationId,
                        String content,
                        List<RetrievedChunk> sources,
                        Integer promptTokens,
                        Integer completionTokens,
                        long latencyMs) {
}