package com.mdkj.agent;

/**
 * 规划阶段拆出的子任务。
 *
 * @param goal     子任务目标
 * @param toolHint 预期用到的工具名，可能为空（表示纯推理）
 */
public record AgentTask(String goal, String toolHint) {
}