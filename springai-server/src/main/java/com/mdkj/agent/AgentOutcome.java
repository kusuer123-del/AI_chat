package com.mdkj.agent;

import com.mdkj.rag.RetrievedChunk;

import java.util.List;

/**
 * 编排阶段产出：已经跑完"计划 + 循环 + 反思"，只差最后一次流式生成。
 *
 * @param trace      轨迹（steps 已填充）
 * @param finalPrompt 最终答案的提示词（证据 + 草稿 + 自检意见）
 * @param sources    本轮 RAG 命中片段
 */
public record AgentOutcome(AgentTrace trace, String finalPrompt, List<RetrievedChunk> sources) {
}