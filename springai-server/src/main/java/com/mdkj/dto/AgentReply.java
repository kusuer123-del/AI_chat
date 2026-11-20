package com.mdkj.dto;

import com.mdkj.agent.AgentStep;
import com.mdkj.agent.AgentTask;
import com.mdkj.rag.RetrievedChunk;

import java.util.List;

/**
 * Agent 非流式执行结果。
 */
public record AgentReply(String traceId,
                         String answer,
                         List<AgentTask> tasks,
                         List<AgentStep> steps,
                         List<RetrievedChunk> sources,
                         boolean success,
                         String status,
                         long costMs,
                         int toolCalls,
                         Integer promptTokens,
                         Integer completionTokens) {
}