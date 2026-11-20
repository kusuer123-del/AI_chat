package com.mdkj.agent;

import com.mdkj.rag.RetrievedChunk;

import java.util.List;

/**
 * 非流式执行的完整结果（评测与接口都用它）。
 */
public record AgentResult(AgentTrace trace, List<RetrievedChunk> sources) {
}